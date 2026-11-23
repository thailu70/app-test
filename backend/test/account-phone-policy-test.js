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
process.env.PASSO_TEST_OTP_ENABLED = 'true';
process.env.PASSO_TEST_OTP_CODE = '123456';
process.env.PASSO_TEST_OTP_PASSENGER_PHONE = '0912346991';
process.env.PASSO_TEST_OTP_DRIVER_PHONE = '0912346992';
process.env.PASSO_TEST_OTP_EXPIRES_AT = new Date(Date.now() + 24 * 60 * 60 * 1000).toISOString();
process.env.PASSO_TEST_OTP_DELIVERY = 'AFRO_SMS';
process.env.AFRO_SMS_API_TOKEN = 'isolated-test-token';
const networkFetch = global.fetch;
global.fetch = async (input, init) => String(input).startsWith('https://api.afromessage.com/api/send')
  ? { ok: true, status: 200, json: async () => ({ acknowledge: 'success' }) }
  : networkFetch(input, init);
const { DB } = require('../src/db');
const { accountPhonePolicy } = require('../src/services/account-phone-policy');

test('deferred account verification preserves truthful ownership and passenger authentication', async t => {
  await require('../src/services/onboarding').initOnboardingSchema();
  await DB.exec('CREATE TABLE IF NOT EXISTS app_settings(key TEXT PRIMARY KEY,value TEXT NOT NULL,updated_at TEXT DEFAULT CURRENT_TIMESTAMP)');
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

  await t.test('passenger sign-in requires a one-time code and stays role-scoped', async () => {
    const legacy = await request('/login', { phone: input.phone, password: input.password, role: 'PASSENGER' });
    assert.equal(legacy.status, 410); assert.equal(legacy.body.code, 'OTP_REQUIRED');
    const sent = await request('/request-otp', { phone: input.phone, purpose: 'LOGIN' });
    assert.equal(sent.status, 200, JSON.stringify(sent.body));
    const verified = await request('/verify-otp', { phone: input.phone, purpose: 'LOGIN', code: '123456' });
    assert.equal(verified.status, 200, JSON.stringify(verified.body));
    const wrongRole = await request('/login-otp', { phone: input.phone, role: 'DRIVER', verificationToken: verified.body.verificationToken });
    assert.equal(wrongRole.status, 401);
    const loggedIn = await request('/login-otp', { phone: '+251912346991', role: 'PASSENGER', verificationToken: verified.body.verificationToken });
    assert.equal(loggedIn.status, 200); assert.equal(loggedIn.body.user.id, created.user.id);
    assert.ok(loggedIn.body.token);
    assert.equal((await request('/register', { ...input, phone: '+251912346991' })).status, 409, 'Alternate spellings cannot create a second account for the same number');
  });

  await t.test('deferral does not relax phone, password or role validation', async () => {
    for (const invalid of [
      { ...input, phone: 'not-a-number' },
      { ...input, phone: '0912346992', role: {} }
    ]) assert.equal((await request('/register', invalid)).status, 400);
    assert.equal((await DB.prepare('SELECT COUNT(*) AS count FROM users WHERE id=?').get(created.user.id)).count, 1);
  });
});
