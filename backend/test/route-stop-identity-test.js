'use strict';

const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
process.env.NODE_ENV = 'test';
process.env.DATABASE_URL = '';
process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), 'routepass-stop-identity-'));
process.env.JWT_SECRET = 'route-stop-identity-tests-authentication-secret';
const { DB } = require('../src/db');
const { signToken } = require('../src/middleware/auth');

test('route editing preserves passenger stop references without guessing unrelated stop identities', async t => {
  const express = require('express');
  const app = express();
  app.use(express.json());
  app.use('/api/routes', require('../src/routes/routes'));
  const server = await new Promise(resolve => { const listener = app.listen(0, '127.0.0.1', () => resolve(listener)); });
  t.after(() => new Promise(resolve => server.close(resolve)));
  const base = `http://127.0.0.1:${server.address().port}`;
  for (const [id, role] of [['stop_admin', 'ADMIN'], ['stop_passenger', 'PASSENGER']]) {
    await DB.prepare('INSERT INTO users(id,role,fullName,phone,passwordHash,status) VALUES(?,?,?,?,?,?)')
      .run(id, role, id, '+251-' + id, 'unused-test-password', 'ACTIVE');
  }
  const token = signToken({ id: 'stop_admin', role: 'ADMIN' });
  const put = async (routeId, body) => {
    const response = await fetch(`${base}/api/routes/${routeId}`, { method: 'PUT', headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + token }, body: JSON.stringify(body) });
    return { status: response.status, data: await response.json() };
  };
  const points = [
    { stopName: 'Garment', stopNameAm: 'ጋርመንት', latitude: 8.95611, longitude: 38.76322 },
    { stopName: 'Bole Medhanialem', stopNameAm: 'ቦሌ መድኃኒዓለም', latitude: 8.99733, longitude: 38.78744 }
  ];
  const makeRoute = async (id, stops = points, companyId = '', routeType = 'DAILY_COMMUTE') => {
    await DB.prepare('INSERT INTO routes(id,name,nameAm,companyId,routeType) VALUES(?,?,?,?,?)').run(id, id, id, companyId, routeType);
    for (const [index, stop] of stops.entries()) {
      await DB.prepare('INSERT INTO route_stops(id,routeId,stopName,stopNameAm,stopOrder,latitude,longitude) VALUES(?,?,?,?,?,?,?)')
        .run(`${id}_stop_${index}`, id, stop.stopName, stop.stopNameAm, index + 1, stop.latitude, stop.longitude);
    }
  };
  const subscribe = async id => {
    await DB.prepare("INSERT INTO subscriptions(id,passengerId,routeId,pickupStopId,destinationStopId,priceEtb,paymentStatus,subscriptionStatus,daysRemaining) VALUES(?,?,?,?,?,100,'PAID','ACTIVE',30)")
      .run(`${id}_subscription`, 'stop_passenger', id, `${id}_stop_0`, `${id}_stop_1`);
  };
  const subscriptionPoints = id => DB.prepare(`SELECT p.id AS pickup_id, p.stopName AS pickup_name, d.id AS destination_id, d.stopName AS destination_name
    FROM subscriptions s JOIN route_stops p ON p.id=s.pickupStopId AND p.routeId=s.routeId
    JOIN route_stops d ON d.id=s.destinationStopId AND d.routeId=s.routeId WHERE s.id=?`).get(`${id}_subscription`);
  const storedStops = id => DB.prepare('SELECT * FROM route_stops WHERE routeId=? ORDER BY stopOrder').all(id);

  await t.test('admin text-editor payload without IDs retains stop IDs and paid passenger pickup and destination', async () => {
    await makeRoute('same_points');
    await subscribe('same_points');
    const result = await put('same_points', { morningDeparture: '07:10', stops: points.map(stop => ({ ...stop, latitude: String(stop.latitude), longitude: String(stop.longitude) })) });
    assert.equal(result.status, 200, JSON.stringify(result.data));
    assert.deepEqual(result.data.stops.map(stop => stop.id), ['same_points_stop_0', 'same_points_stop_1']);
    const selected = await subscriptionPoints('same_points');
    assert.equal(selected.pickup_id, 'same_points_stop_0');
    assert.equal(selected.destination_id, 'same_points_stop_1');
    assert.equal(selected.pickup_name, 'Garment');
    assert.equal(selected.destination_name, 'Bole Medhanialem');
    assert.equal(result.data.route.morningDeparture, '07:10');
  });

  await t.test('reordering without IDs matches physical points rather than their previous list index', async () => {
    await makeRoute('reordered_points');
    await subscribe('reordered_points');
    const result = await put('reordered_points', { stops: [...points].reverse() });
    assert.equal(result.status, 200, JSON.stringify(result.data));
    assert.deepEqual(result.data.stops.map(stop => stop.id), ['reordered_points_stop_1', 'reordered_points_stop_0']);
    assert.deepEqual(result.data.stops.map(stop => stop.stopOrder), [1, 2]);
    const selected = await subscriptionPoints('reordered_points');
    assert.equal(selected.pickup_name, 'Garment');
    assert.equal(selected.destination_name, 'Bole Medhanialem');
  });

  await t.test('explicit route-owned IDs survive renaming moving and reordering', async () => {
    await makeRoute('explicit_points');
    const result = await put('explicit_points', { stops: [
      { ...points[1], id: 'explicit_points_stop_1', stopName: 'Updated destination', latitude: 9.02 },
      { ...points[0], id: 'explicit_points_stop_0', stopName: 'Updated pickup', longitude: 38.8 }
    ] });
    assert.equal(result.status, 200, JSON.stringify(result.data));
    assert.deepEqual(result.data.stops.map(stop => stop.id), ['explicit_points_stop_1', 'explicit_points_stop_0']);
    assert.equal(result.data.stops[0].stopName, 'Updated destination');
    assert.equal(result.data.stops[0].latitude, 9.02);
    assert.equal(result.data.stops[1].longitude, 38.8);
  });

  await t.test('unrelated replacement point gets a new ID instead of adopting a deleted stop at the same index', async () => {
    await makeRoute('new_point');
    const unrelated = { stopName: 'Entirely new pickup', stopNameAm: 'አዲስ መሳፈሪያ', latitude: 9.14, longitude: 38.91 };
    const result = await put('new_point', { stops: [unrelated, points[1]] });
    assert.equal(result.status, 200, JSON.stringify(result.data));
    assert.notEqual(result.data.stops[0].id, 'new_point_stop_0');
    assert.notEqual(result.data.stops[0].id, 'new_point_stop_1');
    assert.match(result.data.stops[0].id, /^stop_/);
    assert.equal(result.data.stops[1].id, 'new_point_stop_1');
    assert.equal(await DB.prepare('SELECT id FROM route_stops WHERE id=?').get('new_point_stop_0'), undefined);
  });

  await t.test('normalized English or Amharic names identify a unique point with unconfigured GPS', async () => {
    const unknownGps = points.map(stop => ({ ...stop, latitude: 0, longitude: 0 }));
    await makeRoute('normalized_names', unknownGps);
    const result = await put('normalized_names', { stops: [
      { ...unknownGps[0], stopName: '  GARMENT  ', stopNameAm: '' },
      { ...unknownGps[1], stopName: 'Corrected English name', stopNameAm: '  ቦሌ   መድኃኒዓለም  ' }
    ] });
    assert.equal(result.status, 200, JSON.stringify(result.data));
    assert.deepEqual(result.data.stops.map(stop => stop.id), ['normalized_names_stop_0', 'normalized_names_stop_1']);
  });

  await t.test('foreign and duplicate explicit IDs are rejected before deleting stops or committing metadata', async () => {
    await makeRoute('protected_points');
    await makeRoute('foreign_points', points, 'other_company', 'INTERCITY');
    const before = await storedStops('protected_points');
    for (const stops of [
      [{ ...points[0], id: 'foreign_points_stop_0' }, points[1]],
      [{ ...points[0], id: 'protected_points_stop_0' }, { ...points[1], id: 'protected_points_stop_0' }]
    ]) {
      const result = await put('protected_points', { name: 'Must roll back', stops });
      assert.equal(result.status, 400, JSON.stringify(result.data));
      assert.deepEqual(await storedStops('protected_points'), before);
      assert.equal((await DB.prepare('SELECT name FROM routes WHERE id=?').get('protected_points')).name, 'protected_points');
    }
    assert.equal((await storedStops('foreign_points')).length, 2);
    assert.equal((await put('foreign_points', { stops: [] })).status, 404);
  });

  await t.test('ambiguous duplicate labels and coordinates never select an arbitrary old identity', async () => {
    await makeRoute('ambiguous_points', [points[0], points[0]]);
    const result = await put('ambiguous_points', { stops: [points[0]] });
    assert.equal(result.status, 200, JSON.stringify(result.data));
    assert(!['ambiguous_points_stop_0', 'ambiguous_points_stop_1'].includes(result.data.stops[0].id));
  });
});
