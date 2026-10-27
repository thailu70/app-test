'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
process.env.NODE_ENV = 'test';
process.env.JWT_SECRET = 'isolated-mobility-security-regression-key';
process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), 'routepass-mobility-security-'));
if (process.env.MOBILITY_TEST_POSTGRES !== 'true') delete process.env.DATABASE_URL;
const { DB } = require('../src/db');
const { initOnboardingSchema, REQUIRED, getOnboardingReadiness } = require('../src/services/onboarding');
const { initMasterDataSchema } = require('../src/services/master-data');
const { createMobilityService } = require('../src/services/mobility');

test('mobility reservations retain capacity, expiry, operational approval and private cargo access', async t => {
  await initOnboardingSchema(); await initMasterDataSchema();
  const time = new Date().toISOString();
  async function approvedDriver(driverId, phone, serviceType, vehicleId) {
    await DB.prepare('INSERT INTO users(id,role,fullName,phone,passwordHash,status) VALUES(?,?,?,?,?,?)')
      .run(driverId, 'DRIVER', driverId, phone, 'isolated-fixture-only', 'ACTIVE');
    await DB.prepare(`INSERT INTO onboarding_accounts(account_id,role,normalized_phone,submitted_at,review_status,created_at,updated_at,
      service_type,route_choice,proposed_departure,proposed_destination,quote_vehicle_type,quote_passenger_capacity,acceptance_status,accepted_at,accepted_by)
      VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)`)
      .run(driverId, 'DRIVER', phone, time, 'APPROVED', time, time, serviceType, 'PROPOSED', 'Fixture departure', 'Fixture destination', 'MINIVAN_8', 8, 'ACCEPTED', time, 'usr_adm_root');
    await DB.prepare('INSERT INTO user_profiles(user_id,emergency_contact_name,emergency_contact_phone,vehicle_color,updated_at) VALUES(?,?,?,?,?)')
      .run(driverId, 'Fixture contact', '+251911234599', 'Teal', time);
    for (const kind of REQUIRED.DRIVER) await DB.prepare('INSERT INTO user_profile_documents(user_id,kind,storage_name,content_type,byte_size,uploaded_at) VALUES(?,?,?,?,?,?)')
      .run(driverId, kind, crypto.randomUUID() + '.png', 'image/png', 100, time);
    await DB.prepare('INSERT INTO vehicles(id,plateNumber,model,vehicleType,capacityLimit,driverId,status) VALUES(?,?,?,?,?,?,?)')
      .run(vehicleId, 'SEC-' + vehicleId, 'Fixture van', 'MINIVAN_8', 8, driverId, 'IN_SERVICE');
    assert.equal((await getOnboardingReadiness(driverId, 'DRIVER')).ready, true);
  }
  await approvedDriver('security-group-driver', '+251911234501', 'GROUP_TRAVEL', 'security-group-van');
  await approvedDriver('security-cargo-driver', '+251911234502', 'CARGO', 'security-cargo-van');
  let clock = Date.now(), creates = 0;
  const payments = new Map();
  const provider = {
    missingConfiguration: () => [], config: () => ({ environment: 'production' }), merchantOrderId: () => crypto.randomUUID(),
    createCheckout: async orderId => { creates++; payments.set(orderId, { paid: false, failed: false, merchantOrderId: orderId, currency: 'ETB', amount: '650.00' }); return { prepayId: 'isolated-prepay-' + creates, checkoutUrl: 'https://payments.example.test/' + creates }; },
    queryOrderStatus: async orderId => payments.get(orderId), checkoutUrlForPrepay: value => 'https://payments.example.test/' + value,
    parseNotification: value => value, verifyNotification: () => false
  };
  const service = createMobilityService({ db: DB, now: () => clock, provider, uploadRoot: path.join(process.env.DATA_DIR, 'private-photos'), sendSms: async () => ({ sent: false }) });
  await service.init();
  async function trip({ approve = true, seats = 8 } = {}) {
    const result = await service.createTrip('security-group-driver', { origin: 'Fixture Addis', destination: 'Fixture Hawassa',
      departureAt: new Date(clock + 24 * 3600000).toISOString(), latitude: 9, longitude: 38, fareEtb: 650, seats, vehicleId: 'security-group-van' });
    if (approve) await service.reviewTrip('usr_adm_root', result.trip.id, { decision: 'APPROVE', note: 'Isolated regression fixture' });
    return result.trip.id;
  }
  const bookingInput = seats => ({ buyerName: 'Fixture buyer', buyerPhone: '0911234590', adultConfirmed: true,
    travelers: seats.map((seatNumber, index) => ({ name: 'Fixture traveler ' + (index + 1), phone: '091123459' + index, seatNumber })) });

  await t.test('multi-seat reservations are all-or-nothing under overlapping booking attempts', async () => {
    const tripId = await trip();
    const first = await service.createBooking(tripId, bookingInput([1, 2]));
    await assert.rejects(service.createBooking(tripId, bookingInput([2, 3])), error => error.status === 409);
    assert.deepEqual((await service.seats(tripId)).occupiedSeats, [1, 2]);
    const attempts = await Promise.allSettled([service.createBooking(tripId, bookingInput([3, 4])), service.createBooking(tripId, bookingInput([4, 5]))]);
    assert.equal(attempts.filter(result => result.status === 'fulfilled').length, 1);
    assert.equal(attempts.filter(result => result.status === 'rejected' && result.reason.status === 409).length, 1);
    assert.equal((await service.seats(tripId)).occupiedSeats.length, 4);
    await assert.rejects(service.booking(first.booking.id, 'wrong-secret'), error => error.status === 404);
    assert.equal((await service.booking(first.booking.id, first.bookingSecret, false)).booking.travelers.some(person => person.ticketNumber), false);
  });

  await t.test('expired checkout cannot return a previously-created payable URL or reclaim seats', async () => {
    const tripId = await trip(), held = await service.createBooking(tripId, bookingInput([1]));
    const checkout = await service.checkout(held.booking.id, held.bookingSecret);
    assert.match(checkout.checkoutUrl, /^https:/); assert.equal(creates, 1);
    clock += 126 * 60000;
    const expired = await service.booking(held.booking.id, held.bookingSecret, false);
    assert.equal(expired.booking.status, 'EXPIRED');
    const replacement = await service.createBooking(tripId, bookingInput([1]));
    await assert.rejects(service.checkout(held.booking.id, held.bookingSecret), error => error.status === 409);
    assert.equal(creates, 1, 'Expired checkout does not create a replacement provider order');
    assert.equal((await service.booking(replacement.booking.id, replacement.bookingSecret, false)).booking.paymentStatus, 'UNPAID');
  });

  await t.test('later vehicle capacity reductions remove incompatible approved trips from booking', async () => {
    const tripId = await trip();
    await DB.prepare('UPDATE vehicles SET capacityLimit=4 WHERE id=?').run('security-group-van');
    assert.equal((await service.trips()).trips.some(row => row.id === tripId), false);
    await assert.rejects(service.createBooking(tripId, bookingInput([1])), error => error.status === 409);
    await DB.prepare('UPDATE vehicles SET capacityLimit=8 WHERE id=?').run('security-group-van');
  });

  await t.test('admin can reject a pending proposal after its driver was suspended, without approving it', async () => {
    const tripId = await trip({ approve: false });
    await DB.prepare("UPDATE users SET status='INACTIVE' WHERE id=?").run('security-group-driver');
    await assert.rejects(service.reviewTrip('usr_adm_root', tripId, { decision: 'APPROVE', note: 'Must remain blocked' }), error => error.status === 409);
    assert.equal((await service.reviewTrip('usr_adm_root', tripId, { decision: 'REJECT', note: 'Driver unavailable' })).trip.status, 'REJECTED');
    await DB.prepare("UPDATE users SET status='ACTIVE' WHERE id=?").run('security-group-driver');
  });

  await t.test('draft cargo photos and submitted requests require the customer capability or authorized assigned driver', async () => {
    const date = new Date(clock + 3 * 3600000).toISOString().slice(0, 10);
    const result = await service.createRequest({ kind: 'CARGO', origin: 'Fixture warehouse', destination: 'Fixture home', travelDate: date,
      vehicleType: 'MINIVAN_8', cargoDetails: 'Two boxed chairs', customerName: 'Cargo fixture', customerPhone: '0911234588', adultConfirmed: true });
    const key = result.request.id, capability = result.requestSecret;
    assert.equal(result.request.status, 'DRAFT'); assert.equal((await service.requests('CARGO')).requests.some(row => row.id === key), false);
    await assert.rejects(service.request(key, 'incorrect'), error => error.status === 404);
    await assert.rejects(service.submitRequest(key, capability), error => error.status === 409);
    const photo = Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), Buffer.alloc(24)]);
    await assert.rejects(service.uploadPhoto(key, 'incorrect', photo, 'image/png'), error => error.status === 404);
    await service.uploadPhoto(key, capability, photo, 'image/png');
    await assert.rejects(service.photo(key), error => error.status === 404);
    await assert.rejects(service.photo(key, null, { role: 'DRIVER', id: 'security-cargo-driver' }), error => error.status === 404);
    assert.deepEqual((await service.photo(key, capability)).bytes, photo);
    assert.deepEqual((await service.photo(key, null, { role: 'ADMIN', id: 'usr_adm_root' })).bytes, photo);
    await service.submitRequest(key, capability);
    await assert.rejects(service.assignRequest('usr_adm_root', key, { driverId: 'security-group-driver', vehicleId: 'security-group-van' }), error => error.status === 409);
    await service.assignRequest('usr_adm_root', key, { driverId: 'security-cargo-driver', vehicleId: 'security-cargo-van', note: 'Fixture assignment' });
    assert.deepEqual((await service.photo(key, null, { role: 'DRIVER', id: 'security-cargo-driver' })).bytes, photo);
    await assert.rejects(service.photo(key, null, { role: 'DRIVER', id: 'security-group-driver' }), error => error.status === 404);
    await assert.rejects(service.uploadPhoto(key, capability, photo, 'image/png'), error => error.status === 409);
  });
});
