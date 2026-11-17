'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
process.env.NODE_ENV = 'test';
process.env.JWT_SECRET = 'passo-cross-service-rides-regression-secret';
process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), 'passo-cross-service-'));
if (process.env.MOBILITY_TEST_POSTGRES === 'true') {
  if (process.env.CI !== 'true' || !process.env.DATABASE_URL) throw new Error('Disposable CI PostgreSQL configuration is required.');
} else delete process.env.DATABASE_URL;
const { DB } = require('../src/db');
const { signToken } = require('../src/middleware/auth');
const { initMasterDataSchema } = require('../src/services/master-data');
const { initOnboardingSchema } = require('../src/services/onboarding');
const { initRidesSchema, assertNoRideJob } = require('../src/services/rides');
const { createMobilityService } = require('../src/services/mobility');
const { verifiedMobilityRequest } = require('./mobility-otp-fixture');

test('legacy commute and mobility starts respect active taxi and shared ride assignments', async t => {
  await initMasterDataSchema(); await initOnboardingSchema(); await require('../src/services/ops-schema').initOpsSchema(DB); await initRidesSchema();
  const instant = new Date().toISOString();
  const addisClock = new Date(Date.now() + 3 * 3600000).toISOString().slice(11, 16);
  await DB.prepare("INSERT INTO users(id,role,fullName,phone,passwordHash,status) VALUES('cross_admin','ADMIN','Fixture admin','+251900881000','unused','ACTIVE')").run();
  await DB.prepare("INSERT INTO routes(id,name,nameAm,routeType,companyId,morningDeparture,eveningDeparture) VALUES('cross_route','Fixture route','Fixture route','DAILY_COMMUTE','',?,?)").run(addisClock, addisClock);
  await DB.prepare("INSERT INTO route_stops(id,routeId,stopName,stopNameAm,stopOrder,latitude,longitude,scheduledMorningTime,scheduledEveningTime) VALUES('cross_stop_a','cross_route','Bole','Bole',1,9,38,?,?),('cross_stop_b','cross_route','CMC','CMC',2,9.1,38.1,?,?)").run(addisClock, addisClock, addisClock, addisClock);
  for (const [driver, type, phone] of [['cross_daily','DAILY_COMMUTE','+251900881001'],['cross_group','GROUP_TRAVEL','+251900881002']]) {
    await DB.prepare("INSERT INTO users(id,role,fullName,phone,passwordHash,status) VALUES(?,'DRIVER',?,?,'unused','ACTIVE')").run(driver, driver, phone);
    await DB.prepare("INSERT INTO onboarding_accounts(account_id,role,normalized_phone,service_type,created_at,updated_at,acceptance_status,accepted_at,accepted_by,acceptance_grandfathered,submitted_at,review_status) VALUES(?,'DRIVER',?,?,?,?, 'ACCEPTED',?,'cross_admin',TRUE,?,'APPROVED')")
      .run(driver, phone, type, instant, instant, instant, instant);
    await DB.prepare("INSERT INTO user_profiles(user_id,emergency_contact_name,emergency_contact_phone,vehicle_color,updated_at) VALUES(?,'Contact','+251900881003','white',?)").run(driver, instant);
    for (const kind of ['LICENSE','VEHICLE_REGISTRATION','INSURANCE','PHOTO']) await DB.prepare("INSERT INTO user_profile_documents(user_id,kind,storage_name,content_type,byte_size,uploaded_at) VALUES(?,?,'fixture','image/jpeg',20,?)").run(driver, kind, instant);
    for (const suffix of ['a','b']) await DB.prepare("INSERT INTO vehicles(id,plateNumber,model,vehicleType,capacityLimit,driverId,assignedRouteId,status,currentOccupancy) VALUES(?,?,?,'MINIVAN_8',8,?,?,'IN_SERVICE',2)")
      .run(driver + '_' + suffix, driver + '_' + suffix, 'Fixture van', driver, type === 'DAILY_COMMUTE' ? 'cross_route' : null);
  }
  const service = createMobilityService({ sendSms: async () => ({ sent: false }) }); await service.init();
  const createVerifiedRequest = async input => service.createRequest(await verifiedMobilityRequest(DB,input));
  // Existing PostgreSQL suites may configure a nonzero marketplace fee. Give
  // this synthetic driver credit so the test isolates job exclusivity rather
  // than depending on the global default commission being zero.
  await DB.prepare('INSERT INTO mobility_wallets(driver_id,balance_cents,held_cents) VALUES(?,1000000,0) ON CONFLICT(driver_id) DO UPDATE SET balance_cents=1000000,held_cents=0').run('cross_group');
  const guestInput = { kind: 'GROUP', people: 3, origin: 'Addis Ababa', destination: 'Adama', travelDate: new Date(Date.now()+86400000).toISOString().slice(0,10), vehicleType: 'MINIVAN_8', customerName: 'Guest fixture', customerPhone: '+251900881004', adultConfirmed: true };
  let serial = 0;
  async function taxi(driver, vehicle, status = 'ACCEPTED') {
    const id = 'cross_ride_' + ++serial;
    await DB.prepare("INSERT INTO ride_jobs(id,driver_id,vehicle_id,status,source,tier,route_json,tariff_json,estimate_json,idempotency_owner,idempotency_key,created_at) VALUES(?,?,?,?,'APP','ECONOMY','{}','{}','{}',?,?,?)")
      .run(id, driver, vehicle, status, driver, id, instant);
    return id;
  }
  async function shared(driver, vehicle, status = 'BOARDING') {
    const id = 'cross_shared_' + ++serial;
    await DB.prepare("INSERT INTO ride_shared_trips(id,initiator_role,driver_id,vehicle_id,route_json,estimate_json,departure_at,seats,fare_cents,status,idempotency_owner,idempotency_key,created_at) VALUES(?,'DRIVER',?,?,'{}','{}',?,8,10000,?,?,?,?)")
      .run(id, driver, vehicle, instant, status, driver, id, instant);
    return id;
  }
  const busy = error => error.status === 409 && error.code === 'DRIVER_BUSY';

  await t.test('driver identity is excluded even when the new operation uses a different vehicle', async () => {
    const ride = await taxi('cross_group','cross_group_b');
    await assert.rejects(DB.transaction(tx => assertNoRideJob('cross_group','cross_group_a',tx)),busy);
    await DB.prepare("UPDATE ride_jobs SET status='COMPLETED' WHERE id=?").run(ride);
    await DB.transaction(tx => assertNoRideJob('cross_group','cross_group_a',tx));
  });

  await t.test('vehicle identity remains excluded after a driver reassignment for every active ride state', async () => {
    for (const status of ['ACCEPTED','AT_PICKUP','IN_PROGRESS']) {
      const ride = await taxi('cross_daily','cross_group_a',status);
      await assert.rejects(DB.transaction(tx => assertNoRideJob('cross_group','cross_group_a',tx)),busy);
      await DB.prepare("UPDATE ride_jobs SET status='CANCELLED' WHERE id=?").run(ride);
    }
    for (const status of ['BOARDING','IN_PROGRESS']) {
      const trip = await shared('cross_daily','cross_group_a',status);
      await assert.rejects(DB.transaction(tx => assertNoRideJob('cross_group','cross_group_a',tx)),busy);
      await DB.prepare("UPDATE ride_shared_trips SET status='COMPLETED' WHERE id=?").run(trip);
    }
    await DB.transaction(tx => assertNoRideJob('cross_group','cross_group_a',tx));
  });

  await t.test('daily departure returns conflict without inserting a trip or resetting occupancy', async () => {
    const express = require('express'), app = express(); app.use(express.json()); app.use('/api/trips',require('../src/routes/trips'));
    const server = app.listen(0,'127.0.0.1'); await new Promise(resolve => server.once('listening',resolve));
    t.after(() => new Promise(resolve => server.close(resolve)));
    const ride = await taxi('cross_daily','cross_daily_b','IN_PROGRESS');
    const response = await fetch('http://127.0.0.1:'+server.address().port+'/api/trips/start',{method:'POST',headers:{'Content-Type':'application/json',Authorization:'Bearer '+signToken({id:'cross_daily',role:'DRIVER'})},body:JSON.stringify({routeId:'cross_route',vehicleId:'cross_daily_a',direction:'OUTBOUND',location:{latitude:9,longitude:38,accuracyMeters:5,timestamp:new Date().toISOString()}})});
    assert.equal(response.status,409); assert.equal((await response.json()).code,'DRIVER_BUSY');
    assert.equal(Number((await DB.prepare("SELECT COUNT(*) AS count FROM trips WHERE driverId='cross_daily'").get()).count),0);
    assert.equal(Number((await DB.prepare("SELECT currentOccupancy FROM vehicles WHERE id='cross_daily_a'").get()).currentOccupancy),2);
    await DB.prepare("UPDATE ride_jobs SET status='COMPLETED' WHERE id=?").run(ride);
  });

  await t.test('marketplace quotes and selections cannot assign a driver already on taxi or shared work', async () => {
    const request = await createVerifiedRequest(guestInput);
    const ride = await taxi('cross_group','cross_group_b');
    await assert.rejects(service.marketplace.quote('cross_group',request.request.id,{vehicleId:'cross_group_a',priceEtb:1000}),busy);
    assert.equal(Number((await DB.prepare('SELECT COUNT(*) AS count FROM mobility_quotes WHERE request_id=?').get(request.request.id)).count),0);
    await DB.prepare("UPDATE ride_jobs SET status='COMPLETED' WHERE id=?").run(ride);
    const quote = (await service.marketplace.quote('cross_group',request.request.id,{vehicleId:'cross_group_a',priceEtb:1000})).quote;
    const trip = await shared('cross_group','cross_group_b');
    await assert.rejects(service.marketplace.select(request.request.id,request.requestSecret,{quoteId:quote.id}),busy);
    assert.equal((await service.request(request.request.id,request.requestSecret)).request.status,'REQUESTED');
    assert.equal(Number((await DB.prepare("SELECT COUNT(*) AS count FROM mobility_credit_ledger WHERE event_key=?").get('hold:'+request.request.id)).count),0,'Rejected selection must not reserve driver credit.');
    await DB.prepare("UPDATE ride_shared_trips SET status='COMPLETED' WHERE id=?").run(trip);
    await service.marketplace.select(request.request.id,request.requestSecret,{quoteId:quote.id});
    assert.equal((await service.request(request.request.id,request.requestSecret)).request.status,'ASSIGNED');
    await DB.prepare("UPDATE mobility_requests SET status='COMPLETED' WHERE id=?").run(request.request.id);
  });

  await t.test('admin assignment fails atomically while a taxi job is active', async () => {
    const request = await createVerifiedRequest({...guestInput,destination:'Hawassa'});
    const ride = await taxi('cross_group','cross_group_a','AT_PICKUP');
    const before = Number((await DB.prepare('SELECT COUNT(*) AS count FROM mobility_sms_outbox').get()).count);
    await assert.rejects(service.assignRequest('cross_admin',request.request.id,{driverId:'cross_group',vehicleId:'cross_group_a',quoteEtb:1000}),busy);
    assert.equal((await service.request(request.request.id,request.requestSecret)).request.status,'REQUESTED');
    assert.equal(Number((await DB.prepare('SELECT COUNT(*) AS count FROM mobility_sms_outbox').get()).count),before,'A rejected assignment must not queue customer notifications.');
    await DB.prepare("UPDATE ride_jobs SET status='COMPLETED' WHERE id=?").run(ride);
  });

  await t.test('scheduled group boarding waits for the current shared trip and resumes afterward', async () => {
    const proposed = (await service.createTrip('cross_group',{vehicleId:'cross_group_a',origin:'Bole',destination:'Adama',departureAt:new Date(Date.now()+30*60000).toISOString(),latitude:9,longitude:38,seats:8,fareEtb:500})).trip;
    await service.reviewTrip('cross_admin',proposed.id,{decision:'APPROVE'});
    const trip = await shared('cross_group','cross_group_b','IN_PROGRESS');
    const location = {latitude:9,longitude:38,accuracyMeters:5,timestamp:new Date().toISOString()};
    await assert.rejects(service.startBoarding('cross_group',proposed.id,location),busy);
    assert.equal((await DB.prepare('SELECT status FROM mobility_trips WHERE id=?').get(proposed.id)).status,'APPROVED');
    await DB.prepare("UPDATE ride_shared_trips SET status='COMPLETED' WHERE id=?").run(trip);
    assert.equal((await service.startBoarding('cross_group',proposed.id,location)).trip.status,'BOARDING');
  });

  await t.test('intercity boarding excludes an active taxi or shared ride on any assigned vehicle', async () => {
    await require('../src/services/intercity-schema').initIntercitySchema();
    await DB.prepare("INSERT INTO transport_companies(id,name,status) VALUES('cross_company','Fixture transport','ACTIVE')").run();
    await DB.prepare("UPDATE users SET companyId='cross_company' WHERE id='cross_daily'").run();
    await DB.prepare("INSERT INTO routes(id,name,nameAm,routeType,companyId,origin,destination) VALUES('cross_intercity_route','Fixture intercity','Fixture intercity','INTERCITY','cross_company','Bole','Adama')").run();
    await DB.prepare("UPDATE vehicles SET companyId='cross_company',assignedRouteId='cross_intercity_route' WHERE id='cross_daily_a'").run();
    await DB.prepare("INSERT INTO intercity_departures(id,company_id,route_id,driver_id,vehicle_id,origin,destination,departure_at,latitude,longitude,seats,fare_etb,status,created_at) VALUES('cross_departure','cross_company','cross_intercity_route','cross_daily','cross_daily_a','Bole','Adama',?,9,38,8,500,'SCHEDULED',?)").run(new Date(Date.now()+10*60000).toISOString(),instant);
    const express = require('express'), app=express(); app.use(express.json()); app.use('/api/intercity',require('../src/routes/intercity'));
    const server=app.listen(0,'127.0.0.1'); await new Promise(resolve=>server.once('listening',resolve));
    t.after(()=>new Promise(resolve=>server.close(resolve)));
    async function arrive() {
      const response=await fetch('http://127.0.0.1:'+server.address().port+'/api/intercity/driver/departures/cross_departure/arrival',{method:'POST',headers:{'Content-Type':'application/json',Authorization:'Bearer '+signToken({id:'cross_daily',role:'DRIVER'})},body:JSON.stringify({latitude:9,longitude:38,accuracyMeters:5,timestamp:new Date().toISOString()})});
      return {status:response.status,body:await response.json()};
    }
    const ride=await taxi('cross_daily','cross_daily_b','IN_PROGRESS');
    let result=await arrive(); assert.equal(result.status,409); assert.equal(result.body.code,'DRIVER_BUSY');
    assert.equal((await DB.prepare("SELECT status FROM intercity_departures WHERE id='cross_departure'").get()).status,'SCHEDULED');
    await DB.prepare("UPDATE ride_jobs SET status='COMPLETED' WHERE id=?").run(ride);
    const active=await shared('cross_daily','cross_daily_b','BOARDING');
    result=await arrive(); assert.equal(result.status,409); assert.equal(result.body.code,'DRIVER_BUSY');
    assert.equal((await DB.prepare("SELECT status FROM intercity_departures WHERE id='cross_departure'").get()).status,'SCHEDULED');
    await DB.prepare("UPDATE ride_shared_trips SET status='COMPLETED' WHERE id=?").run(active);
    result=await arrive(); assert.equal(result.status,200,JSON.stringify(result.body)); assert.equal(result.body.departure.status,'BOARDING');
  });
});
