'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
process.env.NODE_ENV = 'test';
process.env.PAYMENT_MODE = 'TEST';
delete process.env.DATABASE_URL;
process.env.JWT_SECRET = 'passenger-assignment-regression-secret';
process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), 'routepass-passenger-assignment-'));
const { DB } = require('../src/db');
const { signToken } = require('../src/middleware/auth');
const { initProfileSchema } = require('../src/services/profile-schema');

test('passenger assignment, pass and location remain consistent without fabricating a live trip', async t => {
  await initProfileSchema();
  const express = require('express');
  const app = express(); app.use(express.json());
  app.use('/api/vehicles', require('../src/routes/vehicles'));
  app.use('/api/commute', require('../src/routes/commute-passenger'));
  app.use('/api/subscriptions', require('../src/routes/subscriptions'));
  const server = app.listen(0, '127.0.0.1');
  await new Promise(resolve => server.once('listening', resolve));
  t.after(() => new Promise(resolve => server.close(resolve)));
  const base = 'http://127.0.0.1:' + server.address().port;
  const token = {};
  for (const [id, role, companyId] of [['pa_a','PASSENGER',''],['pa_b','PASSENGER',''],['pa_driver','DRIVER',''],['pa_other_driver','DRIVER',''],['pa_company_driver','DRIVER','pa_co'],['pa_admin','ADMIN','']]) {
    await DB.prepare('INSERT INTO users(id,role,fullName,phone,passwordHash,status,companyId) VALUES(?,?,?,?,?,?,?)').run(id,role,id,id,'unused','ACTIVE',companyId);
    token[id] = signToken({ id, role });
  }
  for (const [id, type, company] of [['pa_route','DAILY_COMMUTE',''],['pa_other_route','DAILY_COMMUTE',''],['pa_company_route','INTERCITY','pa_co']]) await DB.prepare('INSERT INTO routes(id,name,nameAm,routeType,companyId) VALUES(?,?,?,?,?)').run(id,id,id,type,company);
  for (const [id, driver, route, company] of [['pa_vehicle','pa_driver','pa_route',''],['pa_other_vehicle','pa_other_driver','pa_route',''],['pa_company_vehicle','pa_company_driver','pa_company_route','pa_co']]) await DB.prepare('INSERT INTO vehicles(id,plateNumber,model,vehicleType,capacityLimit,driverId,driverName,assignedRouteId,companyId,currentLat,currentLng) VALUES(?,?,?,?,?,?,?,?,?,?,?)').run(id,id,'Real assigned van','MINIVAN_8',8,driver,'Obsolete cached driver',route,company,9.1,38.7);
  await DB.prepare(`INSERT INTO subscriptions(id,passengerId,routeId,priceEtb,subscriptionStatus,paymentStatus,daysRemaining,vehicleId,pickupStopId,destinationStopId,qrToken,updatedAt) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)`).run('pa_sub','pa_a','pa_route',1200,'ACTIVE','PAID',20,'pa_vehicle','real_pickup','real_destination','server-token',new Date().toISOString());
  async function get(url, caller = 'pa_a') {
    const response = await fetch(base+url, { headers: caller ? { Authorization: 'Bearer '+token[caller] } : {} });
    return { status: response.status, data: await response.json(), cache: response.headers.get('cache-control') };
  }
  async function tracking() { const response = await get('/api/vehicles/tracking'); assert.equal(response.status,200,JSON.stringify(response.data)); return response.data; }
  await t.test('assigned driver and vehicle stay visible before departure, without default coordinates', async () => {
    const driver = await get('/api/commute/my-driver');
    const tracked = await tracking();
    assert.equal(driver.data.driver.fullName,'pa_driver');
    assert.equal(driver.data.driver.vehicleId,'pa_vehicle');
    assert.equal(driver.data.driver.subscriptionId,'pa_sub');
    assert.equal(tracked.vehicle.id,driver.data.driver.vehicleId);
    assert.equal(tracked.status,'NO_GPS');
    assert.equal(tracked.vehicle.hasGpsLocation,false);
    assert.equal(tracked.vehicle.latitude,null); assert.equal(tracked.vehicle.longitude,null);
    assert.match(tracked.message,/assigned/i);
    assert.equal((await get('/api/vehicles/tracking','pa_driver')).data.vehicle.id,'pa_vehicle');
    const pass = await get('/api/subscriptions/my-status');
    assert.equal(pass.data.subscription.pickupStopId,'real_pickup');
    assert.equal(pass.data.subscription.destinationStopId,'real_destination');
    assert.equal(pass.data.subscription.vehicleId,tracked.vehicle.id);
    assert.equal(pass.cache,'private, no-store');
  });
  await t.test('real GPS is labeled offline, live, stale and invalid accurately', async () => {
    await DB.prepare('INSERT INTO vehicle_live_locations(vehicle_id,latitude,longitude,updated_at) VALUES(?,?,?,?)').run('pa_vehicle',9.04,38.76,new Date().toISOString());
    let tracked = await tracking();
    assert.equal(tracked.status,'OFFLINE'); assert.equal(tracked.vehicle.hasGpsLocation,true);
    await DB.prepare('INSERT INTO trips(id,vehicleId,driverId,routeId,status) VALUES(?,?,?,?,?)').run('pa_trip','pa_vehicle','pa_driver','pa_route','IN_PROGRESS');
    tracked = await tracking(); assert.equal(tracked.status,'LIVE');
    await DB.prepare('UPDATE vehicle_live_locations SET updated_at=? WHERE vehicle_id=?').run(new Date(Date.now()-300000).toISOString(),'pa_vehicle');
    tracked = await tracking(); assert.equal(tracked.status,'STALE'); assert.ok(tracked.vehicle.gpsAgeSeconds>=300); assert.equal(tracked.vehicle.latitude,9.04);
    await DB.prepare('UPDATE vehicle_live_locations SET latitude=91 WHERE vehicle_id=?').run('pa_vehicle');
    tracked = await tracking(); assert.equal(tracked.status,'NO_GPS'); assert.equal(tracked.vehicle.latitude,null);
    await DB.prepare('UPDATE vehicle_live_locations SET latitude=9,updated_at=? WHERE vehicle_id=?').run(new Date(Date.now()+60000).toISOString(),'pa_vehicle');
    tracked = await tracking(); assert.equal(tracked.status,'NO_GPS');
    await DB.prepare('UPDATE vehicle_live_locations SET updated_at=? WHERE vehicle_id=?').run(new Date().toISOString(),'pa_vehicle');
    await DB.prepare("UPDATE trips SET status='COMPLETED' WHERE id='pa_trip'").run();
    tracked = await tracking(); assert.equal(tracked.status,'OFFLINE'); assert.equal(tracked.vehicle.id,'pa_vehicle');
  });
  await t.test('inactive pass, another user, reassignment and company data never expose a location', async () => {
    assert.equal((await get('/api/vehicles/tracking',null)).status,401);
    assert.equal((await get('/api/vehicles/tracking','pa_admin')).status,403);
    assert.equal((await get('/api/vehicles/tracking','pa_b')).data.vehicle,null);
    for (const update of ["paymentStatus='UNPAID'", "subscriptionStatus='PENDING'", "daysRemaining=0"]) {
      await DB.prepare('UPDATE subscriptions SET '+update+" WHERE id='pa_sub'").run();
      const tracked = await tracking(); assert.equal(tracked.status,'INACTIVE'); assert.equal(tracked.vehicle,null);
      assert.equal((await get('/api/subscriptions/my-status')).data.subscription.qrToken,null);
      await DB.prepare("UPDATE subscriptions SET paymentStatus='PAID',subscriptionStatus='ACTIVE',daysRemaining=20 WHERE id='pa_sub'").run();
    }
    for (const vehicleId of [null, 'pa_company_vehicle']) {
      await DB.prepare("UPDATE subscriptions SET vehicleId=? WHERE id='pa_sub'").run(vehicleId);
      assert.equal((await tracking()).vehicle,null);
      assert.equal((await get('/api/commute/my-driver')).data.driver,null);
    }
    await DB.prepare("UPDATE subscriptions SET vehicleId='pa_vehicle' WHERE id='pa_sub'").run();
    await DB.prepare("UPDATE vehicles SET assignedRouteId='pa_other_route' WHERE id='pa_vehicle'").run();
    assert.equal((await tracking()).vehicle,null);
    assert.equal((await get('/api/commute/my-driver')).data.driver,null);
    await DB.prepare("UPDATE vehicles SET assignedRouteId='pa_route' WHERE id='pa_vehicle'").run();
    await DB.prepare("UPDATE users SET status='SUSPENDED' WHERE id='pa_driver'").run();
    assert.equal((await tracking()).vehicle,null);
    await DB.prepare("UPDATE users SET status='ACTIVE' WHERE id='pa_driver'").run();
  });
  await t.test('status recalculates remaining days and expires an old activation counter', async () => {
    const { generateSignedQrToken } = require('../src/routes/subscriptions');
    try {
      for (const [offset, expectedDays, active] of [[4 * 86400000 + 3600000, 5, true], [-60000, 0, false]]) {
        const expiry = Date.now() + offset;
        const qr = generateSignedQrToken('pa_sub', 'pa_a', 'pa_route', expiry);
        await DB.prepare("UPDATE subscriptions SET endDate=?,daysRemaining=30,qrToken=? WHERE id='pa_sub'").run(new Date(expiry).toISOString(), qr);
        const status = await get('/api/subscriptions/my-status');
        assert.equal(status.data.subscription.daysRemaining, expectedDays);
        assert.equal(status.data.isSubscribed, active);
        assert.equal(status.data.subscription.qrToken, active ? qr : null);
        assert.equal((await tracking()).status === 'INACTIVE', !active);
        // Reading status must not rewrite updatedAt and accidentally select an older pass.
        assert.equal((await DB.prepare("SELECT daysRemaining FROM subscriptions WHERE id='pa_sub'").get()).daysRemaining, 30);
      }
    } finally {
      await DB.prepare("UPDATE subscriptions SET endDate=NULL,daysRemaining=20,qrToken='server-token' WHERE id='pa_sub'").run();
    }
  });
  await t.test('all three endpoints select the latest pass, without falling back to an older assigned pass', async () => {
    await DB.prepare(`INSERT INTO subscriptions(id,passengerId,routeId,priceEtb,subscriptionStatus,paymentStatus,daysRemaining,updatedAt) VALUES(?,?,?,?,?,?,?,?)`).run('pa_new','pa_a','pa_route',1200,'PENDING','UNPAID',0,new Date(Date.now()+1000).toISOString());
    const pass = await get('/api/subscriptions/my-status'); assert.equal(pass.data.subscription.id,'pa_new');
    assert.equal((await get('/api/commute/my-driver')).data.driver,null);
    const tracked = await tracking(); assert.equal(tracked.status,'INACTIVE'); assert.equal(tracked.vehicle,null);
  });
});

