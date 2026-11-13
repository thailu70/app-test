'use strict';

const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

process.env.NODE_ENV = 'test';
delete process.env.DATABASE_URL;
process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), 'passo-commute-pricing-checkout-'));
process.env.JWT_SECRET = 'isolated-commute-pricing-checkout-jwt';
process.env.QR_SIGNING_KEY = 'isolated-commute-pricing-checkout-qr';
process.env.PAYMENT_MODE = 'PRODUCTION';
process.env.PAYMENT_TESTS_ENABLED = 'false';
delete process.env.PAYMENT_TEST_PASSENGER_ID;

const { DB } = require('../src/db');
const { signToken } = require('../src/middleware/auth');
const telebirr = require('../src/services/telebirr');
const { initCommutePricingSchema, getSubscriptionTerms, getPaymentTerms } = require('../src/services/commute-pricing');

test('assignment fares govern passenger subscriptions and verified monthly checkout', async t => {
  await require('../src/services/profile-schema').initProfileSchema();
  await require('../src/services/onboarding').initOnboardingSchema();
  await initCommutePricingSchema();
  const tokens = {};
  const accounts = [
    ['pricing_admin', 'ADMIN'], ['pricing_economy_driver', 'DRIVER'], ['pricing_vip_driver', 'DRIVER'],
    ['pricing_subscriber', 'PASSENGER'], ['pricing_payer', 'PASSENGER'], ['pricing_unpriced', 'PASSENGER']
  ];
  for (const [index, [id, role]] of accounts.entries()) {
    const phone = '+25191199810' + index;
    await DB.prepare('INSERT INTO users(id,role,fullName,phone,passwordHash,status) VALUES(?,?,?,?,?,?)')
      .run(id, role, 'Fixture ' + role, phone, 'unused test hash', 'ACTIVE');
    tokens[id] = signToken({ id, role, phone });
  }
  await DB.prepare("INSERT INTO routes(id,name,nameAm,morningDeparture,eveningDeparture,basePriceEtb,active) VALUES('pricing_route','Fixture route','የሙከራ መስመር','06:30','17:30',0,TRUE)").run();
  for (const [suffix, serviceClass, monthlyFare] of [['economy', 'ECONOMY', 2600], ['vip', 'VIP', 5200]]) {
    await DB.prepare('INSERT INTO vehicles(id,plateNumber,model,vehicleType,capacityLimit,driverId,assignedRouteId,status) VALUES(?,?,?,?,?,?,?,?)')
      .run('pricing_vehicle_' + suffix, 'PRICING-' + suffix, 'Fixture van', 'MINIVAN_8', 8, 'pricing_' + suffix + '_driver', 'pricing_route', 'IN_SERVICE');
    await DB.prepare('INSERT INTO commute_route_terms(vehicle_id,route_id,driver_id,service_class,monthly_fare_etb) VALUES(?,?,?,?,?)')
      .run('pricing_vehicle_' + suffix, 'pricing_route', 'pricing_' + suffix + '_driver', serviceClass, monthlyFare);
  }
  for (const [id, order, name] of [['pricing_stop_a', 1, 'Fixture departure'], ['pricing_stop_b', 2, 'Fixture destination']]) {
    await DB.prepare('INSERT INTO route_stops(id,routeId,stopName,stopNameAm,stopOrder,latitude,longitude) VALUES(?,?,?,?,?,?,?)')
      .run(id, 'pricing_route', name, name, order, 9 + order / 100, 38.7 + order / 100);
  }

  const originals = Object.fromEntries(['missingConfiguration', 'config', 'createCheckout', 'queryOrderStatus'].map(key => [key, telebirr[key]]));
  const checkouts = [];
  telebirr.missingConfiguration = () => [];
  telebirr.config = () => ({ environment: 'production', publicKey: 'fixture verification key' });
  telebirr.createCheckout = async (orderId, amount, description) => {
    checkouts.push({ orderId, amount, description });
    return { prepayId: 'fixture-prepay-' + orderId, checkoutUrl: 'https://payments.example.test/checkout/' + orderId };
  };
  telebirr.queryOrderStatus = async orderId => {
    const checkout = checkouts.find(item => item.orderId === orderId);
    assert.ok(checkout, 'Only a locally created test order can be verified.');
    return { paid: true, amount: checkout.amount, currency: 'ETB', merchantOrderId: orderId,
      transactionId: 'fixture-transaction-' + orderId, paymentOrderId: 'fixture-payment-' + orderId };
  };
  t.after(() => Object.assign(telebirr, originals));

  const express = require('express');
  const app = express();
  app.use(express.json());
  app.use('/api/routes', require('../src/routes/routes'));
  app.use('/api/subscriptions/telebirr', require('../src/routes/telebirr'));
  app.use('/api/subscriptions', require('../src/routes/subscriptions'));
  const server = app.listen(0, '127.0.0.1');
  await new Promise(resolve => server.once('listening', resolve));
  t.after(() => new Promise(resolve => { server.closeAllConnections(); server.close(resolve); }));
  const base = 'http://127.0.0.1:' + server.address().port;
  async function request(method, endpoint, account, body) {
    const response = await fetch(base + endpoint, { method,
      headers: { Accept: 'application/json', ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
        ...(account ? { Authorization: 'Bearer ' + tokens[account] } : {}) },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
    return { status: response.status, body: await response.json() };
  }
  let unpricedRouteId;

  await t.test('a newly created route has no fare and cannot be purchased until a driver fare exists', async () => {
    const created = await request('POST', '/api/routes', 'pricing_admin', {
      name: 'Unpriced fixture route', nameAm: 'ያልተወሰነ ዋጋ', basePriceEtb: 1,
      stops: [{ stopName: 'Fixture A', stopNameAm: 'A', latitude: 9.01, longitude: 38.7 },
        { stopName: 'Fixture B', stopNameAm: 'B', latitude: 9.02, longitude: 38.8 }]
    });
    assert.equal(created.status, 201, JSON.stringify(created.body));
    unpricedRouteId = created.body.route.id;
    assert.equal((await DB.prepare('SELECT basePriceEtb FROM routes WHERE id=?').get(unpricedRouteId)).basePriceEtb, 0);
    assert.deepEqual(created.body.route.serviceFares, { ECONOMY: null, DELUXE: null, VIP: null });
    const selected = await request('POST', '/api/subscriptions/subscribe', 'pricing_unpriced', { routeId: unpricedRouteId, serviceClass: 'ECONOMY' });
    assert.equal(selected.status, 409);
    const checkout = await request('POST', '/api/subscriptions/telebirr/pay', 'pricing_unpriced', {
      routeId: unpricedRouteId, amountEtb: 1, serviceClass: 'ECONOMY', idempotencyKey: 'unpriced-checkout-fixture'
    });
    assert.equal(checkout.status, 409);
    assert.equal(checkouts.length, 0);
    assert.equal(await DB.prepare('SELECT id FROM subscriptions WHERE passengerId=?').get('pricing_unpriced'), undefined);
  });

  await t.test('selecting VIP stores its server fare and exposes its class in passenger status', async () => {
    const route = await request('GET', '/api/routes/pricing_route');
    assert.equal(route.status, 200);
    assert.deepEqual(route.body.route.serviceFares, { ECONOMY: 2600, DELUXE: null, VIP: 5200 });
    const selected = await request('POST', '/api/subscriptions/subscribe', 'pricing_subscriber', {
      routeId: 'pricing_route', pickupStopId: 'pricing_stop_a', destinationStopId: 'pricing_stop_b', serviceClass: 'VIP', priceEtb: 1
    });
    assert.equal(selected.status, 200, JSON.stringify(selected.body));
    assert.equal(selected.body.route.priceEtb, 5200);
    assert.equal(selected.body.route.serviceClass, 'VIP');
    const sub = await DB.prepare('SELECT * FROM subscriptions WHERE passengerId=?').get('pricing_subscriber');
    assert.equal(sub.priceEtb, 5200);
    assert.equal((await getSubscriptionTerms(sub.id)).serviceClass, 'VIP');
    const status = await request('GET', '/api/subscriptions/my-status', 'pricing_subscriber');
    assert.equal(status.status, 200);
    assert.equal(status.body.isSubscribed, false);
    assert.equal(status.body.subscription.serviceClass, 'VIP');
    assert.equal(status.body.subscription.priceEtb, 5200);
  });

  await t.test('route or class selection never replaces an unexpired paid pass', async () => {
    const end = new Date(Date.now() + 20 * 86400000).toISOString().slice(0, 10);
    await DB.prepare("UPDATE subscriptions SET paymentStatus='PAID',subscriptionStatus='ACTIVE',endDate=?,qrToken='retained-fixture-qr',vehicleId='pricing_vehicle_vip' WHERE passengerId=?")
      .run(end, 'pricing_subscriber');
    const before = await DB.prepare('SELECT * FROM subscriptions WHERE passengerId=?').get('pricing_subscriber');
    for (const routeId of ['pricing_route', unpricedRouteId]) {
      const selected = await request('POST', '/api/subscriptions/subscribe', 'pricing_subscriber', { routeId, serviceClass: 'ECONOMY' });
      assert.equal(selected.status, 409);
      assert.deepEqual(await DB.prepare('SELECT * FROM subscriptions WHERE passengerId=?').get('pricing_subscriber'), before);
    }
    assert.equal((await getSubscriptionTerms(before.id)).serviceClass, 'VIP');
  });

  await t.test('checkout uses the chosen assignment fare and an idempotency key cannot switch class', async () => {
    const body = { routeId: 'pricing_route', serviceClass: 'VIP', amountEtb: 1, idempotencyKey: 'vip-checkout-fixture-key' };
    const checkout = await request('POST', '/api/subscriptions/telebirr/pay', 'pricing_payer', body);
    assert.equal(checkout.status, 201, JSON.stringify(checkout.body));
    assert.equal(checkout.body.amountEtb, 5200);
    assert.equal(checkout.body.serviceClass, 'VIP');
    assert.equal(checkouts.length, 1);
    assert.equal(checkouts[0].amount, 5200);
    const order = await DB.prepare('SELECT * FROM telebirr_payment_orders WHERE merchant_order_id=?').get(checkout.body.merchantOrderId);
    assert.equal(Number(order.amount_etb), 5200);
    assert.equal((await getPaymentTerms(order.merchant_order_id)).serviceClass, 'VIP');
    const replay = await request('POST', '/api/subscriptions/telebirr/pay', 'pricing_payer', body);
    assert.equal(replay.status, 200);
    assert.equal(replay.body.merchantOrderId, order.merchant_order_id);
    assert.equal(checkouts.length, 1);
    // The second service class deliberately has the same amount. Comparing only
    // payment amounts would accidentally reuse a VIP order for Economy here.
    await DB.prepare("UPDATE commute_route_terms SET monthly_fare_etb=5200 WHERE vehicle_id='pricing_vehicle_economy'").run();
    try {
      const mismatch = await request('POST', '/api/subscriptions/telebirr/pay', 'pricing_payer', { ...body, serviceClass: 'ECONOMY' });
      assert.equal(mismatch.status, 409);
      assert.equal(checkouts.length, 1);
      assert.equal((await getPaymentTerms(order.merchant_order_id)).serviceClass, 'VIP');
    } finally {
      await DB.prepare("UPDATE commute_route_terms SET monthly_fare_etb=2600 WHERE vehicle_id='pricing_vehicle_economy'").run();
    }
  });

  await t.test('verified settlement preserves the purchased class and amount after an admin fare change', async () => {
    const order = await DB.prepare('SELECT * FROM telebirr_payment_orders WHERE idempotency_key=?').get('vip-checkout-fixture-key');
    await DB.prepare("UPDATE commute_route_terms SET monthly_fare_etb=5900 WHERE vehicle_id='pricing_vehicle_vip'").run();
    const paid = await request('GET', '/api/subscriptions/telebirr/status/' + order.merchant_order_id, 'pricing_payer');
    assert.equal(paid.status, 200, JSON.stringify(paid.body));
    assert.equal(paid.body.status, 'PAID');
    const sub = await DB.prepare('SELECT * FROM subscriptions WHERE passengerId=?').get('pricing_payer');
    assert.equal(sub.priceEtb, 5200);
    assert.equal(sub.paymentStatus, 'PAID');
    assert.equal((await getSubscriptionTerms(sub.id)).serviceClass, 'VIP');
    const status = await request('GET', '/api/subscriptions/my-status', 'pricing_payer');
    assert.equal(status.status, 200);
    assert.equal(status.body.isSubscribed, true);
    assert.equal(status.body.subscription.serviceClass, 'VIP');
    assert.equal(status.body.subscription.priceEtb, 5200);
    assert.equal(status.body.subscription.renewalPriceEtb, 5900);
    const payment = await DB.prepare('SELECT * FROM payment_transactions WHERE passengerId=?').get('pricing_payer');
    assert.equal(payment.amountEtb, 5200);
    const expiry = sub.endDate;
    const replay = await request('GET', '/api/subscriptions/telebirr/status/' + order.merchant_order_id, 'pricing_payer');
    assert.equal(replay.body.status, 'PAID');
    assert.equal((await DB.prepare('SELECT endDate FROM subscriptions WHERE id=?').get(sub.id)).endDate, expiry);
    assert.equal((await DB.prepare('SELECT COUNT(*) AS count FROM payment_transactions WHERE passengerId=?').get('pricing_payer')).count, 1);
  });
});
