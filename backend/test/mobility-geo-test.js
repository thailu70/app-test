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
test('road kilometres come from OSRM, validate coordinates and cache real routes', async () => {
  let count = 0;
  const geo = createGeoService({ db, fetchImpl: async url => { count++; assert.equal(url.hostname, 'osrm'); assert.match(url.pathname, /38.7,9;38.8,9.1/); return response({ code: 'Ok', routes: [{ distance: 15750, duration: 1680, geometry: { type: 'LineString', coordinates: [[38.7, 9], [38.8, 9.1]] } }] }); } });
  const points = { originLatitude: 9, originLongitude: 38.7, destinationLatitude: 9.1, destinationLongitude: 38.8 };
  const result = await geo.route(points); assert.equal(result.distanceKm, 15.75); assert.equal(result.durationMinutes, 28); await geo.route(points); assert.equal(count, 1);
  await assert.rejects(geo.route({ ...points, originLatitude: '' }), error => error.status === 400);
  const failed = createGeoService({ fetchImpl: async () => { throw new Error('offline'); } });
  await assert.rejects(failed.route(points), error => error.status === 503);
});
