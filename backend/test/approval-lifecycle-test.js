'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs'), os = require('node:os'), path = require('node:path');
process.env.NODE_ENV = 'test';
delete process.env.DATABASE_URL;
process.env.JWT_SECRET = 'isolated-approval-lifecycle-secret';
process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), 'passo-approval-lifecycle-'));
process.env.UPLOAD_DIR = path.join(process.env.DATA_DIR, 'uploads');
const { DB } = require('../src/db');
const { signToken, authenticate } = require('../src/middleware/auth');
const { initOnboardingSchema, getOnboardingReadiness, isOperationallyEligible, REQUIRED } = require('../src/services/onboarding');

test('driver application approval, corrections and suspension have separate lifecycles', async t => {
  const password = 'Approval lifecycle fixture password';
  const hash = await require('bcryptjs').hash(password, 4);
  for (const [id, phone, status, company] of [
    ['repair_pending_daily', '+251911770001', 'PENDING', ''],
    ['repair_pending_company', '+251911770002', 'PENDING', 'lifecycle_company'],
    ['retain_active_legacy', '+251911770003', 'ACTIVE', '']
  ]) await DB.prepare("INSERT INTO users(id,role,fullName,phone,passwordHash,status,companyId) VALUES(?,'DRIVER',?,?,?,?,?)")
    .run(id, id, phone, hash, status, company);
  await initOnboardingSchema();
  await require('../src/services/master-data').initMasterDataSchema();
  for (const [id, phone] of [['lifecycle_company', '+251911770004'], ['lifecycle_other_company', '+251911770005']]) {
    await DB.prepare("INSERT INTO transport_companies(id,name,phone,status) VALUES(?,?,?,'ACTIVE')").run(id, id, phone);
  }
  const express = require('express'), app = express();
  app.use(express.json());
  app.use('/api/auth', require('../src/routes/auth'));
  app.use('/api/admin', require('../src/routes/admin'));
  app.use('/api/companies', require('../src/routes/company-portal'));
  app.use('/api/onboarding', require('../src/routes/onboarding').createOnboardingRouter({ rateLimitMax: 1000 }));
  app.get('/private', authenticate, (req, res) => res.json({ id: req.user.id }));
  const server = app.listen(0, '127.0.0.1');
  await new Promise(resolve => server.once('listening', resolve));
  t.after(() => new Promise(resolve => { server.closeAllConnections(); server.close(resolve); }));
  const base = 'http://127.0.0.1:' + server.address().port;
  const admin = signToken({ id: 'usr_adm_root', role: 'ADMIN' });
  const company = signToken({ id: 'lifecycle_company', role: 'COMPANY' });
  const otherCompany = signToken({ id: 'lifecycle_other_company', role: 'COMPANY' });
  async function request(endpoint, method = 'GET', token, body, mime) {
    const response = await fetch(base + endpoint, { method,
      headers: { ...(token ? { Authorization: 'Bearer ' + token } : {}), ...(body === undefined ? {} : { 'Content-Type': mime || 'application/json' }) },
      ...(body === undefined ? {} : { body: Buffer.isBuffer(body) ? body : JSON.stringify(body) }) });
    return { status: response.status, data: await response.json() };
  }
  const review = (id, decision, note = '', actor = admin) => request('/api/onboarding/applications/DRIVER/' + id + '/review', 'POST', actor, { decision, note });
  const legacy = (id, status) => request('/api/admin/drivers/' + id + '/approval', 'PATCH', admin, { status });
  const companyApproval = (id, action) => request('/api/companies/operations/drivers/' + id + '/approval', 'PATCH', company, { action });
  const stored = id => DB.prepare('SELECT * FROM onboarding_accounts WHERE account_id=?').get(id);
  const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aB9sAAAAASUVORK5CYII=', 'base64');
  async function login(phone) {
    const result = await request('/api/onboarding/login', 'POST', null, { role: 'DRIVER', phone, password });
    assert.equal(result.status, 200, JSON.stringify(result.data));
    return result.data.token;
  }
  async function documentsAndProfile(token) {
    for (const kind of REQUIRED.DRIVER) assert.equal((await request('/api/onboarding/documents/' + kind, 'PUT', token, png, 'image/png')).status, 200);
    assert.equal((await request('/api/onboarding/profile', 'PATCH', token,
      { emergencyContactName: 'Fixture emergency', emergencyContactPhone: '+251911779999', vehicleColor: 'Green' })).status, 200);
  }
  let daily, dailyToken, companyDriver, companyDriverToken;

  await t.test('startup enrolls older pending app drivers and preserves active legacy drivers', async () => {
    for (const id of ['repair_pending_daily', 'repair_pending_company']) {
      const state = await getOnboardingReadiness(id, 'DRIVER');
      assert.equal(state.tracked, true); assert.equal(state.ready, false);
      assert.equal(await isOperationallyEligible(id, 'DRIVER'), false);
      assert.ok(state.missing.includes('LICENSE'));
    }
    assert.equal((await getOnboardingReadiness('repair_pending_daily', 'DRIVER')).acceptanceRequired, true);
    assert.equal((await getOnboardingReadiness('repair_pending_company', 'DRIVER')).acceptanceRequired, false);
    assert.equal((await request('/api/onboarding/login', 'POST', null, { role: 'DRIVER', phone: '0911770001', password })).status, 200);
    const legacyState = await getOnboardingReadiness('retain_active_legacy', 'DRIVER');
    assert.equal(legacyState.tracked, false); assert.equal(await isOperationallyEligible('retain_active_legacy', 'DRIVER'), true);
    await DB.prepare("INSERT INTO users(id,role,fullName,phone,passwordHash,status) VALUES('late_untracked_pending','DRIVER','Late fixture','+251911770006','unused','PENDING')").run();
    const late = await getOnboardingReadiness('late_untracked_pending', 'DRIVER');
    assert.equal(late.reviewReady, false); assert.ok(late.reviewMissing.includes('ONBOARDING_ENROLLMENT'));
    assert.equal((await legacy('late_untracked_pending', 'ACTIVE')).status, 409);
  });

  await t.test('native daily signup cannot bypass acceptance, required documents or final approval', async () => {
    const created = await request('/api/auth/register', 'POST', null, {
      role: 'DRIVER', fullName: 'Lifecycle daily driver', phone: '0911770010', password, licenseNumber: 'LIFE-LICENSE',
      assignedVehiclePlate: 'LIFE-DAILY', vehicleModel: 'Fixture van', vehicleType: 'MINIVAN_8',
      routeChoice: 'PROPOSED', proposedDeparture: 'Lifecycle North', proposedDestination: 'Lifecycle South',
      proposedMonthlyFeeEtb: 45000, proposedMonthlyFeeBasis: 'PER_VEHICLE'
    });
    assert.equal(created.status, 201, JSON.stringify(created.data)); daily = created.data.user;
    assert.equal(created.data.token, null); assert.equal(created.data.onboarding.tracked, true);
    assert.equal(created.data.onboarding.phoneVerified, false); assert.equal(daily.status, 'PENDING');
    dailyToken = await login('0911770010');
    assert.equal((await review(daily.id, 'APPROVE')).status, 428);
    assert.equal((await legacy(daily.id, 'ACTIVE')).status, 409);
    assert.equal((await request('/api/onboarding/documents/LICENSE', 'PUT', dailyToken, png, 'image/png')).status, 428);
    assert.equal((await review(daily.id, 'ACCEPT')).status, 200);
    assert.equal((await request('/api/onboarding/submit', 'POST', dailyToken, {})).status, 428);
    await documentsAndProfile(dailyToken);
    assert.equal((await legacy(daily.id, 'ACTIVE')).status, 409, 'Uploading documents is not submission or final approval');
    assert.equal((await request('/api/onboarding/submit', 'POST', dailyToken, {})).status, 200);
    const approved = await review(daily.id, 'APPROVE');
    assert.equal(approved.status, 200, JSON.stringify(approved.data)); assert.equal(approved.data.account.status, 'ACTIVE');
    assert.equal(approved.data.onboarding.phoneVerified, false, 'No approval fabricates verification');
    assert.equal(await isOperationallyEligible(daily.id, 'DRIVER'), true);
  });

  await t.test('saving an identical or equivalent phone preserves every approval field', async () => {
    const before = await stored(daily.id);
    for (const phone of ['+251911770010', '0911770010', '+251 911 770 010']) {
      const result = await request('/api/admin/drivers/' + daily.id + '/contact', 'PATCH', admin, { phone });
      assert.equal(result.status, 200); assert.equal(result.data.driver.unchanged, true);
      assert.deepEqual(await stored(daily.id), before);
      assert.equal(await isOperationallyEligible(daily.id, 'DRIVER'), true);
    }
  });

  await t.test('suspend and reinstate preserve approval while blocking all suspended sessions', async () => {
    const before = await stored(daily.id), operationalToken = signToken({ id: daily.id, role: 'DRIVER' });
    assert.equal((await request('/private', 'GET', operationalToken)).status, 200);
    const paused = await legacy(daily.id, 'SUSPENDED');
    assert.equal(paused.status, 200); assert.equal(paused.data.driver.status, 'SUSPENDED');
    assert.deepEqual(await stored(daily.id), before);
    assert.equal((await request('/private', 'GET', operationalToken)).status, 401);
    assert.equal((await request('/api/onboarding/me', 'GET', dailyToken)).status, 403);
    const restored = await legacy(daily.id, 'ACTIVE');
    assert.equal(restored.status, 200); assert.equal(restored.data.driver.status, 'ACTIVE');
    assert.deepEqual(await stored(daily.id), before); assert.equal((await request('/private', 'GET', operationalToken)).status, 200);
    assert.equal((await review(daily.id, 'SUSPEND')).status, 200);
    const outboxBeforeRepeat = (await DB.prepare('SELECT COUNT(*) AS count FROM application_notification_outbox WHERE account_id=?').get(daily.id)).count;
    assert.equal((await review(daily.id, 'SUSPEND')).status, 200);
    assert.equal((await DB.prepare('SELECT COUNT(*) AS count FROM application_notification_outbox WHERE account_id=?').get(daily.id)).count, outboxBeforeRepeat);
    const reviewedRestore = await review(daily.id, 'REACTIVATE');
    assert.equal(reviewedRestore.status, 200); assert.equal(reviewedRestore.data.account.status, 'ACTIVE');
    assert.deepEqual(await stored(daily.id), before);
  });

  await t.test('document corrections keep accepted route, upload access and correction instructions', async () => {
    assert.equal((await review(daily.id, 'CORRECTIONS')).status, 400);
    const before = await stored(daily.id), note = 'Upload a clear current driving licence.';
    const corrected = await review(daily.id, 'CORRECTIONS', note);
    assert.equal(corrected.status, 200, JSON.stringify(corrected.data));
    assert.equal(corrected.data.account.status, 'PENDING'); assert.equal(corrected.data.onboarding.reviewStatus, 'CORRECTIONS_REQUESTED');
    assert.equal(corrected.data.onboarding.acceptanceStatus, 'ACCEPTED'); assert.equal(corrected.data.onboarding.canVerifyAndUpload, true);
    assert.equal(corrected.data.onboarding.submittedAt, null);
    assert.equal((await request('/api/onboarding/documents/LICENSE', 'PUT', dailyToken, png, 'image/png')).status, 200);
    const afterUpload = await stored(daily.id);
    assert.equal(afterUpload.review_status, 'CORRECTIONS_REQUESTED'); assert.equal(afterUpload.review_note, note);
    assert.equal(afterUpload.accepted_at, before.accepted_at); assert.equal(afterUpload.route_name, before.route_name);
    assert.equal((await legacy(daily.id, 'ACTIVE')).status, 409);
    assert.equal((await request('/api/onboarding/submit', 'POST', dailyToken, {})).status, 200);
    assert.equal((await review(daily.id, 'APPROVE')).status, 200);
    assert.equal(await isOperationallyEligible(daily.id, 'DRIVER'), true);
  });

  await t.test('company app drivers are tracked, ownership checked and suspension reversible', async () => {
    const created = await request('/api/auth/register', 'POST', null, { role: 'DRIVER', fullName: 'Lifecycle company driver',
      phone: '0911770020', password, licenseNumber: 'LIFE-COACH', companyId: 'lifecycle_company' });
    assert.equal(created.status, 201, JSON.stringify(created.data)); companyDriver = created.data.user;
    assert.equal(created.data.token, null); assert.equal(created.data.onboarding.tracked, true);
    assert.equal(created.data.onboarding.acceptanceRequired, false);
    companyDriverToken = await login('0911770020');
    assert.equal((await companyApproval(companyDriver.id, 'APPROVE')).status, 409);
    assert.equal((await request('/api/companies/operations/drivers/' + companyDriver.id + '/contact', 'PATCH', otherCompany, { phone: '0911770020' })).status, 404);
    await documentsAndProfile(companyDriverToken);
    assert.equal((await request('/api/onboarding/submit', 'POST', companyDriverToken, {})).status, 200);
    assert.equal((await companyApproval(companyDriver.id, 'APPROVE')).status, 200);
    const before = await stored(companyDriver.id);
    const noChange = await request('/api/companies/operations/drivers/' + companyDriver.id + '/contact', 'PATCH', company, { phone: '0911770020' });
    assert.equal(noChange.status, 200); assert.equal(noChange.data.driver.unchanged, true); assert.deepEqual(await stored(companyDriver.id), before);
    assert.equal((await companyApproval(companyDriver.id, 'SUSPEND')).status, 200);
    assert.deepEqual(await stored(companyDriver.id), before); assert.equal(await isOperationallyEligible(companyDriver.id, 'DRIVER'), false);
    const restored = await companyApproval(companyDriver.id, 'REACTIVATE');
    assert.equal(restored.status, 200); assert.equal(restored.data.driver.status, 'ACTIVE'); assert.deepEqual(await stored(companyDriver.id), before);
    for (let repeat = 0; repeat < 2; repeat++) {
      const cachedToggle = await companyApproval(companyDriver.id, 'REJECT');
      assert.equal(cachedToggle.status, 200); assert.equal(cachedToggle.data.driver.status, 'SUSPENDED');
      assert.deepEqual(await stored(companyDriver.id), before, 'Cached deactivate toggles never become application rejection');
    }
    assert.equal((await companyApproval(companyDriver.id, 'APPROVE')).data.driver.status, 'ACTIVE');
    assert.deepEqual(await stored(companyDriver.id), before);
  });

  await t.test('real phone changes still revoke review and old broken suspensions recover to registration only', async () => {
    const changed = await request('/api/admin/drivers/' + daily.id + '/contact', 'PATCH', admin, { phone: '0911770011' });
    assert.equal(changed.status, 200); assert.equal(changed.data.driver.unchanged, false);
    let state = await getOnboardingReadiness(daily.id, 'DRIVER');
    assert.equal(state.acceptanceStatus, 'PENDING'); assert.equal(state.submittedAt, null); assert.equal(await isOperationallyEligible(daily.id, 'DRIVER'), false);
    await DB.prepare("UPDATE users SET status='SUSPENDED' WHERE id=?").run(daily.id);
    await DB.prepare("UPDATE onboarding_accounts SET review_status='REJECTED' WHERE account_id=?").run(daily.id);
    const restored = await legacy(daily.id, 'ACTIVE');
    assert.equal(restored.status, 200); assert.equal(restored.data.driver.status, 'PENDING');
    assert.equal((await request('/api/onboarding/me', 'GET', dailyToken)).status, 200);
    assert.equal(await isOperationallyEligible(daily.id, 'DRIVER'), false);
    assert.ok(await login('0911770011'));
    assert.equal((await companyApproval(companyDriver.id, 'SUSPEND')).status, 200);
    const companyChanged = await request('/api/companies/operations/drivers/' + companyDriver.id + '/contact', 'PATCH', company, { phone: '0911770021' });
    assert.equal(companyChanged.status, 200); state = await getOnboardingReadiness(companyDriver.id, 'DRIVER');
    assert.equal(state.reviewStatus, 'DRAFT'); assert.equal(state.submittedAt, null); assert.equal(await isOperationallyEligible(companyDriver.id, 'DRIVER'), false);
    assert.equal((await DB.prepare('SELECT status FROM users WHERE id=?').get(companyDriver.id)).status, 'SUSPENDED', 'A phone edit does not lift suspension');
    assert.equal((await companyApproval(companyDriver.id, 'REACTIVATE')).data.driver.status, 'PENDING', 'Changed credentials must complete review after reinstatement');
  });
});
