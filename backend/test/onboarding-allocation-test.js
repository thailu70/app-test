'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
process.env.NODE_ENV = 'test';
process.env.PAYMENT_MODE = 'TEST';
process.env.JWT_SECRET = 'onboarding-allocation-regression-secret';
process.env.QR_SIGNING_KEY = 'onboarding-allocation-qr-regression-secret';
process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), 'routepass-onboarding-allocation-'));
delete process.env.DATABASE_URL;
const express = require('express');
const { DB } = require('../src/db');
const { signToken } = require('../src/middleware/auth');
const { createOnboardingService, acceptInitialOnboarding, initOnboardingSchema, getOnboardingReadiness, isOperationallyEligible, normalizePhone, REQUIRED } = require('../src/services/onboarding');
const { initIntercitySchema } = require('../src/services/intercity-schema');

test('legacy allocation APIs cannot bypass required web onboarding and approval', async t => {
  await initOnboardingSchema();
  await initIntercitySchema();
  await DB.exec("CREATE TABLE IF NOT EXISTS company_route_assignments(id TEXT PRIMARY KEY,company_id TEXT,route_id TEXT,driver_id TEXT,vehicle_id TEXT,status TEXT DEFAULT 'ACTIVE',created_at TEXT DEFAULT CURRENT_TIMESTAMP)");
  for (const [id, phone] of [['allocation_company', '+251911600005'], ['allocation_unready_company', '+251911600006']]) {
    await DB.prepare('INSERT INTO transport_companies(id,name,phone,status) VALUES(?,?,?,?)').run(id, id, phone, 'ACTIVE');
  }
  const people = [
    ['allocation_daily', '+251911600001', '', 'PENDING'],
    ['allocation_company_driver', '+251911600002', 'allocation_company', 'ACTIVE'],
    ['allocation_bad_company_driver', '+251911600003', 'allocation_unready_company', 'ACTIVE'],
    ['allocation_legacy', '+251911600004', '', 'ACTIVE']
  ];
  for (const [id, phone, company, status] of people) await DB.prepare('INSERT INTO users(id,role,fullName,phone,passwordHash,status,companyId) VALUES(?,?,?,?,?,?,?)')
    .run(id, 'DRIVER', id, phone, 'unused', status, company);
  async function track(id, role, phone) {
    const now = new Date().toISOString();
    await DB.prepare('INSERT INTO onboarding_accounts(account_id,role,normalized_phone,review_status,created_at,updated_at) VALUES(?,?,?,?,?,?)')
      .run(id, role, normalizePhone(phone), 'DRAFT', now, now);
  }
  await track('allocation_daily', 'DRIVER', people[0][1]);
  await track('allocation_company_driver', 'DRIVER', people[1][1]);
  await track('allocation_unready_company', 'COMPANY', '+251911600006');
  for (const [id, type, company] of [['allocation_route', 'DAILY_COMMUTE', ''], ['allocation_intercity', 'INTERCITY', 'allocation_company'], ['allocation_bad_intercity', 'INTERCITY', 'allocation_unready_company']]) {
    await DB.prepare('INSERT INTO routes(id,name,nameAm,routeType,companyId,origin,destination,active) VALUES(?,?,?,?,?,?,?,?)')
      .run(id, id, id, type, company, 'Fixture origin', 'Fixture destination', 1);
  }
  for (const [id, driver, route, company] of [
    ['allocation_van', 'allocation_daily', 'allocation_route', ''],
    ['allocation_legacy_van', 'allocation_legacy', null, ''],
    ['allocation_coach', 'allocation_company_driver', 'allocation_intercity', 'allocation_company'],
    ['allocation_bad_coach', 'allocation_bad_company_driver', 'allocation_bad_intercity', 'allocation_unready_company']
  ]) await DB.prepare('INSERT INTO vehicles(id,plateNumber,model,vehicleType,capacityLimit,driverId,assignedRouteId,companyId) VALUES(?,?,?,?,?,?,?,?)')
    .run(id, id, 'Fixture van', 'MINIBUS_14', 14, driver, route, company);
  await DB.prepare("INSERT INTO subscriptions(id,passengerId,routeId,priceEtb,paymentStatus,subscriptionStatus,daysRemaining) VALUES('allocation_sub','usr_pas_alemayehu','allocation_route',2500,'PAID','ACTIVE',20)").run();
  for (const [id, company, driver, vehicle, route] of [
    ['allocation_departure', 'allocation_company', 'allocation_company_driver', 'allocation_coach', 'allocation_intercity'],
    ['allocation_bad_departure', 'allocation_unready_company', 'allocation_bad_company_driver', 'allocation_bad_coach', 'allocation_bad_intercity']
  ]) await DB.prepare('INSERT INTO intercity_departures(id,company_id,route_id,driver_id,vehicle_id,origin,destination,departure_at,latitude,longitude,radius_m,seats,fare_etb,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)')
    .run(id, company, route, driver, vehicle, 'Fixture origin', 'Fixture destination', '2099-01-01T08:00:00Z', 9, 38, 300, 14, 500, new Date().toISOString());

  const app = express(); app.use(express.json());
  app.use('/api/admin', require('../src/routes/admin'));
  app.use('/api/companies', require('../src/routes/company-portal'));
  app.use('/api/intercity', require('../src/routes/intercity'));
  app.use('/api/trips', require('../src/routes/trips'));
  app.use('/api/checkins', require('../src/routes/checkins'));
  app.use('/api/profiles', require('../src/routes/profiles'));
  app.use((error, _req, res, _next) => res.status(error.status || 500).json({ success: false, error: error.message }));
  const server = app.listen(0, '127.0.0.1');
  await new Promise(resolve => server.once('listening', resolve));
  t.after(() => new Promise(resolve => server.close(resolve)));
  const base = 'http://127.0.0.1:' + server.address().port;
  const tokens = { admin: signToken({ id: 'usr_adm_root', role: 'ADMIN' }),
    daily: signToken({ id: 'allocation_daily', role: 'DRIVER' }),
    company: signToken({ id: 'allocation_company', role: 'COMPANY' }),
    companyDriver: signToken({ id: 'allocation_company_driver', role: 'DRIVER' }),
    badCompany: signToken({ id: 'allocation_unready_company', role: 'COMPANY' }) };
  async function req(endpoint, method = 'GET', body, actor = 'admin', secret) {
    const response = await fetch(base + endpoint, { method, headers: { 'Content-Type': 'application/json',
      ...(actor ? { Authorization: 'Bearer ' + tokens[actor] } : {}), ...(secret ? { 'X-Booking-Secret': secret } : {}) },
      body: body === undefined ? undefined : JSON.stringify(body) });
    return { status: response.status, body: await response.json() };
  }
  function blocked(response) { assert.ok([401,403,409,428].includes(response.status), JSON.stringify(response)); }
  async function completeDriver(id, submitted = true) {
    const initial = await getOnboardingReadiness(id, 'DRIVER');
    if (initial.acceptanceRequired && !initial.acceptedAt) {
      const service = createOnboardingService();
      await service.updateProfile(id, 'DRIVER', { routeChoice: 'EXISTING', appliedRouteId: 'allocation_route', proposedMonthlyFeeEtb: 45000, proposedMonthlyFeeBasis: 'PER_VEHICLE' });
      await DB.transaction(async tx => acceptInitialOnboarding(id, 'usr_adm_root', 'Fixture initial acceptance', tx));
    }
    const account = await DB.prepare('SELECT phone FROM users WHERE id=?').get(id);
    const now = new Date().toISOString();
    await DB.prepare("UPDATE onboarding_accounts SET phone_verified_at=?,phone_verified_for=?,submitted_at=?,review_status='SUBMITTED' WHERE account_id=?")
      .run(now, normalizePhone(account.phone), submitted ? now : null, id);
    await DB.prepare('INSERT INTO user_profiles(user_id,emergency_contact_name,emergency_contact_phone,vehicle_color,updated_at) VALUES(?,?,?,?,?) ON CONFLICT(user_id) DO UPDATE SET emergency_contact_name=excluded.emergency_contact_name,emergency_contact_phone=excluded.emergency_contact_phone,vehicle_color=excluded.vehicle_color,updated_at=excluded.updated_at')
      .run(id, 'Fixture contact', '+251911600009', 'Green', now);
    for (const kind of REQUIRED.DRIVER) await DB.prepare('INSERT INTO user_profile_documents(user_id,kind,storage_name,content_type,byte_size,uploaded_at) VALUES(?,?,?,?,?,?) ON CONFLICT(user_id,kind) DO NOTHING')
      .run(id, kind, crypto.randomUUID() + '.png', 'image/png', 100, now);
  }

  await t.test('approved company login accepts national and international phone forms while pending accounts remain locked', async () => {
    const password = 'Allocation-fixture-password';
    const hash = await require('bcryptjs').hash(password, 4);
    await DB.prepare('UPDATE transport_companies SET password_hash=? WHERE id=?').run(hash, 'allocation_company');
    const international = await req('/api/companies/login', 'POST', { phone: '+251911600005', password }, null);
    const national = await req('/api/companies/login', 'POST', { phone: '0911600005', password }, null);
    assert.equal(international.status, 200);
    assert.equal(national.status, 200);
    assert.equal(national.body.user.id, 'allocation_company');
    assert.equal(national.body.user.role, 'COMPANY');
    await DB.prepare("UPDATE transport_companies SET status='PENDING' WHERE id='allocation_company'").run();
    assert.equal((await req('/api/companies/login', 'POST', { phone: '0911600005', password }, null)).status, 401);
    await DB.prepare("UPDATE transport_companies SET status='ACTIVE' WHERE id='allocation_company'").run();
  });

  await t.test('incomplete drivers cannot be approved, reactivated, assigned or receive a daily passenger roster', async () => {
    blocked(await req('/api/admin/drivers/allocation_daily/approval', 'PATCH', { status: 'ACTIVE' }));
    assert.equal((await DB.prepare('SELECT status FROM users WHERE id=?').get('allocation_daily')).status, 'PENDING');
    // Even a pre-existing/misconfigured ACTIVE row cannot bypass readiness.
    await DB.prepare("UPDATE users SET status='ACTIVE' WHERE id='allocation_daily'").run();
    blocked(await req('/api/admin/drivers/allocation_daily/route', 'PATCH', { routeId: 'allocation_route' }));
    blocked(await req('/api/admin/subscriptions/allocation_sub/assignment', 'PATCH', { vehicleId: 'allocation_van' }));
    assert.equal((await DB.prepare('SELECT vehicleId FROM subscriptions WHERE id=?').get('allocation_sub')).vehicleId, null);
    blocked(await req('/api/trips/roster?vehicleId=allocation_van', 'GET', undefined, 'daily'));
    blocked(await req('/api/trips/start', 'POST', { routeId: 'allocation_route', vehicleId: 'allocation_van' }, 'daily'));
    blocked(await req('/api/checkins/scan', 'POST', { qrToken: 'fixture-only' }, 'daily'));
  });

  await t.test('approved complete daily drivers can be assigned before deferred phone verification', async () => {
    await completeDriver('allocation_daily', false);
    blocked(await req('/api/admin/drivers/allocation_daily/approval', 'PATCH', { status: 'ACTIVE' }));
    await completeDriver('allocation_daily');
    await DB.prepare("UPDATE onboarding_accounts SET phone_verified_at=NULL,phone_verified_for=NULL WHERE account_id='allocation_daily'").run();
    assert.equal((await req('/api/admin/drivers/allocation_daily/approval', 'PATCH', { status: 'ACTIVE' })).status, 200);
    const approved = await getOnboardingReadiness('allocation_daily', 'DRIVER');
    assert.equal(approved.reviewReady, true); assert.equal(approved.ready, true); assert.equal(approved.phoneVerified, false);
    assert.equal(approved.phoneVerificationRequiredForApproval, false); assert.equal(approved.reviewStatus, 'APPROVED');
    assert.equal(approved.phoneVerificationRequiredForAllocation, false);
    assert.equal(await isOperationallyEligible('allocation_daily', 'DRIVER'), true);
    assert.equal((await req('/api/admin/drivers/allocation_daily/route', 'PATCH', { routeId: 'allocation_route' })).status, 200);
    assert.equal((await req('/api/admin/subscriptions/allocation_sub/assignment', 'PATCH', { vehicleId: 'allocation_van' })).status, 200);
    assert.equal((await req('/api/trips/roster?vehicleId=allocation_van', 'GET', undefined, 'daily')).status, 200);
    assert.equal((await req('/api/admin/drivers/allocation_legacy/route', 'PATCH', { routeId: 'allocation_route' })).status, 200,
      'Existing untracked approved drivers retain their current workflow');
  });

  await t.test('approved group and cargo drivers remain operationally eligible but cannot acquire or use daily commute assignments',async()=>{
    const {driverEligible,dailyDriverEligible}=require('../src/services/operational-onboarding');
    for(const serviceType of ['GROUP_TRAVEL','CARGO']){
      await DB.prepare('UPDATE onboarding_accounts SET service_type=? WHERE account_id=?').run(serviceType,'allocation_daily');
      assert.equal(await driverEligible('allocation_daily'),true,'Mobility and authentication keep generic approved-driver access');
      assert.equal(await dailyDriverEligible('allocation_daily'),false);
      blocked(await req('/api/admin/drivers/allocation_daily/route','PATCH',{routeId:'allocation_route'}));
      blocked(await req('/api/admin/subscriptions/allocation_sub/assignment','PATCH',{vehicleId:'allocation_van'}));
      blocked(await req('/api/trips/assignments','GET',undefined,'daily'));
      blocked(await req('/api/trips/roster?vehicleId=allocation_van','GET',undefined,'daily'));
      blocked(await req('/api/trips/start','POST',{routeId:'allocation_route',vehicleId:'allocation_van'},'daily'));
      blocked(await req('/api/checkins/scan','POST',{qrToken:'fixture-only'},'daily'));
      const passenger=await require('../src/services/passenger-assignment').passengerAssignment('usr_pas_alemayehu');
      assert.equal(passenger.vehicle,null,'A stale daily passenger assignment cannot reveal a mobility driver');
    }
    await DB.prepare("UPDATE onboarding_accounts SET service_type='DAILY_COMMUTE' WHERE account_id='allocation_daily'").run();
    assert.equal(await dailyDriverEligible('allocation_daily'),true);
  });

  await t.test('company assignment, driver approval and departure changes enforce company and target-driver readiness', async () => {
    blocked(await req('/api/companies/operations/drivers/allocation_company_driver/approval', 'PATCH', { action: 'APPROVE' }, 'company'));
    const assignment = { routeId: 'allocation_intercity', driverId: 'allocation_company_driver', vehicleId: 'allocation_coach' };
    blocked(await req('/api/companies/operations/assignments', 'POST', assignment, 'company'));
    blocked(await req('/api/companies/operations/assignments', 'POST', {
      routeId: 'allocation_bad_intercity', driverId: 'allocation_bad_company_driver', vehicleId: 'allocation_bad_coach'
    }, 'badCompany'));
    const departure = { routeId: 'allocation_intercity', driverId: 'allocation_company_driver', vehicleId: 'allocation_coach',
      departureAt: '2099-02-01T08:00:00Z', latitude: 9, longitude: 38, radiusMeters: 300, seats: 14, fareEtb: 500 };
    blocked(await req('/api/intercity/company/departures', 'POST', departure, 'company'));
    blocked(await req('/api/intercity/company/departures/allocation_departure', 'PATCH', { driverId: 'allocation_company_driver' }, 'company'));
    blocked(await req('/api/intercity/company/roster', 'GET', undefined, 'badCompany'));
    blocked(await req('/api/intercity/driver/departures/allocation_departure/roster', 'GET', undefined, 'companyDriver'));
  });

  await t.test('public and company ticket allocation cannot use an old departure with an incomplete operator', async () => {
    const list = await req('/api/intercity/departures', 'GET', undefined, null);
    assert.equal(list.status, 200);
    assert.equal(list.body.departures.some(departure => departure.id === 'allocation_departure'), false);
    blocked(await req('/api/intercity/departures/allocation_departure/hold', 'POST', { seatNumber: 1 }, null));
    blocked(await req('/api/intercity/company/departures/allocation_departure/bookings', 'POST', {
      passengerName: 'Fixture passenger', passengerPhone: '0912000000', seatNumber: 1, paymentCollected: true, paymentNote: 'CASH-FIXTURE'
    }, 'company'));
    const secret = 'isolated-booking-capability';
    await DB.prepare('INSERT INTO intercity_bookings(id,departure_id,company_id,secret_hash,seat_number,amount_etb,expires_at,created_at) VALUES(?,?,?,?,?,?,?,?)')
      .run('allocation_held', 'allocation_departure', 'allocation_company', crypto.createHash('sha256').update(secret).digest('hex'), 1, 500, '2099-01-01T00:00:00Z', new Date().toISOString());
    await DB.prepare('INSERT INTO intercity_seats(departure_id,seat_number,booking_id) VALUES(?,?,?)').run('allocation_departure', 1, 'allocation_held');
    blocked(await req('/api/intercity/bookings/allocation_held/checkout', 'POST', { passengerName: 'Fixture passenger', passengerPhone: '0912000000' }, null, secret));
    assert.equal((await DB.prepare('SELECT payment_status FROM intercity_bookings WHERE id=?').get('allocation_held')).payment_status, 'UNPAID');
  });

  await t.test('company-approved complete intercity drivers may defer phone OTP without bypassing company approval', async () => {
    await completeDriver('allocation_company_driver');
    await DB.prepare("UPDATE onboarding_accounts SET phone_verified_at=NULL,phone_verified_for=NULL WHERE account_id='allocation_company_driver'").run();
    assert.equal(await isOperationallyEligible('allocation_company_driver', 'DRIVER'), false, 'Required final approval still gates operators');
    const approved = await req('/api/companies/operations/drivers/allocation_company_driver/approval', 'PATCH', { action: 'APPROVE' }, 'company');
    assert.equal(approved.status, 200, JSON.stringify(approved.body));
    const state = await getOnboardingReadiness('allocation_company_driver', 'DRIVER');
    assert.equal(state.phoneVerified, false); assert.equal(state.phoneVerificationRequiredForAllocation, false);
    assert.equal(state.reviewStatus, 'APPROVED'); assert.equal(await isOperationallyEligible('allocation_company_driver', 'DRIVER'), true);
    const assignment = await req('/api/companies/operations/assignments', 'POST', { routeId: 'allocation_intercity', driverId: 'allocation_company_driver', vehicleId: 'allocation_coach' }, 'company');
    assert.equal(assignment.status, 201, JSON.stringify(assignment.body));
    blocked(await req('/api/companies/operations/assignments', 'POST', { routeId: 'allocation_bad_intercity', driverId: 'allocation_bad_company_driver', vehicleId: 'allocation_bad_coach' }, 'badCompany'));
  });

  await t.test('old profile and contact endpoints invalidate tracked approval and phone verification', async () => {
    assert.equal((await req('/api/profiles/me', 'PATCH', { vehicleColor: 'White' }, 'daily')).status, 200);
    assert.equal(await isOperationallyEligible('allocation_daily', 'DRIVER'), false);
    assert.equal((await getOnboardingReadiness('allocation_daily', 'DRIVER')).submittedAt, null);
    assert.equal((await req('/api/admin/drivers/allocation_daily/contact', 'PATCH', { phone: '+251911600007' })).status, 200);
    assert.equal((await getOnboardingReadiness('allocation_daily', 'DRIVER')).phoneVerified, false);
    blocked(await req('/api/admin/drivers/allocation_daily/approval', 'PATCH', { status: 'ACTIVE' }));
  });
});
