'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
process.env.NODE_ENV = 'test';
process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), 'routepass-intercity-'));
delete process.env.DATABASE_URL;
const express = require('express');
const { DB } = require('../src/db');
const { signToken } = require('../src/middleware/auth');
const { initIntercitySchema } = require('../src/services/intercity-schema');
const telebirr = require('../src/services/telebirr');

test('intercity guest tickets, tenant isolation, seat locks and boarding lifecycle', async t => {
  await DB.exec('CREATE TABLE IF NOT EXISTS transport_companies(id TEXT PRIMARY KEY,name TEXT,phone TEXT,representative_name TEXT,status TEXT)');
  for (const [id,name] of [['co1','First Transport'],['co2','Second Transport']]) await DB.prepare('INSERT INTO transport_companies(id,name,phone,status) VALUES(?,?,?,?)').run(id,name,id,'ACTIVE');
  await DB.prepare("INSERT INTO users(id,role,fullName,phone,passwordHash,status,companyId) VALUES('icdriver','DRIVER','Intercity Driver','0999001111','test','ACTIVE','co1')").run();
  await DB.prepare("INSERT INTO routes(id,name,nameAm,routeType,companyId,origin,destination,active) VALUES('icroute','Addis to Hawassa','Addis to Hawassa','INTERCITY','co1','Addis Ababa','Hawassa',1)").run();
  await DB.prepare("INSERT INTO vehicles(id,plateNumber,model,vehicleType,capacityLimit,companyId) VALUES('icbus','TEST-IC-1','Touring Bus','MINIBUS_14',14,'co1')").run();
  await DB.prepare("UPDATE vehicles SET driverId='icdriver',assignedRouteId='icroute' WHERE id='icbus'").run();
  await initIntercitySchema();
  const app=express();app.use(express.json());app.use('/api/intercity',require('../src/routes/intercity'));
  const server=app.listen(0,'127.0.0.1');await new Promise(resolve=>server.once('listening',resolve));t.after(()=>new Promise(resolve=>server.close(resolve)));
  const base='http://127.0.0.1:'+server.address().port+'/api/intercity';
  const tokens={company:signToken({id:'co1',role:'COMPANY'}),other:signToken({id:'co2',role:'COMPANY'}),driver:signToken({id:'icdriver',role:'DRIVER'}),admin:signToken({id:'usr_adm_root',role:'ADMIN'})};
  async function req(url,method='GET',body,role,secret){const r=await fetch(base+url,{method,headers:{'Content-Type':'application/json',...(role?{Authorization:'Bearer '+tokens[role]}:{}),...(secret?{'X-Booking-Secret':secret}:{})},signal:AbortSignal.timeout(15000),...(body?{body:JSON.stringify(body)}:{})});const type=r.headers.get('content-type')||'';return {status:r.status,body:type.includes('application/json')?await r.json():Buffer.from(await r.arrayBuffer()),type};}
  const payload={routeId:'icroute',driverId:'icdriver',vehicleId:'icbus',departureAt:new Date(Date.now()+600000).toISOString(),latitude:9,longitude:38,seats:14,fareEtb:450,radiusMeters:250};
  let id,held,manual,guest;
  await t.test('company publishes its departure; admin cannot inspect operational data',async()=>{
    assert.equal((await req('/company/departures','POST',payload)).status,401);
    assert.equal((await req('/company/departures','POST',payload,'admin')).status,403);
    assert.equal((await req('/company/departures','POST',payload,'other')).status,400);
    const r=await req('/company/departures','POST',payload,'company');assert.equal(r.status,201);id=r.body.departure.id;
    assert.equal((await req('/company/departures', 'GET',null,'admin')).status,403);
    assert.equal((await req('/company/departures','GET',null,'other')).body.departures.length,0);
  });
  await t.test('public search returns transport and dated availability without passenger records',async()=>{
    const r=await req('/departures?origin=Addis&destination=Hawassa');assert.equal(r.status,200);assert.equal(r.body.departures[0].availableSeats,14);assert.equal(r.body.departures[0].companyName,'First Transport');assert.equal(JSON.stringify(r.body).includes('passenger_phone'),false);
    assert.equal((await req('/departures','GET',null,'admin')).status,403);
    assert.equal((await req('/departures?date=not-a-date')).status,400);
  });
  await t.test('anonymous reservation requires a private capability and prevents concurrent duplicate seats',async()=>{
    const r=await req('/departures/'+id+'/hold','POST',{seatNumber:1});assert.equal(r.status,201);held=r.body;
    assert.equal((await req('/bookings/'+held.booking.id)).status,404);
    assert.equal((await req('/bookings/'+held.booking.id,'GET',null,null,'wrong-secret')).status,404);
    assert.equal((await req('/bookings/'+held.booking.id,'GET',null,null,held.bookingSecret)).status,200);
    const concurrent=await Promise.all([req('/departures/'+id+'/hold','POST',{seatNumber:2}),req('/departures/'+id+'/hold','POST',{seatNumber:2})]);assert.deepEqual(concurrent.map(r=>r.status).sort(),[201,409]);
    assert.equal((await req('/departures/'+id+'/hold','POST',{seatNumber:99})).status,400);
  });
  await t.test('unconfigured payment fails closed; request bodies cannot mark tickets paid',async()=>{
    const original=telebirr.missingConfiguration;telebirr.missingConfiguration=()=>['merchant credentials'];
    try {const r=await req('/bookings/'+held.booking.id+'/checkout','POST',{passengerName:'Guest Rider',passengerPhone:'0911223344',paymentStatus:'PAID'},null,held.bookingSecret);assert.equal(r.status,503);assert.equal((await req('/bookings/'+held.booking.id+'/ticket.pdf','GET',null,null,held.bookingSecret)).status,409);}finally{telebirr.missingConfiguration=original;}
  });
  await t.test('verified server provider reconciliation alone settles a guest ticket',async()=>{
    const r=await req('/departures/'+id+'/hold','POST',{seatNumber:4});guest=r.body;
    const originals={missingConfiguration:telebirr.missingConfiguration,config:telebirr.config,createCheckout:telebirr.createCheckout,queryOrderStatus:telebirr.queryOrderStatus};let order,callbacks;
    const originalBase=process.env.PUBLIC_BASE_URL,originalNotify=process.env.TELEBIRR_INTERCITY_NOTIFY_URL;delete process.env.PUBLIC_BASE_URL;delete process.env.TELEBIRR_INTERCITY_NOTIFY_URL;
    telebirr.missingConfiguration=()=>[];telebirr.config=()=>({environment:'production'});telebirr.createCheckout=async (o,_amount,_title,urls)=>{order=o;callbacks=urls;return {prepayId:'prepay-1',checkoutUrl:'https://payments.example.test/checkout'};};
    telebirr.queryOrderStatus=async()=>({paid:true,failed:false,merchantOrderId:order,currency:'ETB',amount:'1',transactionId:'tx-wrong'});
    try {
      const c=await req('/bookings/'+guest.booking.id+'/checkout','POST',{passengerName:'Verified Guest',passengerPhone:'0911556677'},null,guest.bookingSecret);assert.equal(c.status,200);assert.match(c.body.checkoutUrl,/^https:/);
      assert.deepEqual(callbacks,{notifyUrl:'https://passo.live/api/intercity/payments/webhook',redirectUrl:'https://passo.live/travel/?booking='+encodeURIComponent(guest.booking.id)});
      assert.equal(JSON.stringify(callbacks).includes(guest.bookingSecret),false,'Private booking capabilities never enter provider callback URLs.');
      assert.equal((await req('/bookings/'+guest.booking.id,'GET',null,null,guest.bookingSecret)).status,502);
      telebirr.queryOrderStatus=async()=>({paid:true,failed:false,merchantOrderId:order,currency:'ETB',amount:'450.00',transactionId:'tx-verified'});
      const paid=await req('/bookings/'+guest.booking.id,'GET',null,null,guest.bookingSecret);assert.equal(paid.status,200);assert.equal(paid.body.booking.paymentStatus,'PAID');assert.equal(paid.body.booking.passengerName,'Verified Guest');assert.match(paid.body.booking.qrImage,/^data:image\/png;base64,/);
    }finally{Object.assign(telebirr,originals);if(originalBase===undefined)delete process.env.PUBLIC_BASE_URL;else process.env.PUBLIC_BASE_URL=originalBase;if(originalNotify===undefined)delete process.env.TELEBIRR_INTERCITY_NOTIFY_URL;else process.env.TELEBIRR_INTERCITY_NOTIFY_URL=originalNotify;}
  });
  await t.test('registered legacy intercity webhook remains configurable while payment return uses the canonical domain',async()=>{
    const booking=(await req('/departures/'+id+'/hold','POST',{seatNumber:5})).body;
    const originals={missingConfiguration:telebirr.missingConfiguration,config:telebirr.config,createCheckout:telebirr.createCheckout};
    const originalBase=process.env.PUBLIC_BASE_URL,originalNotify=process.env.TELEBIRR_INTERCITY_NOTIFY_URL;
    process.env.PUBLIC_BASE_URL='https://passo.live';process.env.TELEBIRR_INTERCITY_NOTIFY_URL='https://routepass.duckdns.org/api/intercity/payments/webhook';
    let callbacks;telebirr.missingConfiguration=()=>[];telebirr.config=()=>({environment:'production'});
    telebirr.createCheckout=async (_order,_amount,_title,urls)=>{callbacks=urls;return {prepayId:'legacy-notify-prepay',checkoutUrl:'https://payments.example.test/checkout'};};
    try {
      const result=await req('/bookings/'+booking.booking.id+'/checkout','POST',{passengerName:'Migration Rider',passengerPhone:'0911556688'},null,booking.bookingSecret);
      assert.equal(result.status,200);
      assert.deepEqual(callbacks,{notifyUrl:'https://routepass.duckdns.org/api/intercity/payments/webhook',redirectUrl:'https://passo.live/travel/?booking='+encodeURIComponent(booking.booking.id)});
      assert.equal(JSON.stringify(callbacks).includes(booking.bookingSecret),false);
    } finally {
      Object.assign(telebirr,originals);
      if(originalBase===undefined)delete process.env.PUBLIC_BASE_URL;else process.env.PUBLIC_BASE_URL=originalBase;
      if(originalNotify===undefined)delete process.env.TELEBIRR_INTERCITY_NOTIFY_URL;else process.env.TELEBIRR_INTERCITY_NOTIFY_URL=originalNotify;
    }
  });
  await t.test('manual tickets require an explicit company cash collection and include a signed QR/PDF',async()=>{
    const p={seatNumber:3,passengerName:'Walk In Passenger',passengerPhone:'+251911222333',paymentNote:'Receipt 240',paymentCollected:false};
    assert.equal((await req('/company/departures/'+id+'/bookings','POST',p,'company')).status,400);
    p.paymentCollected=true;assert.equal((await req('/company/departures/'+id+'/bookings','POST',p,'other')).status,404);
    const r=await req('/company/departures/'+id+'/bookings','POST',p,'company');assert.equal(r.status,201);manual=r.body.booking;assert.equal(manual.paymentStatus,'PAID');assert.equal(manual.paymentMethod,'CASH_COLLECTED');assert.ok(manual.qrToken);
    const pdf=await req('/company/bookings/'+manual.id+'/ticket.pdf','GET',null,'company');assert.equal(pdf.status,200);assert.equal(pdf.type,'application/pdf');assert.equal(pdf.body.subarray(0,4).toString(),'%PDF');
    const link=await req('/company/bookings/'+manual.id+'/download','GET',null,'company');assert.equal(link.status,200);
    assert.match(link.body.ticketDownloadUrl,/^\/api\/intercity\/tickets\//);
    for(const origin of ['https://passo.live','https://routepass.duckdns.org'])assert.equal(new URL(link.body.ticketDownloadUrl,origin).origin,origin,'PDF capability stays on the current first-party API origin.');
    assert.equal(link.body.ticketDownloadUrl.includes(tokens.company),false,'Downloads never forward the company login token.');
    const signedPath=link.body.ticketDownloadUrl.replace('/api/intercity','');assert.equal((await req(signedPath)).status,200);assert.equal((await req(signedPath+'tamper')).status,401);assert.equal((await req('/company/bookings/'+manual.id+'/download','GET',null,'other')).status,404);
    assert.equal((await req('/company/bookings/'+manual.id+'/ticket.pdf','GET',null,'other')).status,404);
    assert.equal((await req('/company/bookings/'+manual.id+'/ticket.pdf','GET',null,'admin')).status,403);
  });
  await t.test('company owns sortable rosters and route requests; other companies see none',async()=>{
    const r=await req('/company/roster?sort=name&direction=asc','GET',null,'company');assert.equal(r.status,200);assert.ok(r.body.roster.some(b=>b.passengerName==='Walk In Passenger'));
    assert.equal((await req('/company/roster','GET',null,'other')).body.roster.length,0);
    assert.equal((await req('/company/roster','GET',null,'admin')).status,403);
    const travelDate=new Date(Date.now()+86400000).toISOString().slice(0,10);assert.equal((await req('/requests','POST',{origin:'Addis',destination:'Bahir Dar',date:travelDate,companyId:'co1',passengerName:'Future Rider',passengerPhone:'0912345678'})).status,201);
    assert.equal((await req('/company/requests','GET',null,'company')).body.requests.length,1);assert.equal((await req('/company/requests','GET',null,'other')).body.requests.length,0);
  });
  await t.test('adjustable departure radius, fresh precise GPS and server departure window gate boarding',async()=>{
    assert.equal((await req('/company/departures/'+id,'PATCH',{radiusMeters:100},'other')).status,404);
    assert.equal((await req('/company/departures/'+id,'PATCH',{radiusMeters:10},'company')).status,400);
    assert.equal((await req('/company/departures/'+id,'PATCH',{radiusMeters:100},'company')).status,200);
    const changed=await req('/company/departures/'+id,'PATCH',{latitude:9,longitude:38,departureAt:new Date(Date.now()+600000).toISOString()},'company');assert.equal(changed.status,200);assert.equal(changed.body.departure.latitude,9);
    assert.equal((await req('/driver/departures/'+id+'/board','POST',{qrToken:manual.qrToken},'driver')).status,409);
    const location={latitude:9.02,longitude:38,accuracyMeters:5,timestamp:new Date().toISOString()};assert.equal((await req('/driver/departures/'+id+'/arrival','POST',{location},'driver')).status,403);
    location.latitude=9;location.timestamp=new Date(Date.now()-130000).toISOString();assert.equal((await req('/driver/departures/'+id+'/arrival','POST',{location},'driver')).status,403);
    location.timestamp=new Date().toISOString();const arrived=await req('/driver/departures/'+id+'/arrival','POST',{location},'driver');assert.equal(arrived.status,200);assert.equal(arrived.body.departure.status,'BOARDING');
    const boarded=await req('/driver/departures/'+id+'/board','POST',{qrToken:manual.qrToken},'driver');assert.equal(boarded.status,200);assert.equal(boarded.body.booking.status,'BOARDED');assert.equal((await req('/driver/departures/'+id+'/board','POST',{qrToken:manual.qrToken},'driver')).status,409);
    assert.equal((await req('/driver/departures/'+id+'/board','POST',{qrToken:manual.qrToken+'tampered'},'driver')).status,400);
    const roster=await req('/driver/departures/'+id+'/roster','GET',null,'driver');assert.equal(roster.body.roster.find(b=>b.id===manual.id).status,'BOARDED');
    const completed=await req('/driver/departures/'+id+'/complete','POST',{},'driver');assert.equal(completed.status,200);assert.equal(completed.body.departure.status,'COMPLETED');assert.equal((await req('/driver/departures/'+id+'/board','POST',{qrToken:manual.qrToken},'driver')).status,409);
  });
  await t.test('performance is scoped and reports verified paid tickets and boarded counts',async()=>{
    for(const period of ['daily','weekly','monthly']) {const r=await req('/company/performance?period='+period,'GET',null,'company');assert.equal(r.status,200);assert.equal(r.body.total.bookings,2);assert.equal(r.body.total.revenueEtb,900);assert.equal(r.body.total.boarded,1);}
    assert.equal((await req('/company/performance','GET',null,'other')).body.total.bookings,0);assert.equal((await req('/company/performance','GET',null,'admin')).status,403);
  });
  await t.test('expired holds can be reused and arrival cannot be opened outside the 30-minute window',async()=>{
    const p={...payload,departureAt:new Date(Date.now()+86400000).toISOString()};const d=(await req('/company/departures','POST',p,'company')).body.departure;
    const h=(await req('/departures/'+d.id+'/hold','POST',{seatNumber:1})).body;await DB.prepare('UPDATE intercity_bookings SET expires_at=? WHERE id=?').run(new Date(Date.now()-1000).toISOString(),h.booking.id);
    assert.equal((await req('/departures/'+d.id+'/hold','POST',{seatNumber:1})).status,201);
    const r=await req('/driver/departures/'+d.id+'/arrival','POST',{location:{latitude:9,longitude:38,accuracyMeters:5,timestamp:new Date().toISOString()}},'driver');assert.equal(r.status,403);assert.equal(r.body.code,'OUTSIDE_DEPARTURE_WINDOW');
  });
  await t.test('arrival rechecks the live assignment after a departure was scheduled',async()=>{
    const d=(await req('/company/departures','POST',{...payload,departureAt:new Date(Date.now()+600000).toISOString()},'company')).body.departure;
    const location={latitude:9,longitude:38,accuracyMeters:5,timestamp:new Date().toISOString()};
    for (const change of ["driverId=NULL", "assignedRouteId=NULL", "companyId='co2'"]) {
      await DB.prepare('UPDATE vehicles SET '+change+" WHERE id='icbus'").run();
      const r=await req('/driver/departures/'+d.id+'/arrival','POST',{location},'driver');assert.equal(r.status,409);assert.match(r.body.error,/assignment/i);
      assert.equal((await DB.prepare('SELECT status FROM intercity_departures WHERE id=?').get(d.id)).status,'SCHEDULED');
      await DB.prepare("UPDATE vehicles SET driverId='icdriver',assignedRouteId='icroute',companyId='co1' WHERE id='icbus'").run();
    }
    await DB.prepare("UPDATE users SET companyId='co2' WHERE id='icdriver'").run();
    assert.equal((await req('/driver/departures/'+d.id+'/arrival','POST',{location},'driver')).status,409);
    await DB.prepare("UPDATE users SET companyId='co1',status='SUSPENDED' WHERE id='icdriver'").run();
    assert.equal((await req('/driver/departures/'+d.id+'/arrival','POST',{location},'driver')).status,401);
    await DB.prepare("UPDATE users SET status='ACTIVE' WHERE id='icdriver'").run();
  });
  await t.test('simultaneous departures cannot both open boarding for the same vehicle or driver',async()=>{
    const p={...payload,departureAt:new Date(Date.now()+600000).toISOString()};
    const first=(await req('/company/departures','POST',p,'company')).body.departure;
    const second=(await req('/company/departures','POST',p,'company')).body.departure;
    const location={latitude:9,longitude:38,accuracyMeters:5,timestamp:new Date().toISOString()};
    const competing=await Promise.all([req('/driver/departures/'+first.id+'/arrival','POST',{location},'driver'),req('/driver/departures/'+second.id+'/arrival','POST',{location},'driver')]);
    assert.deepEqual(competing.map(r=>r.status).sort(),[200,409]);
    assert.equal((await DB.prepare("SELECT COUNT(*) AS count FROM intercity_departures WHERE status='BOARDING' AND (vehicle_id='icbus' OR driver_id='icdriver')").get()).count,1);
    const active=competing.find(r=>r.status===200).body.departure;
    // Completion remains available to the scheduled owner, even if legacy
    // assignment state was changed outside the guarded company workflow.
    await DB.prepare("UPDATE vehicles SET driverId=NULL,assignedRouteId=NULL WHERE id='icbus'").run();
    assert.equal((await req('/driver/departures/'+active.id+'/complete','POST',{},'driver')).status,200);
    await DB.prepare("UPDATE vehicles SET driverId='icdriver',assignedRouteId='icroute' WHERE id='icbus'").run();
  });
});
