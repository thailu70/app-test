'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
process.env.NODE_ENV = 'test';
process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), 'passo-mobility-'));
if (process.env.MOBILITY_TEST_POSTGRES === 'true') {
  if (process.env.CI !== 'true' || !process.env.DATABASE_URL) throw new Error('Disposable CI PostgreSQL configuration is required.');
} else delete process.env.DATABASE_URL;
const { DB } = require('../src/db');
const { initMasterDataSchema } = require('../src/services/master-data');
const { initOnboardingSchema } = require('../src/services/onboarding');
const { createMobilityService } = require('../src/services/mobility');
const { signToken } = require('../src/middleware/auth');
test('group approval, multi-person paid tickets, cargo privacy and admin assignment', async t => {
  await initMasterDataSchema(); await initOnboardingSchema();
  await require('../src/services/mobility-schema').initMobilitySchema();
  if (DB.isPostgres) await DB.exec('TRUNCATE mobility_sms_outbox,mobility_seats,mobility_travelers,mobility_bookings,mobility_requests,mobility_trips');
  await DB.prepare("INSERT INTO users(id,role,fullName,phone,passwordHash,status) VALUES('usr_adm_root','ADMIN','Fixture Admin','+251900999001','unused','ACTIVE') ON CONFLICT(id) DO NOTHING").run();
  const at = new Date().toISOString();
  for (const [key, type, phone] of [['group','GROUP_TRAVEL','+251911000101'],['cargo','CARGO','+251911000102']]) {
    await DB.prepare("INSERT INTO users(id,role,fullName,phone,passwordHash,status) VALUES(?,'DRIVER',?,?,'unused','ACTIVE')").run(key,key + ' Driver',phone);
    await DB.prepare("INSERT INTO onboarding_accounts(account_id,role,normalized_phone,service_type,created_at,updated_at,acceptance_status,accepted_at,accepted_by,acceptance_grandfathered,submitted_at,review_status) VALUES(?,'DRIVER',?,?,?,?, 'ACCEPTED',?,'usr_adm_root',TRUE,?,'APPROVED')").run(key,phone,type,at,at,at,at);
    await DB.prepare("INSERT INTO user_profiles(user_id,emergency_contact_name,emergency_contact_phone,vehicle_color,updated_at) VALUES(?,'Emergency Person','+251911000103','white',?)").run(key,at);
    for (const kind of ['LICENSE','VEHICLE_REGISTRATION','INSURANCE','PHOTO']) await DB.prepare("INSERT INTO user_profile_documents(user_id,kind,storage_name,content_type,byte_size,uploaded_at) VALUES(?,?,'fixture','image/jpeg',20,?)").run(key,kind,at);
    await DB.prepare("INSERT INTO vehicles(id,plateNumber,model,vehicleType,capacityLimit,driverId,status) VALUES(?,?,?,'MINIVAN_8',8,?,'IN_SERVICE')").run(key+'vehicle','TEST-'+key,'Fixture vehicle',key);
  }
  let clock = Date.now(), order = 0;
  const sent = [], providerOrders = new Map();
  const provider = { missingConfiguration:()=>[],config:()=>({environment:'production'}),merchantOrderId:()=> 'group-order-'+(++order),
    createCheckout:async (key,amount,_title,urls)=>{providerOrders.set(key,{amount,paid:false,urls});return {prepayId:'pre-'+key,checkoutUrl:'https://payments.example.test/'+key};},
    queryOrderStatus:async key=>({merchantOrderId:key,currency:'ETB',amount:providerOrders.get(key).amount,paid:providerOrders.get(key).paid,failed:false,transactionId:'paid-'+key}),
    parseNotification:body=>body,verifyNotification:()=>false };
  const service = createMobilityService({now:()=>clock,provider,sendSms:async(phone,message)=>{sent.push({phone,message});return {sent:true};}}); await service.init();
  const input = {vehicleId:'groupvehicle',origin:'Addis Ababa',destination:'Hawassa',departureAt:new Date(clock+30*60000).toISOString(),latitude:9,longitude:38,seats:8,fareEtb:500};
  let trip, held;
  await t.test('only approved group drivers propose, and only admin-approved future trips are discoverable', async()=>{
    await assert.rejects(service.createTrip('cargo',{...input,vehicleId:'cargovehicle'}),e=>e.status===409);
    trip=(await service.createTrip('group',input)).trip;
    assert.equal(trip.status,'PENDING_REVIEW');assert.equal((await service.trips()).trips.length,0);
    await assert.rejects(service.createBooking(trip.id,{adultConfirmed:true,buyerName:'Buyer',buyerPhone:'0911000201',travelers:[{name:'Traveler',phone:'0911000202',seatNumber:1}]}),e=>e.status===409);
    trip=(await service.reviewTrip('usr_adm_root',trip.id,{decision:'APPROVE'})).trip;
    assert.equal((await service.trips()).trips.length,1);assert.equal(trip.fareEtb,500);
    assert.equal(JSON.stringify(await service.trips()).includes('secret_hash'),false);
  });
  const buyer={buyerName:'Ticket Buyer',buyerPhone:'0911000201',adultConfirmed:true};
  await t.test('multi-seat booking is atomic, keeps each traveler, and prevents concurrent double reservations', async()=>{
    const payload={...buyer,travelers:[{name:'First Traveler',phone:'0911000202',seatNumber:1},{name:'Second Traveler',phone:'0911000203',seatNumber:2}]};
    held=await service.createBooking(trip.id,payload); assert.equal(held.booking.amountEtb,1000);assert.equal(held.booking.travelers.length,2);
    assert.equal(held.booking.travelers[0].ticketNumber,undefined); assert.equal(sent.length,0);
    await assert.rejects(service.booking(held.booking.id,'wrong'),e=>e.status===404);
    await assert.rejects(service.createBooking(trip.id,{...buyer,travelers:[{name:'Duplicate',phone:'0911000204',seatNumber:3},{name:'Duplicate',phone:'0911000205',seatNumber:3}]}),e=>e.status===400);
    const attempts=await Promise.allSettled([1,2].map(()=>service.createBooking(trip.id,{...buyer,travelers:[{name:'Concurrent Traveler',phone:'0911000204',seatNumber:3}]})));
    assert.equal(attempts.filter(x=>x.status==='fulfilled').length,1);assert.equal(attempts.filter(x=>x.status==='rejected'&&x.reason.status===409).length,1);
    await assert.rejects(service.createBooking(trip.id,{...buyer,travelers:[{name:'Conflict',phone:'0911000204',seatNumber:4},{name:'Conflict',phone:'0911000205',seatNumber:1}]}),e=>e.status===409);
    assert.equal((await service.seats(trip.id)).occupiedSeats.includes(4),false,'Failed multi-seat booking rolls back every seat.');
  });
  await t.test('provider amount and signature gates payment; buyer and every traveler receive individual paid ticket links once', async()=>{
    const first=await service.checkout(held.booking.id,held.bookingSecret);assert.match(first.checkoutUrl,/^https:/);assert.equal(order,1);
    assert.equal((await service.checkout(held.booking.id,held.bookingSecret)).checkoutUrl,first.checkoutUrl);assert.equal(order,1);
    await assert.rejects(service.webhook({merch_order_id:'group-order-1'}),e=>e.status===401);
    providerOrders.get('group-order-1').paid=true;providerOrders.get('group-order-1').amount=1;
    await assert.rejects(service.booking(held.booking.id,held.bookingSecret),e=>e.status===502);
    providerOrders.get('group-order-1').amount=1000;
    const paid=(await service.booking(held.booking.id,held.bookingSecret)).booking;assert.equal(paid.paymentStatus,'PAID');
    assert.ok(paid.travelers.every(p=>p.ticketNumber&&p.boardingPassUrl&&p.qrToken));
    await service.flushSms();
    assert.equal(sent.length,4);assert.equal(sent.filter(x=>x.phone==='+251911000201').length,2);
    for(const message of sent)assert.match(message.message,/PG-[A-F0-9]{16}.*Boarding pass: https:\/\/passo.live\/api\/mobility\/tickets\//);
    await service.booking(held.booking.id,held.bookingSecret);await service.flushSms();assert.equal(sent.length,4);
    const traveler=paid.travelers[0];assert.equal((await service.ticket(traveler.id,traveler.qrToken)).traveler.name,traveler.name);
    await assert.rejects(service.ticket(paid.travelers[1].id,traveler.qrToken),e=>e.status===401);
    assert.equal(providerOrders.get('group-order-1').urls.notifyUrl,'https://passo.live/api/mobility/payments/webhook');
  });
  await t.test('driver boarding validates assigned operator, fresh GPS, paid ticket and single use', async()=>{
    const location={latitude:9,longitude:38,accuracyMeters:5,timestamp:new Date(clock).toISOString()};
    await assert.rejects(service.startBoarding('cargo',trip.id,location),e=>e.status===404);
    await assert.rejects(service.startBoarding('group',trip.id,{...location,latitude:10}),e=>e.status===403);
    await assert.rejects(service.startBoarding('group',trip.id,{...location,timestamp:new Date(clock-130000).toISOString()}),e=>e.status===403);
    await service.startBoarding('group',trip.id,location);
    const paid=(await service.booking(held.booking.id,held.bookingSecret)).booking;
    assert.equal((await service.board('group',trip.id,{...location,ticketNumber:paid.travelers[0].ticketNumber})).traveler.status,'BOARDED');
    await assert.rejects(service.board('group',trip.id,{...location,ticketNumber:paid.travelers[0].ticketNumber}),e=>e.status===409);
    assert.equal((await service.roster('group',trip.id)).travelers.length,2);
    await assert.rejects(service.roster('cargo',trip.id),e=>e.status===404);
    await service.completeTrip('group',trip.id);assert.equal((await service.trips()).trips.length,0);
  });
  const requestBase={origin:'Addis Ababa',destination:'Adama',travelDate:new Date(clock+86400000).toISOString().slice(0,10),vehicleType:'MINIVAN_8',customerName:'Request Customer',customerPhone:'0911000301',adultConfirmed:true};
  await t.test('private group and photo-backed cargo requests have capability privacy and service-specific admin assignment', async()=>{
    const group=await service.createRequest({...requestBase,kind:'GROUP',people:5});assert.equal(group.request.status,'REQUESTED');
    const cargo=await service.createRequest({...requestBase,kind:'CARGO',cargoDetails:'Boxed household furniture',originLatitude:9,originLongitude:38,destinationLatitude:8.5,destinationLongitude:39});assert.equal(cargo.request.status,'DRAFT');
    assert.equal((await service.requests('CARGO')).requests.length,0);
    await assert.rejects(service.submitRequest(cargo.request.id,cargo.requestSecret),e=>e.status===409);
    await assert.rejects(service.request(group.request.id,'wrong'),e=>e.status===404);
    await assert.rejects(service.uploadPhoto(cargo.request.id,cargo.requestSecret,Buffer.from('<svg>not allowed</svg>'),'image/svg+xml'),e=>e.status===400);
    const photo=Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl6QmcAAAAASUVORK5CYII=','base64');
    await service.uploadPhoto(cargo.request.id,cargo.requestSecret,photo,'image/png');await service.submitRequest(cargo.request.id,cargo.requestSecret);
    assert.equal((await service.requests('CARGO')).requests.length,1);
    await assert.rejects(service.photo(cargo.request.id,'wrong'),e=>e.status===404);
    assert.deepEqual((await service.photo(cargo.request.id,cargo.requestSecret)).bytes,photo);
    await assert.rejects(service.assignRequest('usr_adm_root',cargo.request.id,{driverId:'group',vehicleId:'groupvehicle'}),e=>e.status===409);
    const assigned=(await service.assignRequest('usr_adm_root',cargo.request.id,{driverId:'cargo',vehicleId:'cargovehicle',quoteEtb:1200,note:'Please call before pickup.'})).request;
    assert.equal(assigned.status,'ASSIGNED');assert.equal(assigned.quoteEtb,1200);
    assert.equal((await service.dashboard('cargo')).requests[0].id,cargo.request.id);
    assert.equal((await service.dashboard('group')).requests.length,0);
    await assert.rejects(service.photo(cargo.request.id,null,{role:'DRIVER',id:'group'}),e=>e.status===404);
    assert.equal((await service.photo(cargo.request.id,null,{role:'DRIVER',id:'cargo'})).contentType,'image/png');
    await service.assignRequest('usr_adm_root',group.request.id,{driverId:'group',vehicleId:'groupvehicle'});
    await service.requestStatus('usr_adm_root',cargo.request.id,{status:'COMPLETED'});
    await assert.rejects(service.assignRequest('usr_adm_root',cargo.request.id,{driverId:'cargo',vehicleId:'cargovehicle'}),e=>e.status===409);
    const noCapacity=await service.createRequest({...requestBase,kind:'GROUP',people:10});
    await assert.rejects(service.assignRequest('usr_adm_root',noCapacity.request.id,{driverId:'group',vehicleId:'groupvehicle'}),e=>e.status===400);
  });
  await t.test('HTTP role guards isolate admin reviews and driver data; missing capabilities do not disclose customers',async()=>{
    const express=require('express'),app=express();app.use(express.json());app.use('/api/mobility',require('../src/routes/mobility').createMobilityRouter({service,rateLimitMax:1000}));
    const server=app.listen(0,'127.0.0.1');await new Promise(resolve=>server.once('listening',resolve));t.after(()=>new Promise(resolve=>server.close(resolve)));
    const base='http://127.0.0.1:'+server.address().port+'/api/mobility';
    const auth=role=>'Bearer '+signToken({id:role==='ADMIN'?'usr_adm_root':'group',role});
    assert.equal((await fetch(base+'/admin/trips')).status,401);
    assert.equal((await fetch(base+'/admin/trips',{headers:{Authorization:auth('DRIVER')}})).status,403);
    assert.equal((await fetch(base+'/driver/dashboard',{headers:{Authorization:auth('ADMIN')}})).status,403);
    assert.equal((await fetch(base+'/admin/requests',{headers:{Authorization:auth('ADMIN')}})).status,200);
    assert.equal((await fetch(base+'/bookings/'+held.booking.id)).status,404);
    const publicTrips=await(await fetch(base+'/trips')).json();assert.equal(publicTrips.success,true);
    const guest=await fetch(base+'/requests',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({...requestBase,kind:'GROUP',people:3})});
    assert.equal(guest.status,201,'Group transport requests do not require an account, login or OTP.');
    const cargoResponse=await fetch(base+'/requests',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({...requestBase,kind:'CARGO',cargoDetails:'Guest cargo without registration'})});
    assert.equal(cargoResponse.status,201);const cargo=await cargoResponse.json();
    const png=Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl6QmcAAAAASUVORK5CYII=','base64');
    assert.equal((await fetch(base+'/requests/'+cargo.request.id+'/photo',{method:'PUT',headers:{'Content-Type':'image/png','X-Request-Secret':cargo.requestSecret},body:png})).status,200);
    assert.equal((await fetch(base+'/requests/'+cargo.request.id+'/submit',{method:'POST',headers:{'X-Request-Secret':cargo.requestSecret}})).status,200,'Cargo photo submission uses private request access without user registration.');
  });
  await t.test('abandoned cargo photo drafts expire privately without changing submitted requests',async()=>{
    const draft=await service.createRequest({...requestBase,kind:'CARGO',cargoDetails:'Unsubmitted draft package'});
    const png=Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl6QmcAAAAASUVORK5CYII=','base64');
    await service.uploadPhoto(draft.request.id,draft.requestSecret,png,'image/png');
    clock+=8*86400000;assert.equal((await service.cleanDrafts()).expired,1);
    const expired=(await service.request(draft.request.id,draft.requestSecret)).request;assert.equal(expired.status,'EXPIRED');assert.equal(expired.customerPhone,'');assert.equal(expired.photoUrl,null);
    await assert.rejects(service.photo(draft.request.id,draft.requestSecret),e=>e.status===404);
    assert.equal((await service.requests('CARGO')).requests.some(x=>x.id===draft.request.id),false);
    assert.equal((await service.requests('CARGO')).requests.some(x=>x.status==='COMPLETED'),true);
  });
});
