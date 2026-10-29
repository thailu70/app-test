'use strict';
const crypto = require('node:crypto');
const fs = require('node:fs/promises');
const path = require('node:path');
const bcrypt = require('bcryptjs');
const { getReviewRuntime } = require('./isolation');
const sha = value => crypto.createHash('sha256').update(value).digest('hex');
const PNG = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl6QmcAAAAASUVORK5CYII=', 'base64');
const ACCOUNTS = Object.freeze([
  ['passenger','PASSENGER','Demo Adult Passenger','+251900000101',''],
  ['daily','DRIVER','Demo Daily Driver','+251900000102','DAILY_COMMUTE'],
  ['group','DRIVER','Demo Group Driver','+251900000103','GROUP_TRAVEL'],
  ['cargo','DRIVER','Demo Cargo Driver','+251900000104','CARGO'],
  ['company-driver','DRIVER','Demo Company Driver','+251900000105','INTERCITY'],
  ['pending','DRIVER','Demo Pending Applicant','+251900000106','DAILY_COMMUTE'],
  ['accepted','DRIVER','Demo Accepted Applicant','+251900000107','DAILY_COMMUTE'],
  ['disposable','PASSENGER','Demo Disposable Adult','+251900000108',''],
  ['company-applicant','DRIVER','Demo Company Applicant','+251900000110','INTERCITY']
]);
function createReviewFixtures(db, config, { now = Date.now } = {}) {
  if (config !== getReviewRuntime()) throw Error('Review fixtures require the validated isolated runtime.');
  const capability = slot => crypto.createHmac('sha256',config.fixtureSecret).update('passo-review:' + slot).digest('base64url');
  const instant = () => new Date(now()).toISOString();
  let passwordHash, refreshing;
  async function init() {
    await db.exec(`CREATE TABLE IF NOT EXISTS review_fixture_owners (
      entity_type TEXT NOT NULL,entity_id TEXT NOT NULL,marker TEXT NOT NULL,PRIMARY KEY(entity_type,entity_id));
      CREATE TABLE IF NOT EXISTS review_fixture_slots (slot TEXT PRIMARY KEY,entity_id TEXT NOT NULL);`);
    passwordHash ||= await bcrypt.hash(config.password,12);
  }
  async function owned(kind,id,tx=db) {
    const row=await tx.prepare('SELECT marker FROM review_fixture_owners WHERE entity_type=? AND entity_id=?').get(kind,id);
    return row?.marker === config.marker;
  }
  async function claim(kind,id,table,tx=db) {
    if (await owned(kind,id,tx)) return;
    if (await tx.prepare('SELECT id FROM ' + table + ' WHERE id=?').get(id)) throw Error('Review fixture ID collision; refusing to overwrite unowned ' + kind);
    await tx.prepare('INSERT INTO review_fixture_owners(entity_type,entity_id,marker) VALUES(?,?,?)').run(kind,id,config.marker);
  }
  async function slot(name) { return (await db.prepare('SELECT entity_id FROM review_fixture_slots WHERE slot=?').get(name))?.entity_id; }
  async function setSlot(name,id) { await db.prepare('INSERT INTO review_fixture_slots(slot,entity_id) VALUES(?,?) ON CONFLICT(slot) DO UPDATE SET entity_id=excluded.entity_id').run(name,id); }
  async function account(spec) {
    const [key,role,name,phone,type]=spec;
    let id=await slot('account:'+key), row=id && await db.prepare('SELECT * FROM users WHERE id=?').get(id);
    if (row && !await owned('user',id)) throw Error('Review account ownership changed.');
    const closed=row && await require('../services/account-deletion').isAccountClosed(id,role,db);
    if(!row || closed) {
      // Recover an interrupted initialization only if its exact account was
      // already claimed by this deployment; never adopt a phone collision.
      const collision=await db.prepare('SELECT * FROM users WHERE phone=?').get(phone);
      if(collision && !await owned('user',collision.id)) throw Error('Synthetic review phone is already owned by another account.');
      if(collision && await require('../services/account-deletion').isAccountClosed(collision.id,role,db)) throw Error('A retained deletion receipt cannot be restored.');
      id=collision?.id || 'rv_'+key.replace(/-/g,'_')+'_'+crypto.randomBytes(5).toString('hex');
      row=collision;
    }
    await claim('user',id,'users');
    const company=['company-driver','company-applicant'].includes(key)?'review_company':'';
    await db.prepare('INSERT INTO users(id,role,fullName,phone,passwordHash,status,licenseNumber,companyId,companyName,assignedVehiclePlate) VALUES(?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO NOTHING')
      .run(id,role,name,phone,passwordHash,['pending','accepted','company-applicant'].includes(key)?'PENDING':'ACTIVE',role==='DRIVER'?'SYNTHETIC-DEMO-LICENSE':'',company,company?'Demo Transport Company':'',role==='DRIVER'?'DEMO-'+key.toUpperCase():'');
    if (role==='DRIVER') {
      const pending=key==='pending', accepted=key==='accepted', applicant=key==='company-applicant', approved=!pending&&!accepted&&!applicant, documented=approved||applicant;
      await db.prepare(`INSERT INTO onboarding_accounts(account_id,role,normalized_phone,service_type,created_at,updated_at,acceptance_status,accepted_at,accepted_by,acceptance_grandfathered,initial_submitted_at,submitted_at,review_status,reviewed_at,reviewed_by,review_note,route_choice,proposed_departure,proposed_destination,proposed_monthly_fee_etb,proposed_monthly_fee_basis,quote_vehicle_type,quote_passenger_capacity)
        VALUES(?,'DRIVER',?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(account_id) DO NOTHING`)
        .run(id,phone,type,instant(),instant(),applicant?'NOT_REQUIRED':pending?'PENDING':'ACCEPTED',pending?null:instant(),pending?null:'review_internal_reviewer',db.isPostgres?approved:approved?1:0,instant(),documented?instant():null,pending?'AWAITING_ACCEPTANCE':accepted?'DRAFT':applicant?'SUBMITTED':'APPROVED',approved?instant():null,approved?'review_internal_reviewer':null,'Synthetic demonstration account; no identity or commercial verification implied.','PROPOSED','Demo origin','Demo destination',type==='DAILY_COMMUTE'?20000:null,type==='DAILY_COMMUTE'?'PER_VEHICLE':'','MINIVAN_8',8);
      if (documented) {
        await db.prepare('INSERT INTO user_profiles(user_id,emergency_contact_name,emergency_contact_phone,vehicle_color,updated_at) VALUES(?,?,?,?,?) ON CONFLICT(user_id) DO NOTHING').run(id,'Demo Emergency Contact','+251900000199','Demo teal and white',instant());
        const folder=path.join(config.uploadDirectory,'profiles'); await fs.mkdir(folder,{recursive:true,mode:0o700});
        for (const kind of ['LICENSE','VEHICLE_REGISTRATION','INSURANCE','PHOTO']) {
          if(await db.prepare('SELECT kind FROM user_profile_documents WHERE user_id=? AND kind=?').get(id,kind)) continue;
          const isPhoto=kind==='PHOTO', bytes=isPhoto?PNG:await document(kind), storage=crypto.randomUUID()+(isPhoto?'.png':'.pdf');
          await fs.writeFile(path.join(folder,storage),bytes,{flag:'wx',mode:0o600});
          await db.prepare('INSERT INTO user_profile_documents(user_id,kind,storage_name,content_type,byte_size,uploaded_at) VALUES(?,?,?,?,?,?)').run(id,kind,storage,isPhoto?'image/png':'application/pdf',bytes.length,instant());
        }
      }
    }
    await setSlot('account:'+key,id); return id;
  }
  async function document(kind) {
    const PDFDocument=require('pdfkit');
    return new Promise((resolve,reject)=>{ const chunks=[], doc=new PDFDocument({size:'A5',margin:32}); doc.on('data',c=>chunks.push(c));doc.on('error',reject);doc.on('end',()=>resolve(Buffer.concat(chunks)));
      doc.fontSize(24).text('SYNTHETIC DEMO');doc.moveDown().fontSize(15).text(kind.replace(/_/g,' '));doc.moveDown().text('Not a real identity, licence, registration or insurance document. For Passo app demonstration only.');doc.end(); });
  }
  async function route(id,name,company='') {
    await claim('route',id,'routes');
    await db.prepare('INSERT INTO routes(id,name,nameAm,description,morningDeparture,eveningDeparture,distanceKm,basePriceEtb,companyId,routeType,origin,destination,active) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO NOTHING')
      .run(id,name,name,'Synthetic demonstration route. No real transport is offered.','06:30','17:30',10,2500,company,company?'INTERCITY':'DAILY_COMMUTE','Demo origin','Demo destination',db.isPostgres?true:1);
    for (const [suffix,order,lat,lng] of [['origin',1,9,38.7],['destination',2,9.01,38.71]]) {
      const stop=id+'_'+suffix; await claim('stop',stop,'route_stops');
      await db.prepare('INSERT INTO route_stops(id,routeId,stopName,stopNameAm,stopOrder,latitude,longitude,scheduledMorningTime,scheduledEveningTime,maxCapacity) VALUES(?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO NOTHING')
        .run(stop,id,'Demo '+suffix,'Demo '+suffix,order,lat,lng,'06:30','17:30',8);
    }
  }
  async function vehicle(key,driver,routeId,company='') {
    const id='review_vehicle_'+key;await claim('vehicle',id,'vehicles');
    await db.prepare('INSERT INTO vehicles(id,plateNumber,model,vehicleType,capacityLimit,currentOccupancy,driverId,driverName,assignedRouteId,companyId,status) VALUES(?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO NOTHING')
      .run(id,'DEMO-'+key.toUpperCase(),'Synthetic demo minivan','MINIVAN_8',8,0,driver,'Demo '+key+' driver',routeId,company,'IN_SERVICE');
    // Only transfer our own synthetic slot when its previous account was deleted.
    const row=await db.prepare('SELECT driverId FROM vehicles WHERE id=?').get(id);
    if (row.driverId!==driver) await db.prepare("UPDATE vehicles SET driverId=?,status='IN_SERVICE',currentOccupancy=0 WHERE id=?").run(driver,id);
    return id;
  }
  async function journeySlot(key,kind,table,terminal) {
    let id=await slot(key), row=id && await db.prepare('SELECT * FROM '+table+' WHERE id=?').get(id);
    if (row && !await owned(kind,id)) throw Error('Review journey ownership changed.');
    if (row && !terminal.includes(row.status)) return {id,row};
    id='rv_'+kind+'_'+crypto.randomBytes(8).toString('hex');await claim(kind,id,table);await setSlot(key,id);return {id,row:null};
  }
  async function companyOwner() {
    const id='review_company',phone='+251900000109';
    await claim('company',id,'transport_companies');
    const existing=await db.prepare('SELECT id,phone FROM transport_companies WHERE id=?').get(id);
    const collision=await db.prepare('SELECT id FROM transport_companies WHERE phone=? AND id<>?').get(phone,id);
    const enrollment=await db.prepare('SELECT account_id FROM onboarding_accounts WHERE normalized_phone=? AND account_id<>?').get(phone,id);
    if(collision||enrollment||existing && !['+251900000190',phone].includes(existing.phone)) throw Error('Review company owner phone ownership changed.');
    const initialized=await db.prepare('SELECT account_id FROM onboarding_accounts WHERE account_id=?').get(id);
    await db.prepare('INSERT INTO transport_companies(id,name,phone,representative_name,password_hash,status) VALUES(?,?,?,?,?,?) ON CONFLICT(id) DO NOTHING').run(id,'Demo Transport Company',phone,'Demo Adult Company Owner',passwordHash,'ACTIVE');
    // Upgrade the earlier owned non-login placeholder exactly once. Never
    // modify a production company or relax its real phone verification policy.
    if(!initialized) await db.prepare('UPDATE transport_companies SET phone=?,representative_name=?,password_hash=? WHERE id=?').run(phone,'Demo Adult Company Owner',passwordHash,id);
    await db.prepare(`INSERT INTO onboarding_accounts(account_id,role,normalized_phone,phone_verified_at,phone_verified_for,service_type,submitted_at,review_status,reviewed_at,reviewed_by,review_note,company_address,company_registration_number,acceptance_status,created_at,updated_at)
      VALUES(?,'COMPANY',?,?,?,'COMPANY',?,'APPROVED',?,'review_internal_reviewer','Synthetic company, preverified for isolated demonstration only.','Demo address - no real premises','SYNTHETIC-NOT-A-REAL-REGISTRATION','NOT_REQUIRED',?,?) ON CONFLICT(account_id) DO NOTHING`)
      .run(id,phone,instant(),phone,instant(),instant(),instant(),instant());
    const folder=path.join(config.uploadDirectory,'onboarding-companies');await fs.mkdir(folder,{recursive:true,mode:0o700});
    for(const kind of require('../services/onboarding').REQUIRED.COMPANY) {
      if(await db.prepare('SELECT kind FROM onboarding_company_documents WHERE company_id=? AND kind=?').get(id,kind))continue;
      const bytes=await document(kind),storage=crypto.randomUUID()+'.pdf';await fs.writeFile(path.join(folder,storage),bytes,{flag:'wx',mode:0o600});
      await db.prepare('INSERT INTO onboarding_company_documents(company_id,kind,storage_name,content_type,byte_size,uploaded_at) VALUES(?,?,?,\'application/pdf\',?,?)').run(id,kind,storage,bytes.length,instant());
    }
    return id;
  }
  async function refresh() {
    if (refreshing) return refreshing;
    refreshing=(async()=>{
      await init();
      const marker=await db.prepare('SELECT marker FROM review_environment_marker WHERE id=1').get();
      if(marker?.marker!==config.marker) throw Error('Review database ownership marker changed.');
      const company=await companyOwner();
      const accounts={};for(const spec of ACCOUNTS) accounts[spec[0]]=await account(spec);
      await route('review_daily_route','Demo daily commute');await route('review_company_route','Demo intercity route',company);
      const dailyVehicle=await vehicle('daily',accounts.daily,'review_daily_route');
      const groupVehicle=await vehicle('group',accounts.group,null),cargoVehicle=await vehicle('cargo',accounts.cargo,null),companyVehicle=await vehicle('company',accounts['company-driver'],'review_company_route',company);
      const assignment='review_company_assignment';await claim('company-assignment',assignment,'company_route_assignments');
      await db.prepare("INSERT INTO company_route_assignments(id,company_id,route_id,driver_id,vehicle_id,status) VALUES(?,?,?,?,?,'ACTIVE') ON CONFLICT(id) DO NOTHING").run(assignment,company,'review_company_route',accounts['company-driver'],companyVehicle);
      await vehicle('company-applicant',accounts['company-applicant'],null,company);
      const request='review_company_route_request';await claim('company-request',request,'intercity_route_requests');
      await db.prepare('INSERT INTO intercity_route_requests(id,origin,destination,travel_date,passenger_name,passenger_phone,company_id,created_at) VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(id) DO NOTHING').run(request,'Demo request origin','Demo request destination',new Date(now()+86400000).toISOString().slice(0,10),'Demo Adult Requester','+251900000101',company,instant());
      for(const key of ['pending','accepted']) {
        const id=await vehicle(key,accounts[key],null);
        await db.prepare("UPDATE vehicles SET status='OFF_DUTY' WHERE id=?").run(id);
      }
      const expires=now()+90*86400000,expiry=new Date(expires).toISOString().slice(0,10),sub='review_subscription_'+accounts.passenger;
      await claim('subscription',sub,'subscriptions');
      const qr=require('../routes/subscriptions').generateSignedQrToken(sub,accounts.passenger,'review_daily_route',expires);
      await db.prepare(`INSERT INTO subscriptions(id,passengerId,routeId,pickupStopId,destinationStopId,morningSchedule,eveningSchedule,startDate,endDate,priceEtb,paymentStatus,subscriptionStatus,vehicleId,qrToken,daysRemaining,updatedAt)
        VALUES(?,?,?,?,?,?,?,?,?,?,'PAID','ACTIVE',?,?,90,?) ON CONFLICT(id) DO UPDATE SET endDate=excluded.endDate,qrToken=excluded.qrToken,daysRemaining=90,updatedAt=excluded.updatedAt`)
        .run(sub,accounts.passenger,'review_daily_route','review_daily_route_origin','review_daily_route_destination','06:30','17:30',instant().slice(0,10),expiry,2500,dailyVehicle,qr,instant());
      const dep=await journeySlot('intercity-departure','departure','intercity_departures',['COMPLETED','CANCELLED']);
      const soon=new Date(now()+15*60000).toISOString();
      if(!dep.row) await db.prepare("INSERT INTO intercity_departures(id,company_id,route_id,driver_id,vehicle_id,origin,destination,departure_at,latitude,longitude,radius_m,seats,fare_etb,status,created_at) VALUES(?,?,?,?,?,?,?, ?,9,38.7,300,8,500,'SCHEDULED',?)").run(dep.id,company,'review_company_route',accounts['company-driver'],companyVehicle,'Demo origin','Demo destination',soon,instant());
      else if(dep.row.status==='SCHEDULED') await db.prepare('UPDATE intercity_departures SET departure_at=? WHERE id=?').run(soon,dep.id);
      const intercityBooking='rv_ib_'+dep.id;await claim('intercity-booking',intercityBooking,'intercity_bookings');
      await db.prepare("INSERT INTO intercity_bookings(id,departure_id,company_id,secret_hash,passenger_name,passenger_phone,seat_number,amount_etb,status,payment_status,payment_method,payment_reference,expires_at,created_at) VALUES(?,?,?,?,?,?,1,500,'CONFIRMED','PAID','SYNTHETIC_REVIEW','NO_REAL_PAYMENT',?,?) ON CONFLICT(id) DO NOTHING")
        .run(intercityBooking,dep.id,company,sha(capability(intercityBooking)),'Demo Adult Traveler','+251900000101',new Date(now()+7*86400000).toISOString(),instant());
      await db.prepare('INSERT INTO intercity_seats(departure_id,seat_number,booking_id) VALUES(?,1,?) ON CONFLICT(departure_id,seat_number) DO NOTHING').run(dep.id,intercityBooking);
      const group=await journeySlot('group-trip','group-trip','mobility_trips',['COMPLETED','CANCELLED']);
      if(!group.row) await db.prepare("INSERT INTO mobility_trips(id,driver_id,vehicle_id,origin,destination,departure_at,latitude,longitude,seats,fare_etb,status,review_note,reviewed_by,reviewed_at,created_at) VALUES(?,?,?,?,?,?,9,38.7,8,500,'APPROVED','Synthetic demonstration trip','review_internal_reviewer',?,?)").run(group.id,accounts.group,groupVehicle,'Demo origin','Demo destination',soon,instant(),instant());
      else if(group.row.status==='APPROVED') await db.prepare('UPDATE mobility_trips SET departure_at=? WHERE id=?').run(soon,group.id);
      const groupBooking='rv_gb_'+group.id;await claim('group-booking',groupBooking,'mobility_bookings');
      await db.prepare("INSERT INTO mobility_bookings(id,trip_id,secret_hash,buyer_name,buyer_phone,amount_etb,status,payment_status,payment_reference,expires_at,created_at) VALUES(?,?,?,?,?,1000,'CONFIRMED','PAID','SYNTHETIC_REVIEW_NO_REAL_PAYMENT',?,?) ON CONFLICT(id) DO NOTHING").run(groupBooking,group.id,sha(capability(groupBooking)),'Demo Adult Buyer','+251900000101',new Date(now()+7*86400000).toISOString(),instant());
      for(let seat=1;seat<=2;seat++) {
        const traveler='rv_tv_'+group.id+'_'+seat;await claim('traveler',traveler,'mobility_travelers');
        await db.prepare("INSERT INTO mobility_travelers(id,booking_id,trip_id,name,phone,seat_number,ticket_number,status) VALUES(?,?,?,?,?,?,?,'CONFIRMED') ON CONFLICT(id) DO NOTHING").run(traveler,groupBooking,group.id,'Demo Adult Traveler '+seat,'+25190000010'+seat,seat,'PG-'+sha(traveler).slice(0,16).toUpperCase());
        await db.prepare('INSERT INTO mobility_seats(trip_id,seat_number,traveler_id,booking_id) VALUES(?,?,?,?) ON CONFLICT(trip_id,seat_number) DO NOTHING').run(group.id,seat,traveler,groupBooking);
      }
      for(const [kind,driver,veh] of [['GROUP',accounts.group,groupVehicle],['CARGO',accounts.cargo,cargoVehicle]]) {
        const id='review_request_'+kind.toLowerCase();await claim('request',id,'mobility_requests');
        const cargo=kind==='CARGO',folder=path.join(config.uploadDirectory,'mobility'),storage='11111111-1111-4111-8111-111111111111.blob';
        if(cargo){await fs.mkdir(folder,{recursive:true,mode:0o700});await fs.writeFile(path.join(folder,storage),PNG,{mode:0o600});}
        await db.prepare(`INSERT INTO mobility_requests(id,kind,secret_hash,origin,destination,travel_date,people,vehicle_type,customer_name,customer_phone,details,photo_storage,photo_type,photo_size,status,driver_id,vehicle_id,quote_etb,review_note,assigned_by,assigned_at,created_at)
          VALUES(?,?,?,?,?,?,?,'MINIVAN_8','Demo Adult Customer','+251900000101',?,?,?,?, 'ASSIGNED',?,?,1200,'Synthetic assignment; no real transport or charge.','review_internal_reviewer',?,?) ON CONFLICT(id) DO NOTHING`)
          .run(id,kind,sha(capability(id)),'Demo origin','Demo destination',new Date(now()+86400000).toISOString().slice(0,10),cargo?null:4,cargo?'Synthetic cargo: empty demonstration cartons.':'Synthetic group journey.',cargo?storage:null,cargo?'image/png':null,cargo?PNG.length:null,driver,veh,instant(),instant());
      }
      await setSlot('group-booking',groupBooking);await setSlot('intercity-booking',intercityBooking);
      // Queue items are intentionally never delivered. Discard synthetic outbox
      // messages rather than repeatedly retrying or retaining contact text.
      await db.prepare("UPDATE mobility_sms_outbox SET status='FAILED',message='Synthetic review: SMS disabled',phone='' WHERE status IN ('PENDING','SENDING')").run();
      return accounts;
    })().finally(()=>{refreshing=null;});return refreshing;
  }
  async function resources(principal) {
    if(!principal || !await owned('user',principal.id)) return null;
    const group=await slot('group-booking'),intercity=await slot('intercity-booking');
    return {success:true,environment:'REVIEW',synthetic:true,paymentsEnabled:false,smsEnabled:false,
      groupBooking:{id:group,bookingSecret:capability(group)},intercityBooking:{id:intercity,bookingSecret:capability(intercity)},
      requests:['GROUP','CARGO'].map(kind=>({id:'review_request_'+kind.toLowerCase(),requestSecret:capability('review_request_'+kind.toLowerCase()),kind})),
      disposablePhone:'+251900000108',note:'All samples are synthetic. No real payment, transport or SMS.'};
  }
  return {init,refresh,resources,owned,slot};
}
module.exports={createReviewFixtures,ACCOUNTS};
