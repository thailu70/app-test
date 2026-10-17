'use strict';

const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

// Each run uses an isolated database, including when the developer has a .env file.
process.env.NODE_ENV = 'test';
process.env.DATABASE_URL = '';
process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), 'routepass-admin-activation-'));
process.env.JWT_SECRET = 'admin-activation-integration-test-jwt-secret';
process.env.QR_SIGNING_KEY = 'admin-activation-integration-test-qr-secret';
process.env.PAYMENT_MODE = 'PRODUCTION';
delete process.env.ALLOW_ADMIN_PASS_ACTIVATION;
delete process.env.ALLOW_MANUAL_TEST_RECHARGE;

const { DB } = require('../src/db');
const { server } = require('../src/server');
const { signToken } = require('../src/middleware/auth');
const { verifySignedQrToken } = require('../src/routes/subscriptions');

test('temporary admin grants are authorized, audited, production opt-in, and create no payment', async (t) => {
  // db.js initializes the complete SQLite schema. The server's PostgreSQL
  // migrations contain ALTER TABLE ADD COLUMN IF NOT EXISTS, unsupported by SQLite.
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', resolve);
  });
  t.after(async () => {
    process.env.NODE_ENV = 'test';
    delete process.env.ALLOW_ADMIN_PASS_ACTIVATION;
    delete process.env.ALLOW_MANUAL_TEST_RECHARGE;
    await new Promise((resolve, reject) => server.close(err => err ? reject(err) : resolve()));
  });
  const baseUrl = 'http://127.0.0.1:' + server.address().port;
  async function request(method, route, token, body) {
    const response = await fetch(baseUrl + route, {
      method,
      headers: {
        'Content-Type': 'application/json',
        ...(token ? { Authorization: 'Bearer ' + token } : {})
      },
      ...(body === undefined ? {} : { body: JSON.stringify(body) })
    });
    return { status: response.status, data: await response.json() };
  }
  async function createUser(id, role, status = 'ACTIVE') {
    await DB.prepare('INSERT INTO users (id, role, fullName, phone, passwordHash, status) VALUES (?, ?, ?, ?, ?, ?)')
      .run(id, role, 'Activation test ' + id, '+251' + id, 'unused-test-hash', status);
    return signToken({ id, role });
  }
  await DB.prepare('INSERT INTO routes (id, name, nameAm, basePriceEtb, active) VALUES (?, ?, ?, ?, ?)')
    .run('activation_route', 'Activation corridor', 'Activation corridor', 2500, 1);
  await DB.prepare('INSERT INTO routes (id, name, nameAm, basePriceEtb, active) VALUES (?, ?, ?, ?, ?)')
    .run('activation_inactive_route', 'Inactive corridor', 'Inactive corridor', 2500, 0);
  const adminToken = await createUser('activation_admin', 'ADMIN');
  const passengerToken = await createUser('activation_passenger', 'PASSENGER');
  const driverToken = await createUser('activation_driver', 'DRIVER');
  await createUser('activation_inactive_passenger', 'PASSENGER', 'SUSPENDED');
  const suspendedAdminToken = await createUser('activation_inactive_admin', 'ADMIN', 'SUSPENDED');
  async function createSub(id, passengerId = 'activation_passenger', routeId = 'activation_route') {
    await DB.prepare("INSERT INTO subscriptions (id, passengerId, routeId, priceEtb, paymentStatus, subscriptionStatus, daysRemaining) VALUES (?, ?, ?, 2500, 'UNPAID', 'PENDING', 0)")
      .run(id, passengerId, routeId);
  }
  await createSub('activation_main');
  await createSub('activation_inactive_user', 'activation_inactive_passenger');
  await createSub('activation_inactive_route_sub', 'activation_passenger', 'activation_inactive_route');
  const endpoint = '/api/admin/subscriptions/activation_main/activate';
  const grant = { days: 30, reason: 'Temporary launch access approved by administrator' };
  const paymentCountBefore = (await DB.prepare('SELECT COUNT(*) AS c FROM payment_transactions').get()).c;
  const initialStats = await request('GET', '/api/admin/stats', adminToken);
  assert.equal(initialStats.status, 200);

  await t.test('feature flag defaults closed and cannot be bypassed by the old test flag', async () => {
    const features = await request('GET', '/api/admin/features', adminToken);
    assert.equal(features.status, 200);
    assert.equal(features.data.adminPassActivationEnabled, false);
    assert.equal((await request('POST', endpoint, adminToken, grant)).status, 403);
    process.env.ALLOW_MANUAL_TEST_RECHARGE = 'true';
    assert.equal((await request('POST', endpoint, adminToken, grant)).status, 403);
    delete process.env.ALLOW_MANUAL_TEST_RECHARGE;
    assert.equal((await DB.prepare('SELECT * FROM subscriptions WHERE id = ?').get('activation_main')).subscriptionStatus, 'PENDING');
  });

  process.env.ALLOW_ADMIN_PASS_ACTIVATION = 'true';
  await t.test('only an active administrator may view or use the activation capability', async () => {
    const features = await request('GET', '/api/admin/features', adminToken);
    assert.equal(features.data.adminPassActivationEnabled, true);
    for (const [token, expected] of [[null, 401], [passengerToken, 403], [driverToken, 403], [suspendedAdminToken, 401]]) {
      assert.equal((await request('POST', endpoint, token, grant)).status, expected);
      assert.equal((await request('GET', '/api/admin/features', token)).status, expected);
    }
  });

  await t.test('invalid durations and reasons leave the pending subscription unchanged', async () => {
    for (const days of [0, -1, 91, 1.5, '30', '30days', null, true, [], {}]) {
      assert.equal((await request('POST', endpoint, adminToken, { ...grant, days })).status, 400, 'Invalid days: ' + JSON.stringify(days));
    }
    for (const reason of ['', '  ', 'ab', 'x'.repeat(201), null, 17, {}, []]) {
      assert.equal((await request('POST', endpoint, adminToken, { ...grant, reason })).status, 400, 'Invalid reason: ' + JSON.stringify(reason));
    }
    const sub = await DB.prepare('SELECT * FROM subscriptions WHERE id = ?').get('activation_main');
    assert.equal(sub.paymentStatus, 'UNPAID');
    assert.equal(sub.subscriptionStatus, 'PENDING');
    assert.equal(sub.daysRemaining, 0);
    assert.equal(sub.qrToken, null);
    assert.equal((await DB.prepare("SELECT COUNT(*) AS c FROM audit_logs WHERE action = 'SUBSCRIPTION_ADMIN_ACTIVATED'").get()).c, 0);
  });

  await t.test('missing subscriptions, inactive passengers and inactive routes are rejected', async () => {
    assert.equal((await request('POST', '/api/admin/subscriptions/activation_missing/activate', adminToken, grant)).status, 404);
    for (const id of ['activation_inactive_user', 'activation_inactive_route_sub']) {
      const result = await request('POST', '/api/admin/subscriptions/' + id + '/activate', adminToken, grant);
      assert.ok([404, 409].includes(result.status), 'Inactive resource rejected: ' + id);
      const sub = await DB.prepare('SELECT * FROM subscriptions WHERE id = ?').get(id);
      assert.equal(sub.subscriptionStatus, 'PENDING');
      assert.equal(sub.qrToken, null);
    }
  });

  await t.test('audit failure rolls back activation and QR issuance atomically', async () => {
    await DB.exec("CREATE TRIGGER reject_activation_audit BEFORE INSERT ON audit_logs WHEN NEW.action = 'SUBSCRIPTION_ADMIN_ACTIVATED' BEGIN SELECT RAISE(ABORT, 'injected activation audit failure'); END");
    try {
      const before = await DB.prepare('SELECT * FROM subscriptions WHERE id = ?').get('activation_main');
      const failed = await request('POST', endpoint, adminToken, grant);
      assert.equal(failed.status, 500);
      assert.deepEqual(await DB.prepare('SELECT * FROM subscriptions WHERE id = ?').get('activation_main'), before);
      assert.equal((await DB.prepare("SELECT COUNT(*) AS c FROM audit_logs WHERE action = 'SUBSCRIPTION_ADMIN_ACTIVATED'").get()).c, 0);
    } finally {
      await DB.exec('DROP TRIGGER reject_activation_audit');
    }
  });

  await t.test('explicit flag works in production while test recharge remains forbidden', async () => {
    // Startup uses isolated SQLite; change NODE_ENV only after loading the server to
    // exercise the request-time production gate without pretending SQLite is a production database.
    process.env.NODE_ENV = 'production';
    process.env.ALLOW_MANUAL_TEST_RECHARGE = 'true';
    const features = await request('GET', '/api/admin/features', adminToken);
    assert.equal(features.data.adminPassActivationEnabled, true);
    assert.equal(features.data.manualTestRechargeEnabled, false);
    assert.equal((await request('POST', '/api/admin/subscriptions/activation_main/recharge', adminToken, { days: 30 })).status, 403);
    process.env.ALLOW_ADMIN_PASS_ACTIVATION = 'false';
    assert.equal((await request('POST', endpoint, adminToken, grant)).status, 403);
    process.env.ALLOW_ADMIN_PASS_ACTIVATION = 'true';

    const result = await request('POST', endpoint, adminToken, grant);
    assert.equal(result.status, 200, JSON.stringify(result.data));
    assert.equal(result.data.success, true);
    assert.equal(result.data.activationSource, 'ADMIN_GRANT');
    assert.equal(result.data.noPaymentCollected, true);
    assert.equal(result.data.transaction, undefined);
    const sub = await DB.prepare('SELECT * FROM subscriptions WHERE id = ?').get('activation_main');
    assert.equal(sub.subscriptionStatus, 'ACTIVE');
    assert.equal(sub.paymentStatus, 'PAID');
    assert.equal(sub.daysRemaining, 30);
    const qr = verifySignedQrToken(sub.qrToken);
    assert.equal(qr.valid, true);
    assert.equal(qr.subId, sub.id);
    assert.equal(qr.passengerId, sub.passengerId);
    assert.equal(qr.routeId, sub.routeId);
    assert.equal(Number(qr.expiresTimestamp), Date.parse(sub.endDate));
    assert.equal(Number(qr.expiresTimestamp) > Date.now() + 29 * 86400000, true);
    assert.equal(Number(qr.expiresTimestamp) <= Date.now() + 30 * 86400000, true);
    // Remove the other pending fixture so my-status resolves this passenger's grant.
    await DB.prepare('DELETE FROM subscriptions WHERE id = ?').run('activation_inactive_route_sub');
    const status = await request('GET', '/api/subscriptions/my-status', passengerToken);
    assert.equal(status.status, 200);
    assert.equal(status.data.isSubscribed, true);
    assert.equal(status.data.subscription.qrToken, sub.qrToken);
    assert.equal(status.data.subscription.daysRemaining, 30);
    process.env.NODE_ENV = 'test';
    delete process.env.ALLOW_MANUAL_TEST_RECHARGE;
  });

  await t.test('repeat activation does not extend or replace an existing pass', async () => {
    const before = await DB.prepare('SELECT * FROM subscriptions WHERE id = ?').get('activation_main');
    const repeated = await request('POST', endpoint, adminToken, grant);
    assert.equal(repeated.status, 409);
    assert.equal(repeated.data.code, 'PASS_ALREADY_ACTIVE');
    const after = await DB.prepare('SELECT * FROM subscriptions WHERE id = ?').get('activation_main');
    assert.deepEqual(after, before);
  });

  await t.test('one grant audit records the actor and reason without creating revenue', async () => {
    const logs = await DB.prepare("SELECT * FROM audit_logs WHERE action = 'SUBSCRIPTION_ADMIN_ACTIVATED'").all();
    assert.equal(logs.length, 1);
    assert.equal(logs[0].userId, 'activation_admin');
    assert.equal(logs[0].role, 'ADMIN');
    const details = JSON.parse(logs[0].details);
    assert.equal(details.subscriptionId, 'activation_main');
    assert.equal(details.days, 30);
    assert.equal(details.reason, grant.reason);
    assert.equal(details.noPaymentCollected, true);
    assert.equal((await DB.prepare('SELECT COUNT(*) AS c FROM payment_transactions').get()).c, paymentCountBefore);
    const stats = await request('GET', '/api/admin/stats', adminToken);
    assert.equal(stats.status, 200);
    assert.equal(stats.data.stats.totalRevenueEtb, initialStats.data.stats.totalRevenueEtb);
  });
});
