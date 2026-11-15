'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { settingsPayload, fields, markup } = require('../public/admin/rides');
function data(overrides = {}) {
  const values = Object.fromEntries(fields.map(([key, , min]) => [key, String(Math.max(min, 1))]));
  Object.assign(values, { enabled: false, sharedEnabled: false, 'tier-ECONOMY': '1', 'tier-COMFORT': '1.25', 'tier-MINI_VAN': '1.5', ...overrides });
  return values;
}
const payload = input => settingsPayload(key => input[key]);
test('admin can save unactivated setup without accidentally enabling bookings or inventing monetary defaults', () => {
  const result = payload(data({ baseFareEtb: '0', perKmEtb: '0', minimumFareEtb: '0', fixedCommissionEtb: '0', sharedFixedCommissionEtb: '0' }));
  assert.equal(result.enabled, false); assert.equal(result.sharedEnabled, false);
  assert.equal(result.fixedCommissionEtb, 0); assert.equal(result.sharedFixedCommissionEtb, 0);
  assert.deepEqual(result.tiers.map(tier => tier.id), ['ECONOMY', 'COMFORT', 'MINI_VAN']);
});
test('activation requires real positive fares and cannot submit blank or nonfinite monetary fields', () => {
  for (const key of ['baseFareEtb', 'perKmEtb', 'minimumFareEtb']) assert.throws(() => payload(data({ enabled: true, [key]: '0' })), /positive/);
  for (const value of ['', 'NaN', 'Infinity', '-1']) assert.throws(() => payload(data({ fixedCommissionEtb: value })));
  assert.equal(payload(data({ enabled: true, baseFareEtb: '25', perKmEtb: '12.50', minimumFareEtb: '50' })).perKmEtb, 12.5);
});
test('shared fare bounds, GPS frequency and the physical arrival radius are validated before submission', () => {
  assert.throws(() => payload(data({ minimumSharedFareEtb: '200', maximumSharedFareEtb: '100' })), /maximum shared fare/);
  assert.throws(() => payload(data({ gpsUploadSeconds: '0' })), /GPS/);
  assert.throws(() => payload(data({ arrivalRadiusMeters: '1' })), /radius/);
  assert.throws(() => payload(data({ 'tier-COMFORT': '100' })), /multiplier/);
});
test('admin view exposes separate driver approvals and shared fee charged per vehicle trip', () => {
  assert.match(markup(), /Driver approvals/);
  assert.match(markup(), /once per completed vehicle trip/);
  assert.match(markup(), /Normal traffic time is never billable waiting/);
  assert.doesNotMatch(markup(), /value="(?:240|280|80)"/);
});
