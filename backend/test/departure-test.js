'use strict';

const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

process.env.NODE_ENV = 'test';
process.env.DATABASE_URL = '';
process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), 'routepass-driver-operations-'));
process.env.JWT_SECRET = 'driver-operations-tests-authentication-secret';
process.env.QR_SIGNING_KEY = 'driver-operations-tests-qr-signing-secret';
const { DB } = require('../src/db');
const { signToken } = require('../src/middleware/auth');
const { initOpsSchema } = require('../src/services/ops-schema');
const { evaluateDeparture, commuteDepartureAt } = require('../src/services/departure-rules');

test('departure gate uses server time, fresh precise GPS, configured radius and Addis local schedules', () => {
  const now = Date.parse('2026-10-17T03:30:00Z'); // 06:30 in Addis Ababa
  const valid = { latitude: 9, longitude: 38.7, radiusMeters: 300, departureAt: now, location: { latitude: 9, longitude: 38.7, accuracyMeters: 8, timestamp: now }, now };
  assert.equal(evaluateDeparture(valid).allowed, true);
  assert.equal(evaluateDeparture({ ...valid, now: now + 1800000 }).code, 'GPS_STALE');
  assert.equal(evaluateDeparture({ ...valid, now: now + 1800001 }).code, 'OUTSIDE_DEPARTURE_WINDOW');
  assert.equal(evaluateDeparture({ ...valid, departureAt: now - 1800000 }).allowed, true);
  assert.equal(evaluateDeparture({ ...valid, departureAt: now + 1800001 }).code, 'OUTSIDE_DEPARTURE_WINDOW');
  assert.equal(evaluateDeparture({ ...valid, latitude: null }).code, 'DEPARTURE_NOT_CONFIGURED');
  assert.equal(evaluateDeparture({ ...valid, departureAt: null }).code, 'SCHEDULE_NOT_CONFIGURED');
  for (const location of [null, {}, { ...valid.location, accuracyMeters: 101 }, { ...valid.location, latitude: '9' }]) {
    assert.equal(evaluateDeparture({ ...valid, location }).code, 'GPS_REQUIRED');
  }
  for (const timestamp of [now - 120001, now + 10001, 'invalid']) assert.equal(evaluateDeparture({ ...valid, location: { ...valid.location, timestamp } }).code, 'GPS_STALE');
  const away = { ...valid, location: { ...valid.location, latitude: 9.004 } };
  assert.equal(evaluateDeparture(away).code, 'OUTSIDE_DEPARTURE_RADIUS');
  assert.equal(evaluateDeparture({ ...away, radiusMeters: 500 }).allowed, true);
  assert.equal(commuteDepartureAt('06:30', now), now);
  assert.equal(commuteDepartureAt('00:05', Date.parse('2026-10-17T20:50:00Z')), Date.parse('2026-10-17T21:05:00Z'));
  assert.equal(commuteDepartureAt('29:30', now), null);
});

