'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs'), os = require('node:os'), path = require('node:path');
process.env.NODE_ENV = 'test';
delete process.env.DATABASE_URL;
process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), 'passo-commute-quote-'));
process.env.UPLOAD_DIR = path.join(process.env.DATA_DIR, 'uploads');
process.env.JWT_SECRET = 'isolated-commute-quote-test-secret';
const { DB } = require('../src/db');
const { createOnboardingService, initOnboardingSchema, getOnboardingReadiness } = require('../src/services/onboarding');
const { commuteQuote, commuteSuggestions } = require('../src/services/commute-quote');

test('typed daily commute quotes use exact public endpoints and catalog capacity with server enforcement', async t => {
  await initOnboardingSchema(); await require('../src/services/master-data').initMasterDataSchema();
  await DB.prepare("INSERT INTO users(id,role,fullName,phone,passwordHash,status) VALUES('quote_admin','ADMIN','Quote reviewer','0912300001','unused','ACTIVE')").run();
  const admin = { id: 'quote_admin', role: 'ADMIN' };
  const service = createOnboardingService({ sendSms: async () => { throw new Error('This test must not send SMS'); } });
  async function route(id, departure, destination, fare = 2500.25, { active = 1, company = '', type = 'DAILY_COMMUTE' } = {}) {
    await DB.prepare('INSERT INTO routes(id,name,nameAm,origin,destination,basePriceEtb,active,companyId,routeType) VALUES(?,?,?,?,?,?,?,?,?)').run(id, 'Fixture ' + id, 'መስመር', departure, destination, fare, active, company, type);
  }
  await route('quote_direct', 'Quote Alpha', 'Quote Omega');
  await route('quote_stops', '', '', 100.05);
  for (const [id, name, am, order] of [['qs_last','Quote Terminal','መድረሻ',9],['qs_middle','Not an endpoint','መካከል',5],['qs_first','Quote Station','መነሻ',1]]) {
    await DB.prepare('INSERT INTO route_stops(id,routeId,stopName,stopNameAm,stopOrder,latitude,longitude) VALUES(?,?,?,?,?,?,?)').run(id, 'quote_stops', name, am, order, 9, 38);
  }
  await route('quote_inactive', 'Hidden Alpha', 'Hidden Omega', 1, { active: 0 });
  await route('quote_company', 'Private Alpha', 'Private Omega', 1, { company: 'private-company' });
  await route('quote_intercity', 'Intercity Alpha', 'Intercity Omega', 1, { type: 'INTERCITY' });
  await route('quote_ambiguous_a', 'Shared Alpha', 'Shared Omega');
  await route('quote_ambiguous_b', 'Shared Alpha', 'Shared Omega', 9000);
  await route('quote_no_fare', 'Unpriced Alpha', 'Unpriced Omega', 0);
  await route('quote_huge', 'Huge Alpha', 'Huge Omega', 200000);
  const express = require('express'), app = express(); app.use(express.json());
  app.use('/api/onboarding', require('../src/routes/onboarding').createOnboardingRouter({ service, rateLimitMax: 100 }));
  const server = app.listen(0, '127.0.0.1'); await new Promise(resolve => server.once('listening', resolve));
  t.after(() => new Promise(resolve => { server.closeAllConnections(); server.close(resolve); }));
  const base = 'http://127.0.0.1:' + server.address().port + '/api/onboarding';
  async function get(endpoint, params) { const res = await fetch(base + endpoint + '?' + new URLSearchParams(params)); return { status: res.status, body: await res.json() }; }
  const input = (phone, plate, extra = {}) => ({ role: 'DRIVER', fullName: 'Quote applicant', phone, password: 'Long fixture password', licenseNumber: 'QUOTE-LICENSE', vehicleModel: 'Minivan', assignedVehiclePlate: plate,
    vehicleType: 'MINIVAN_8', proposedDeparture: 'Quote Alpha', proposedDestination: 'Quote Omega', ...extra });

  await t.test('public suggestions retain endpoint translations, avoid intermediate stops and exclude tenant routes', async () => {
    const result = await get('/commute-suggestions', { field: 'departure', q: 'መነሻ' });
    assert.equal(result.status, 200); assert.deepEqual(result.body.places, [{ label: 'Quote Station', aliases: ['Quote Station','መነሻ'] }]);
    assert.equal(result.body.routes[0].destination, 'Quote Terminal');
    const filtered = await get('/commute-suggestions', { field: 'destination', departure: 'መነሻ' });
    assert.deepEqual(filtered.body.places, [{ label: 'Quote Terminal', aliases: ['Quote Terminal','መድረሻ'] }]);
    const all = await commuteSuggestions();
    assert.ok(!all.routes.some(item => ['quote_inactive','quote_company','quote_intercity'].includes(item.id)));
    assert.ok(!JSON.stringify(all.places).includes('Not an endpoint'));
    assert.equal((await get('/commute-suggestions', { field: 'invalid' })).status, 400);
    assert.equal((await get('/commute-suggestions', { q: 'x'.repeat(121) })).status, 400);
  });

  await t.test('known prices use catalog seats and passenger fare, preserve decimal precision and distinguish ambiguous/new/unpriced routes', async () => {
    const result = await get('/commute-quote', { departure: '  Ｑｕｏｔｅ   Alpha ', destination: 'quote omega', vehicleType: 'MINIVAN_8' });
    assert.equal(result.status, 200); assert.equal(result.body.routeMatch.status, 'EXACT');
    assert.deepEqual(result.body.price, { mode: 'FIXED', amountEtb: 20002, currency: 'ETB', basis: 'PER_VEHICLE', source: 'ROUTE_PASSENGER_FARE_X_CAPACITY', passengerCapacity: 8, passengerMonthlyFareEtb: 2500.25 });
    const translated = await commuteQuote({ departure: 'መነሻ', destination: 'መድረሻ', vehicleType: 'MINIBUS_14' });
    assert.equal(translated.price.amountEtb, 1400.7); assert.equal(translated.price.passengerCapacity, 14);
    for (const [departure, destination, status] of [['Quote Omega','Quote Alpha','NONE'],['Shared Alpha','Shared Omega','AMBIGUOUS'],['Unpriced Alpha','Unpriced Omega','EXACT'],['Private Alpha','Private Omega','NONE']]) {
      const quote = await commuteQuote({ departure, destination, vehicleType: 'MINIVAN_8' });
      assert.equal(quote.routeMatch.status, status); assert.equal(quote.price.mode, 'PROPOSED'); assert.equal(quote.price.amountEtb, null);
    }
    await DB.prepare("INSERT INTO vehicle_types(code,name,capacity,active) VALUES('QUOTE_11','Eleven seats',11,TRUE)").run();
    assert.equal((await commuteQuote({ departure: 'Quote Alpha', destination: 'Quote Omega', vehicleType: 'QUOTE_11' })).price.amountEtb, 27502.75);
    for (const params of [{ departure: 'Quote Alpha', destination: 'Quote Alpha', vehicleType: 'MINIVAN_8' }, { departure: 'Quote Alpha', destination: 'Quote Omega', vehicleType: 'UNKNOWN' }, { departure: '!!', destination: 'Quote Omega', vehicleType: 'MINIVAN_8' }]) assert.equal((await get('/commute-quote', params)).status, 400);
    assert.equal((await get('/commute-quote', { departure: 'Huge Alpha', destination: 'Huge Omega', vehicleType: 'MINIVAN_8' })).status, 409);
    await DB.prepare("UPDATE vehicle_types SET active=FALSE WHERE code='QUOTE_11'").run();
    assert.equal((await get('/commute-quote', { departure: 'Quote Alpha', destination: 'Quote Omega', vehicleType: 'QUOTE_11' })).status, 400);
  });

  let known;
  await t.test('registration ignores client tampering for known fees and requires valid manual proposals otherwise', async () => {
    known = await service.register(input('0912346901','QUOTE-KNOWN',{ proposedMonthlyFeeEtb: 1, feeLocked: false }));
    const pref = known.onboarding.routePreference;
    assert.equal(pref.mode, 'EXISTING'); assert.equal(pref.routeId, 'quote_direct'); assert.equal(pref.proposedMonthlyFeeEtb, 20002);
    assert.equal(pref.feeLocked, true); assert.equal(pref.feeSource, 'ROUTE_PASSENGER_FARE_X_CAPACITY'); assert.equal(pref.passengerCapacity, 8);
    assert.equal(known.account.status, 'PENDING'); assert.equal(known.onboarding.canVerifyAndUpload, false);
    const vehicle = await DB.prepare('SELECT assignedRouteId,currentOccupancy FROM vehicles WHERE driverId=?').get(known.account.id);
    assert.equal(vehicle.assignedRouteId, null); assert.equal(vehicle.currentOccupancy, 0, 'Quote capacity never comes from occupancy');
    await assert.rejects(() => service.register(input('0912346902','QUOTE-MISSING',{ proposedDeparture: 'New Alpha', proposedDestination: 'New Omega' })), error => error.status === 400);
    const manual = await service.register(input('0912346902','QUOTE-MANUAL',{ proposedDeparture: 'New Alpha', proposedDestination: 'New Omega', proposedMonthlyFeeEtb: 12345.67 }));
    assert.equal(manual.onboarding.routePreference.proposedMonthlyFeeEtb, 12345.67); assert.equal(manual.onboarding.routePreference.feeLocked, false);
    assert.equal((await DB.prepare("SELECT basePriceEtb FROM routes WHERE id='quote_direct'").get()).basePriceEtb, 2500.25, 'Driver applications never mutate passenger fares');
  });

  await t.test('endpoint edits use the registered type and invalidate acceptance; changed published prices need fresh review', async () => {
    await service.review(admin, known.account.id, 'DRIVER', 'ACCEPT');
    await assert.rejects(() => service.updateProfile(known.account.id, 'DRIVER', { vehicleType: 'MINIBUS_14' }), error => error.status === 400);
    const edited = await service.updateProfile(known.account.id, 'DRIVER', { proposedDeparture: 'መነሻ', proposedDestination: 'መድረሻ', vehicleType: 'MINIVAN_8', proposedMonthlyFeeEtb: 2 });
    assert.equal(edited.onboarding.routePreference.routeId, 'quote_stops'); assert.equal(edited.onboarding.routePreference.proposedMonthlyFeeEtb, 800.4);
    assert.equal(edited.onboarding.acceptedAt, null); assert.equal(edited.account.vehicleCapacity, 8);
    await DB.prepare("UPDATE routes SET basePriceEtb=200 WHERE id='quote_stops'").run();
    await assert.rejects(() => service.review(admin, known.account.id, 'DRIVER', 'ACCEPT'), error => error.status === 409);
    assert.equal((await service.snapshot(known.account.id, 'DRIVER')).onboarding.routePreference.proposedMonthlyFeeEtb, 800.4, 'A reviewer never silently accepts a different price');
    const refreshed = await service.submit(known.account.id, 'DRIVER');
    assert.equal(refreshed.onboarding.routePreference.proposedMonthlyFeeEtb, 1600); assert.equal(refreshed.onboarding.reviewStatus, 'AWAITING_ACCEPTANCE');
    await service.review(admin, known.account.id, 'DRIVER', 'ACCEPT');
    // Simulate already completed verification and private documents without any SMS delivery.
    await DB.prepare('UPDATE onboarding_accounts SET phone_verified_at=?,phone_verified_for=? WHERE account_id=?').run(new Date().toISOString(), known.account.phone, known.account.id);
    await DB.prepare('INSERT INTO user_profiles(user_id,emergency_contact_name,emergency_contact_phone,vehicle_color,updated_at) VALUES(?,?,?,?,?) ON CONFLICT(user_id) DO UPDATE SET emergency_contact_name=excluded.emergency_contact_name,emergency_contact_phone=excluded.emergency_contact_phone,vehicle_color=excluded.vehicle_color').run(known.account.id, 'Contact person', '+251912000099', 'White', new Date().toISOString());
    for (const kind of ['LICENSE','VEHICLE_REGISTRATION','INSURANCE','PHOTO']) await DB.prepare('INSERT INTO user_profile_documents(user_id,kind,storage_name,content_type,byte_size,uploaded_at) VALUES(?,?,?,?,?,?)').run(known.account.id, kind, 'fixture.png', 'image/png', 100, new Date().toISOString());
    await service.submit(known.account.id, 'DRIVER');
    await DB.prepare("UPDATE routes SET basePriceEtb=201 WHERE id='quote_stops'").run();
    await assert.rejects(() => service.review(admin, known.account.id, 'DRIVER', 'APPROVE'), error => error.status === 409);
    const reopened = await service.submit(known.account.id, 'DRIVER');
    assert.equal(reopened.onboarding.acceptedAt, null); assert.equal(reopened.onboarding.phoneVerified, false);
    assert.equal((await getOnboardingReadiness(known.account.id, 'DRIVER')).ready, false);
  });

  await t.test('suggestion output remains bounded without exposing applicant proposals', async () => {
    for (let index = 0; index < 110; index++) await route('quote_bound_' + index, 'Bound ' + index, 'Bound destination', 10);
    const suggestions = await commuteSuggestions({ field: 'departure', q: 'Bound' });
    assert.equal(suggestions.places.length, 50); assert.equal(suggestions.routes.length, 100);
    assert.ok(!JSON.stringify(suggestions).includes('proposedMonthlyFeeEtb'));
  });
});
