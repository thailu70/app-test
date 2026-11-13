'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { createGeoService } = require('../src/services/mobility-geo');
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
test('road kilometres come from OSRM, validate coordinates and cache real routes', async () => {
  let count = 0;
  const geo = createGeoService({ db, fetchImpl: async url => { count++; assert.equal(url.hostname, 'osrm'); assert.match(url.pathname, /38.7,9;38.8,9.1/); return response({ code: 'Ok', routes: [{ distance: 15750, duration: 1680, geometry: { type: 'LineString', coordinates: [[38.7, 9], [38.8, 9.1]] } }] }); } });
  const points = { originLatitude: 9, originLongitude: 38.7, destinationLatitude: 9.1, destinationLongitude: 38.8 };
  const result = await geo.route(points); assert.equal(result.distanceKm, 15.75); assert.equal(result.durationMinutes, 28); await geo.route(points); assert.equal(count, 1);
  await assert.rejects(geo.route({ ...points, originLatitude: '' }), error => error.status === 400);
  const failed = createGeoService({ fetchImpl: async () => { throw new Error('offline'); } });
  await assert.rejects(failed.route(points), error => error.status === 503);
});
