'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
process.env.NODE_ENV = 'test';
process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), 'passo-driver-contract-'));
process.env.JWT_SECRET = 'isolated-driver-registration-contract-secret';
process.env.ADMIN_REGISTRATION_SECRET = 'isolated-driver-registration-admin-secret';
const { DB } = require('../src/db');

test('driver and passenger phone spellings preserve the shared application and operational boundaries', async t => {
  await DB.ready;
  await require('../src/services/onboarding').initOnboardingSchema();
  await require('../src/services/master-data').initMasterDataSchema();
  const express = require('express'), app = express();
  app.use(express.json());
  app.use('/api/auth', require('../src/routes/auth'));
  app.use('/api/onboarding', require('../src/routes/onboarding').createOnboardingRouter({ rateLimitMax: 100 }));
  const server = app.listen(0, '127.0.0.1');
  await new Promise(resolve => server.once('listening', resolve));
  t.after(() => new Promise(resolve => { server.closeAllConnections(); server.close(resolve); }));
  const base = 'http://127.0.0.1:' + server.address().port;
  const request = async (url, body, token) => {
    const response = await fetch(base + url, { method: body === undefined ? 'GET' : 'POST',
      headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}) },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
    return { status: response.status, body: await response.json() };
  };
  const password = 'Isolated driver contract password';
  const driver = { role: 'DRIVER', fullName: 'Driver Contract Fixture', phone: '0912 400321', password,
    licenseNumber: 'CONTRACT-LICENSE', assignedVehiclePlate: 'CONTRACT-01', vehicleModel: 'Fixture van', vehicleType: 'MINIVAN_8',
    proposedDeparture: 'Contract new origin', proposedDestination: 'Contract new destination', proposedMonthlyFeeEtb: 30000 };
  let driverId;
  await t.test('older driver signup also creates a tracked application and normalizes the phone', async () => {
    const result = await request('/api/auth/register', driver);
    assert.equal(result.status, 201, JSON.stringify(result.body));
    driverId = result.body.user.id;
    assert.equal(result.body.token, null);
    assert.equal(result.body.user.phone, '+251912400321');
    assert.equal(result.body.onboarding.routePreference.departure, driver.proposedDeparture);
    assert.equal(result.body.onboarding.routePreference.destination, driver.proposedDestination);
    assert.equal(result.body.onboarding.routePreference.proposedMonthlyFeeEtb, 30000);
    assert.equal((await DB.prepare('SELECT COUNT(*) AS count FROM onboarding_accounts WHERE account_id=?').get(driverId)).count, 1);
    assert.equal(result.body.onboarding.phoneVerified, false);
  });
  await t.test('pending drivers can open only the scoped application using equivalent phone spellings', async () => {
    for (const phone of ['0912400321', '+251 912 400321', '251912400321']) {
      const pending = await request('/api/auth/login', { phone, password, role: 'DRIVER' });
      assert.equal(pending.status, 403, JSON.stringify(pending.body));
      assert.equal(pending.body.code, 'PENDING');
      const application = await request('/api/onboarding/login', { phone, password, role: 'DRIVER' });
      assert.equal(application.status, 200, JSON.stringify(application.body));
      assert.equal(application.body.account.id, driverId);
      assert.equal((await request('/api/auth/me', undefined, application.body.token)).status, 401);
    }
  });
  await t.test('equivalent phone registrations cannot create another driver or vehicle', async () => {
    const result = await request('/api/auth/register', { ...driver, phone: '+251912400321', assignedVehiclePlate: 'CONTRACT-02' });
    assert.equal(result.status, 409);
    assert.equal((await DB.prepare("SELECT COUNT(*) AS count FROM vehicles WHERE plateNumber='CONTRACT-02'").get()).count, 0);
  });
  await t.test('missing route and fee are rejected rather than creating an untracked driver', async () => {
    const result = await request('/api/auth/register', { ...driver, phone: '0912400322', assignedVehiclePlate: 'CONTRACT-03', proposedDeparture: '', proposedDestination: '', proposedMonthlyFeeEtb: undefined });
    assert.equal(result.status, 400);
    assert.equal((await DB.prepare("SELECT COUNT(*) AS count FROM vehicles WHERE plateNumber='CONTRACT-03'").get()).count, 0);
  });
  await t.test('passenger signup stores a canonical phone and role isolation remains intact', async () => {
    const result = await request('/api/auth/register', { role: 'PASSENGER', fullName: 'Passenger Contract Fixture', phone: '0912 400323', password });
    assert.equal(result.status, 201, JSON.stringify(result.body));
    assert.equal(result.body.user.phone, '+251912400323');
    assert.ok(result.body.token);
    const credentials = { phone: '0912400323', password, role: 'PASSENGER' };
    assert.equal((await request('/api/auth/login', credentials)).status, 200);
    assert.equal((await request('/api/auth/login', { ...credentials, role: 'DRIVER' })).status, 403);
  });
  await t.test('approved legacy drivers with spaced stored phones retain sign-in access', async () => {
    const hash = await require('bcryptjs').hash(password, 4);
    await DB.prepare("INSERT INTO users(id,role,fullName,phone,passwordHash,status) VALUES('contract_legacy','DRIVER','Legacy Fixture','0912 400324',?,'ACTIVE')").run(hash);
    const result = await request('/api/auth/login', { phone: '0912400324', password, role: 'DRIVER' });
    assert.equal(result.status, 200, JSON.stringify(result.body));
    assert.equal(result.body.user.id, 'contract_legacy');
    assert.equal((await request('/api/auth/me', undefined, result.body.token)).status, 200);
  });
});
