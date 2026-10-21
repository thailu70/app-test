'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs'), os = require('node:os'), path = require('node:path');
process.env.NODE_ENV = 'test';
delete process.env.DATABASE_URL;
process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), 'passo-onboarding-'));
process.env.UPLOAD_DIR = path.join(process.env.DATA_DIR, 'private-uploads');
process.env.JWT_SECRET = 'isolated-onboarding-regression-secret-not-production';
process.env.ADMIN_REGISTRATION_SECRET = 'isolated-onboarding-admin-secret';
const { DB } = require('../src/db');
const { authenticate, signToken, resolveActivePrincipal } = require('../src/middleware/auth');
const { createOnboardingService, initOnboardingSchema, getOnboardingReadiness, isOperationallyEligible, invalidateOnboarding } = require('../src/services/onboarding');
const { createOnboardingRouter } = require('../src/routes/onboarding');

test('public driver and company onboarding isolates credentials, verifies real delivery and gates review/operations', async t => {
  // Seed the previous schema before running the additive upgrade, including an
  // already operational applicant and a prepared but not approved application.
  await require('../src/services/profile-schema').initProfileSchema();
  await DB.exec(`CREATE TABLE onboarding_accounts (
    account_id TEXT PRIMARY KEY,role TEXT NOT NULL,normalized_phone TEXT NOT NULL UNIQUE,
    phone_verified_at TEXT,phone_verified_for TEXT,submitted_at TEXT,review_status TEXT NOT NULL DEFAULT 'DRAFT',
    reviewed_at TEXT,reviewed_by TEXT,review_note TEXT NOT NULL DEFAULT '',company_address TEXT NOT NULL DEFAULT '',
    company_registration_number TEXT NOT NULL DEFAULT '',created_at TEXT NOT NULL,updated_at TEXT NOT NULL
  )`);
  const legacyTime = new Date().toISOString();
  for (const [id, phone, status, review] of [['legacy_approved','+251912346080','ACTIVE','APPROVED'],['legacy_prepared','+251912346081','PENDING','SUBMITTED']]) {
    await DB.prepare('INSERT INTO users(id,role,fullName,phone,passwordHash,status) VALUES(?,?,?,?,?,?)').run(id, 'DRIVER', 'Legacy Driver', phone, 'unused', status);
    await DB.prepare('INSERT INTO onboarding_accounts(account_id,role,normalized_phone,phone_verified_at,phone_verified_for,submitted_at,review_status,reviewed_at,reviewed_by,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)')
      .run(id, 'DRIVER', phone, legacyTime, phone, legacyTime, review, legacyTime, 'previous_admin', legacyTime, legacyTime);
    await DB.prepare('INSERT INTO user_profiles(user_id,emergency_contact_name,emergency_contact_phone,vehicle_color,updated_at) VALUES(?,?,?,?,?)').run(id, 'Emergency person', '+251912000070', 'White', legacyTime);
    for (const kind of ['LICENSE','VEHICLE_REGISTRATION','INSURANCE','PHOTO']) await DB.prepare('INSERT INTO user_profile_documents(user_id,kind,storage_name,content_type,byte_size,uploaded_at) VALUES(?,?,?,?,?,?)').run(id, kind, 'old-private-file.png', 'image/png', 100, legacyTime);
  }
  await initOnboardingSchema();
  await require('../src/services/master-data').initMasterDataSchema();
  // The full server creates this separate native-registration challenge table at startup.
  await DB.exec('CREATE TABLE IF NOT EXISTS auth_otps(id TEXT PRIMARY KEY,phone TEXT,purpose TEXT,code_hash TEXT,verification_token_hash TEXT,expires_at TIMESTAMP,verified_at TIMESTAMP,attempts INTEGER DEFAULT 0,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)');
  const express = require('express'), app = express();
  let clock = Date.now(), smsAvailable = true;
  const messages = [];
  const service = createOnboardingService({ now: () => clock, sendSms: async (phone, message) => {
    messages.push({ phone, code: /\b(\d{6})\b/.exec(message)?.[1] });
    return { sent: smsAvailable };
  } });
  app.use(express.json({ limit: '1mb' }));
  app.use('/api/onboarding', createOnboardingRouter({ service, rateLimitMax: 1000 }));
  app.use('/limited', createOnboardingRouter({ service, rateLimitMax: 2 }));
  app.use('/api/auth', require('../src/routes/auth'));
  app.get('/private', authenticate, (req, res) => res.json({ success: true, role: req.user.role }));
  await DB.prepare("INSERT INTO users(id,role,fullName,phone,passwordHash,status) VALUES('onb_admin','ADMIN','Review administrator','0900000010','unused','ACTIVE')").run();
  await DB.prepare("INSERT INTO transport_companies(id,name,phone,representative_name,password_hash,status) VALUES('onb_company_a','Approved Company A','0900000020','Representative A','unused','ACTIVE'),('onb_company_b','Approved Company B','0900000021','Representative B','unused','ACTIVE')").run();
  const admin = signToken({ id: 'onb_admin', role: 'ADMIN' }), companyA = signToken({ id: 'onb_company_a', role: 'COMPANY' }), companyB = signToken({ id: 'onb_company_b', role: 'COMPANY' });
  const server = app.listen(0, '127.0.0.1'); await new Promise(resolve => server.once('listening', resolve));
  t.after(() => new Promise(resolve => { server.closeAllConnections(); server.close(resolve); }));
  const base = 'http://127.0.0.1:' + server.address().port;
  async function request(endpoint, method = 'GET', token, body, mime) {
    const response = await fetch(base + endpoint, { method,
      headers: { ...(token ? { Authorization: 'Bearer ' + token } : {}), ...(body !== undefined ? { 'Content-Type': mime || 'application/json' } : {}) },
      ...(body === undefined ? {} : { body: Buffer.isBuffer(body) ? body : JSON.stringify(body) }) });
    const bytes = Buffer.from(await response.arrayBuffer());
    return { status: response.status, headers: response.headers, data: response.headers.get('content-type')?.includes('application/json') ? JSON.parse(bytes.toString()) : null, bytes };
  }
  const api = (suffix, method, token, body, mime) => request('/api/onboarding' + suffix, method, token, body, mime);
  const password = 'Onboarding fixture password';
  const driverInput = (phone = '0912346001', plate = 'ONBOARD-001') => ({ role: 'DRIVER', fullName: 'Applicant Driver', phone, password, licenseNumber: 'LICENSE-1', assignedVehiclePlate: plate, vehicleModel: 'Minivan', vehicleType: 'MINIVAN_8', routeChoice: 'PROPOSED', proposedDeparture: 'Bole', proposedDestination: 'CMC', proposedMonthlyFeeEtb: 50000, proposedMonthlyFeeBasis: 'PER_VEHICLE' });
  const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aB9sAAAAASUVORK5CYII=', 'base64');
  const pdf = Buffer.from('%PDF-1.4\n1 0 obj\n<<>>\nendobj\n%%EOF');
  let driver, company, associated;
  async function accept(account) {
    const result = await api('/applications/DRIVER/' + account.account.id + '/review', 'POST', admin, { decision: 'ACCEPT' });
    assert.equal(result.status, 200, JSON.stringify(result.data));
    assert.equal(result.data.account.status, 'PENDING');
    assert.equal(result.data.onboarding.acceptanceStatus, 'ACCEPTED');
    return result.data;
  }
  async function verify(account) {
    const sent = await api('/request-otp', 'POST', account.token, {});
    assert.equal(sent.status, 200, JSON.stringify(sent.data)); assert.equal(sent.data.delivery, 'SENT');
    const code = messages.at(-1).code;
    assert.equal((await api('/verify-otp', 'POST', account.token, { code })).status, 200);
  }
  async function completeDriver(account) {
    await verify(account);
    for (const kind of ['LICENSE','VEHICLE_REGISTRATION','INSURANCE','PHOTO']) assert.equal((await api('/documents/' + kind, 'PUT', account.token, png, 'image/png')).status, 200);
    assert.equal((await api('/profile', 'PATCH', account.token, { emergencyContactName: 'Emergency Person', emergencyContactPhone: '0912000070', vehicleColor: 'White' })).status, 200);
    assert.equal((await api('/submit', 'POST', account.token, {})).status, 200);
  }

  await t.test('upgrade preserves complete approved drivers and requires initial acceptance for prepared applications', async () => {
    const approved = await getOnboardingReadiness('legacy_approved', 'DRIVER');
    assert.equal(approved.acceptanceStatus, 'ACCEPTED'); assert.ok(approved.acceptedAt);
    assert.equal(approved.ready, true); assert.equal(await isOperationallyEligible('legacy_approved', 'DRIVER'), true);
    const prepared = await getOnboardingReadiness('legacy_prepared', 'DRIVER');
    assert.equal(prepared.acceptedAt, null); assert.equal(prepared.canVerifyAndUpload, false);
    assert.equal(prepared.reviewStatus, 'AWAITING_ACCEPTANCE'); assert.equal(prepared.submittedAt, null);
    assert.ok(prepared.missing.includes('ADMIN_ACCEPTANCE')); assert.ok(prepared.initialMissing.includes('ROUTE_PREFERENCE'));
    assert.equal((await DB.prepare('SELECT COUNT(*) AS count FROM user_profile_documents WHERE user_id=?').get('legacy_prepared')).count, 4, 'Migration keeps uploaded files');
    assert.equal((await api('/applications/DRIVER/legacy_prepared/review', 'POST', admin, { decision: 'ACCEPT' })).status, 428, 'Old prepared applications must provide the route and vehicle fee');
    await assert.rejects(() => service.submit('legacy_prepared', 'DRIVER'), error => error.status === 428);
  });

  await t.test('registration queues initial acceptance without OTP, assignment or operational approval', async () => {
    const result = await api('/register', 'POST', null, driverInput());
    assert.equal(result.status, 201, JSON.stringify(result.data)); driver = result.data;
    assert.equal(driver.account.status, 'PENDING'); assert.equal(driver.account.phone, '+251912346001');
    assert.equal(driver.account.licenseNumber, 'LICENSE-1'); assert.equal(driver.account.vehicleModel, 'Minivan');
    assert.equal(driver.onboarding.phoneVerified, false); assert.equal(driver.onboarding.reviewStatus, 'AWAITING_ACCEPTANCE');
    assert.equal(driver.onboarding.canVerifyAndUpload, false); assert.equal(driver.onboarding.acceptanceRequired, true);
    assert.equal(driver.onboarding.acceptedAt, null); assert.equal(driver.onboarding.submittedAt, null); assert.ok(driver.onboarding.initialSubmittedAt);
    assert.equal(driver.onboarding.routePreference.proposedMonthlyFeeEtb, 50000);
    assert.equal(driver.onboarding.routePreference.proposedMonthlyFeeBasis, 'PER_VEHICLE');
    assert.equal((await DB.prepare('SELECT appliedRouteId FROM users WHERE id=?').get(driver.account.id)).appliedRouteId, '');
    assert.equal((await DB.prepare('SELECT assignedRouteId FROM vehicles WHERE driverId=?').get(driver.account.id)).assignedRouteId, null);
    assert.equal(messages.length, 0, 'Registration never sends an unsolicited code');
    assert.equal((await api('/login', 'POST', null, { role: 'DRIVER', phone: '+251912346001', password })).status, 200);
    assert.equal((await api('/register', 'POST', null, driverInput('+251912346001', 'DIFFERENT-PLATE'))).status, 409);
    assert.equal((await api('/register', 'POST', null, { ...driverInput(), role: 'ADMIN' })).status, 400);
    assert.equal((await request('/private', 'GET', driver.token)).status, 401);
    await DB.prepare("UPDATE users SET status='ACTIVE' WHERE id=?").run(driver.account.id);
    assert.equal(await resolveActivePrincipal(driver.token), null, 'Scoped token never becomes an operational credential');
    assert.equal(await resolveActivePrincipal(signToken({ id: driver.account.id, role: 'DRIVER' })), null, 'An ACTIVE flag alone cannot bypass onboarding');
    assert.equal((await api('/request-otp', 'POST', driver.token, {})).status, 428);
    assert.equal((await api('/verify-otp', 'POST', driver.token, { code: '123456' })).status, 428);
    assert.equal((await api('/documents/LICENSE', 'PUT', driver.token, png, 'image/png')).status, 428);
    assert.equal(messages.length, 0, 'Unaccepted requests cannot trigger delivery');
    assert.equal((await api('/applications/DRIVER/' + driver.account.id + '/review', 'POST', admin, { decision: 'APPROVE' })).status, 428);
    const queued = await api('/submit', 'POST', driver.token, {});
    assert.equal(queued.status, 200); assert.equal(queued.data.onboarding.reviewStatus, 'AWAITING_ACCEPTANCE');
    assert.equal(queued.data.onboarding.canVerifyAndUpload, false);
  });

  await t.test('existing native registration still requires OTP in production', async () => {
    process.env.NODE_ENV = 'production';
    try { assert.equal((await request('/api/auth/register', 'POST', null, driverInput('0912346020','NATIVE-OTP'))).status, 428); }
    finally { process.env.NODE_ENV = 'test'; }
  });

  await t.test('daily applications validate route ownership, distinct places and whole-vehicle monthly proposals', async () => {
    await DB.prepare('INSERT INTO routes(id,name,nameAm,routeType,companyId,active,basePriceEtb) VALUES(?,?,?,?,?,?,?)').run('onb_route', 'Bole to CMC', 'Route', 'DAILY_COMMUTE', '', 1, 2500);
    await DB.prepare('INSERT INTO routes(id,name,nameAm,routeType,companyId,active) VALUES(?,?,?,?,?,?)').run('onb_company_route', 'Company route', 'Route', 'DAILY_COMMUTE', 'onb_company_a', 1);
    await DB.prepare('INSERT INTO routes(id,name,nameAm,routeType,companyId,active) VALUES(?,?,?,?,?,?)').run('onb_intercity_route', 'Intercity route', 'Route', 'INTERCITY', '', 1);
    await DB.prepare('INSERT INTO routes(id,name,nameAm,routeType,companyId,active) VALUES(?,?,?,?,?,?)').run('onb_inactive_route', 'Inactive route', 'Route', 'DAILY_COMMUTE', '', 0);
    const input = driverInput('0912346030', 'ONBOARD-EXISTING');
    for (const proposal of [
      { routeChoice: '', proposedMonthlyFeeEtb: 50000 },
      { proposedDeparture: ' Bole ', proposedDestination: 'bole' },
      { proposedDestination: '!!' }, { proposedMonthlyFeeEtb: 0 },
      { proposedMonthlyFeeEtb: 1000000.01 }, { proposedMonthlyFeeEtb: 100.001 },
      { proposedMonthlyFeeEtb: '100.999' }, { proposedMonthlyFeeBasis: 'PER_PASSENGER' },
      ...['missing_route','onb_company_route','onb_intercity_route','onb_inactive_route'].map(appliedRouteId => ({ routeChoice: 'EXISTING', appliedRouteId }))
    ]) {
      const result = await api('/register', 'POST', null, { ...input, ...proposal });
      assert.equal(result.status, 400, JSON.stringify({ proposal, response: result.data }));
    }
    const created = await api('/register', 'POST', null, { ...input, routeChoice: 'EXISTING', appliedRouteId: 'onb_route', proposedMonthlyFeeEtb: '50000.25' });
    assert.equal(created.status, 201, JSON.stringify(created.data)); const applicant = created.data;
    assert.equal(applicant.onboarding.routePreference.routeId, 'onb_route');
    assert.equal(applicant.onboarding.routePreference.routeName, 'Bole to CMC');
    assert.equal(applicant.onboarding.routePreference.proposedMonthlyFeeEtb, 50000.25);
    assert.equal((await DB.prepare('SELECT basePriceEtb FROM routes WHERE id=?').get('onb_route')).basePriceEtb, 2500, 'A proposal never changes passenger fares');
    await DB.prepare('UPDATE routes SET active=FALSE WHERE id=?').run('onb_route');
    assert.equal((await api('/applications/DRIVER/' + applicant.account.id + '/review', 'POST', admin, { decision: 'ACCEPT' })).status, 400, 'Acceptance rechecks current route availability');
    await DB.prepare('UPDATE routes SET active=TRUE WHERE id=?').run('onb_route');
    await accept(applicant);
    assert.equal((await DB.prepare('SELECT assignedRouteId FROM vehicles WHERE driverId=?').get(applicant.account.id)).assignedRouteId, null, 'Acceptance does not allocate a route');
  });

  await t.test('rejected initial applications can resubmit unchanged; changed route or fee requires new acceptance', async () => {
    const registered = await api('/register', 'POST', null, driverInput('0912346031','ONBOARD-REJECT'));
    assert.equal(registered.status, 201); const applicant = registered.data;
    const reviewUrl = '/applications/DRIVER/' + applicant.account.id + '/review';
    const rejected = await api(reviewUrl, 'POST', admin, { decision: 'REJECT', note: 'Please confirm route availability.' });
    assert.equal(rejected.status, 200); assert.equal(rejected.data.onboarding.acceptanceStatus, 'REJECTED');
    assert.equal(rejected.data.onboarding.canInitiallySubmit, true);
    assert.equal((await api('/request-otp', 'POST', applicant.token, {})).status, 428);
    assert.equal((await api('/documents/PHOTO', 'PUT', applicant.token, png, 'image/png')).status, 428);
    const resubmitted = await api('/submit', 'POST', applicant.token, {});
    assert.equal(resubmitted.status, 200); assert.equal(resubmitted.data.onboarding.reviewStatus, 'AWAITING_ACCEPTANCE');
    assert.equal(resubmitted.data.onboarding.acceptedAt, null); assert.equal(resubmitted.data.onboarding.canVerifyAndUpload, false);
    await accept(applicant);
    assert.equal((await api('/request-otp', 'POST', applicant.token, {})).status, 200);
    const oldCode = messages.at(-1).code;
    const changed = await api('/profile', 'PATCH', applicant.token, { proposedDestination: 'Lebu', proposedMonthlyFeeEtb: 60000 });
    assert.equal(changed.status, 200); assert.equal(changed.data.onboarding.routePreference.destination, 'Lebu');
    assert.equal(changed.data.onboarding.routePreference.proposedMonthlyFeeEtb, 60000);
    assert.equal(changed.data.onboarding.acceptedAt, null); assert.equal(changed.data.onboarding.canVerifyAndUpload, false);
    assert.equal((await api('/verify-otp', 'POST', applicant.token, { code: oldCode })).status, 428);
    assert.equal(await DB.prepare('SELECT * FROM onboarding_otps WHERE account_id=?').get(applicant.account.id), undefined, 'Route/fee changes discard the old challenge');
    await accept(applicant);
    assert.equal((await api('/verify-otp', 'POST', applicant.token, { code: oldCode })).status, 400, 'A previous acceptance challenge cannot survive replacement');
    await DB.transaction(async tx => {
      await tx.prepare('UPDATE users SET phone=? WHERE id=?').run('+251912346032', applicant.account.id);
      await invalidateOnboarding(applicant.account.id, 'DRIVER', tx, { phoneChanged: true });
    });
    const contactChanged = await api('/me', 'GET', applicant.token);
    assert.equal(contactChanged.data.onboarding.acceptedAt, null);
    assert.equal((await DB.prepare('SELECT normalized_phone FROM onboarding_accounts WHERE account_id=?').get(applicant.account.id)).normalized_phone, '+251912346032', 'Existing contact editors keep onboarding phone ownership synchronized');
    assert.equal((await api('/login', 'POST', null, { role: 'DRIVER', phone: '0912346032', password })).status, 200);
    await accept(applicant);
    await verify(applicant); assert.equal(messages.at(-1).phone, '+251912346032');
  });

  await t.test('OTP is account-bound, rate limited, expires, and consumes a successful code exactly once', async () => {
    await accept(driver);
    assert.equal((await api('/applications/DRIVER/' + driver.account.id + '/review', 'POST', companyA, { decision: 'ACCEPT' })).status, 404);
    const sent = await api('/request-otp', 'POST', driver.token, { phone: '0912999999' });
    assert.equal(sent.status, 200); assert.equal(sent.data.delivery, 'SENT');
    assert.equal(messages.at(-1).phone, '+251912346001');
    assert.equal(sent.data.testCode, undefined); assert.equal(sent.data.code, undefined);
    assert.equal((await api('/request-otp', 'POST', driver.token, {})).status, 429);
    const old = messages.at(-1).code;
    const wrong = old === '000000' ? '111111' : '000000';
    const failures = await Promise.all(Array.from({ length: 6 }, () => api('/verify-otp', 'POST', driver.token, { code: wrong })));
    assert.ok(failures.every(result => result.status === 400));
    assert.equal((await DB.prepare('SELECT attempts FROM onboarding_otps WHERE account_id=?').get(driver.account.id)).attempts, 5);
    assert.equal((await api('/verify-otp', 'POST', driver.token, { code: old })).status, 400);
    clock += 61000;
    assert.equal((await api('/request-otp', 'POST', driver.token, {})).status, 200);
    const expired = messages.at(-1).code; clock += 10 * 60000 + 1;
    assert.equal((await api('/verify-otp', 'POST', driver.token, { code: expired })).status, 400);
    assert.equal((await api('/request-otp', 'POST', driver.token, {})).status, 200);
    const current = messages.at(-1).code;
    assert.equal((await api('/verify-otp', 'POST', driver.token, { code: current })).status, 200);
    assert.equal((await api('/verify-otp', 'POST', driver.token, { code: current })).status, 400);
  });

  await t.test('private document validation and profile completion preserve the approval gate', async () => {
    const acceptedAt = (await api('/me', 'GET', driver.token)).data.onboarding.acceptedAt;
    assert.equal((await api('/documents/LICENSE', 'PUT', driver.token, Buffer.from('<html>bad</html>'), 'image/png')).status, 415);
    assert.equal((await api('/documents/PHOTO', 'PUT', driver.token, pdf, 'application/pdf')).status, 415);
    assert.equal((await api('/documents/LICENSE', 'PUT', driver.token, Buffer.alloc(5 * 1024 * 1024 + 1), 'image/png')).status, 413);
    for (const kind of ['LICENSE','VEHICLE_REGISTRATION','INSURANCE','PHOTO']) {
      const result = await api('/documents/' + kind, 'PUT', driver.token, png, 'image/png');
      assert.equal(result.status, 200, JSON.stringify(result.data));
      assert.equal(JSON.stringify(result.data).includes('storage_name'), false);
    }
    assert.equal((await api('/documents/LICENSE')).status, 401);
    const document = await api('/documents/LICENSE', 'GET', driver.token);
    assert.deepEqual(document.bytes, png); assert.equal(document.headers.get('cache-control'), 'private, no-store');
    assert.equal(document.headers.get('x-content-type-options'), 'nosniff');
    assert.equal((await api('/profile', 'PATCH', driver.token, { role: 'ADMIN', status: 'ACTIVE' })).status, 400);
    assert.equal((await api('/submit', 'POST', driver.token, {})).status, 428);
    assert.equal((await api('/profile', 'PATCH', driver.token, { emergencyContactName: 'Emergency Person', emergencyContactPhone: '0912000070', vehicleColor: 'White' })).status, 200);
    assert.equal((await api('/me', 'GET', driver.token)).data.onboarding.acceptedAt, acceptedAt, 'First-time document/profile completion preserves initial acceptance');
    assert.equal((await api('/applications/DRIVER/' + driver.account.id + '/review', 'POST', admin, { decision: 'APPROVE' })).status, 428, 'Completeness is not submission');
    const submitted = await api('/submit', 'POST', driver.token, {});
    assert.equal(submitted.status, 200); assert.equal(submitted.data.account.status, 'PENDING');
    assert.equal(submitted.data.onboarding.reviewStatus, 'SUBMITTED');
    const approved = await api('/applications/DRIVER/' + driver.account.id + '/review', 'POST', admin, { decision: 'APPROVE' });
    assert.equal(approved.status, 200, JSON.stringify(approved.data));
    assert.equal(approved.data.account.status, 'ACTIVE'); assert.equal(approved.data.onboarding.reviewStatus, 'APPROVED');
    assert.equal(await isOperationallyEligible(driver.account.id, 'DRIVER'), true);
    assert.equal((await request('/private', 'GET', driver.token)).status, 401, 'Approval does not broaden the onboarding JWT');
    assert.equal((await request('/private', 'GET', signToken({ id: driver.account.id, role: 'DRIVER' }))).status, 200);
  });

  await t.test('new companies register publicly and require phone, private documents and administrator review', async () => {
    const registered = await api('/register', 'POST', null, { role: 'COMPANY', fullName: 'Company Representative', companyName: 'Public Applicant Transport', phone: '0912346010', password });
    assert.equal(registered.status, 201, JSON.stringify(registered.data)); company = registered.data;
    assert.equal(company.account.status, 'PENDING'); assert.equal(company.onboarding.requiredDocuments.length, 3);
    assert.equal(company.onboarding.acceptanceRequired, false); assert.equal(company.onboarding.canVerifyAndUpload, true);
    assert.equal((await api('/register', 'POST', null, { role: 'COMPANY', fullName: 'Duplicate', companyName: 'Other Transport', phone: '+251912346001', password })).status, 409);
    assert.equal((await api('/documents/LICENSE', 'GET', company.token)).status, 404);
    assert.equal((await api('/login', 'POST', null, { role: 'COMPANY', phone: '+251912346010', password })).status, 200);
    await verify(company);
    for (const kind of company.onboarding.requiredDocuments) assert.equal((await api('/documents/' + kind, 'PUT', company.token, pdf, 'application/pdf')).status, 200);
    assert.equal((await api('/submit', 'POST', company.token, {})).status, 200);
    assert.equal((await api('/applications/COMPANY/' + company.account.id + '/review', 'POST', companyA, { decision: 'APPROVE' })).status, 404);
    assert.equal((await api('/applications/COMPANY/' + company.account.id + '/review', 'POST', admin, { decision: 'APPROVE' })).status, 200);
    assert.equal(await isOperationallyEligible(company.account.id, 'COMPANY'), true);
  });

  await t.test('review access follows the existing company boundary and scoped pagination', async () => {
    const result = await api('/register', 'POST', null, { role: 'DRIVER', fullName: 'Company Applicant', phone: '0912346011', password, licenseNumber: 'COMPANY-LICENSE', serviceType: 'INTERCITY', companyId: 'onb_company_a' });
    assert.equal(result.status, 201, JSON.stringify(result.data)); associated = result.data;
    assert.equal(associated.onboarding.acceptanceRequired, false); assert.equal(associated.onboarding.canVerifyAndUpload, true);
    assert.equal((await api('/applications/DRIVER/' + associated.account.id + '/review', 'POST', companyA, { decision: 'ACCEPT' })).status, 403);
    assert.equal((await DB.prepare('SELECT COUNT(*) AS count FROM vehicles WHERE driverId=?').get(associated.account.id)).count, 0, 'Company drivers cannot register their own company vehicle');
    await completeDriver(associated);
    const url = '/applications/DRIVER/' + associated.account.id;
    assert.equal((await api(url, 'GET', admin)).status, 404);
    assert.equal((await api(url, 'GET', companyB)).status, 404);
    assert.equal((await api(url, 'GET', companyA)).status, 200);
    assert.equal((await api(url + '/documents/LICENSE', 'GET', companyB)).status, 404);
    assert.equal((await api(url + '/documents/LICENSE', 'GET', companyA)).status, 200);
    const own = await api('/applications?limit=1', 'GET', companyA);
    assert.deepEqual(own.data.applications.map(item => item.id), [associated.account.id]);
    assert.equal((await api(url + '/review', 'POST', companyA, { decision: 'APPROVE' })).status, 200);
    const first = await api('/applications?limit=1', 'GET', admin);
    assert.equal(first.data.applications.length, 1); assert.ok(first.data.nextCursor);
    const second = await api('/applications?limit=1&cursor=' + first.data.nextCursor, 'GET', admin);
    assert.equal(second.data.applications.length, 1); assert.notEqual(second.data.applications[0].id, first.data.applications[0].id);
    assert.equal((await api('/applications?limit=101', 'GET', admin)).status, 400);
    assert.equal((await api('/applications', 'GET', driver.token)).status, 401);
  });

  await t.test('replacing reviewed documents or phone revokes submission and requires a fresh review', async () => {
    const replaced = await api('/documents/PHOTO', 'PUT', driver.token, png, 'image/png');
    assert.equal(replaced.data.account.status, 'PENDING'); assert.equal(replaced.data.onboarding.submittedAt, null);
    assert.equal(replaced.data.onboarding.phoneVerified, true); assert.equal(await isOperationallyEligible(driver.account.id, 'DRIVER'), false);
    assert.equal((await api('/submit', 'POST', driver.token, {})).status, 200);
    assert.equal((await api('/applications/DRIVER/' + driver.account.id + '/review', 'POST', admin, { decision: 'APPROVE' })).status, 200);
    const changed = await api('/profile', 'PATCH', driver.token, { phone: '0912346002' });
    assert.equal(changed.status, 200); assert.equal(changed.data.onboarding.phoneVerified, false);
    assert.equal(changed.data.onboarding.reviewStatus, 'AWAITING_ACCEPTANCE'); assert.equal(changed.data.account.status, 'PENDING');
    assert.equal(changed.data.onboarding.acceptedAt, null); assert.equal(changed.data.onboarding.canVerifyAndUpload, false);
    assert.equal((await api('/submit', 'POST', driver.token, {})).status, 200);
    assert.equal((await api('/request-otp', 'POST', driver.token, {})).status, 428);
    assert.equal((await api('/login', 'POST', null, { role: 'DRIVER', phone: '0912346001', password })).status, 401);
    assert.equal((await api('/login', 'POST', null, { role: 'DRIVER', phone: '+251912346002', password })).status, 200);
    await accept(driver); await verify(driver); assert.equal(messages.at(-1).phone, '+251912346002');
  });

  await t.test('failed SMS delivery is not verification and IP throttles also cover failed attempts', async () => {
    const result = await api('/register', 'POST', null, driverInput('0912346012','ONBOARD-SMS-FAIL'));
    assert.equal(result.status, 201); const applicant = result.data;
    await accept(applicant);
    smsAvailable = false;
    const sent = await api('/request-otp', 'POST', applicant.token, {});
    assert.equal(sent.status, 503); assert.equal(sent.data.success, false);
    const code = messages.at(-1).code;
    assert.equal((await api('/verify-otp', 'POST', applicant.token, { code })).status, 400);
    assert.equal((await api('/me', 'GET', applicant.token)).data.onboarding.phoneVerified, false);
    smsAvailable = true;
    for (let index = 0; index < 2; index++) assert.equal((await request('/limited/login', 'POST', null, { role: 'DRIVER', phone: '0912346012', password: 'wrong' })).status, 401);
    assert.equal((await request('/limited/login', 'POST', null, { role: 'DRIVER', phone: '0912346012', password: 'wrong' })).status, 429);
    assert.equal(await getOnboardingReadiness('usr_drv_kassahun', 'DRIVER').then(result => result.tracked), false, 'Existing accounts are not retrospectively enrolled');
    assert.equal(await isOperationallyEligible('onb_company_a', 'COMPANY'), true);
  });

  await t.test('hourly resend cap survives replacement challenges and binds codes to one account', async () => {
    const result = await api('/register', 'POST', null, driverInput('0912346013','ONBOARD-RESEND'));
    assert.equal(result.status, 201); const applicant = result.data;
    await accept(applicant);
    for (let index = 0; index < 5; index++) {
      assert.equal((await api('/request-otp', 'POST', applicant.token, {})).status, 200);
      assert.equal((await api('/verify-otp', 'POST', driver.token, { code: messages.at(-1).code })).status, 400);
      clock += 61000;
    }
    assert.equal((await api('/request-otp', 'POST', applicant.token, {})).status, 429);
  });

  await t.test('old scoped sessions cannot revive suspended deleted or inactive accounts', async () => {
    const result = await api('/register', 'POST', null, driverInput('0912346014','ONBOARD-DISABLED'));
    assert.equal(result.status, 201); const applicant = result.data;
    for (const status of ['SUSPENDED','DELETED','INACTIVE']) {
      await DB.prepare('UPDATE users SET status=? WHERE id=?').run(status, applicant.account.id);
      assert.equal((await api('/login', 'POST', null, { role: 'DRIVER', phone: '0912346014', password })).status, 401);
      assert.equal((await api('/me', 'GET', applicant.token)).status, 403);
      assert.equal((await api('/request-otp', 'POST', applicant.token, {})).status, 403);
      assert.equal((await api('/profile', 'PATCH', applicant.token, { vehicleColor: 'Red' })).status, 403);
      assert.equal((await api('/documents/PHOTO', 'PUT', applicant.token, png, 'image/png')).status, 403);
      assert.equal((await api('/submit', 'POST', applicant.token, {})).status, 403);
      assert.equal((await api('/applications/DRIVER/' + applicant.account.id + '/review', 'POST', admin, { decision: 'APPROVE' })).status, 403);
      await assert.rejects(() => service.updateProfile(applicant.account.id, 'DRIVER', { vehicleColor: 'Red' }), error => error.status === 403);
      assert.equal((await DB.prepare('SELECT status FROM users WHERE id=?').get(applicant.account.id)).status, status);
    }
    await DB.prepare("UPDATE transport_companies SET status='INACTIVE' WHERE id=?").run(company.account.id);
    assert.equal((await api('/login', 'POST', null, { role: 'COMPANY', phone: '0912346010', password })).status, 401);
    assert.equal((await api('/profile', 'PATCH', company.token, { address: 'Changed address' })).status, 403);
    assert.equal((await DB.prepare('SELECT status FROM transport_companies WHERE id=?').get(company.account.id)).status, 'INACTIVE');
    assert.equal((await api('/applications', 'GET', admin)).status, 200, 'Reviewers may still inspect disabled records');
  });

  await t.test('only an explicitly deleted and anonymized account frees its phone for a fresh application', async () => {
    const first = await api('/register', 'POST', null, driverInput('0912346015', 'ONBOARD-OLD-PHONE'));
    assert.equal(first.status, 201); const old = first.data;
    await DB.prepare("UPDATE users SET status='DELETED',phone='not-the-delete-marker' WHERE id=?").run(old.account.id);
    const input = { ...driverInput('0912346015', 'ONBOARD-NEW-PHONE'), password: 'A different new application password' };
    assert.equal((await api('/register', 'POST', null, input)).status, 409, 'A disabled account alone cannot release verification ownership');
    await DB.prepare("UPDATE users SET phone=? WHERE id=? AND status='DELETED'").run('deleted:' + old.account.id, old.account.id);
    const created = await api('/register', 'POST', null, input);
    assert.equal(created.status, 201, JSON.stringify(created.data));
    assert.notEqual(created.data.account.id, old.account.id);
    assert.equal(created.data.account.status, 'PENDING'); assert.equal(created.data.onboarding.phoneVerified, false);
    assert.equal(created.data.onboarding.reviewStatus, 'AWAITING_ACCEPTANCE'); assert.equal(created.data.onboarding.documents.length, 0);
    assert.ok(created.data.onboarding.missing.includes('PHONE_VERIFICATION'));
    assert.equal((await api('/submit', 'POST', created.data.token, {})).status, 200);
    assert.equal((await api('/request-otp', 'POST', created.data.token, {})).status, 428);
    assert.equal((await api('/documents/PHOTO', 'PUT', created.data.token, png, 'image/png')).status, 428);
    assert.equal((await api('/me', 'GET', old.token)).status, 403);
    assert.equal((await api('/login', 'POST', null, { role: 'DRIVER', phone: '0912346015', password })).status, 401);
    assert.equal((await DB.prepare('SELECT status FROM users WHERE id=?').get(old.account.id)).status, 'DELETED');
  });
});
