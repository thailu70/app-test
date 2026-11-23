'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs'), os = require('node:os'), path = require('node:path');

process.env.NODE_ENV = 'test';
delete process.env.DATABASE_URL;
process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), 'passo-credential-race-'));
process.env.JWT_SECRET = 'isolated-credential-reset-race-test-secret';

const { DB } = require('../src/db');
const bcrypt = require('bcryptjs');
const { initAccountRecoverySchema, createAccountRecoveryService } = require('../src/services/account-recovery');
const { verifyToken } = require('../src/middleware/auth');

test('passenger and driver accounts require OTP, while company reset cannot revive a stale password proof', async t => {
  await require('../src/services/onboarding').initOnboardingSchema();
  await require('../src/services/account-deletion').initAccountDeletionSchema();
  await initAccountRecoverySchema();

  const password = 'Legacy password fixture';
  const hash = await bcrypt.hash(password, 4);
  await DB.prepare('INSERT INTO users(id,role,fullName,phone,passwordHash,status) VALUES(?,?,?,?,?,?)')
    .run('otp_passenger', 'PASSENGER', 'OTP Fixture', '+251912345670', hash, 'ACTIVE');
  await DB.prepare('INSERT INTO users(id,role,fullName,phone,passwordHash,status) VALUES(?,?,?,?,?,?)')
    .run('otp_driver', 'DRIVER', 'OTP Driver', '+251912345671', hash, 'PENDING');
  await DB.prepare("INSERT INTO onboarding_accounts(account_id,role,normalized_phone,review_status,service_type,acceptance_status,created_at,updated_at) VALUES(?,'DRIVER',?,'AWAITING_ACCEPTANCE','DAILY_COMMUTE','PENDING',?,?)")
    .run('otp_driver', '+251912345671', new Date().toISOString(), new Date().toISOString());

  const companyHash = await bcrypt.hash('Old company password', 4);
  await DB.prepare("INSERT INTO transport_companies(id,name,phone,representative_name,password_hash,status) VALUES(?,?,?,?,?,'ACTIVE')")
    .run('race_company', 'Race Company', '+251912345672', 'Company Fixture', companyHash);

  const express = require('express'), app = express();
  app.use(express.json());
  app.use('/api/auth', require('../src/routes/auth'));
  app.use('/api/onboarding', require('../src/routes/onboarding'));
  app.use('/api/company', require('../src/routes/company-portal'));
  const server = app.listen(0, '127.0.0.1');
  await new Promise(resolve => server.once('listening', resolve));
  t.after(() => new Promise(resolve => { server.closeAllConnections(); server.close(resolve); }));
  const base = 'http://127.0.0.1:' + server.address().port;
  const call = async (endpoint, body, token) => {
    const response = await fetch(base + endpoint, {
      method: body ? 'POST' : 'GET',
      headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}) },
      ...(body ? { body: JSON.stringify(body) } : {})
    });
    return { status: response.status, data: await response.json() };
  };

  await t.test('passenger and driver password endpoints direct users to OTP sign-in', async () => {
    const passenger = await call('/api/auth/login', { role: 'PASSENGER', phone: '+251912345670', password });
    assert.equal(passenger.status, 410);
    assert.equal(passenger.data.code, 'OTP_REQUIRED');
    const driver = await call('/api/onboarding/login', { role: 'DRIVER', phone: '+251912345671', password });
    assert.equal(driver.status, 410);
    assert.match(driver.data.error, /phone number and SMS code/i);
  });

  await t.test('a company password reset cannot upgrade an in-flight old-password proof', async () => {
    let sms;
    const recovery = createAccountRecoveryService({ sendSms: async (_phone, message) => {
      sms = message;
      return { sent: true };
    } });
    await recovery.request({ role: 'COMPANY', phone: '+251912345672' });
    await recovery.waitForDeliveries();
    const code = /\b(\d{6})\b/.exec(sms)[1];
    const oldPassword = 'Old company password', newPassword = 'New company password';
    let release, entered;
    const paused = new Promise(resolve => { release = resolve; });
    const atPasswordCheck = new Promise(resolve => { entered = resolve; });
    const originalCompare = bcrypt.compare;
    bcrypt.compare = async (...args) => {
      const result = await originalCompare(...args);
      if (args[0] === oldPassword) { entered(); await paused; }
      return result;
    };
    try {
      const pending = call('/api/company/login', { phone: '+251912345672', password: oldPassword });
      await atPasswordCheck;
      await recovery.reset({ role: 'COMPANY', phone: '+251912345672', code, newPassword });
      release();
      const staleLogin = await pending;
      if (staleLogin.status === 200) {
        assert.equal(verifyToken(staleLogin.data.token).sessionVersion, 0,
          'A password proof cannot acquire a session version created by the later reset');
        assert.equal((await call('/api/company/me', undefined, staleLogin.data.token)).status, 401);
      } else assert.equal(staleLogin.status, 401);
      const fresh = await call('/api/company/login', { phone: '+251912345672', password: newPassword });
      assert.equal(fresh.status, 200, JSON.stringify(fresh.data));
      assert.equal(verifyToken(fresh.data.token).sessionVersion, 1);
      assert.equal((await call('/api/company/me', undefined, fresh.data.token)).status, 200);
    } finally {
      release();
      bcrypt.compare = originalCompare;
    }
  });
});
