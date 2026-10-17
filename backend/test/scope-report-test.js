'use strict';

const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
process.env.NODE_ENV = 'test';
process.env.DATABASE_URL = '';
process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), 'routepass-scope-report-'));
process.env.UPLOAD_DIR = path.join(process.env.DATA_DIR, 'uploads');
process.env.JWT_SECRET = 'scope-report-tests-authentication-secret';
process.env.QR_SIGNING_KEY = 'scope-report-tests-boarding-secret';
const { DB } = require('../src/db');
const { server, runDatabaseMigrations } = require('../src/server');
const { signToken } = require('../src/middleware/auth');

test('mounted application isolates commute/company operations, reports and assigned driver profiles', async t => {
  await runDatabaseMigrations();
  const now = new Date().toISOString();
  const yesterday = new Date(Date.now() - 2 * 86400000).toISOString();
  const tokens = {};
  for (const company of ['scope_company_a', 'scope_company_b']) {
    await DB.prepare('INSERT INTO transport_companies(id,name,phone,representative_name,status) VALUES(?,?,?,?,?)')
      .run(company, company, '+251900001100', company, 'ACTIVE');
    tokens[company] = signToken({ id: company, role: 'COMPANY' });
  }
  for (const [id, role, company] of [
    ['scope_admin', 'ADMIN', ''], ['scope_driver', 'DRIVER', ''], ['scope_driver_other', 'DRIVER', ''],
    ['scope_passenger', 'PASSENGER', ''], ['scope_passenger_other', 'PASSENGER', ''],
    ['scope_driver_a', 'DRIVER', 'scope_company_a'], ['scope_driver_b', 'DRIVER', 'scope_company_b'],
    ['scope_passenger_a', 'PASSENGER', 'scope_company_a'], ['scope_passenger_b', 'PASSENGER', 'scope_company_b']
  ]) {
    await DB.prepare('INSERT INTO users(id,role,fullName,phone,passwordHash,status,companyId,createdAt) VALUES(?,?,?,?,?,?,?,?)')
      .run(id, role, id, '+251-' + id, 'unused-test-password', 'ACTIVE', company, now);
    tokens[id] = signToken({ id, role });
  }
  for (const [id, company, type] of [
    ['scope_commute', '', 'DAILY_COMMUTE'], ['scope_commute_other', '', 'DAILY_COMMUTE'],
    ['scope_route_a', 'scope_company_a', 'INTERCITY'], ['scope_route_b', 'scope_company_b', 'INTERCITY']
  ]) {
    await DB.prepare('INSERT INTO routes(id,name,nameAm,routeType,companyId,origin,destination,active) VALUES(?,?,?,?,?,?,?,TRUE)')
      .run(id, id, id, type, company, 'Addis Ababa', 'Adama');
    await DB.prepare('INSERT INTO route_stops(id,routeId,stopName,stopNameAm,stopOrder,latitude,longitude) VALUES(?,?,?,?,?,?,?)')
      .run(id + '_stop', id, id, id, 1, 9, 38.7);
  }
  for (const [id, driver, route, company] of [
    ['scope_vehicle', 'scope_driver', 'scope_commute', ''],
    ['scope_vehicle_other', 'scope_driver_other', 'scope_commute_other', ''],
    ['scope_vehicle_a', 'scope_driver_a', 'scope_route_a', 'scope_company_a'],
    ['scope_vehicle_b', 'scope_driver_b', 'scope_route_b', 'scope_company_b']
  ]) {
    await DB.prepare('INSERT INTO vehicles(id,plateNumber,model,vehicleType,capacityLimit,driverId,driverName,assignedRouteId,companyId) VALUES(?,?,?,?,?,?,?,?,?)')
      .run(id, 'PLATE-' + id, 'Test minibus', 'MINIBUS_14', 14, driver, driver, route, company);
  }
  for (const [id, passenger, route, vehicle] of [
    ['scope_sub', 'scope_passenger', 'scope_commute', 'scope_vehicle'],
    ['scope_sub_other', 'scope_passenger_other', 'scope_commute_other', 'scope_vehicle_other'],
    ['scope_sub_a', 'scope_passenger_a', 'scope_route_a', 'scope_vehicle_a'],
    ['scope_sub_b', 'scope_passenger_b', 'scope_route_b', 'scope_vehicle_b']
  ]) {
    await DB.prepare("INSERT INTO subscriptions(id,passengerId,routeId,vehicleId,priceEtb,paymentStatus,subscriptionStatus,daysRemaining,updatedAt) VALUES(?,?,?,?,100,'PAID','ACTIVE',30,?)")
      .run(id, passenger, route, vehicle, now);
  }
  for (const suffix of ['a', 'b']) {
    await DB.prepare("INSERT INTO company_route_assignments(id,company_id,route_id,driver_id,vehicle_id,status) VALUES(?,?,?,?,?,'ACTIVE')")
      .run('scope_assignment_' + suffix, 'scope_company_' + suffix, 'scope_route_' + suffix, 'scope_driver_' + suffix, 'scope_vehicle_' + suffix);
  }
  await DB.prepare('INSERT INTO user_profiles(user_id,emergency_contact_name,emergency_contact_phone,vehicle_color,updated_at) VALUES(?,?,?,?,?)')
    .run('scope_driver', 'Family contact', '+251911100000', 'Blue', now);
  await DB.prepare('INSERT INTO user_profiles(user_id,emergency_contact_name,emergency_contact_phone,vehicle_color,updated_at) VALUES(?,?,?,?,?)')
    .run('scope_driver_other', 'Other family', '+251911100001', 'Red', now);

  for (const [id, driver, vehicle, route, date, passenger] of [
    ['scope_trip_1', 'scope_driver', 'scope_vehicle', 'scope_commute', now, 'scope_passenger'],
    ['scope_trip_2', 'scope_driver', 'scope_vehicle', 'scope_commute', yesterday, 'scope_passenger'],
    ['scope_trip_a', 'scope_driver_a', 'scope_vehicle_a', 'scope_route_a', now, 'scope_passenger_a'],
    ['scope_trip_b', 'scope_driver_b', 'scope_vehicle_b', 'scope_route_b', now, 'scope_passenger_b']
  ]) {
    await DB.prepare("INSERT INTO trips(id,driverId,vehicleId,routeId,status,startTime,endTime) VALUES(?,?,?,?,'COMPLETED',?,?)")
      .run(id, driver, vehicle, route, date, date);
    await DB.prepare("INSERT INTO checkin_records(id,tripId,passengerId,passengerName,routeId,stopName,timestamp,status,vehicleId,driverId) VALUES(?,?,?,?,?,?,?,'BOARDED',?,?)")
      .run(id + '_checkin', id, passenger, passenger, route, 'Origin', date, vehicle, driver);
  }
  for (const [id, passenger, amount, provider, date] of [
    ['scope_payment_1', 'scope_passenger', 100, 'Telebirr', now],
    ['scope_payment_2', 'scope_passenger', 50, 'Telebirr', yesterday],
    ['scope_payment_test', 'scope_passenger', 9000, 'ADMIN_TEST', now],
    ['scope_payment_a', 'scope_passenger_a', 15000, 'Telebirr', now],
    ['scope_payment_b', 'scope_passenger_b', 25000, 'Telebirr', now]
  ]) {
    await DB.prepare("INSERT INTO payment_transactions(id,passengerId,referenceNumber,amountEtb,provider,date,status) VALUES(?,?,?,?,?,?,'COMPLETED')")
      .run(id, passenger, id, amount, provider, date);
  }
  for (const suffix of ['a', 'b']) {
    await DB.prepare('INSERT INTO intercity_departures(id,company_id,route_id,driver_id,vehicle_id,origin,destination,departure_at,latitude,longitude,seats,fare_etb,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)')
      .run('scope_departure_' + suffix, 'scope_company_' + suffix, 'scope_route_' + suffix, 'scope_driver_' + suffix, 'scope_vehicle_' + suffix, 'Addis Ababa', 'Adama', new Date(Date.now() + 86400000).toISOString(), 9, 38.7, 14, 500, now);
    await DB.prepare("INSERT INTO intercity_bookings(id,departure_id,company_id,secret_hash,passenger_name,passenger_phone,seat_number,amount_etb,status,payment_status,expires_at,created_at) VALUES(?,?,?,?,?,?,?,?,'CONFIRMED','PAID',?,?)")
      .run('scope_booking_' + suffix, 'scope_departure_' + suffix, 'scope_company_' + suffix, 'test-secret-hash', 'Guest ' + suffix, '+251911000000', 1, suffix === 'a' ? 500 : 9999, new Date(Date.now() + 86400000).toISOString(), now);
  }

  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => new Promise(resolve => server.close(resolve)));
  const base = `http://127.0.0.1:${server.address().port}`;
  const request = async (method, endpoint, principal, body, contentType = 'application/json') => {
    const response = await fetch(base + endpoint, { method,
      headers: { 'Content-Type': contentType, ...(principal ? { Authorization: 'Bearer ' + tokens[principal] } : {}) },
      ...(body === undefined ? {} : { body: Buffer.isBuffer(body) ? body : JSON.stringify(body) }) });
    const bytes = Buffer.from(await response.arrayBuffer());
    return { status: response.status, data: response.headers.get('content-type')?.includes('application/json') ? JSON.parse(bytes.toString()) : null, bytes };
  };
  const expectOkay = value => { assert.equal(value.status, 200, JSON.stringify(value.data)); return value.data; };
  const ids = (response, key) => expectOkay(response)[key].map(row => row.id);

  await t.test('admin lists and public commute routes exclude both companies', async () => {
    for (const [endpoint, key, own, excluded] of [
      ['/api/admin/routes', 'routes', 'scope_commute', ['scope_route_a', 'scope_route_b']],
      ['/api/admin/drivers', 'drivers', 'scope_driver', ['scope_driver_a', 'scope_driver_b']],
      ['/api/admin/subscriptions', 'subscriptions', 'scope_sub', ['scope_sub_a', 'scope_sub_b']],
      ['/api/vehicles', 'vehicles', 'scope_vehicle', ['scope_vehicle_a', 'scope_vehicle_b']]
    ]) {
      const records = ids(await request('GET', endpoint, 'scope_admin'), key);
      assert(records.includes(own), endpoint);
      for (const foreign of excluded) assert(!records.includes(foreign), endpoint + ' leaked ' + foreign);
    }
    const publicRoutes = ids(await request('GET', '/api/routes'), 'routes');
    assert(publicRoutes.includes('scope_commute'));
    assert(!publicRoutes.includes('scope_route_a'));
    assert(!publicRoutes.includes('scope_route_b'));
    assert.equal((await request('GET', '/api/routes/scope_route_a')).status, 404);
  });

  await t.test('commute admin cannot mutate company records through direct IDs', async () => {
    for (const suffix of ['a', 'b']) {
      for (const [method, endpoint, body] of [
        ['PATCH', `/api/admin/drivers/scope_driver_${suffix}/approval`, { status: 'SUSPENDED' }],
        ['PATCH', `/api/admin/drivers/scope_driver_${suffix}/route`, { routeId: 'scope_commute' }],
        ['PUT', `/api/routes/scope_route_${suffix}`, { name: 'Compromised', nameAm: 'Compromised' }],
        ['PUT', `/api/admin/routes/scope_route_${suffix}/departure-settings`, { latitude: 9, longitude: 38.7, radiusMeters: 200 }],
        ['PATCH', `/api/vehicles/scope_vehicle_${suffix}/type`, { vehicleType: 'MINIVAN_8' }],
        ['PATCH', `/api/admin/subscriptions/scope_sub_${suffix}/assignment`, { vehicleId: 'scope_vehicle' }]
      ]) assert.equal((await request(method, endpoint, 'scope_admin', body)).status, 404, endpoint);
      assert.equal((await DB.prepare('SELECT status FROM users WHERE id=?').get('scope_driver_' + suffix)).status, 'ACTIVE');
      assert.equal((await DB.prepare('SELECT name FROM routes WHERE id=?').get('scope_route_' + suffix)).name, 'scope_route_' + suffix);
    }
  });

  await t.test('sync and company portals retain their respective scope', async () => {
    for (const principal of ['scope_admin', 'scope_driver', 'scope_passenger']) {
      const sync = expectOkay(await request('POST', '/api/sync/pull', principal, {}));
      assert(sync.data.routes.some(row => row.id === 'scope_commute'));
      assert(sync.data.routes.every(row => !['scope_route_a', 'scope_route_b'].includes(row.id)));
      assert(sync.data.stops.every(row => !['scope_route_a', 'scope_route_b'].includes(row.routeId)));
      assert(sync.data.vehicles.every(row => !['scope_vehicle_a', 'scope_vehicle_b'].includes(row.id)));
    }
    for (const suffix of ['a', 'b']) {
      const principal = 'scope_company_' + suffix, other = suffix === 'a' ? 'b' : 'a';
      assert.equal((await request('GET', '/api/routes', principal)).status, 403);
      assert.equal((await request('GET', '/api/routes/scope_commute', principal)).status, 403);
      assert.equal((await request('GET', '/api/vehicles/scope_vehicle', principal)).status, 404);
      assert.equal((await request('POST', '/api/sync/pull', principal, {})).status, 403);
      assert.equal((await request('GET', '/api/admin/subscriptions', principal)).status, 403);
      assert.deepEqual(ids(await request('GET', '/api/companies/operations/routes', principal), 'routes'), ['scope_route_' + suffix]);
      assert.deepEqual(ids(await request('GET', '/api/vehicles', principal), 'vehicles'), ['scope_vehicle_' + suffix]);
      assert.equal((await request('GET', '/api/vehicles/scope_vehicle_' + other, principal)).status, 404);
      const roster = expectOkay(await request('GET', '/api/companies/operations/bookings', principal));
      assert.deepEqual(roster.bookings.map(row => row.passenger_id), ['scope_passenger_' + suffix]);
      assert.equal((await request('POST', '/api/companies/operations/routes', principal, { name: 'Daily commute attempt', routeType: 'DAILY_COMMUTE', origin: 'Addis Ababa', destination: 'Adama' })).status, 400);
    }
  });

  await t.test('daily, weekly and monthly commute reports aggregate scope and honor sorting', async () => {
    for (const period of ['daily', 'weekly', 'monthly']) {
      const report = expectOkay(await request('GET', `/api/admin/reports?period=${period}&sort=date&order=asc`, 'scope_admin'));
      assert.equal(report.period, period);
      assert.equal(report.timezone, 'Africa/Addis_Ababa');
      assert.equal(report.totals.trips, 2);
      assert.equal(report.totals.boardings, 2);
      assert.equal(report.totals.revenue, 150);
      assert.equal(report.totals.passengers, 3); // Seeded commuter plus our two commute passengers.
      assert.deepEqual(report.rows.map(row => row.date), [...report.rows.map(row => row.date)].sort());
      const descending = expectOkay(await request('GET', `/api/admin/reports?period=${period}&sort=revenue&order=desc`, 'scope_admin'));
      assert.deepEqual(descending.rows.map(row => row.revenue), [...descending.rows.map(row => row.revenue)].sort((a, b) => b - a));
      assert.deepEqual(descending.totals, report.totals);
      const company = expectOkay(await request('GET', `/api/intercity/company/performance?period=${period}`, 'scope_company_a'));
      assert.equal(company.total.revenueEtb, 500);
      assert.equal(company.total.bookings, 1);
    }
    assert.equal((await request('GET', '/api/admin/reports?period=yearly', 'scope_admin')).status, 400);
    assert.equal((await request('GET', '/api/admin/reports?sort=arbitrary', 'scope_admin')).status, 400);
    assert.equal((await request('GET', '/api/admin/reports?order=invalid', 'scope_admin')).status, 400);
    assert.equal((await request('GET', '/api/admin/reports', 'scope_company_a')).status, 403);
  });

  await t.test('passenger sees only assigned driver details and authenticated photo', async () => {
    assert.equal((await request('GET', '/api/commute/my-driver')).status, 401);
    assert.equal((await request('GET', '/api/commute/my-driver', 'scope_driver')).status, 403);
    const profile = expectOkay(await request('GET', '/api/commute/my-driver', 'scope_passenger')).driver;
    assert.equal(profile.userId, 'scope_driver');
    assert.equal(profile.fullName, 'scope_driver');
    assert.equal(profile.phone, '+251-scope_driver');
    assert.equal(profile.vehicleColor, 'Blue');
    assert.equal(profile.vehicleType, 'MINIBUS_14');
    assert.equal(profile.plateNumber, 'PLATE-scope_vehicle');
    assert.equal(profile.photoUrl, '/api/profiles/scope_driver/photo');
    assert.equal(profile.emergencyContactName, undefined);
    const other = expectOkay(await request('GET', '/api/commute/my-driver', 'scope_passenger_other')).driver;
    assert.equal(other.userId, 'scope_driver_other');
    assert.equal(other.vehicleColor, 'Red');
    assert.equal(expectOkay(await request('GET', '/api/commute/my-driver', 'scope_passenger_a')).driver, null);
    const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aB9sAAAAASUVORK5CYII=', 'base64');
    assert.equal((await request('PUT', '/api/profiles/me/documents/PHOTO', 'scope_driver', png, 'image/png')).status, 200);
    const photo = await request('GET', profile.photoUrl, 'scope_passenger');
    assert.equal(photo.status, 200);
    assert.equal(photo.bytes.compare(png), 0);
    assert.equal((await request('GET', profile.photoUrl, 'scope_passenger_other')).status, 404);
    assert.equal((await request('GET', profile.photoUrl, 'scope_company_a')).status, 404);
    await DB.prepare("UPDATE subscriptions SET vehicleId=NULL WHERE id='scope_sub'").run();
    assert.equal(expectOkay(await request('GET', '/api/commute/my-driver', 'scope_passenger')).driver, null);
    assert.equal((await request('GET', profile.photoUrl, 'scope_passenger')).status, 404);
  });
});
