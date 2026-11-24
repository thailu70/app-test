'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { createGeoService, detailedGeometry } = require('../src/services/mobility-geo');
const db = { prepare: () => ({ all: async () => [{ stopName: 'Bole', stopNameAm: 'ቦሌ', latitude: 8.99, longitude: 38.78 }] }) };
function response(value) { return { ok: true, text: async () => JSON.stringify(value) }; }
test('location suggestions use private Photon and safely fall back to public saved stops', async () => {
  let url;
  const geo = createGeoService({ db, fetchImpl: async input => { url = input; return response({ features: [{ properties: { name: 'Bole', city: 'Addis Ababa' }, geometry: { coordinates: [38.78, 8.99] } }] }); } });
  const result = await geo.locations('Bole'); assert.equal(url.hostname, 'photon'); assert.equal(result.searchAvailable, true); assert.equal(result.places.length, 2);
  const failed = createGeoService({ db, fetchImpl: async () => { throw new Error('offline'); } });
  const saved = await failed.locations('ቦሌ'); assert.equal(saved.source, 'saved'); assert.equal(saved.places[0].name, 'Bole'); assert.equal(saved.searchAvailable, false);
});

test('saved suggestions skip missing coordinates and zero placeholders', async () => {
  const savedDb = { prepare: () => ({ all: async () => [
    { stopName: 'Bole missing latitude', latitude: null, longitude: 38.78 },
    { stopName: 'Bole missing longitude', latitude: 8.99, longitude: null },
    { stopName: 'Bole blank latitude', latitude: ' ', longitude: 38.78 },
    { stopName: 'Bole blank longitude', latitude: 8.99, longitude: '' },
    { stopName: 'Bole placeholder', latitude: 0, longitude: 0 },
    { stopName: 'Bole invalid latitude', latitude: 91, longitude: 38.78 },
    { stopName: 'Bole valid', latitude: '8.99', longitude: '38.78' },
    { stopName: ' ', stopNameAm: 'Bole ቦሌ', latitude: 9, longitude: 38.79 }
  ] }) };
  const geo = createGeoService({ db: savedDb, fetchImpl: async () => { throw Error('offline'); } });
  const result = await geo.locations('Bole');
  assert.deepEqual(result.places, [
    { name: 'Bole valid', latitude: 8.99, longitude: 38.78 },
    { name: 'Bole ቦሌ', latitude: 9, longitude: 38.79 }
  ]);
  assert.equal(result.source, 'saved');
});

test('saved route locations refresh during the geocoder cache lifetime', async () => {
  let rows = [{ stopName: 'Bole old stop', latitude: 8.99, longitude: 38.78 }], fetches = 0;
  const savedDb = { prepare: () => ({ all: async () => rows }) };
  const geo = createGeoService({ db: savedDb, fetchImpl: async () => {
    fetches++;
    return response({ features: [{ properties: { name: 'Bole geocoder result' }, geometry: { coordinates: [38.8, 9.1] } }] });
  } });
  const before = await geo.locations('Bole');
  assert.equal(before.places[0].name, 'Bole old stop');
  rows = [{ stopName: 'Bole new stop', latitude: 9.01, longitude: 38.79 }];
  const after = await geo.locations('Bole');
  assert.equal(fetches, 1, 'The unchanged Photon response remains cached');
  assert.deepEqual(after.places, [
    { name: 'Bole new stop', latitude: 9.01, longitude: 38.79 },
    { name: 'Bole geocoder result', latitude: 9.1, longitude: 38.8 }
  ]);
  rows = [];
  assert.deepEqual((await geo.locations('Bole')).places, [after.places[1]], 'Deleted stops disappear from the next search');
});
test('reverse geocoding labels pickup by nearby district and city with a short cache', async () => {
  let url, count = 0;
  const geo = createGeoService({ fetchImpl: async input => {
    url = input; count++;
    return response({ features: [{ properties: { name: '111201', street: 'Lorenzo Taezaz Street', district: 'Arat Kilo', county: 'Arada', city: 'Addis Ababa' } }] });
  } });
  const input = { lat: 9.03, lon: 38.77 };
  const first = await geo.reverse(input);
  assert.equal(first.name, 'Arat Kilo, Addis Ababa');
  assert.equal(url.pathname, '/reverse/');
  assert.equal(url.searchParams.get('lang'), 'en');
  assert.equal(await geo.reverse(input).then(value => value.name), first.name);
  assert.equal(count, 1, 'Repeated nearby fixes reuse the reverse-geocode cache.');
  await assert.rejects(geo.reverse({ lat: '', lon: 38.77 }), error => error.status === 400);
  const fallback = createGeoService({ fetchImpl: async () => { throw Error('offline'); } });
  await assert.rejects(fallback.reverse(input), error => error.status === 503);
});
test('road kilometres come from OSRM, validate coordinates and cache real routes', async () => {
  let count = 0;
  const geo = createGeoService({ db, fetchImpl: async url => { count++; assert.equal(url.hostname, 'osrm'); assert.match(url.pathname, /38.7,9;38.8,9.1/); return response({ code: 'Ok', routes: [{ distance: 15750, duration: 1680, geometry: { type: 'LineString', coordinates: [[38.7, 9], [38.8, 9.1]] } }] }); } });
  const points = { originLatitude: 9, originLongitude: 38.7, destinationLatitude: 9.1, destinationLongitude: 38.8 };
  const result = await geo.route(points); assert.equal(result.distanceKm, 15.75); assert.equal(result.durationMinutes, 28); await geo.route(points); assert.equal(count, 1);
  await assert.rejects(geo.route({ ...points, originLatitude: '' }), error => error.status === 400);
  const failed = createGeoService({ fetchImpl: async () => { throw new Error('offline'); } });
  await assert.rejects(failed.route(points), error => error.status === 503);
});