test('passenger stop names resolve current own-route assignments without rewriting legacy passes', async t => {
  const express = require('express');
  const app = express(); app.use(express.json());
  app.use('/api/subscriptions', require('../src/routes/subscriptions'));
  const server = app.listen(0, '127.0.0.1');
  await new Promise(resolve => server.once('listening', resolve));
  t.after(() => new Promise(resolve => server.close(resolve)));
  const base = 'http://127.0.0.1:' + server.address().port;
  const tokens = {};
  for (const id of ['ps_legacy', 'ps_deleted', 'ps_null', 'ps_exact', 'ps_ambiguous', 'ps_foreign_id', 'ps_pending', 'ps_activate', 'ps_change']) {
    await DB.prepare('INSERT INTO users(id,role,fullName,phone,passwordHash,status) VALUES(?,?,?,?,?,?)')
      .run(id, 'PASSENGER', id, id, 'unused', 'ACTIVE');
    tokens[id] = signToken({ id, role: 'PASSENGER' });
  }
  for (const id of ['ps_two', 'ps_many', 'ps_foreign'])
    await DB.prepare('INSERT INTO routes(id,name,nameAm,morningDeparture,eveningDeparture,basePriceEtb) VALUES(?,?,?,?,?,?)')
      .run(id, 'Published daily route', 'የዕለት መስመር', '05:40', '17:55', 1200);
  for (const [id, route, name, am, order] of [
    ['ps_start', 'ps_two', 'Garment', 'ጋርመንት', 1],
    ['ps_end', 'ps_two', 'Bole Medhanialem', 'ቦሌ መድሃኒአለም', 2],
    ['ps_many_start', 'ps_many', 'Route origin', 'መነሻ', 1],
    ['ps_personal', 'ps_many', 'Personal pickup', 'የግል መሳፈሪያ', 2],
    ['ps_many_end', 'ps_many', 'Personal destination', 'የግል መድረሻ', 3],
    ['ps_foreign_stop', 'ps_foreign', 'Unrelated route pickup', 'ሌላ መስመር', 1]
  ]) await DB.prepare('INSERT INTO route_stops(id,routeId,stopName,stopNameAm,stopOrder,latitude,longitude) VALUES(?,?,?,?,?,?,?)')
    .run(id, route, name, am, order, 9.03, 38.74);
  for (const [id, route, pickup, destination, status, payment] of [
    ['ps_legacy', 'ps_two', 'stop_atlas', 'stop_merkato', 'ACTIVE', 'PAID'],
    ['ps_deleted', 'ps_two', 'deleted_pickup', 'deleted_destination', 'ACTIVE', 'PAID'],
    ['ps_null', 'ps_two', null, null, 'ACTIVE', 'PAID'],
    ['ps_exact', 'ps_many', 'ps_personal', 'ps_many_end', 'ACTIVE', 'PAID'],
    ['ps_ambiguous', 'ps_many', 'deleted_middle', 'deleted_end', 'ACTIVE', 'PAID'],
    ['ps_foreign_id', 'ps_two', 'ps_foreign_stop', 'ps_end', 'ACTIVE', 'PAID'],
    ['ps_pending', 'ps_two', 'stop_atlas', 'stop_merkato', 'PENDING', 'UNPAID'],
    ['ps_change', 'ps_many', 'ps_personal', 'ps_many_end', 'ACTIVE', 'PAID']
  ]) await DB.prepare('INSERT INTO subscriptions(id,passengerId,routeId,pickupStopId,destinationStopId,priceEtb,paymentStatus,subscriptionStatus,daysRemaining,qrToken,updatedAt) VALUES(?,?,?,?,?,?,?,?,?,?,?)')
    .run('sub_' + id, id, route, pickup, destination, 1200, payment, status, status === 'ACTIVE' ? 30 : 0, 'private-qr-' + id, '2026-10-01T00:00:00.000Z');
  async function request(id, method = 'GET', path = '/my-status', body) {
    const response = await fetch(base + '/api/subscriptions' + path, {
      method, headers: { Authorization: 'Bearer ' + tokens[id], 'Content-Type': 'application/json' },
      ...(body ? { body: JSON.stringify(body) } : {})
    });
    return { status: response.status, data: await response.json() };
  }

  await t.test('explicit current personal stops retain the real intermediate pickup rather than the route origin', async () => {
    const result = await request('ps_exact');
    assert.equal(result.status, 200);
    assert.equal(result.data.subscription.pickupStopId, 'ps_personal');
    assert.equal(result.data.subscription.pickupStopName, 'Personal pickup');
    assert.equal(result.data.subscription.pickupStopNameAm, 'የግል መሳፈሪያ');
    assert.equal(result.data.subscription.destinationStopName, 'Personal destination');
  });
  await t.test('two-stop routes recover known demo, deleted and unset assignments in response only', async () => {
    for (const id of ['ps_legacy', 'ps_deleted', 'ps_null']) {
      const before = await DB.prepare('SELECT * FROM subscriptions WHERE passengerId=?').get(id);
      const result = await request(id);
      assert.equal(result.status, 200, JSON.stringify(result.data));
      assert.equal(result.data.subscription.pickupStopId, 'ps_start');
      assert.equal(result.data.subscription.destinationStopId, 'ps_end');
      assert.equal(result.data.subscription.pickupStopName, 'Garment');
      assert.equal(result.data.subscription.pickupStopNameAm, 'ጋርመንት');
      assert.equal(result.data.subscription.destinationStopName, 'Bole Medhanialem');
      assert.equal(result.data.subscription.destinationStopNameAm, 'ቦሌ መድሃኒአለም');
      assert.equal(result.data.subscription.qrToken, before.qrToken);
      assert.deepEqual(await DB.prepare('SELECT * FROM subscriptions WHERE passengerId=?').get(id), before);
    }
  });
  await t.test('ambiguous orphan and existing cross-route personal IDs stay unresolved', async () => {
    const ambiguous = (await request('ps_ambiguous')).data.subscription;
    assert.equal(ambiguous.pickupStopId, 'deleted_middle');
    assert.equal(ambiguous.destinationStopId, 'deleted_end');
    assert.equal(ambiguous.pickupStopName, '');
    assert.equal(ambiguous.destinationStopName, '');
    const foreign = (await request('ps_foreign_id')).data.subscription;
    assert.equal(foreign.pickupStopId, 'ps_foreign_stop');
    assert.equal(foreign.pickupStopName, '');
    assert.equal(foreign.destinationStopId, 'ps_end');
    assert.equal(foreign.destinationStopName, 'Bole Medhanialem');
  });
  await t.test('pending passes receive real location names without activation or a QR token', async () => {
    const result = await request('ps_pending');
    assert.equal(result.data.isSubscribed, false);
    assert.equal(result.data.subscription.status, 'PENDING');
    assert.equal(result.data.subscription.paymentStatus, 'UNPAID');
    assert.equal(result.data.subscription.qrToken, null);
    assert.equal(result.data.subscription.pickupStopName, 'Garment');
    assert.equal(result.data.subscription.destinationStopName, 'Bole Medhanialem');
  });
  await t.test('test activation uses target route stops and schedules for new and changed routes', async t => {
    const previousSwitch = process.env.PAYMENT_TESTS_ENABLED;
    process.env.PAYMENT_TESTS_ENABLED = 'true';
    t.after(() => {
      if (previousSwitch === undefined) delete process.env.PAYMENT_TESTS_ENABLED;
      else process.env.PAYMENT_TESTS_ENABLED = previousSwitch;
    });
    for (const id of ['ps_activate', 'ps_change']) {
      const result = await request(id, 'POST', '/telebirr/pay', { routeId: 'ps_two' });
      assert.equal(result.status, 200, JSON.stringify(result.data));
      const stored = await DB.prepare('SELECT * FROM subscriptions WHERE passengerId=?').get(id);
      assert.equal(stored.routeId, 'ps_two');
      assert.equal(stored.pickupStopId, 'ps_start');
      assert.equal(stored.destinationStopId, 'ps_end');
      assert.equal(stored.morningSchedule, '05:40');
      assert.equal(stored.eveningSchedule, '17:55');
      assert.equal(result.data.subscription.pickupStopName, 'Garment');
      assert.equal(result.data.subscription.destinationStopName, 'Bole Medhanialem');
      assert.match(stored.qrToken, /^RP1:/);
    }
    const sameRoute = await request('ps_exact', 'POST', '/telebirr/pay', { routeId: 'ps_many' });
    assert.equal(sameRoute.status, 200);
    const storedPersonal = await DB.prepare('SELECT * FROM subscriptions WHERE passengerId=?').get('ps_exact');
    assert.equal(storedPersonal.pickupStopId, 'ps_personal');
    assert.equal(storedPersonal.destinationStopId, 'ps_many_end');
  });
});