test('driver operations enforce assignment and departure gate on direct API calls; complete route needs no GPS', async (t) => {
  await initOpsSchema(DB);
  const express = require('express');
  const app = express();
  app.use(express.json());
  app.use('/api/trips', require('../src/routes/trips'));
  app.use('/api/checkins', require('../src/routes/checkins'));
  const server = await new Promise(resolve => { const listener = app.listen(0, '127.0.0.1', () => resolve(listener)); });
  t.after(() => new Promise(resolve => server.close(resolve)));
  const base = `http://127.0.0.1:${server.address().port}`;
  const tokens = {};
  for (const [id, role, status] of [['driver_a', 'DRIVER', 'ACTIVE'], ['driver_b', 'DRIVER', 'ACTIVE'], ['ops_admin', 'ADMIN', 'ACTIVE'], ['assigned', 'PASSENGER', 'ACTIVE'], ['pending', 'PASSENGER', 'ACTIVE'], ['inactive', 'PASSENGER', 'SUSPENDED'], ['foreign', 'PASSENGER', 'ACTIVE']]) {
    await DB.prepare('INSERT INTO users (id,role,fullName,phone,passwordHash,status) VALUES (?,?,?,?,?,?)').run(id, role, id, '+251-ops-' + id, 'unused', status);
    tokens[id] = signToken({ id, role });
  }
  const currentClock = new Date(Date.now() + 10800000).toISOString().slice(11, 16);
  await DB.prepare('INSERT INTO routes (id,name,nameAm,morningDeparture,eveningDeparture) VALUES (?,?,?,?,?)').run('ops_route', 'Operations test', 'Operations test', currentClock, currentClock);
  await DB.prepare('INSERT INTO routes (id,name,nameAm,morningDeparture) VALUES (?,?,?,?)').run('unconfigured_route', 'Missing origin', 'Missing origin', currentClock);
  for (const [id, order, lat, lng] of [['ops_origin', 1, 9, 38.7], ['ops_end', 2, 9.1, 38.8]]) await DB.prepare('INSERT INTO route_stops (id,routeId,stopName,stopNameAm,stopOrder,latitude,longitude) VALUES (?,?,?,?,?,?,?)').run(id, 'ops_route', id, id, order, lat, lng);
  for (const [id, driver, route] of [['vehicle_a', 'driver_a', 'ops_route'], ['vehicle_b', 'driver_b', 'ops_route'], ['vehicle_empty', 'driver_a', 'unconfigured_route']]) await DB.prepare('INSERT INTO vehicles (id,plateNumber,model,vehicleType,capacityLimit,driverId,assignedRouteId) VALUES (?,?,?,?,?,?,?)').run(id, id, 'Test bus', 'MINIBUS_14', 14, driver, route);
  for (const [person, status, payment, days, vehicle] of [['assigned', 'ACTIVE', 'PAID', 30, 'vehicle_a'], ['pending', 'PENDING', 'UNPAID', 0, 'vehicle_a'], ['inactive', 'ACTIVE', 'PAID', 30, 'vehicle_a'], ['foreign', 'ACTIVE', 'PAID', 30, 'vehicle_b']]) await DB.prepare('INSERT INTO subscriptions (id,passengerId,routeId,vehicleId,priceEtb,paymentStatus,subscriptionStatus,daysRemaining,qrToken) VALUES (?,?,?,?,?,?,?,?,?)').run('sub_' + person, person, 'ops_route', vehicle, 100, payment, status, days, 'qr_' + person);
  const request = async (method, route, principal, body) => {
    const r = await fetch(base + route, { method, headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + tokens[principal] }, ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
    return { status: r.status, data: await r.json() };
  };
  const fix = () => ({ latitude: 9, longitude: 38.7, accuracyMeters: 5, timestamp: Date.now() });
  const departure = (overrides = {}) => ({ routeId: 'ops_route', vehicleId: 'vehicle_a', location: fix(), ...overrides });

  await t.test('assignments return current canonical route endpoints and refresh same-route name edits', async () => {
    const before = await request('GET', '/api/trips/assignments', 'driver_a');
    assert.equal(before.status, 200, JSON.stringify(before.data));
    const assigned = before.data.vehicles.find(v => v.id === 'vehicle_a');
    assert.equal(assigned.assignedRouteId, 'ops_route');
    assert.equal(assigned.departureStopId, 'ops_origin');
    assert.equal(assigned.departureStopName, 'ops_origin');
    assert.equal(assigned.destinationStopId, 'ops_end');
    assert.equal(assigned.destinationStopName, 'ops_end');
    await DB.prepare('UPDATE route_stops SET stopName=?,stopNameAm=? WHERE id=?')
      .run('Updated actual departure', 'የተዘመነ መነሻ', 'ops_origin');
    await DB.prepare('UPDATE route_stops SET stopName=?,stopNameAm=? WHERE id=?')
      .run('Updated actual destination', 'የተዘመነ መድረሻ', 'ops_end');
    const refreshed = (await request('GET', '/api/trips/assignments', 'driver_a')).data.vehicles.find(v => v.id === 'vehicle_a');
    assert.equal(refreshed.assignedRouteId, assigned.assignedRouteId);
    assert.equal(refreshed.departureStopName, 'Updated actual departure');
    assert.equal(refreshed.departureStopNameAm, 'የተዘመነ መነሻ');
    assert.equal(refreshed.destinationStopName, 'Updated actual destination');
    assert.equal(refreshed.destinationStopNameAm, 'የተዘመነ መድረሻ');
    const unconfigured = before.data.vehicles.find(v => v.id === 'vehicle_empty');
    assert.equal(unconfigured.departureStopId, null);
    assert.equal(unconfigured.departureStopName, '');
    assert.equal(unconfigured.destinationStopId, null);
    assert.equal(unconfigured.destinationStopName, '');
    // Keep the separate boarding-gate fixtures independent of these edit checks.
    await DB.prepare('UPDATE route_stops SET stopName=?,stopNameAm=? WHERE id=?').run('ops_origin', 'ops_origin', 'ops_origin');
    await DB.prepare('UPDATE route_stops SET stopName=?,stopNameAm=? WHERE id=?').run('ops_end', 'ops_end', 'ops_end');
  });
  await t.test('endpoint selection orders equal stop orders by ID and never leaks another driver assignment', async () => {
    await DB.prepare('INSERT INTO routes(id,name,nameAm) VALUES(?,?,?)').run('tie_route', 'No encoded endpoint names', 'Test');
    await DB.prepare('INSERT INTO routes(id,name,nameAm) VALUES(?,?,?)').run('single_route', 'Single stop', 'Test');
    for (const [id, route] of [['vehicle_tie', 'tie_route'], ['vehicle_single', 'single_route']])
      await DB.prepare('INSERT INTO vehicles(id,plateNumber,model,vehicleType,capacityLimit,driverId,assignedRouteId) VALUES(?,?,?,?,?,?,?)')
        .run(id, id, 'Test bus', 'MINIBUS_14', 14, 'driver_a', route);
    for (const [id, route, order] of [['z_first', 'tie_route', 1], ['a_first', 'tie_route', 1], ['a_last', 'tie_route', 2], ['z_last', 'tie_route', 2], ['one_stop', 'single_route', 1]])
      await DB.prepare('INSERT INTO route_stops(id,routeId,stopName,stopNameAm,stopOrder,latitude,longitude) VALUES(?,?,?,?,?,?,?)')
        .run(id, route, id, id, order, 9, 38.7);
    const mine = await request('GET', '/api/trips/assignments', 'driver_a');
    const tied = mine.data.vehicles.find(v => v.id === 'vehicle_tie');
    assert.equal(tied.departureStopId, 'a_first');
    assert.equal(tied.destinationStopId, 'z_last');
    const single = mine.data.vehicles.find(v => v.id === 'vehicle_single');
    assert.equal(single.departureStopId, 'one_stop');
    assert.equal(single.destinationStopId, null);
    assert.equal(single.destinationStopName, '');
    const other = await request('GET', '/api/trips/assignments', 'driver_b');
    assert.deepEqual(other.data.vehicles.map(v => v.id), ['vehicle_b']);
    assert.equal(other.data.vehicles[0].departureStopId, 'ops_origin');
    assert.equal(other.data.vehicles[0].destinationStopId, 'ops_end');
    assert.equal((await request('GET', '/api/trips/assignments', 'ops_admin')).status, 403);
  });

  await t.test('roster is specific to own vehicle, including assigned/pending/inactive and photo links', async () => {
    const result = await request('GET', '/api/trips/roster?vehicleId=vehicle_a', 'driver_a');
    assert.equal(result.status, 200, JSON.stringify(result.data));
    assert.deepEqual(result.data.roster.map(p => [p.id, p.status]), [['assigned', 'ASSIGNED'], ['inactive', 'INACTIVE'], ['pending', 'PENDING']]);
    assert.equal(result.data.roster[0].photoUrl, '/api/profiles/assigned/photo');
    assert.equal((await request('GET', '/api/trips/roster?vehicleId=vehicle_b', 'driver_a')).status, 403);
    assert.equal((await request('GET', '/api/trips/roster?vehicleId=vehicle_a', 'driver_b')).status, 403);
    assert.equal((await request('GET', '/api/trips/roster?vehicleId=vehicle_a', 'ops_admin')).status, 403);
  });
  await t.test('arrival cannot bypass missing GPS, imprecise GPS, stale GPS, distance, time or ownership', async () => {
    for (const location of [undefined, { ...fix(), accuracyMeters: 150 }, { ...fix(), timestamp: Date.now() - 121000 }, { ...fix(), latitude: 10 }]) assert.equal((await request('POST', '/api/trips/start', 'driver_a', departure({ location }))).status, 403);
    assert.equal((await request('POST', '/api/trips/start', 'driver_b', departure())).status, 403);
    const missing = await request('POST', '/api/trips/start', 'driver_a', departure({ routeId: 'unconfigured_route', vehicleId: 'vehicle_empty' }));
    assert.equal(missing.status, 403); assert.equal(missing.data.code, 'DEPARTURE_NOT_CONFIGURED');
    const wrongClock = new Date(Date.now() + 10800000 + 3 * 3600000).toISOString().slice(11, 16);
    await DB.prepare('UPDATE routes SET morningDeparture = ? WHERE id = ?').run(wrongClock, 'ops_route');
    assert.equal((await request('POST', '/api/trips/start', 'driver_a', departure())).data.code, 'OUTSIDE_DEPARTURE_WINDOW');
    await DB.prepare('UPDATE routes SET morningDeparture = ? WHERE id = ?').run(currentClock, 'ops_route');
    assert.equal((await DB.prepare("SELECT COUNT(*) AS c FROM trips WHERE routeId='ops_route'").get()).c, 0);
  });
  await t.test('server confirms departure once, scans only exact assigned passengers, and completing requires no destination fix', async () => {
    const result = await request('POST', '/api/trips/start', 'driver_a', departure());
    assert.equal(result.status, 200, JSON.stringify(result.data));
    const id = result.data.trip.id;
    assert.ok(await DB.prepare('SELECT trip_id FROM trip_departure_confirmations WHERE trip_id=?').get(id));
    assert.equal((await request('POST', '/api/trips/start', 'driver_a', departure())).status, 409);
    assert.equal((await request('POST', `/api/trips/${id}/end`, 'driver_b', {})).status, 403);
    assert.equal((await request('POST', '/api/checkins/scan', 'driver_a', { qrToken: 'qr_foreign', tripId: id, vehicleId: 'vehicle_a' })).data.status, 'PASSENGER_NOT_ASSIGNED');
    assert.equal((await request('POST', '/api/checkins/scan', 'driver_a', { qrToken: 'qr_inactive', tripId: id, vehicleId: 'vehicle_a' })).data.status, 'PASSENGER_INACTIVE');
    const boarding = { qrToken: 'qr_assigned', tripId: id, vehicleId: 'vehicle_a', currentStop: 'ops_origin' };
    assert.equal((await request('POST', '/api/checkins/scan', 'driver_a', boarding)).status, 200);
    assert.equal((await request('GET', '/api/trips/roster?vehicleId=vehicle_a', 'driver_a')).data.roster.find(p => p.id === 'assigned').status, 'BOARDED');
    assert.equal((await request('POST', '/api/checkins/scan', 'driver_a', boarding)).status, 409);
    assert.equal((await request('POST', `/api/trips/${id}/end`, 'driver_a', {})).status, 200);
    assert.equal((await request('POST', '/api/checkins/scan', 'driver_a', boarding)).status, 403);
    assert.equal((await request('POST', `/api/trips/${id}/end`, 'driver_a', {})).status, 409);
  });
  await t.test('legacy active trips cannot bypass GPS confirmation and inbound departure uses the final stop', async () => {
    await DB.prepare("INSERT INTO trips (id,driverId,vehicleId,routeId,status) VALUES ('legacy_ops','driver_a','vehicle_a','ops_route','IN_PROGRESS')").run();
    const scan = await request('POST', '/api/checkins/scan', 'driver_a', { qrToken: 'qr_assigned', tripId: 'legacy_ops', vehicleId: 'vehicle_a' });
    assert.equal(scan.status, 403); assert.equal(scan.data.status, 'DEPARTURE_NOT_CONFIRMED');
    await request('POST', '/api/trips/legacy_ops/end', 'driver_a', {});
    assert.equal((await request('POST', '/api/trips/start', 'driver_a', departure({ direction: 'INBOUND' }))).data.code, 'OUTSIDE_DEPARTURE_RADIUS');
    const inbound = await request('POST', '/api/trips/start', 'driver_a', departure({ direction: 'INBOUND', location: { ...fix(), latitude: 9.1, longitude: 38.8 } }));
    assert.equal(inbound.status, 200, JSON.stringify(inbound.data));
    assert.equal(inbound.data.trip.currentStop, 'ops_end');
    await request('POST', `/api/trips/${inbound.data.trip.id}/end`, 'driver_a', {});
  });
});
