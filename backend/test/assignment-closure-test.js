'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
process.env.NODE_ENV = 'test';
// PostgreSQL is opt-in on the disposable CI service; local/default runs always
// isolate SQLite rather than inheriting an operator's configured database.
if (process.env.ASSIGNMENT_TEST_POSTGRES === 'true') {
  if (process.env.CI !== 'true' || !process.env.DATABASE_URL) throw new Error('Disposable CI PostgreSQL configuration is required.');
} else delete process.env.DATABASE_URL;
process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), 'passo-assignment-closure-'));
process.env.UPLOAD_DIR = path.join(process.env.DATA_DIR, 'uploads');
const bcrypt = require('bcryptjs');
const express = require('express');
const { DB } = require('../src/db');
const { signToken } = require('../src/middleware/auth');
const { initOnboardingSchema } = require('../src/services/onboarding');
const { initIntercitySchema } = require('../src/services/intercity-schema');
const { initAccountDeletionSchema, createAccountDeletionService } = require('../src/services/account-deletion');

test('assignment writers revalidate accounts after closure commits between preflight and write', async t => {
  await initOnboardingSchema(); await initIntercitySchema(); await initAccountDeletionSchema();
  await DB.exec("CREATE TABLE IF NOT EXISTS company_route_assignments(id TEXT PRIMARY KEY,company_id TEXT,route_id TEXT,driver_id TEXT,vehicle_id TEXT,status TEXT DEFAULT 'ACTIVE',created_at TEXT DEFAULT CURRENT_TIMESTAMP)");
  const password = 'Private isolated test password', hash = await bcrypt.hash(password, 4);
  const deletion = createAccountDeletionService();
  await DB.prepare("INSERT INTO users(id,role,fullName,phone,passwordHash,status) VALUES('race_admin','ADMIN','Test Admin','0912345670',?,'ACTIVE'),('race_passenger_default','PASSENGER','Test Passenger','0912345671',?,'ACTIVE')").run(hash, hash);
  await DB.prepare("INSERT INTO transport_companies(id,name,phone,status) VALUES('race_company','Race Company','0912345699','ACTIVE')").run();
  const future = new Date(Date.now() + 86400000).toISOString();
  await DB.prepare("INSERT INTO routes(id,name,nameAm,routeType,companyId,origin,destination) VALUES('race_commute','Commute','Commute','DAILY_COMMUTE','','Bole','CMC'),('race_intercity','Intercity','Intercity','INTERCITY','race_company','Addis','Hawassa')").run();
  let serial = 0;
  async function driver(company = '') {
    const id = 'race_driver_' + ++serial, vehicleId = 'race_vehicle_' + serial;
    await DB.prepare("INSERT INTO users(id,role,fullName,phone,passwordHash,status,companyId) VALUES(?,'DRIVER',?,?,?,'ACTIVE',?)").run(id, id, '091233' + String(serial).padStart(4, '0'), hash, company);
    await DB.prepare('INSERT INTO vehicles(id,plateNumber,model,vehicleType,capacityLimit,driverId,assignedRouteId,companyId) VALUES(?,?,?,?,?,?,?,?)').run(vehicleId, vehicleId, 'Van', 'MINIVAN_8', 8, id, company ? 'race_intercity' : 'race_commute', company);
    return { id, vehicleId };
  }
  async function subscription(id, passengerId = 'race_passenger_default') {
    await DB.prepare("INSERT INTO subscriptions(id,passengerId,routeId,priceEtb,paymentStatus,subscriptionStatus,daysRemaining,endDate) VALUES(?,?,'race_commute',2500,'PAID','ACTIVE',1,?)").run(id, passengerId, future);
  }
  const app = express(); app.use(express.json());
  app.use('/api/admin', require('../src/routes/admin'));
  app.use('/api/intercity', require('../src/routes/intercity'));
  app.use('/api/companies', require('../src/routes/company-portal'));
  const server = app.listen(0, '127.0.0.1'); await new Promise(resolve => server.once('listening', resolve));
  t.after(() => new Promise(resolve => server.close(resolve)));
  const base = 'http://127.0.0.1:' + server.address().port;
  const admin = signToken({ id: 'race_admin', role: 'ADMIN' }), company = signToken({ id: 'race_company', role: 'COMPANY' });
  async function request(endpoint, method, body, token = admin) {
    const response = await fetch(base + endpoint, { method, headers: { Authorization: 'Bearer ' + token, 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
    return { status: response.status, body: await response.json() };
  }
  const transaction = DB.transaction;
  let beforeWrite;
  DB.transaction = async function (fn) {
    const action = beforeWrite; beforeWrite = null;
    if (action) await action();
    return transaction.call(DB, fn);
  };
  t.after(() => { DB.transaction = transaction; });
  const close = id => deletion.erase(id, 'DRIVER', { password, confirmation: 'DELETE' });
  const payload = d => ({ routeId: 'race_intercity', driverId: d.id, vehicleId: d.vehicleId, departureAt: future, latitude: 9, longitude: 38, seats: 8, fareEtb: 500 });

  await t.test('subscription assignment cannot attach passengers to a driver closed after preflight', async () => {
    const d = await driver(); await subscription('race_sub');
    beforeWrite = () => close(d.id);
    assert.equal((await request('/api/admin/subscriptions/race_sub/assignment', 'PATCH', { vehicleId: d.vehicleId })).status, 409);
    assert.equal((await DB.prepare("SELECT vehicleId FROM subscriptions WHERE id='race_sub'").get()).vehicleId, null);
    assert.equal((await DB.prepare('SELECT driverId FROM vehicles WHERE id=?').get(d.vehicleId)).driverId, null);
  });
  await t.test('driver route assignment cannot restore route fields on a closed account', async () => {
    const d = await driver(); beforeWrite = () => close(d.id);
    assert.equal((await request('/api/admin/drivers/' + d.id + '/route', 'PATCH', { routeId: 'race_commute' })).status, 409);
    const account = await DB.prepare('SELECT appliedRouteId,status FROM users WHERE id=?').get(d.id);
    assert.equal(account.status, 'SUSPENDED'); assert.equal(account.appliedRouteId, '');
  });
  await t.test('passenger closure between assignment reads and write keeps cancelled pass unassigned', async () => {
    const d = await driver(), id = 'race_passenger';
    await DB.prepare("INSERT INTO users(id,role,fullName,phone,passwordHash,status) VALUES(?,'PASSENGER','Private Passenger','0912345688',?,'ACTIVE')").run(id, hash);
    await subscription('race_passenger_sub', id);
    beforeWrite = () => deletion.erase(id, 'PASSENGER', { password, confirmation: 'DELETE' });
    assert.equal((await request('/api/admin/subscriptions/race_passenger_sub/assignment', 'PATCH', { vehicleId: d.vehicleId })).status, 409);
    const sub = await DB.prepare("SELECT vehicleId,subscriptionStatus FROM subscriptions WHERE id='race_passenger_sub'").get();
    assert.equal(sub.vehicleId, ''); assert.equal(sub.subscriptionStatus, 'CANCELLED');
  });
  await t.test('a deletion receipt rejects accidental driver reactivation even with restored ownership', async () => {
    const d = await driver(); await subscription('race_reactivated_sub'); await close(d.id);
    await DB.prepare("UPDATE users SET status='ACTIVE' WHERE id=?").run(d.id);
    await DB.prepare("UPDATE vehicles SET driverId=?,assignedRouteId='race_commute' WHERE id=?").run(d.id, d.vehicleId);
    assert.equal((await request('/api/admin/subscriptions/race_reactivated_sub/assignment', 'PATCH', { vehicleId: d.vehicleId })).status, 409);
    assert.equal((await DB.prepare("SELECT vehicleId FROM subscriptions WHERE id='race_reactivated_sub'").get()).vehicleId, null);
  });
  await t.test('intercity creation rechecks driver closure inside its write transaction', async () => {
    const d = await driver('race_company'); beforeWrite = () => close(d.id);
    assert.equal((await request('/api/intercity/company/departures', 'POST', payload(d), company)).status, 409);
    assert.equal(Number((await DB.prepare('SELECT COUNT(*) AS count FROM intercity_departures WHERE driver_id=?').get(d.id)).count), 0);
  });
  await t.test('departure editing cannot assign a replacement driver who closed after preflight', async () => {
    const original = await driver('race_company'), replacement = await driver('race_company');
    const created = await request('/api/intercity/company/departures', 'POST', payload(original), company);
    assert.equal(created.status, 201);
    const departureId = created.body.departure.id;
    beforeWrite = () => close(replacement.id);
    assert.equal((await request('/api/intercity/company/departures/' + departureId, 'PATCH', { vehicleId: replacement.vehicleId, driverId: replacement.id }, company)).status, 409);
    const saved = await DB.prepare('SELECT driver_id,vehicle_id FROM intercity_departures WHERE id=?').get(departureId);
    assert.equal(saved.driver_id, original.id); assert.equal(saved.vehicle_id, original.vehicleId);
  });
  await t.test('company approval is rechecked after request authentication', async () => {
    const d = await driver('race_company');
    beforeWrite = () => DB.prepare("UPDATE transport_companies SET status='INACTIVE' WHERE id='race_company'").run();
    assert.equal((await request('/api/intercity/company/departures', 'POST', payload(d), company)).status, 409);
    assert.equal(Number((await DB.prepare('SELECT COUNT(*) AS count FROM intercity_departures WHERE driver_id=?').get(d.id)).count), 0);
    await DB.prepare("UPDATE transport_companies SET status='ACTIVE' WHERE id='race_company'").run();
  });
  await t.test('company assignment cannot restore a driver closed after authentication', async () => {
    const d = await driver('race_company'); beforeWrite = () => close(d.id);
    const response = await request('/api/companies/operations/assignments', 'POST', { routeId: 'race_intercity', driverId: d.id, vehicleId: d.vehicleId }, company);
    assert.equal(response.status, 403);
    assert.equal(Number((await DB.prepare('SELECT COUNT(*) AS count FROM company_route_assignments WHERE driver_id=?').get(d.id)).count), 0);
    assert.equal((await DB.prepare('SELECT driverId FROM vehicles WHERE id=?').get(d.vehicleId)).driverId, null);
  });
  await t.test('company allocation checks permanent deletion receipts despite accidental reactivation', async () => {
    const d = await driver('race_company'); await close(d.id);
    await DB.prepare("UPDATE users SET status='ACTIVE' WHERE id=?").run(d.id);
    const response = await request('/api/companies/operations/assignments', 'POST', { routeId: 'race_intercity', driverId: d.id, vehicleId: d.vehicleId }, company);
    assert.equal(response.status, 409);
    assert.equal(Number((await DB.prepare('SELECT COUNT(*) AS count FROM company_route_assignments WHERE driver_id=?').get(d.id)).count), 0);
  });
  await t.test('company allocation rechecks a company disabled after request authentication', async () => {
    const d = await driver('race_company');
    beforeWrite = () => DB.prepare("UPDATE transport_companies SET status='INACTIVE' WHERE id='race_company'").run();
    assert.equal((await request('/api/companies/operations/assignments', 'POST', { routeId: 'race_intercity', driverId: d.id, vehicleId: d.vehicleId }, company)).status, 409);
    assert.equal(Number((await DB.prepare('SELECT COUNT(*) AS count FROM company_route_assignments WHERE driver_id=?').get(d.id)).count), 0);
    await DB.prepare("UPDATE transport_companies SET status='ACTIVE' WHERE id='race_company'").run();
  });
  await t.test('a valid current commute assignment still succeeds', async () => {
    const d = await driver(); await subscription('race_valid_sub');
    assert.equal((await request('/api/admin/subscriptions/race_valid_sub/assignment', 'PATCH', { vehicleId: d.vehicleId })).status, 200);
    assert.equal((await DB.prepare("SELECT vehicleId FROM subscriptions WHERE id='race_valid_sub'").get()).vehicleId, d.vehicleId);
  });
  await t.test('a valid current company assignment still succeeds', async () => {
    const d = await driver('race_company');
    const result = await request('/api/companies/operations/assignments', 'POST', { routeId: 'race_intercity', driverId: d.id, vehicleId: d.vehicleId }, company);
    assert.equal(result.status, 201);
    assert.equal((await DB.prepare('SELECT driverId FROM vehicles WHERE id=?').get(d.vehicleId)).driverId, d.id);
  });
});
