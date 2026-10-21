'use strict';

const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs'), os = require('node:os'), path = require('node:path');
process.env.NODE_ENV = 'test';
delete process.env.DATABASE_URL;
process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), 'routepass-master-data-'));
process.env.JWT_SECRET = 'master-data-regression-tests-authentication-secret';
const { DB } = require('../src/db');
const { signToken } = require('../src/middleware/auth');
const { initMasterDataSchema } = require('../src/services/master-data');

test('master data controls registered vehicle types and effective GPS rules for both services', async t => {
  await initMasterDataSchema();
  await require('../src/services/ops-schema').initOpsSchema(DB);
  await require('../src/services/intercity-schema').initIntercitySchema();
  await DB.exec('CREATE TABLE IF NOT EXISTS transport_companies(id TEXT PRIMARY KEY,name TEXT,phone TEXT,representative_name TEXT,status TEXT)');
  await DB.prepare("INSERT INTO transport_companies(id,name,status) VALUES('md_company','Master Company','ACTIVE')").run();
  for (const [id, role, company] of [['md_admin', 'ADMIN', ''], ['md_driver', 'DRIVER', ''], ['md_intercity_driver', 'DRIVER', 'md_company'], ['md_passenger', 'PASSENGER', '']])
    await DB.prepare('INSERT INTO users(id,role,fullName,phone,passwordHash,status,companyId) VALUES(?,?,?,?,?,?,?)')
      .run(id, role, id, id, 'unused', 'ACTIVE', company);
  const tokens = Object.fromEntries([['admin', 'md_admin', 'ADMIN'], ['driver', 'md_driver', 'DRIVER'],
    ['intercityDriver', 'md_intercity_driver', 'DRIVER'], ['passenger', 'md_passenger', 'PASSENGER'], ['company', 'md_company', 'COMPANY']]
    .map(([key, id, role]) => [key, signToken({ id, role })]));
  const clock = new Date(Date.now() + 3 * 3600000).toISOString().slice(11, 16);
  await DB.prepare('INSERT INTO routes(id,name,nameAm,morningDeparture,eveningDeparture) VALUES(?,?,?,?,?)')
    .run('md_daily', 'Master commute', 'Master commute', clock, clock);
  for (const [id, order, lat] of [['md_start', 1, 9], ['md_end', 2, 9.01]])
    await DB.prepare('INSERT INTO route_stops(id,routeId,stopName,stopNameAm,stopOrder,latitude,longitude) VALUES(?,?,?,?,?,?,?)')
      .run(id, 'md_daily', id, id, order, lat, 38.7);
  await DB.prepare('INSERT INTO route_departure_settings(route_id,latitude,longitude,radius_m) VALUES(?,?,?,?)').run('md_daily', 9, 38.7, 100);
  await DB.prepare('INSERT INTO vehicles(id,plateNumber,model,vehicleType,capacityLimit,driverId,assignedRouteId) VALUES(?,?,?,?,?,?,?)')
    .run('md_daily_vehicle', 'MASTER-DAILY', 'Master bus', 'MINIBUS_14', 14, 'md_driver', 'md_daily');
  await DB.prepare("INSERT INTO routes(id,name,nameAm,companyId,routeType,origin,destination) VALUES('md_intercity','Intercity','Intercity','md_company','INTERCITY','Addis','Hawassa')").run();
  await DB.prepare("INSERT INTO vehicles(id,plateNumber,model,vehicleType,capacityLimit,driverId,assignedRouteId,companyId) VALUES('md_intercity_vehicle','MASTER-IC','Intercity bus','MINIBUS_14',14,'md_intercity_driver','md_intercity','md_company')").run();
  await DB.prepare('INSERT INTO intercity_departures(id,company_id,route_id,driver_id,vehicle_id,origin,destination,departure_at,latitude,longitude,radius_m,seats,fare_etb,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)')
    .run('md_departure', 'md_company', 'md_intercity', 'md_intercity_driver', 'md_intercity_vehicle', 'Addis', 'Hawassa', new Date(Date.now() + 600000).toISOString(), 9, 38.7, 100, 14, 450, new Date().toISOString());
  const express = require('express'), app = express(); app.use(express.json());
  app.use('/api/master-data', require('../src/routes/master-data'));
  app.use('/api/trips', require('../src/routes/trips'));
  app.use('/api/intercity', require('../src/routes/intercity'));
  app.use('/api/operations', require('../src/routes/commute-operations'));
  app.use('/api/vehicles', require('../src/routes/vehicles'));
  app.use('/api/companies', require('../src/routes/company-portal'));
  app.use('/api/auth', require('../src/routes/auth'));
  const server = app.listen(0, '127.0.0.1'); await new Promise(resolve => server.once('listening', resolve));
  t.after(() => new Promise(resolve => server.close(resolve)));
  const base = 'http://127.0.0.1:' + server.address().port;
  async function request(url, method = 'GET', body, role = 'admin') {
    const r = await fetch(base + url, { method, headers: { 'Content-Type': 'application/json', ...(role ? { Authorization: 'Bearer ' + tokens[role] } : {}) },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
    return { status: r.status, data: await r.json() };
  }
  const fix = () => ({ latitude: 9.002, longitude: 38.7, accuracyMeters: 5, timestamp: Date.now() });
  const commuteCheck = () => request('/api/trips/departure-check', 'POST', { routeId: 'md_daily', vehicleId: 'md_daily_vehicle', location: fix() }, 'driver');

  await t.test('defaults preserve the existing four vehicle types and per-departure radii', async () => {
    const catalog = await request('/api/master-data/vehicle-types', 'GET', undefined, null);
    assert.equal(catalog.status, 200); assert.equal(catalog.data.vehicleTypes.length, 4);
    assert.deepEqual(catalog.data.vehicleTypes.map(v => v.capacity), [8, 14, 24, 30]);
    assert.deepEqual((await request('/api/master-data/gps-radius')).data, { success: true, radiusMeters: 300, scope: 'ALL_VEHICLES', overrideEnabled: false });
    const validation = (await commuteCheck()).data.validation;
    assert.equal(validation.radiusMeters, 100); assert.equal(validation.code, 'OUTSIDE_DEPARTURE_RADIUS');
    const intercity = await request('/api/intercity/driver/departures/md_departure/arrival', 'POST', { location: fix() }, 'intercityDriver');
    assert.equal(intercity.status, 403); assert.equal(intercity.data.validation.radiusMeters, 100);
  });
  await t.test('only admins can add edit or change system GPS settings', async () => {
    for (const role of [null, 'driver', 'passenger', 'company']) {
      const expected = role ? 403 : 401;
      assert.equal((await request('/api/master-data/vehicle-types', 'POST', { code: 'COACH_50', name: 'Coach', capacity: 50 }, role)).status, expected);
      assert.equal((await request('/api/master-data/gps-radius', 'PUT', { radiusMeters: 500 }, role)).status, expected);
      assert.equal((await request('/api/master-data/gps-sharing', 'PUT', { intervalSeconds: 10 }, role)).status, expected);
    }
  });
  await t.test('new and edited catalog values are used by actual vehicle creation without rewriting physical fleet capacities', async () => {
    assert.equal((await request('/api/master-data/vehicle-types', 'POST', { code: 'COACH_50', name: 'Intercity coach', capacity: 50 })).status, 201);
    assert.equal((await request('/api/master-data/vehicle-types', 'POST', { code: 'COACH_50', name: 'Duplicate', capacity: 50 })).status, 409);
    const first = await request('/api/companies/operations/vehicles', 'POST', { plateNumber: 'MASTER-CUSTOM-1', model: 'Coach', vehicleType: 'COACH_50' }, 'company');
    assert.equal(first.status, 201); assert.equal(first.data.vehicle.capacityLimit, 50);
    assert.equal((await request('/api/master-data/vehicle-types/COACH_50', 'PATCH', { name: 'Premium coach', capacity: 52 })).status, 200);
    assert.equal((await DB.prepare('SELECT capacityLimit FROM vehicles WHERE id=?').get(first.data.vehicle.id)).capacityLimit, 50);
    const second = await request('/api/companies/operations/vehicles', 'POST', { plateNumber: 'MASTER-CUSTOM-2', model: 'Coach', vehicleType: 'COACH_50' }, 'company');
    assert.equal(second.status, 201); assert.equal(second.data.vehicle.capacityLimit, 52);
    const driver = await request('/api/auth/register', 'POST', { fullName: 'Custom Type Driver', phone: '0912349876', password: 'MasterDataDriver#2026', role: 'DRIVER', licenseNumber: 'MD-LICENSE', assignedVehiclePlate: 'MASTER-CUSTOM-3', vehicleModel: 'Custom coach', vehicleType: 'COACH_50' }, null);
    assert.equal(driver.status, 201, JSON.stringify(driver.data));
    assert.equal((await DB.prepare('SELECT capacityLimit FROM vehicles WHERE driverId=?').get(driver.data.user.id)).capacityLimit, 52);
    const changed = await request('/api/vehicles/md_daily_vehicle/type', 'PATCH', { vehicleType: 'COACH_50' });
    assert.equal(changed.status, 200); assert.equal(changed.data.vehicle.capacityLimit, 52);
    assert.equal((await request('/api/master-data/vehicle-types/COACH_50', 'PATCH', { active: false })).status, 200);
    assert.equal((await request('/api/companies/operations/vehicles', 'POST', { plateNumber: 'MASTER-INACTIVE', model: 'Coach', vehicleType: 'COACH_50' }, 'company')).status, 400);
    await initMasterDataSchema();
    const saved = (await request('/api/master-data/vehicle-types')).data.vehicleTypes.find(v => v.code === 'COACH_50');
    assert.equal(saved.active, false); assert.equal(saved.capacity, 52); assert.equal(saved.name, 'Premium coach');
  });
  await t.test('global radius updates existing and future commute and intercity departure checks and reported settings', async () => {
    assert.equal((await request('/api/master-data/gps-radius', 'PUT', { radiusMeters: 500 })).status, 200);
    const commute = (await commuteCheck()).data.validation;
    assert.equal(commute.allowed, true); assert.equal(commute.radiusMeters, 500);
    const settings = (await request('/api/operations/routes/md_daily/departure-settings')).data.settings;
    assert.equal(settings.radius_m, 500); assert.equal(settings.configuredRadiusMeters, 100); assert.equal(settings.globalRadiusOverride, true);
    const listed = (await request('/api/intercity/driver/departures', 'GET', undefined, 'intercityDriver')).data.departures[0];
    assert.equal(listed.radius_m, 500); assert.equal(listed.configuredRadiusMeters, 100); assert.equal(listed.globalRadiusOverride, true);
    const arrival = await request('/api/intercity/driver/departures/md_departure/arrival', 'POST', { location: fix() }, 'intercityDriver');
    assert.equal(arrival.status, 200, JSON.stringify(arrival.data)); assert.equal(arrival.data.validation.radiusMeters, 500);
    assert.equal((await request('/api/intercity/driver/departures/md_departure/complete', 'POST', {}, 'intercityDriver')).status, 200);
    const created = await request('/api/intercity/company/departures', 'POST', { routeId: 'md_intercity', vehicleId: 'md_intercity_vehicle', departureAt: new Date(Date.now() + 600000).toISOString(), seats: 14, fareEtb: 450, latitude: 9, longitude: 38.7, radiusMeters: 25 }, 'company');
    assert.equal(created.status, 201, JSON.stringify(created.data));
    const future = await request('/api/intercity/driver/departures/' + created.data.departure.id + '/arrival', 'POST', { location: fix() }, 'intercityDriver');
    assert.equal(future.status, 200, JSON.stringify(future.data)); assert.equal(future.data.validation.radiusMeters, 500);
    assert.equal((await DB.prepare("SELECT radius_m FROM route_departure_settings WHERE route_id='md_daily'").get()).radius_m, 100, 'Global rules must not erase stored per-route configuration.');
  });
  await t.test('GPS sharing frequency defaults to five seconds and accepts only the requested integer range', async () => {
    assert.deepEqual((await request('/api/master-data/gps-sharing', 'GET', undefined, 'driver')).data, { success: true, intervalSeconds: 5 });
    assert.equal((await request('/api/master-data/gps-sharing', 'GET', undefined, null)).status, 401);
    for (const intervalSeconds of [0, 61, 1.5, '5', null]) assert.equal((await request('/api/master-data/gps-sharing', 'PUT', { intervalSeconds })).status, 400);
    for (const intervalSeconds of [1, 60]) {
      assert.equal((await request('/api/master-data/gps-sharing', 'PUT', { intervalSeconds })).status, 200);
      assert.equal((await request('/api/master-data/gps-sharing', 'GET', undefined, 'driver')).data.intervalSeconds, intervalSeconds);
    }
    assert.equal((await DB.prepare("SELECT value FROM app_settings WHERE key='GPS_SHARING_INTERVAL_SECONDS'").get()).value, '60');
  });
  await t.test('catalog code identity and numeric bounds cannot be bypassed by malformed inputs', async () => {
    for (const capacity of [0, 101, 1.5, '50']) assert.equal((await request('/api/master-data/vehicle-types', 'POST', { code: 'BAD_TYPE', name: 'Bad type', capacity })).status, 400);
    assert.equal((await request('/api/master-data/vehicle-types', 'POST', { code: 'BAD-CODE', name: 'Bad type', capacity: 50 })).status, 400);
    assert.equal((await request('/api/master-data/vehicle-types/COACH_50', 'PATCH', { code: 'RENAMED', name: 'Renamed' })).status, 400);
    for (const radiusMeters of [0, 24, 5001, 99.5, '500']) assert.equal((await request('/api/master-data/gps-radius', 'PUT', { radiusMeters })).status, 400);
    assert.equal((await request('/api/master-data/gps-radius')).data.radiusMeters, 500);
  });
  await t.test('missing, array and null mutation bodies fail as bad requests and preserve admin isolation', async () => {
    for (const [method, endpoint] of [['POST', '/vehicle-types'], ['PATCH', '/vehicle-types/MINIBUS_14'],
      ['PUT', '/gps-radius'], ['PUT', '/gps-sharing']]) {
      for (const body of [undefined, [], null]) {
        const response = await fetch(base + '/api/master-data' + endpoint, { method,
          headers: { Authorization: 'Bearer ' + tokens.admin, 'Content-Type': 'application/json' },
          ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
        assert.equal(response.status, 400, method + ' ' + endpoint + ' ' + JSON.stringify(body));
        await response.text();
      }
      assert.equal((await request('/api/master-data' + endpoint, method, undefined, null)).status, 401);
      assert.equal((await request('/api/master-data' + endpoint, method, [], 'driver')).status, 403);
    }
    assert.equal((await request('/api/master-data/gps-radius')).data.radiusMeters, 500);
    assert.equal((await request('/api/master-data/gps-sharing')).data.intervalSeconds, 60);
    assert.equal((await request('/api/master-data/vehicle-types')).data.vehicleTypes.length, 5);
  });
});