test('detailed routes preserve every road bend and report road-access offsets without changing distance', async () => {
  const curve = [[38.7, 9], [38.7001, 9.00002], [38.7002, 9.0002], [38.70025, 9.0003], [38.8, 9.1]];
  const geo = createGeoService({ fetchImpl: async url => {
    assert.equal(url.searchParams.get('overview'), 'full');
    assert.equal(url.searchParams.get('radiuses'), '500;500');
    return response({ code: 'Ok', waypoints: [{ distance: 12.4 }, { distance: 34.8 }], routes: [{ distance: 15750, duration: 1680, geometry: { type: 'LineString', coordinates: curve } }] });
  } });
  const result = await geo.route({ originLatitude: 9, originLongitude: 38.7, destinationLatitude: 9.1, destinationLongitude: 38.8 });
  assert.deepEqual(result.geometry.coordinates, curve);
  assert.equal(result.geometryQuality, 'FULL');
  assert.equal(result.geometryToleranceMeters, 0);
  assert.equal(result.distanceKm, 15.75);
  assert.equal(result.durationMinutes, 28);
  assert.equal(result.originSnapMeters, 12);
  assert.equal(result.destinationSnapMeters, 35);
});

test('very long routes retain real corners within a stated tolerance and the existing mobile point limit', () => {
  const count = 12000, points = [];
  for (let i = 0; i < count; i++) points.push([38.7 + i / count * .1, 9]);
  points.push([38.8, 9]);
  for (let i = 1; i <= count; i++) points.push([38.8, 9 + i / count * .1]);
  const detail = detailedGeometry({ type: 'LineString', coordinates: points });
  assert.equal(detail.geometryQuality, 'HIGH_DETAIL');
  assert.ok(detail.geometryToleranceMeters <= 5);
  assert.ok(detail.geometry.coordinates.length <= 20000);
  assert.deepEqual(detail.geometry.coordinates[0], points[0]);
  assert.deepEqual(detail.geometry.coordinates.at(-1), points.at(-1));
  assert.ok(detail.geometry.coordinates.some(p => p[0] === 38.8 && p[1] === 9), 'A real right-angle road turn must not become a diagonal shortcut');
  for (const geometry of [null, { type: 'Polygon', coordinates: points }, { type: 'LineString', coordinates: [[38, 9], [NaN, 9]] }, { type: 'LineString', coordinates: [[38, 9], [38, 91]] }])
    assert.throws(() => detailedGeometry(geometry));
});

test('road and place lookups give verified accounts separate budgets on a shared network', async t => {
  const express = require('express');
  const { createGeoRouter } = require('../src/routes/mobility-geo');
  let lookups = 0;
  const app = express();
  app.use('/api/mobility', createGeoRouter({ limit: 2,
    service: {
      async route() { lookups++; return { distanceKm: 3, geometry: { type: 'LineString', coordinates: [[38.78, 9], [38.79, 9.01]] } }; },
      async locations() { lookups++; return { places: [] }; },
      async reverse() { lookups++; return { name: 'Arat Kilo, Addis Ababa' }; }
    },
    authenticateRequest(req, res, next) {
      const token = req.get('Authorization');
      if (!['Bearer active-driver', 'Bearer active-passenger'].includes(token))
        return res.status(401).json({ success: false });
      req.user = { id: token.slice(7) };
      next();
    }
  }));
  const server = app.listen(0, '127.0.0.1');
  await new Promise(resolve => server.once('listening', resolve));
  t.after(() => new Promise(resolve => server.close(resolve)));
  const base = 'http://127.0.0.1:' + server.address().port + '/api/mobility';
  const request = (path, headers) => fetch(base + path, { headers });
  const driver = { Authorization: 'Bearer active-driver' };
  const passenger = { Authorization: 'Bearer active-passenger' };
  assert.equal((await request('/route')).status, 200);
  assert.equal((await request('/locations?q=Bole')).status, 200);
  const guestReverse = await request('/locations/reverse?lat=9.03&lon=38.77');
  assert.equal(guestReverse.status, 429, 'Reverse geocoding shares the anonymous search budget.');
  assert.equal((await request('/route', driver)).status, 200, 'Guest traffic cannot exhaust a verified driver');
  const driverReverse = await request('/locations/reverse?lat=9.03&lon=38.77', driver);
  assert.equal(driverReverse.status, 200);
  assert.equal((await driverReverse.json()).name, 'Arat Kilo, Addis Ababa');
  const capped = await request('/locations?q=Bole', driver);
  assert.equal(capped.status, 429, 'A driver cannot bypass the combined route/search budget');
  assert.ok(Number(capped.headers.get('retry-after')) > 0);
  assert.equal((await request('/route', passenger)).status, 200, 'Another account on the same network has its own budget');
  assert.equal((await request('/route', { Authorization: 'Bearer forged' })).status, 401);
  assert.equal((await request('/route', { Authorization: 'Bearer expired' })).status, 401);
  assert.equal(lookups, 5, 'Rejected requests never call the routing or search service');
});
