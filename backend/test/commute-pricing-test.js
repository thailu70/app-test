'use strict';

const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
process.env.NODE_ENV = 'test';
process.env.DATABASE_URL = '';
process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), 'routepass-assignment-pricing-'));
process.env.JWT_SECRET = 'assignment-pricing-isolated-test-secret';
process.env.QR_SIGNING_KEY = 'assignment-pricing-isolated-qr-secret';
process.env.PAYMENT_MODE = 'PRODUCTION';
const express = require('express');
const { DB } = require('../src/db');
const { signToken } = require('../src/middleware/auth');
const { initCommutePricingSchema, getRoutePricing, resolveCommuteFare, saveSubscriptionTerms,
  getSubscriptionTerms, savePaymentTerms, getPaymentTerms } = require('../src/services/commute-pricing');

test('monthly fares and service classes belong to driver route assignments without repricing paid passes', async t => {
  await Promise.all([initCommutePricingSchema(), initCommutePricingSchema()]);
  const app = express();
  app.use(express.json());
  app.use('/api/routes', require('../src/routes/routes'));
  app.use('/api/admin', require('../src/routes/admin'));
  const server = app.listen(0, '127.0.0.1');
  await new Promise(resolve => server.once('listening', resolve));
  t.after(() => new Promise(resolve => server.close(resolve)));
  const base = 'http://127.0.0.1:' + server.address().port;
  const token = signToken({ id: 'usr_adm_root', role: 'ADMIN' });
  async function request(method, endpoint, body, auth = token) {
    const response = await fetch(base + endpoint, { method, headers: { 'Content-Type': 'application/json', ...(auth ? { Authorization: 'Bearer ' + auth } : {}) },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
    return { status: response.status, body: await response.json() };
  }
  let route;
  await t.test('creating or editing a route cannot set a passenger fare', async () => {
    const created = await request('POST', '/api/routes', { name: 'Assignment-priced route', nameAm: 'መስመር', basePriceEtb: 9000 });
    assert.equal(created.status, 201, JSON.stringify(created));
    route = await DB.prepare('SELECT * FROM routes WHERE id=?').get(created.body.route.id);
    assert.equal(route.basePriceEtb, 0);
    assert.deepEqual(created.body.route.serviceFares, { ECONOMY: null, DELUXE: null, VIP: null });
    assert.equal((await request('PUT', '/api/routes/' + route.id, { basePriceEtb: 8000 })).status, 200);
    assert.equal((await DB.prepare('SELECT basePriceEtb FROM routes WHERE id=?').get(route.id)).basePriceEtb, 0);
    await assert.rejects(resolveCommuteFare({ route }), error => error.status === 409);
    await DB.prepare("INSERT INTO routes(id,name,nameAm,basePriceEtb,active) VALUES('pricing_legacy','Historical route','መስመር',1800,TRUE)").run();
    assert.equal((await request('GET', '/api/routes/pricing_legacy', undefined, null)).body.route.basePriceEtb, 1800);
    assert.equal((await request('PUT', '/api/routes/pricing_legacy', { basePriceEtb: 6000 })).status, 200);
    assert.equal((await DB.prepare("SELECT basePriceEtb FROM routes WHERE id='pricing_legacy'").get()).basePriceEtb, 1800);
  });

  await t.test('historical Economy fares stay stable while an assigned driver completes onboarding', async () => {
    await require('../src/services/onboarding').initOnboardingSchema();
    await DB.prepare("INSERT INTO users(id,role,fullName,phone,passwordHash,status) VALUES('pricing_unready','DRIVER','Unready fixture','+251911701005','unused','ACTIVE')").run();
    await DB.prepare("INSERT INTO vehicles(id,plateNumber,model,vehicleType,capacityLimit,driverId,assignedRouteId,status) VALUES('v_pricing_unready','pricing_unready_plate','Fixture','MINIVAN_8',8,'pricing_unready','pricing_legacy','IN_SERVICE')").run();
    const now = new Date().toISOString();
    await DB.prepare("INSERT INTO onboarding_accounts(account_id,role,normalized_phone,review_status,service_type,created_at,updated_at) VALUES('pricing_unready','DRIVER','+251911701005','DRAFT','DAILY_COMMUTE',?,?)").run(now, now);
    const historical = await DB.prepare("SELECT * FROM routes WHERE id='pricing_legacy'").get();
    assert.equal((await getRoutePricing(historical)).serviceFares.ECONOMY, 1800);
    await DB.prepare("UPDATE vehicles SET assignedRouteId=NULL WHERE id='v_pricing_unready'").run();
    assert.equal((await getRoutePricing(historical)).serviceFares.ECONOMY, 1800);
  });

  for (const [id, phone, status] of [['pricing_economy', '+251911701001', 'ACTIVE'], ['pricing_vip', '+251911701002', 'ACTIVE'], ['pricing_pending', '+251911701003', 'PENDING']]) {
    await DB.prepare('INSERT INTO users(id,role,fullName,phone,passwordHash,status) VALUES(?,?,?,?,?,?)').run(id, 'DRIVER', id, phone, 'unused-test-hash', status);
    await DB.prepare('INSERT INTO vehicles(id,plateNumber,model,vehicleType,capacityLimit,driverId,status) VALUES(?,?,?,?,?,?,?)')
      .run('v_' + id, 'plate_' + id, 'Fixture minivan', 'MINIVAN_8', 8, id, 'OFF_DUTY');
  }
  const assignment = (id, body) => request('PATCH', '/api/admin/drivers/' + id + '/route', { routeId: route.id, ...body });
  await t.test('assignment requires approved drivers and valid explicit class and fare', async () => {
    for (const body of [{}, { serviceClass: 'Economy' }, { serviceClass: 'Business', monthlyFareEtb: 2000 },
      ...[0, -1, 1.001, 1000000.01, '2000', null, true].map(monthlyFareEtb => ({ serviceClass: 'ECONOMY', monthlyFareEtb }))]) {
      assert.equal((await assignment('pricing_economy', body)).status, 400, JSON.stringify(body));
    }
    assert.equal((await assignment('pricing_pending', { serviceClass: 'ECONOMY', monthlyFareEtb: 2100 })).status, 409);
    assert.equal((await DB.prepare("SELECT * FROM vehicles WHERE id='v_pricing_economy'").get()).assignedRouteId, null);
    assert.equal((await DB.prepare('SELECT COUNT(*) AS c FROM commute_route_terms').get()).c, 0);
  });

  await t.test('two drivers on one route have independent service classes and prices', async () => {
    assert.equal((await assignment('pricing_economy', { serviceClass: 'Economy', monthlyFareEtb: 2100.5 })).status, 200);
    assert.equal((await assignment('pricing_vip', { serviceClass: 'VIP', monthlyFareEtb: 3600 })).status, 200);
    const result = await request('GET', '/api/routes/' + route.id, undefined, null);
    assert.deepEqual(result.body.route.serviceFares, { ECONOMY: 2100.5, DELUXE: null, VIP: 3600 });
    assert.equal(result.body.route.basePriceEtb, 2100.5);
    assert.equal((await DB.prepare('SELECT basePriceEtb FROM routes WHERE id=?').get(route.id)).basePriceEtb, 0);
    const drivers = (await request('GET', '/api/admin/drivers')).body.drivers;
    assert.equal(drivers.find(driver => driver.id === 'pricing_vip').assignmentServiceClass, 'VIP');
    assert.equal(drivers.find(driver => driver.id === 'pricing_vip').assignmentMonthlyFareEtb, 3600);
    assert.equal((await resolveCommuteFare({ route, serviceClass: 'VIP' })).monthlyFareEtb, 3600);
    assert.equal((await resolveCommuteFare({ route })).monthlyFareEtb, 2100.5);
    assert.equal((await resolveCommuteFare({ route: { id: route.id } })).monthlyFareEtb, 2100.5);
    await assert.rejects(resolveCommuteFare({ route, serviceClass: 'DELUXE' }), error => error.status === 409);
  });

  await t.test('a full current trip retains its monthly service fare while boarding capacity remains separate', async () => {
    await DB.prepare("UPDATE vehicles SET status='FULL',currentOccupancy=capacityLimit WHERE id='v_pricing_vip'").run();
    const priced = await request('GET', '/api/routes/' + route.id, undefined, null);
    assert.equal(priced.body.route.serviceFares.VIP, 3600);
    const fare = await resolveCommuteFare({ route, serviceClass: 'VIP' });
    assert.equal(fare.monthlyFareEtb, 3600);
    assert.equal(fare.vehicleId, 'v_pricing_vip');
    const full = await DB.prepare("SELECT status,currentOccupancy,capacityLimit FROM vehicles WHERE id='v_pricing_vip'").get();
    assert.equal(full.status, 'FULL');
    assert.equal(full.currentOccupancy, full.capacityLimit, 'Reading monthly fares never changes trip occupancy.');
  });

  await DB.prepare("INSERT INTO users(id,role,fullName,phone,passwordHash,status) VALUES('pricing_passenger','PASSENGER','Fixture passenger','+251911701004','unused','ACTIVE')").run();
  await DB.prepare("INSERT INTO subscriptions(id,passengerId,routeId,priceEtb,paymentStatus,subscriptionStatus,vehicleId,qrToken,endDate,daysRemaining) VALUES('pricing_sub','pricing_passenger',?,2100.5,'PAID','ACTIVE','v_pricing_economy','immutable-qr','2099-01-01',30)").run(route.id);
  await saveSubscriptionTerms(DB, 'pricing_sub', { serviceClass: 'ECONOMY', monthlyFareEtb: 2100.5, vehicleId: 'v_pricing_economy' }, route.id);
  await t.test('changing driver prices does not change paid pass prices or allow a conflicting paid assignment', async () => {
    const before = await DB.prepare("SELECT * FROM subscriptions WHERE id='pricing_sub'").get();
    const termsBefore = await getSubscriptionTerms('pricing_sub');
    assert.equal((await assignment('pricing_economy', { serviceClass: 'ECONOMY', monthlyFareEtb: 2400 })).status, 200);
    assert.deepEqual(await DB.prepare("SELECT * FROM subscriptions WHERE id='pricing_sub'").get(), before);
    assert.deepEqual(await getSubscriptionTerms('pricing_sub'), termsBefore);
    assert.equal((await request('PATCH', '/api/admin/subscriptions/pricing_sub/assignment', { vehicleId: 'v_pricing_vip' })).status, 409);
    assert.deepEqual(await DB.prepare("SELECT * FROM subscriptions WHERE id='pricing_sub'").get(), before);
    const renewal = await resolveCommuteFare({ route, passengerId: 'pricing_passenger' });
    assert.equal(renewal.monthlyFareEtb, 2400);
    await assert.rejects(resolveCommuteFare({ route, passengerId: 'pricing_passenger', serviceClass: 'VIP' }), error => error.status === 409);
    assert.equal((await assignment('pricing_economy', { serviceClass: 'DELUXE', monthlyFareEtb: 2400 })).status, 409);
    assert.equal((await assignment('pricing_economy', { routeId: 'pricing_legacy', serviceClass: 'ECONOMY', monthlyFareEtb: 1800 })).status, 409);
    assert.equal((await DB.prepare("SELECT * FROM commute_route_terms WHERE vehicle_id='v_pricing_economy'").get()).service_class, 'ECONOMY');
    assert.equal((await DB.prepare("SELECT * FROM vehicles WHERE id='v_pricing_economy'").get()).assignedRouteId, route.id);
    // The resolver also defends against an inconsistent historical/manual change.
    await DB.prepare("UPDATE commute_route_terms SET service_class='DELUXE' WHERE vehicle_id='v_pricing_economy'").run();
    await assert.rejects(resolveCommuteFare({ route, passengerId: 'pricing_passenger' }), error => error.status === 409);
    await DB.prepare("UPDATE commute_route_terms SET service_class='ECONOMY' WHERE vehicle_id='v_pricing_economy'").run();
  });

  await t.test('unpaid passenger assignment records its selected fare and class while leaving payment pending', async () => {
    await DB.prepare("UPDATE subscriptions SET paymentStatus='UNPAID',subscriptionStatus='PENDING' WHERE id='pricing_sub'").run();
    assert.equal((await request('PATCH', '/api/admin/subscriptions/pricing_sub/assignment', { vehicleId: 'v_pricing_vip' })).status, 200);
    const sub = await DB.prepare("SELECT * FROM subscriptions WHERE id='pricing_sub'").get();
    assert.equal(sub.priceEtb, 3600);
    assert.equal(sub.paymentStatus, 'UNPAID');
    assert.equal(sub.subscriptionStatus, 'PENDING');
    assert.deepEqual(await getSubscriptionTerms(sub.id), { routeId: route.id, vehicleId: 'v_pricing_vip', serviceClass: 'VIP', monthlyFareEtb: 3600 });
    const selected = await resolveCommuteFare({ route, passengerId: 'pricing_passenger' });
    assert.equal(selected.serviceClass, 'VIP');
    await DB.prepare("INSERT INTO telebirr_payment_orders(merchant_order_id,idempotency_key,passenger_id,subscription_id,route_id,amount_etb,status) VALUES('pricing_order','pricing-order-key','pricing_passenger','pricing_sub',?,3600,'PENDING')").run(route.id);
    await savePaymentTerms(DB, 'pricing_order', selected, route.id);
    assert.deepEqual(await getPaymentTerms('pricing_order'), await getSubscriptionTerms(sub.id));
    await assert.rejects(savePaymentTerms(DB, 'pricing_order', { ...selected, monthlyFareEtb: 3700 }, route.id), error => error.status === 409);
    assert.equal((await getPaymentTerms('pricing_order')).monthlyFareEtb, 3600);
  });

  await t.test('failed assignment audits roll back both vehicle and fare changes atomically', async () => {
    const prior = await DB.prepare("SELECT * FROM commute_route_terms WHERE vehicle_id='v_pricing_economy'").get();
    const priorVehicle = await DB.prepare("SELECT * FROM vehicles WHERE id='v_pricing_economy'").get();
    await DB.exec("CREATE TRIGGER pricing_reject_audit BEFORE INSERT ON audit_logs WHEN NEW.action='DRIVER_ROUTE_ASSIGNED' BEGIN SELECT RAISE(ABORT,'injected assignment audit failure'); END");
    try {
      assert.equal((await assignment('pricing_economy', { serviceClass: 'DELUXE', monthlyFareEtb: 3200 })).status, 500);
      assert.deepEqual(await DB.prepare("SELECT * FROM commute_route_terms WHERE vehicle_id='v_pricing_economy'").get(), prior);
      assert.deepEqual(await DB.prepare("SELECT * FROM vehicles WHERE id='v_pricing_economy'").get(), priorVehicle);
    } finally { await DB.exec('DROP TRIGGER pricing_reject_audit'); }
  });

  await t.test('stale ownership and paused vehicles do not advertise assigned fares or restore a legacy fare', async () => {
    await DB.prepare('UPDATE routes SET basePriceEtb=5000 WHERE id=?').run(route.id);
    const updated = await DB.prepare('SELECT * FROM routes WHERE id=?').get(route.id);
    await DB.prepare("UPDATE vehicles SET status='MAINTENANCE' WHERE id='v_pricing_economy'").run();
    await DB.prepare("UPDATE vehicles SET driverId='pricing_economy' WHERE id='v_pricing_vip'").run();
    const pricing = await getRoutePricing(updated);
    assert.equal(pricing.basePriceEtb, 0);
    assert.deepEqual(pricing.serviceFares, { ECONOMY: null, DELUXE: null, VIP: null });
    assert.equal(pricing.monthlyPassengerFareEtb, null);
    await assert.rejects(resolveCommuteFare({ route: updated }), error => error.status === 409);
    await DB.prepare("UPDATE routes SET active=FALSE WHERE id='pricing_legacy'").run();
    await assert.rejects(resolveCommuteFare({ route: { id: 'pricing_legacy', basePriceEtb: 1800 } }), error => error.status === 409);
  });
});
