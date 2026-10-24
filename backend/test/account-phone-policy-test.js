'use strict';

const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs'), os = require('node:os'), path = require('node:path');

// Use an isolated local database, then exercise the real HTTP handlers in
// production mode so NODE_ENV cannot silently bypass a registration OTP gate.
process.env.NODE_ENV = 'test';
delete process.env.DATABASE_URL;
process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), 'passo-account-phone-policy-'));
process.env.JWT_SECRET = 'isolated-account-phone-policy-auth-secret';
process.env.ADMIN_REGISTRATION_SECRET = 'isolated-account-phone-policy-admin-secret';
const { DB } = require('../src/db');
const { accountPhonePolicy } = require('../src/services/account-phone-policy');

test('deferred account verification preserves truthful ownership and passenger authentication', async t => {
  await require('../src/services/onboarding').initOnboardingSchema();
  await DB.exec('CREATE TABLE IF NOT EXISTS auth_otps(id TEXT PRIMARY KEY,phone TEXT,purpose TEXT,code_hash TEXT,verification_token_hash TEXT,expires_at TIMESTAMP,verified_at TIMESTAMP,attempts INTEGER DEFAULT 0,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)');
  await DB.prepare("INSERT INTO routes(id,name,nameAm,routeType,active,companyId,basePriceEtb) VALUES('phone_policy_route','Bole to CMC','Bole to CMC','DAILY_COMMUTE',1,'',2500)").run();

  process.env.NODE_ENV = 'production';
  t.after(() => { process.env.NODE_ENV = 'test'; });
  const express = require('express'), app = express();
  app.use(express.json());
  app.use('/api/auth', require('../src/routes/auth'));
  const server = app.listen(0, '127.0.0.1');
  await new Promise(resolve => server.once('listening', resolve));
  t.after(() => new Promise(resolve => { server.closeAllConnections(); server.close(resolve); }));
  const base = 'http://127.0.0.1:' + server.address().port;
  const request = async (endpoint, body, token) => {
    const response = await fetch(base + '/api/auth' + endpoint, {
      method: body === undefined ? 'GET' : 'POST',
      headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}) },
      ...(body === undefined ? {} : { body: JSON.stringify(body) })
    });
    return { status: response.status, body: await response.json() };
  };
  const input = { fullName: 'Unverified Passenger', phone: '0912346991', password: 'Passenger fixture password', role: 'PASSENGER', appliedRouteId: 'phone_policy_route' };
  let created;

  await t.test('only passenger and driver account verification is deferred, including company drivers', () => {
    for (const account of [{ role: 'PASSENGER' }, { role: 'DRIVER' }, { role: 'DRIVER', companyId: 'company' }, { role: 'DRIVER', company_id: 'company' }]) {
      assert.deepEqual(accountPhonePolicy(account), { phoneVerificationRequiredForRegistration: false, phoneVerificationRequiredForApproval: false, phoneVerificationRequiredForAllocation: false });
    }
    for (const role of ['COMPANY', 'ADMIN', 'UNKNOWN', '']) assert.equal(accountPhonePolicy({ role }).phoneVerificationRequiredForRegistration, true);
  });

  await t.test('production passenger signup succeeds without OTP but grants no paid pass or verified ownership', async () => {
    const registered = await request('/register', input);
    assert.equal(registered.status, 201, JSON.stringify(registered.body));
    created = registered.body;
    assert.equal(created.user.role, 'PASSENGER');
    assert.equal(created.user.status, 'ACTIVE');
    assert.ok(created.token);
    assert.equal(created.user.phoneVerified, undefined, 'Skipping verification must not claim verified ownership');
    assert.deepEqual({ payment: created.subscription.paymentStatus, status: created.subscription.status, qr: created.subscription.qrToken, days: created.subscription.daysRemaining }, { payment: 'UNPAID', status: 'PENDING', qr: null, days: 0 });
    assert.equal((await DB.prepare('SELECT COUNT(*) AS count FROM auth_otps').get()).count, 0, 'Registration does not request, create or fabricate OTP proof');
    assert.equal((await DB.prepare('SELECT COUNT(*) AS count FROM onboarding_accounts WHERE account_id=?').get(created.user.id)).count, 0);
    const me = await request('/me', undefined, created.token);
    assert.equal(me.status, 200); assert.equal(me.body.user.id, created.user.id);
  });

  await t.test('phone-password login and role isolation remain mandatory without account OTP', async () => {
    assert.equal((await request('/login', { phone: input.phone, password: 'wrong-password', role: 'PASSENGER' })).status, 401);
    assert.equal((await request('/login', { phone: input.phone, password: input.password, role: 'DRIVER' })).status, 403);
    const loggedIn = await request('/login', { phone: '+251912346991', password: input.password, role: 'PASSENGER' });
    assert.equal(loggedIn.status, 200); assert.equal(loggedIn.body.user.id, created.user.id);
    assert.equal((await request('/register', { ...input, phone: '+251912346991' })).status, 409, 'Alternate spellings cannot create a second account for the same number');
  });

  await t.test('deferral does not relax phone, password or role validation', async () => {
    for (const invalid of [
      { ...input, phone: 'not-a-number' },
      { ...input, phone: '0912346992', password: 'short' },
      { ...input, phone: '0912346992', role: 'COMPANY' },
      { ...input, phone: '0912346992', role: {} },
      { ...input, phone: { value: '0912346992' } }
    ]) assert.equal((await request('/register', invalid)).status, 400);
    assert.equal((await DB.prepare('SELECT COUNT(*) AS count FROM users WHERE id=?').get(created.user.id)).count, 1);
  });
});
