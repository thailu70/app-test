'use strict';
const crypto = require('crypto');
const bcrypt = require('bcryptjs');
const fs = require('fs/promises');
const path = require('path');
const { DB } = require('../db');
const { initProfileSchema } = require('./profile-schema');
const { commuteQuote, commuteEndpoints, vehicleMonthlyPrice } = require('./commute-quote');
const { accountPhonePolicy } = require('./account-phone-policy');
const { loadCredentialSnapshot } = require('./account-sessions');

const REQUIRED = Object.freeze({ DRIVER: ['LICENSE', 'VEHICLE_REGISTRATION', 'INSURANCE', 'PHOTO'], COMPANY: ['BUSINESS_LICENSE', 'REGISTRATION', 'REPRESENTATIVE_ID'] });
const MAX_DOCUMENT_BYTES = 5 * 1024 * 1024;
class OnboardingError extends Error { constructor(status, message) { super(message); this.status = status; } }
const fail = (status, message) => { throw new OnboardingError(status, message); };
function normalizePhone(value) {
  const text = String(value || '').trim().replace(/[\s()-]/g, '');
  const match = /^(?:\+251|251|0)?([79]\d{8})$/.exec(text);
  return match ? '+251' + match[1] : null;
}
function text(value, name, max, required = true) {
  if (typeof value !== 'string' || /[\u0000-\u001f]/.test(value) || value.trim().length > max || (required && value.trim().length < 2)) fail(400, `Enter a valid ${name}.`);
  return value.trim();
}
const missingTable = error => error?.code === '42P01' || /no such table.*onboarding_accounts/i.test(String(error?.message));
const companyId = account => account.companyId || account.company_id || '';
const mayContinue = account => account && ['PENDING', 'ACTIVE'].includes(account.status);
const schemaTasks = new WeakMap();
async function initOnboardingSchema(db = DB) {
  if (!schemaTasks.has(db)) schemaTasks.set(db, (async () => {
    if (db === DB) await initProfileSchema();
    await db.exec(`CREATE TABLE IF NOT EXISTS transport_companies (
      id TEXT PRIMARY KEY, name TEXT NOT NULL, phone TEXT, representative_name TEXT,
      password_hash TEXT, status TEXT NOT NULL DEFAULT 'ACTIVE', created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
    );
    CREATE TABLE IF NOT EXISTS onboarding_accounts (
      account_id TEXT PRIMARY KEY, role TEXT NOT NULL CHECK(role IN ('DRIVER','COMPANY')),
      normalized_phone TEXT NOT NULL UNIQUE, phone_verified_at TEXT, phone_verified_for TEXT,
      submitted_at TEXT, review_status TEXT NOT NULL DEFAULT 'DRAFT', reviewed_at TEXT,
      reviewed_by TEXT, review_note TEXT NOT NULL DEFAULT '', company_address TEXT NOT NULL DEFAULT '',
      company_registration_number TEXT NOT NULL DEFAULT '', created_at TEXT NOT NULL, updated_at TEXT NOT NULL
    );
    CREATE INDEX IF NOT EXISTS onboarding_review_queue ON onboarding_accounts(role,review_status,account_id);
    CREATE TABLE IF NOT EXISTS onboarding_otps (
      account_id TEXT PRIMARY KEY REFERENCES onboarding_accounts(account_id) ON DELETE CASCADE,
      challenge_id TEXT NOT NULL, phone TEXT NOT NULL, code_hash TEXT NOT NULL, expires_at TEXT NOT NULL,
      requested_at TEXT NOT NULL, sent_at TEXT, consumed_at TEXT, attempts INTEGER NOT NULL DEFAULT 0,
      window_start TEXT NOT NULL, resend_count INTEGER NOT NULL DEFAULT 1
    );
    CREATE TABLE IF NOT EXISTS onboarding_company_documents (
      company_id TEXT NOT NULL REFERENCES transport_companies(id) ON DELETE CASCADE,
      kind TEXT NOT NULL, storage_name TEXT NOT NULL, content_type TEXT NOT NULL,
      byte_size INTEGER NOT NULL, uploaded_at TEXT NOT NULL, PRIMARY KEY(company_id,kind)
    );`);
    if (db.isPostgres) {
      await db.exec("ALTER TABLE transport_companies DROP CONSTRAINT IF EXISTS transport_companies_status_check; ALTER TABLE transport_companies ADD CONSTRAINT transport_companies_status_check CHECK(status IN ('ACTIVE','INACTIVE','INVITED','PENDING'));");
    }
    const additions = {
      service_type: "TEXT NOT NULL DEFAULT ''", route_choice: "TEXT NOT NULL DEFAULT ''", route_id: "TEXT NOT NULL DEFAULT ''",
      route_name: "TEXT NOT NULL DEFAULT ''", proposed_departure: "TEXT NOT NULL DEFAULT ''", proposed_destination: "TEXT NOT NULL DEFAULT ''",
      proposed_monthly_fee_etb: 'NUMERIC', proposed_monthly_fee_basis: "TEXT NOT NULL DEFAULT ''",
      monthly_fee_source: "TEXT NOT NULL DEFAULT 'MANUAL_PROPOSAL'", quote_vehicle_type: "TEXT NOT NULL DEFAULT ''",
      quote_passenger_capacity: 'INTEGER', quote_passenger_fare_etb: 'NUMERIC',
      initial_submitted_at: 'TEXT', acceptance_status: "TEXT NOT NULL DEFAULT 'PENDING'", accepted_at: 'TEXT', accepted_by: 'TEXT',
      acceptance_grandfathered: 'BOOLEAN NOT NULL DEFAULT FALSE'
    };
    const existing = db.isPostgres ? [] : await db.prepare('PRAGMA table_info(onboarding_accounts)').all();
    for (const [column, definition] of Object.entries(additions)) {
      if (db.isPostgres) await db.exec(`ALTER TABLE onboarding_accounts ADD COLUMN IF NOT EXISTS ${column} ${definition}`);
      else if (!existing.some(item => item.name === column)) await db.exec(`ALTER TABLE onboarding_accounts ADD COLUMN ${column} ${definition}`);
    }
    // Only accounts approved under the previous complete workflow retain their acceptance.
    // Pending applications move to the initial review without deleting already-uploaded records.
    const legacy = await db.prepare("SELECT * FROM onboarding_accounts WHERE service_type=''").all();
    for (const record of legacy) {
      const account = await loadAccount(record.account_id, record.role, db);
      const required = record.role === 'DRIVER' && account && !companyId(account);
      let approved = false;
      if (required && account.status === 'ACTIVE' && record.review_status === 'APPROVED' && record.submitted_at && record.phone_verified_at && record.phone_verified_for === normalizePhone(account.phone) && record.normalized_phone === normalizePhone(account.phone)) {
        const saved = await documents(record.account_id, 'DRIVER', db), profile = await db.prepare('SELECT * FROM user_profiles WHERE user_id=?').get(record.account_id) || {};
        approved = REQUIRED.DRIVER.every(kind => saved.some(doc => doc.kind === kind)) && Boolean(profile.emergency_contact_name && normalizePhone(profile.emergency_contact_phone) && profile.vehicle_color);
      }
      const instant = new Date().toISOString();
      await db.prepare(`UPDATE onboarding_accounts SET service_type=?,acceptance_status=?,accepted_at=?,accepted_by=?,acceptance_grandfathered=?,initial_submitted_at=?,
        review_status=?,submitted_at=? WHERE account_id=?`).run(record.role === 'COMPANY' ? 'COMPANY' : required ? 'DAILY_COMMUTE' : 'INTERCITY',
        required ? approved ? 'ACCEPTED' : 'PENDING' : 'NOT_REQUIRED', approved ? record.reviewed_at || instant : null,
        approved ? record.reviewed_by || 'LEGACY_APPROVAL' : null, db.isPostgres ? Boolean(approved) : approved ? 1 : 0, required ? record.created_at || instant : null,
        required && !approved ? 'AWAITING_ACCEPTANCE' : record.review_status, required && !approved ? null : record.submitted_at, record.account_id);
    }
    // Older Android versions created pending drivers without an application.
    // Enroll those applicants for the same document review; existing active
    // legacy accounts keep their established operational access.
    const pending = await db.prepare("SELECT u.id FROM users u LEFT JOIN onboarding_accounts o ON o.account_id=u.id WHERE u.role='DRIVER' AND u.status='PENDING' AND o.account_id IS NULL").all();
    for (const driver of pending) await enrollPendingDriver(driver.id, db);
    await require('./application-notifications').initApplicationNotificationSchema(db);
  })().catch(error => { schemaTasks.delete(db); throw error; }));
  return schemaTasks.get(db);
}

async function loadAccount(id, role, db = DB, lock = false) {
  const suffix = lock && db.isPostgres ? ' FOR UPDATE' : '';
  if (role === 'COMPANY') {
    const row = await db.prepare('SELECT * FROM transport_companies WHERE id=?' + suffix).get(id);
    return row ? { ...row, role: 'COMPANY' } : null;
  }
  if (role !== 'DRIVER') return null;
  return db.prepare("SELECT * FROM users WHERE id=? AND role='DRIVER'" + suffix).get(id);
}
async function enrollment(id, role, db = DB, lock = false) {
  try { return await db.prepare('SELECT * FROM onboarding_accounts WHERE account_id=? AND role=?' + (lock && db.isPostgres ? ' FOR UPDATE' : '')).get(id, role); }
  catch (error) { if (missingTable(error)) return null; throw error; }
}
async function documents(id, role, db = DB) {
  const table = role === 'COMPANY' ? 'onboarding_company_documents' : 'user_profile_documents';
  const key = role === 'COMPANY' ? 'company_id' : 'user_id';
  return db.prepare(`SELECT * FROM ${table} WHERE ${key}=? ORDER BY kind`).all(id);
}
async function enrollPendingDriver(id, db = DB) {
  const account = await loadAccount(id, 'DRIVER', db, true);
  if (!account || account.status !== 'PENDING') return false;
  if (await enrollment(id, 'DRIVER', db, true)) return false;
  const phone = normalizePhone(account.phone), initial = !companyId(account);
  // Preserve unusual legacy phone collisions for an administrator to correct,
  // without granting either account the other account's verification state.
  const collision = phone && await db.prepare('SELECT account_id FROM onboarding_accounts WHERE normalized_phone=?').get(phone);
  const instant = new Date().toISOString();
  await db.prepare('INSERT INTO onboarding_accounts(account_id,role,normalized_phone,created_at,updated_at,service_type,review_status,initial_submitted_at,acceptance_status) VALUES(?,?,?,?,?,?,?,?,?)')
    .run(id, 'DRIVER', phone && !collision ? phone : 'pending:' + id, instant, instant,
      initial ? 'DAILY_COMMUTE' : 'INTERCITY', initial ? 'AWAITING_ACCEPTANCE' : 'DRAFT', initial ? instant : null, initial ? 'PENDING' : 'NOT_REQUIRED');
  return true;
}
function routePreference(record) {
  return { mode: record.route_choice || '', routeId: record.route_id || '', routeName: record.route_name || '',
    departure: record.proposed_departure || '', destination: record.proposed_destination || '',
    proposedMonthlyFeeEtb: record.proposed_monthly_fee_etb == null ? null : Number(record.proposed_monthly_fee_etb), proposedMonthlyFeeBasis: record.proposed_monthly_fee_basis || '',
    feeLocked: record.monthly_fee_source === 'ROUTE_PASSENGER_FARE_X_CAPACITY', feeSource: record.monthly_fee_source || 'MANUAL_PROPOSAL',
    vehicleType: record.quote_vehicle_type || '', passengerCapacity: record.quote_passenger_capacity == null ? null : Number(record.quote_passenger_capacity),
    passengerMonthlyFareEtb: record.quote_passenger_fare_etb == null ? null : Number(record.quote_passenger_fare_etb) };
}
function feeProposal(amount, basis = 'PER_VEHICLE') {
  if ((typeof amount !== 'number' && typeof amount !== 'string') || (typeof amount === 'string' && !/^\d+(?:\.\d{1,2})?$/.test(amount))) fail(400, 'Enter a proposed monthly fee in ETB with no more than two decimal places.');
  const value = Number(amount);
  if (!Number.isFinite(value) || value <= 0 || value > 1000000 || Math.abs(value * 100 - Math.round(value * 100)) > 0.000001) fail(400, 'Propose a monthly fee greater than zero and no more than ETB 1,000,000 per vehicle.');
  if (basis !== 'PER_VEHICLE') fail(400, 'The proposed monthly fee must be for the whole vehicle per month.');
  return { proposedMonthlyFeeEtb: Math.round(value * 100) / 100, proposedMonthlyFeeBasis: basis };
}
function completePreference(record) {
  if (record.acceptance_grandfathered === true || record.acceptance_grandfathered === 1) return true;
  try { feeProposal(record.proposed_monthly_fee_etb, record.proposed_monthly_fee_basis); } catch (_) { return false; }
  return record.route_choice === 'EXISTING' ? Boolean(record.route_id) : record.route_choice === 'PROPOSED' && Boolean(record.proposed_departure && record.proposed_destination);
}
function acceptance(account, record) {
  const required = record.role === 'DRIVER' && !companyId(account || {});
  const accepted = !required || Boolean(record.acceptance_status === 'ACCEPTED' && record.accepted_at && record.accepted_by && record.normalized_phone === normalizePhone(account?.phone));
  const initialMissing = [];
  if (required && !completePreference(record)) {
    if (!['EXISTING','PROPOSED'].includes(record.route_choice) || (record.route_choice === 'EXISTING' ? !record.route_id : !(record.proposed_departure && record.proposed_destination))) initialMissing.push('ROUTE_PREFERENCE');
    try { feeProposal(record.proposed_monthly_fee_etb, record.proposed_monthly_fee_basis); } catch (_) { initialMissing.push('PROPOSED_MONTHLY_FEE'); }
  }
  return { acceptanceRequired: required, acceptanceStatus: required ? accepted ? 'ACCEPTED' : record.acceptance_status === 'REJECTED' ? 'REJECTED' : 'PENDING' : 'NOT_REQUIRED',
    acceptedAt: required && accepted ? record.accepted_at : null, acceptedBy: required && accepted ? record.accepted_by : null,
    initialSubmittedAt: record.initial_submitted_at || null, canVerifyAndUpload: Boolean(mayContinue(account) && accepted), routePreference: routePreference(record),
    initialApplicationComplete: initialMissing.length === 0, initialMissing,
    canInitiallySubmit: Boolean(required && !accepted && mayContinue(account) && initialMissing.length === 0) };
}
function requireAcceptance(account, record) {
  if (!acceptance(account, record).canVerifyAndUpload) fail(428, 'Your daily commute application must be accepted by an administrator before phone verification or document upload.');
}
function currentEndpointLabel(value, aliases = [], canonical = '') {
  // Match the quote resolver's NFKC/case/spacing semantics. Existing applications
  // retain a valid submitted alias so the stale-quote guard does not mistake a
  // translation or spelling normalization for a route change. A removed alias
  // falls back to the current endpoint, preserving genuine change detection.
  if (typeof value !== 'string' || value.trim().length > 120 || /[\u0000-\u001f]/.test(value)) return canonical;
  const key = label => label.normalize('NFKC').trim().replace(/\s+/gu, ' ').toLowerCase();
  return aliases.some(alias => key(alias) === key(value)) ? value.trim() : canonical;
}
async function validatePreference(input, db = DB) {
  const mode = String(input.routeChoice || '').toUpperCase();
  if (input.proposedMonthlyFeeBasis !== undefined && input.proposedMonthlyFeeBasis !== 'PER_VEHICLE') fail(400, 'The proposed monthly fee must be for the whole vehicle per month.');
  if (db.isPostgres) await db.prepare('SELECT code FROM vehicle_types WHERE code=? FOR SHARE').get(String(input.vehicleType || '').toUpperCase());
  const type = await require('./master-data').getVehicleType(String(input.vehicleType || '').toUpperCase(), db);
  if (!type) fail(400, 'Choose an active vehicle type.');
  let resolved, departure = input.proposedDeparture, destination = input.proposedDestination;
  if (mode === 'EXISTING') {
    const id = text(input.appliedRouteId, 'daily commute route', 64);
    const route = await db.prepare("SELECT id,name,basePriceEtb FROM routes WHERE id=? AND active=TRUE AND COALESCE(companyId,'')='' AND COALESCE(routeType,'DAILY_COMMUTE')='DAILY_COMMUTE'" + (db.isPostgres ? ' FOR SHARE' : '')).get(id);
    if (!route) fail(400, 'Choose an active daily commute route.');
    const endpoints = (await commuteEndpoints(db)).find(item => item.id === route.id);
    departure = currentEndpointLabel(input.proposedDeparture, endpoints?.departureAliases, endpoints?.departure);
    destination = currentEndpointLabel(input.proposedDestination, endpoints?.destinationAliases, endpoints?.destination);
    resolved = { routeMatch: { status: 'EXACT', routeId: route.id, routeName: route.name }, price: vehicleMonthlyPrice({ passengerMonthlyFareEtb: route.basePriceEtb }, type) };
  } else {
    if (mode && mode !== 'PROPOSED') fail(400, 'Choose an existing daily commute route or propose a route.');
    resolved = await commuteQuote({ proposedDeparture: departure, proposedDestination: destination, vehicleType: type.code }, db);
    if (db.isPostgres && resolved.routeMatch.routeId) {
      await db.prepare('SELECT id FROM routes WHERE id=? FOR SHARE').get(resolved.routeMatch.routeId);
      resolved = await commuteQuote({ proposedDeparture: departure, proposedDestination: destination, vehicleType: type.code }, db);
    }
    departure = departure.trim(); destination = destination.trim();
  }
  const { routeMatch: match, price } = resolved;
  const fee = feeProposal(price.mode === 'FIXED' ? price.amountEtb : input.proposedMonthlyFeeEtb, 'PER_VEHICLE');
  return { mode: match.status === 'EXACT' ? 'EXISTING' : 'PROPOSED', routeId: match.routeId || '', routeName: match.routeName || departure + ' → ' + destination,
    departure, destination, ...fee, feeLocked: price.mode === 'FIXED', feeSource: price.source || 'MANUAL_PROPOSAL', vehicleType: type.code,
    passengerCapacity: price.passengerCapacity, passengerMonthlyFareEtb: price.passengerMonthlyFareEtb };
}
async function savePreference(id, preference, db) {
  await db.prepare('UPDATE onboarding_accounts SET route_choice=?,route_id=?,route_name=?,proposed_departure=?,proposed_destination=?,proposed_monthly_fee_etb=?,proposed_monthly_fee_basis=?,monthly_fee_source=?,quote_vehicle_type=?,quote_passenger_capacity=?,quote_passenger_fare_etb=? WHERE account_id=?')
    .run(preference.mode, preference.routeId, preference.routeName, preference.departure, preference.destination, preference.proposedMonthlyFeeEtb, preference.proposedMonthlyFeeBasis,
      preference.feeSource, preference.vehicleType, preference.passengerCapacity, preference.passengerMonthlyFareEtb, id);
}
async function currentPreference(id, record, db) {
  const vehicle = await db.prepare('SELECT vehicleType FROM vehicles WHERE driverId=? ORDER BY id LIMIT 1').get(id);
  return validatePreference({ routeChoice: record.route_choice, appliedRouteId: record.route_id, proposedDeparture: record.proposed_departure, proposedDestination: record.proposed_destination,
    proposedMonthlyFeeEtb: record.proposed_monthly_fee_etb, proposedMonthlyFeeBasis: record.proposed_monthly_fee_basis, vehicleType: vehicle?.vehicleType || record.quote_vehicle_type }, db);
}
async function getOnboardingReadiness(id, role, db = DB, { requireSubmission = true } = {}) {
  const record = await enrollment(id, role, db);
  const account = await loadAccount(id, role, db);
  if (!record) {
    const missing = role === 'DRIVER' && account?.status === 'PENDING' ? ['ONBOARDING_ENROLLMENT'] : [];
    return { tracked: false, ready: missing.length === 0, missing, reviewReady: missing.length === 0, reviewMissing: missing,
      phoneVerificationRequiredForApproval: false, phoneVerificationRequiredForAllocation: false, phoneVerified: false, submittedAt: null, reviewStatus: null };
  }
  const initial = acceptance(account, record);
  const currentPhone = normalizePhone(account?.phone);
  const phoneVerified = Boolean(currentPhone && record.phone_verified_at && record.phone_verified_for === currentPhone && record.normalized_phone === currentPhone);
  const policy = accountPhonePolicy(account, role);
  const missing = !phoneVerified && policy.phoneVerificationRequiredForAllocation ? ['PHONE_VERIFICATION'] : [];
  if (!currentPhone) missing.unshift('PHONE_NUMBER');
  if (initial.acceptanceRequired && !initial.acceptedAt) missing.unshift('ADMIN_ACCEPTANCE');
  missing.unshift(...initial.initialMissing);
  const saved = await documents(id, role, db);
  for (const kind of REQUIRED[role] || []) if (!saved.some(doc => doc.kind === kind)) missing.push(kind);
  if (role === 'DRIVER') {
    const profile = await db.prepare('SELECT * FROM user_profiles WHERE user_id=?').get(id) || {};
    if (!profile.emergency_contact_name) missing.push('EMERGENCY_CONTACT_NAME');
    if (!normalizePhone(profile.emergency_contact_phone)) missing.push('EMERGENCY_CONTACT_PHONE');
    if (!profile.vehicle_color) missing.push('VEHICLE_COLOR');
  }
  if (requireSubmission && !record.submitted_at) missing.push('SUBMIT_FOR_REVIEW');
  const reviewMissing = missing.filter(item => item !== 'PHONE_VERIFICATION');
  if (!phoneVerified && policy.phoneVerificationRequiredForApproval) reviewMissing.push('PHONE_VERIFICATION');
  return { tracked: true, ready: missing.length === 0, missing, reviewReady: reviewMissing.length === 0, reviewMissing,
    phoneVerificationRequiredForApproval: policy.phoneVerificationRequiredForApproval, phoneVerificationRequiredForAllocation: policy.phoneVerificationRequiredForAllocation, phoneVerified,
    submittedAt: record.submitted_at || null, reviewStatus: record.review_status, reviewNote: record.review_note || '', ...initial };
}
async function isOperationallyEligible(id, role, db = DB) {
  if (!['DRIVER', 'COMPANY'].includes(role)) return true;
  const account = await loadAccount(id, role, db);
  if (account?.status !== 'ACTIVE') return false;
  const state = await getOnboardingReadiness(id, role, db);
  return !state.tracked || (state.ready && state.reviewStatus === 'APPROVED');
}
async function markOnboardingReviewed(id, role, decision, reviewer, note = '', db = DB) {
  const account = await loadAccount(id, role, db, true);
  const record = await enrollment(id, role, db, true);
  if (!record) return false;
  if (!['APPROVED', 'REJECTED'].includes(decision)) fail(400, 'Invalid review decision.');
  if (decision === 'APPROVED' && !mayContinue(account)) fail(409, 'This account is disabled. Restore it explicitly before reviewing onboarding.');
  if (decision === 'APPROVED' && acceptance(account, record).acceptanceRequired && completePreference(record) && !(record.acceptance_grandfathered === true || record.acceptance_grandfathered === 1)) {
    if (JSON.stringify(await currentPreference(id, record, db)) !== JSON.stringify(routePreference(record))) fail(409, 'The route or vehicle monthly price has changed. The applicant must refresh and resubmit their application.');
  }
  if (decision === 'APPROVED' && !(await getOnboardingReadiness(id, role, db)).reviewReady) fail(428, 'Complete every required approval step and submit the application for review.');
  if (decision === 'REJECTED' && acceptance(account, record).acceptanceRequired) {
    await db.prepare("UPDATE onboarding_accounts SET acceptance_status='REJECTED',accepted_at=NULL,accepted_by=NULL,acceptance_grandfathered=FALSE WHERE account_id=?").run(id);
    await db.prepare('DELETE FROM onboarding_otps WHERE account_id=?').run(id);
  }
  await db.prepare('UPDATE onboarding_accounts SET review_status=?,reviewed_by=?,reviewed_at=?,review_note=?,submitted_at=?,updated_at=? WHERE account_id=? AND role=?')
    .run(decision, reviewer, new Date().toISOString(), text(String(note), 'review note', 500, false), decision === 'REJECTED' ? null : record.submitted_at, new Date().toISOString(), id, role);
  return true;
}
async function requestOnboardingCorrections(id, role, reviewer, note, db = DB) {
  const account = await loadAccount(id, role, db, true), record = await enrollment(id, role, db, true);
  if (!account || !record || !mayContinue(account)) fail(409, 'This application is unavailable for document corrections.');
  const reason = text(note, 'correction instructions', 500);
  const initial = acceptance(account, record);
  if (initial.acceptanceRequired && !initial.acceptedAt) fail(409, 'Accept the route application before requesting document corrections.');
  await db.prepare("UPDATE onboarding_accounts SET review_status='CORRECTIONS_REQUESTED',review_note=?,reviewed_by=?,reviewed_at=?,submitted_at=NULL,updated_at=? WHERE account_id=? AND role=?")
    .run(reason, reviewer, new Date().toISOString(), new Date().toISOString(), id, role);
  if (role === 'COMPANY') await db.prepare("UPDATE transport_companies SET status='PENDING',updated_at=CURRENT_TIMESTAMP WHERE id=?").run(id);
  else await db.prepare("UPDATE users SET status='PENDING' WHERE id=? AND role='DRIVER'").run(id);
}
async function setDriverSuspension(id, suspend, db = DB) {
  const account = await loadAccount(id, 'DRIVER', db, true);
  if (!account || account.status === 'DELETED' || await require('./account-deletion').isAccountClosed(id, 'DRIVER', db)) fail(409, 'Deleted or closed driver accounts cannot be restored.');
  if (suspend) {
    if (account.status === 'SUSPENDED') return { changed: false, status: 'SUSPENDED' };
    if (account.status !== 'ACTIVE') fail(409, 'Only an active driver can be suspended. Review a pending application separately.');
    await db.prepare("UPDATE users SET status='SUSPENDED' WHERE id=? AND role='DRIVER'").run(id);
    return { changed: true, status: 'SUSPENDED' };
  }
  if (account.status === 'ACTIVE') return { changed: false, status: 'ACTIVE' };
  if (account.status !== 'SUSPENDED') fail(409, 'This driver is not suspended. Complete application approval first.');
  const state = await getOnboardingReadiness(id, 'DRIVER', db);
  // A previous deployment conflated rejection with suspension. Restore those
  // applicants to their registration account, never directly to operations.
  let status = !state.tracked || (state.reviewStatus === 'APPROVED' && state.ready) ? 'ACTIVE' : 'PENDING';
  if (status === 'ACTIVE' && companyId(account) && !await isOperationallyEligible(companyId(account), 'COMPANY', db)) status = 'PENDING';
  await db.prepare("UPDATE users SET status=? WHERE id=? AND role='DRIVER'").run(status, id);
  return { changed: true, status };
}
async function invalidateOnboarding(id, role, db = DB, { phoneChanged = false, identityChanged = false, routeChanged = false } = {}) {
  if (!REQUIRED[role]) return false;
  const account = await loadAccount(id, role, db, true);
  const suspended = role === 'DRIVER' && account?.status === 'SUSPENDED';
  if (!mayContinue(account) && !suspended) return false;
  const record = await enrollment(id, role, db, true);
  if (!record) return false;
  const initial = acceptance(account, record), revoke = initial.acceptanceRequired && (phoneChanged || identityChanged || routeChanged);
  if (revoke) await db.prepare("UPDATE onboarding_accounts SET acceptance_status='PENDING',accepted_at=NULL,accepted_by=NULL,acceptance_grandfathered=FALSE,initial_submitted_at=? WHERE account_id=?").run(new Date().toISOString(), id);
  const corrections = !revoke && record.review_status === 'CORRECTIONS_REQUESTED';
  await db.prepare('UPDATE onboarding_accounts SET submitted_at=NULL,review_status=?,reviewed_by=?,reviewed_at=?,review_note=?,updated_at=? WHERE account_id=?')
    .run(initial.acceptanceRequired && (revoke || !initial.acceptedAt) ? 'AWAITING_ACCEPTANCE' : corrections ? 'CORRECTIONS_REQUESTED' : 'DRAFT',
      corrections ? record.reviewed_by : null, corrections ? record.reviewed_at : null, corrections ? record.review_note : '', new Date().toISOString(), id);
  if (phoneChanged || revoke) {
    const phone = normalizePhone(account.phone);
    if (phoneChanged && phone && record.normalized_phone !== phone) {
      if (!(await phoneAvailable(phone, id, db))) fail(409, 'This phone number is already registered.');
      await db.prepare('UPDATE onboarding_accounts SET normalized_phone=? WHERE account_id=?').run(phone, id);
    }
    await db.prepare('UPDATE onboarding_accounts SET phone_verified_at=NULL,phone_verified_for=NULL WHERE account_id=?').run(id);
    await db.prepare('DELETE FROM onboarding_otps WHERE account_id=?').run(id);
  }
  if (role === 'COMPANY') await db.prepare("UPDATE transport_companies SET status='PENDING',updated_at=CURRENT_TIMESTAMP WHERE id=?").run(id);
  else if (!suspended) await db.prepare("UPDATE users SET status='PENDING' WHERE id=? AND role='DRIVER'").run(id);
  return true;
}
async function acceptInitialOnboarding(id, reviewer, note = '', db = DB) {
  const admin = await db.prepare("SELECT id FROM users WHERE id=? AND role='ADMIN' AND status='ACTIVE'").get(reviewer);
  if (!admin) fail(403, 'Only a system administrator can accept a daily commute application.');
  const account = await loadAccount(id, 'DRIVER', db, true), record = await enrollment(id, 'DRIVER', db, true);
  if (!account || !record || companyId(account)) fail(404, 'Daily commute application unavailable.');
  if (!mayContinue(account)) fail(403, 'This account is disabled. Contact support.');
  if (acceptance(account, record).acceptedAt) return false;
  if (!completePreference(record)) fail(428, 'The applicant must select or propose a route and propose a monthly fee before acceptance.');
  const refreshed = await currentPreference(id, record, db);
  if (JSON.stringify(refreshed) !== JSON.stringify(routePreference(record))) fail(409, 'The route, vehicle or monthly price has changed. The applicant must refresh and resubmit before acceptance.');
  const phone = normalizePhone(account.phone);
  if (!phone) fail(428, 'The applicant must provide a valid phone number before acceptance.');
  if (record.normalized_phone !== phone) {
    if (!(await phoneAvailable(phone, id, db))) fail(409, 'This phone number is already registered.');
    await db.prepare('UPDATE onboarding_accounts SET normalized_phone=?,phone_verified_at=NULL,phone_verified_for=NULL WHERE account_id=?').run(phone, id);
    await db.prepare('DELETE FROM onboarding_otps WHERE account_id=?').run(id);
  }
  const instant = new Date().toISOString();
  await db.prepare("UPDATE onboarding_accounts SET acceptance_status='ACCEPTED',accepted_at=?,accepted_by=?,review_status='DRAFT',review_note=?,submitted_at=NULL,reviewed_at=NULL,reviewed_by=NULL,updated_at=? WHERE account_id=?")
    .run(instant, reviewer, text(note, 'acceptance note', 500, false), instant, id);
  await db.prepare("UPDATE users SET status='PENDING' WHERE id=? AND role='DRIVER'").run(id);
  return true;
}
async function phoneAvailable(phone, except, db = DB) {
  for (const table of ['users', 'transport_companies']) {
    const clean = "REPLACE(REPLACE(REPLACE(REPLACE(phone,' ',''),'-',''),'(',''),')','')";
    const national = phone.slice(4), variants = [phone, phone.slice(1), '0' + national, national];
    const row = await db.prepare(`SELECT id FROM ${table} WHERE ${clean} IN (?,?,?,?) AND id<>? LIMIT 1`).get(...variants, except || '');
    if (row) return false;
  }
  return true;
}
async function findAccountByPhone(phone, role, db = DB) {
  const table = role === 'COMPANY' ? 'transport_companies' : 'users';
  const normalized = normalizePhone(phone);
  if (!normalized) return db.prepare(`SELECT * FROM ${table} WHERE phone=?` + (role === 'COMPANY' ? '' : ' AND role=?')).get(String(phone || '').trim(), ...(role === 'COMPANY' ? [] : [role]));
  const national = normalized.slice(4), variants = [normalized, normalized.slice(1), '0' + national, national];
  const clean = "REPLACE(REPLACE(REPLACE(REPLACE(phone,' ',''),'-',''),'(',''),')','')";
  return db.prepare(`SELECT * FROM ${table} WHERE ${clean} IN (?,?,?,?)` + (role === 'COMPANY' ? '' : ' AND role=?')).get(...variants, ...(role === 'COMPANY' ? [] : [role]));
}
function detectDocument(bytes) {
  if (bytes.length >= 32 && bytes.subarray(0, 8).equals(Buffer.from([137,80,78,71,13,10,26,10])) && bytes.subarray(12, 16).toString() === 'IHDR') {
    const width = bytes.readUInt32BE(16), height = bytes.readUInt32BE(20);
    if (width && height && width * height <= 24000000) return { mime: 'image/png', extension: 'png' };
  }
  if (bytes.length >= 32 && bytes[0] === 255 && bytes[1] === 216 && bytes[2] === 255 && bytes.at(-2) === 255 && bytes.at(-1) === 217) return { mime: 'image/jpeg', extension: 'jpg' };
  if (bytes.length >= 16 && bytes.subarray(0, 5).toString() === '%PDF-' && bytes.subarray(Math.max(0, bytes.length - 1024)).includes(Buffer.from('%%EOF'))) return { mime: 'application/pdf', extension: 'pdf' };
  return null;
}

function createOnboardingService({ db = DB, sendSms = require('./afro-sms').sendSms, now = Date.now } = {}) {
  let transactionTail = Promise.resolve();
  const transact = fn => {
    if (db.isPostgres) return db.transaction(fn);
    const operation = transactionTail.then(() => db.transaction(fn));
    transactionTail = operation.catch(() => {});
    return operation;
  };
  const time = () => new Date(now()).toISOString();
  const root = path.resolve(process.env.UPLOAD_DIR || path.join(process.env.DATA_DIR || path.join(__dirname, '..', '..', 'data'), 'uploads'));
  const directory = role => path.join(root, role === 'COMPANY' ? 'onboarding-companies' : 'profiles');
  const codeHash = (account, challenge, code) => crypto.createHmac('sha256', require('../middleware/auth').JWT_SECRET)
    .update([account.id, normalizePhone(account.phone), challenge, code].join(':')).digest('hex');
  async function lock(tx, id, role, { allowSuspended = false } = {}) {
    const account = await loadAccount(id, role, tx, true), record = await enrollment(id, role, tx, true);
    if (!account || !record) fail(401, 'Onboarding account unavailable.');
    if (!mayContinue(account) && !(allowSuspended && role === 'DRIVER' && account.status === 'SUSPENDED')) fail(403, 'This account is disabled. Contact support.');
    return { account, record };
  }
  async function invalidate(tx, id, role, phoneChanged = false, routeChanged = false) {
    await invalidateOnboarding(id, role, tx, { phoneChanged, routeChanged });
  }
  async function snapshot(id, role, { review = false } = {}) {
    const account = await loadAccount(id, role, db), record = await enrollment(id, role, db);
    if (!account || !record) fail(401, 'Onboarding account unavailable.');
    if (!review && !mayContinue(account)) fail(403, 'This account is disabled. Contact support.');
    const saved = await documents(id, role, db);
    const profile = role === 'DRIVER' ? await db.prepare('SELECT * FROM user_profiles WHERE user_id=?').get(id) || {} : {};
    const vehicle = role === 'DRIVER' ? await db.prepare('SELECT model,vehicleType,capacityLimit FROM vehicles WHERE driverId=? ORDER BY id LIMIT 1').get(id) : null;
    return { account: { id, role, fullName: role === 'COMPANY' ? account.representative_name || account.name : account.fullName,
      phone: account.phone, companyName: role === 'COMPANY' ? account.name : account.companyName || '', companyId: companyId(account), status: account.status,
      ...(role === 'DRIVER' ? { licenseNumber: account.licenseNumber || '', assignedVehiclePlate: account.assignedVehiclePlate || '', vehicleModel: vehicle?.model || '', vehicleType: vehicle?.vehicleType || '', vehicleCapacity: vehicle?.capacityLimit || null } : {}) },
    onboarding: { ...await getOnboardingReadiness(id, role, db, { requireSubmission: false }), requiredDocuments: REQUIRED[role],
      profile: { emergencyContactName: profile.emergency_contact_name || '', emergencyContactPhone: profile.emergency_contact_phone || '', vehicleColor: profile.vehicle_color || '', address: record.company_address || '', registrationNumber: record.company_registration_number || '' },
      documents: saved.map(doc => ({ kind: doc.kind, contentType: doc.content_type, size: doc.byte_size, uploadedAt: doc.uploaded_at, url: '/api/onboarding/documents/' + doc.kind })) } };
  }
  async function register(input) {
    const role = String(input.role || '').toUpperCase();
    if (!REQUIRED[role]) fail(400, 'Choose DRIVER or COMPANY.');
    const fullName = text(input.fullName, 'full name', 120), phone = normalizePhone(input.phone);
    if (!phone) fail(400, 'Enter a valid Ethiopian phone number.');
    if (typeof input.password !== 'string' || input.password.length < 10 || Buffer.byteLength(input.password, 'utf8') > 72) fail(400, 'Use a password with at least 10 characters and at most 72 bytes.');
    let name = '', company = null, plate = '', model = '', type = null, preference = null;
    if (role === 'COMPANY') name = text(input.companyName, 'company name', 100);
    else {
      text(input.licenseNumber, 'driving licence number', 50);
      const service = String(input.serviceType || 'DAILY_COMMUTE').toUpperCase();
      if (!['DAILY_COMMUTE', 'INTERCITY'].includes(service)) fail(400, 'Choose Daily Commute or Intercity service.');
      if (service === 'INTERCITY') {
        company = await db.prepare("SELECT id,name FROM transport_companies WHERE id=? AND status='ACTIVE'").get(String(input.companyId || ''));
        if (!company || !(await isOperationallyEligible(company.id, 'COMPANY', db))) fail(400, 'Choose an approved transport company.');
      } else {
        plate = text(input.assignedVehiclePlate, 'vehicle plate', 30).toUpperCase(); model = text(input.vehicleModel, 'vehicle model', 80);
        type = await require('./master-data').getVehicleType(String(input.vehicleType || '').toUpperCase(), db);
        if (!type) fail(400, 'Choose an available vehicle type.');
        preference = await validatePreference(input, db);
      }
    }
    const hash = await bcrypt.hash(input.password, 12), id = (role === 'COMPANY' ? 'company_' : 'usr_drv_') + crypto.randomUUID().replace(/-/g, '').slice(0, 20);
    await transact(async tx => {
      if (tx.isPostgres) await tx.prepare("SELECT pg_advisory_xact_lock(hashtext('routepass-onboarding-phone:' || ?))").get(phone);
      const previous = await tx.prepare('SELECT account_id,role FROM onboarding_accounts WHERE normalized_phone=?').get(phone);
      let reclaimed = false;
      if (previous?.role === 'DRIVER') {
        const old = await loadAccount(previous.account_id, previous.role, tx, true);
        if (old?.status === 'DELETED' && old.phone === 'deleted:' + old.id) {
          // A new applicant may reclaim a phone deliberately freed by the existing delete flow.
          // The old account stays deleted, and its verification/session capability is never reused.
          await tx.prepare('UPDATE onboarding_accounts SET normalized_phone=?,phone_verified_at=NULL,phone_verified_for=NULL,updated_at=? WHERE account_id=? AND normalized_phone=?')
            .run('deleted:' + old.id, time(), old.id, phone);
          await tx.prepare('DELETE FROM onboarding_otps WHERE account_id=?').run(old.id);
          reclaimed = true;
        }
      }
      if (previous && !reclaimed) fail(409, 'This phone number is already registered. Sign in to your account.');
      if (!(await phoneAvailable(phone, null, tx))) fail(409, 'This phone number is already registered. Sign in to your account.');
      if (role === 'COMPANY') {
        if (await tx.prepare('SELECT id FROM transport_companies WHERE LOWER(name)=LOWER(?)').get(name)) fail(409, 'This company name is already registered.');
        await tx.prepare("INSERT INTO transport_companies(id,name,phone,representative_name,password_hash,status) VALUES(?,?,?,?,?,'PENDING')").run(id, name, phone, fullName, hash);
      } else {
        if (plate && await tx.prepare('SELECT id FROM vehicles WHERE UPPER(plateNumber)=?').get(plate)) fail(409, 'This vehicle plate is already registered.');
        if (type) {
          if (tx.isPostgres) await tx.prepare('SELECT code FROM vehicle_types WHERE code=? FOR SHARE').get(type.code);
          type = await require('./master-data').getVehicleType(type.code, tx);
          if (!type) fail(400, 'Choose an active vehicle type.');
        }
        await tx.prepare("INSERT INTO users(id,role,fullName,phone,email,passwordHash,status,licenseNumber,companyName,companyId,assignedVehiclePlate) VALUES(?,'DRIVER',?,?,?,?,'PENDING',?,?,?,?)")
          .run(id, fullName, phone, phone + '@transport.et', hash, input.licenseNumber.trim(), company?.name?.slice(0, 100) || '', company?.id || null, plate);
        if (plate) await tx.prepare("INSERT INTO vehicles(id,plateNumber,model,vehicleType,capacityLimit,currentOccupancy,assignedRouteId,driverId,driverName,status) VALUES(?,?,?,?,?,0,NULL,?,?,'OFF_DUTY')")
          .run('veh_' + crypto.randomUUID().replace(/-/g, '').slice(0, 20), plate, model, type.code, type.capacity, id, fullName);
      }
      await tx.prepare('INSERT INTO onboarding_accounts(account_id,role,normalized_phone,created_at,updated_at,service_type,review_status,initial_submitted_at,acceptance_status) VALUES(?,?,?,?,?,?,?,?,?)')
        .run(id, role, phone, time(), time(), role === 'COMPANY' ? 'COMPANY' : company ? 'INTERCITY' : 'DAILY_COMMUTE', preference ? 'AWAITING_ACCEPTANCE' : 'DRAFT', preference ? time() : null, preference ? 'PENDING' : 'NOT_REQUIRED');
      if (preference) await savePreference(id, await validatePreference(input, tx), tx);
    });
    return snapshot(id, role);
  }
  async function login(input) {
    const role = String(input.role || '').toUpperCase(), phone = normalizePhone(input.phone);
    if (!REQUIRED[role] || !phone || typeof input.password !== 'string' || Buffer.byteLength(input.password) > 72) fail(401, 'Invalid onboarding credentials.');
    const row = await db.prepare('SELECT account_id FROM onboarding_accounts WHERE role=? AND normalized_phone=?').get(role, phone);
    const account = row ? await loadCredentialSnapshot(row.account_id, role, db) : null;
    if (!mayContinue(account) || !(await bcrypt.compare(input.password, account.passwordHash || account.password_hash || ''))) fail(401, 'Invalid onboarding credentials.');
    const result = await snapshot(account.id, role);
    Object.defineProperty(result, 'authenticatedSessionVersion', { value: Number(account.credential_session_version) });
    return result;
  }
  async function requestOtp(id, role) {
    const challenge = crypto.randomUUID(), code = String(crypto.randomInt(100000, 1000000));
    const account = await transact(async tx => {
      const { account, record } = await lock(tx, id, role), phone = normalizePhone(account.phone);
      requireAcceptance(account, record);
      if (!phone) fail(400, 'Update your account phone before requesting a code.');
      if (record.phone_verified_for === phone && record.phone_verified_at) fail(409, 'This phone number is already verified.');
      const previous = await tx.prepare('SELECT * FROM onboarding_otps WHERE account_id=?').get(id);
      if (previous && now() - Date.parse(previous.requested_at) < 60000) fail(429, 'Wait 60 seconds before requesting another code.');
      const sameWindow = previous && now() - Date.parse(previous.window_start) < 3600000;
      if (sameWindow && previous.resend_count >= 5) fail(429, 'Too many verification requests. Try again in one hour.');
      if (record.normalized_phone !== phone) {
        if (!(await phoneAvailable(phone, id, tx))) fail(409, 'This phone number is already registered.');
        await tx.prepare('UPDATE onboarding_accounts SET normalized_phone=? WHERE account_id=?').run(phone, id);
        await invalidate(tx, id, role, true);
      }
      await tx.prepare(`INSERT INTO onboarding_otps(account_id,challenge_id,phone,code_hash,expires_at,requested_at,window_start,resend_count,attempts)
        VALUES(?,?,?,?,?,?,?,?,0) ON CONFLICT(account_id) DO UPDATE SET challenge_id=excluded.challenge_id,phone=excluded.phone,code_hash=excluded.code_hash,
        expires_at=excluded.expires_at,requested_at=excluded.requested_at,window_start=excluded.window_start,resend_count=excluded.resend_count,attempts=0,sent_at=NULL,consumed_at=NULL`)
        .run(id, challenge, phone, codeHash(account, challenge, code), new Date(now() + 10 * 60000).toISOString(), time(), sameWindow ? previous.window_start : time(), sameWindow ? previous.resend_count + 1 : 1);
      return account;
    });
    let delivery;
    try { delivery = await sendSms(normalizePhone(account.phone), `PASSO verification code: ${code}. It expires in 10 minutes. Do not share it.`); } catch (_) { delivery = null; }
    if (!delivery?.sent) {
      await db.prepare('UPDATE onboarding_otps SET consumed_at=? WHERE account_id=? AND challenge_id=?').run(time(), id, challenge);
      fail(503, 'The SMS could not be sent. Please try again after 60 seconds or contact support.');
    }
    await db.prepare('UPDATE onboarding_otps SET sent_at=? WHERE account_id=? AND challenge_id=? AND consumed_at IS NULL').run(time(), id, challenge);
    return { message: 'Verification code sent by SMS.', delivery: 'SENT', retryAfterSeconds: 60 };
  }
  async function verifyOtp(id, role, code) {
    if (typeof code !== 'string' || !/^\d{6}$/.test(code)) fail(400, 'Enter the six-digit verification code.');
    const result = await transact(async tx => {
      const { account, record } = await lock(tx, id, role);
      requireAcceptance(account, record);
      const otp = await tx.prepare('SELECT * FROM onboarding_otps WHERE account_id=?').get(id);
      if (!otp || !otp.sent_at || otp.consumed_at || Date.parse(otp.expires_at) <= now() || otp.attempts >= 5 || otp.phone !== normalizePhone(account.phone)) return false;
      const claimed = await tx.prepare('UPDATE onboarding_otps SET attempts=attempts+1 WHERE account_id=? AND attempts<5 AND consumed_at IS NULL').run(id);
      if (claimed.changes !== 1 || !crypto.timingSafeEqual(Buffer.from(otp.code_hash, 'hex'), Buffer.from(codeHash(account, otp.challenge_id, code), 'hex'))) return false;
      await tx.prepare('UPDATE onboarding_otps SET consumed_at=? WHERE account_id=?').run(time(), id);
      await tx.prepare('UPDATE onboarding_accounts SET phone_verified_at=?,phone_verified_for=?,updated_at=? WHERE account_id=?').run(time(), normalizePhone(account.phone), time(), id);
      return true;
    });
    if (!result) fail(400, 'Invalid, expired or already-used code. Request a new code if needed.');
    return snapshot(id, role);
  }
  async function updateProfile(id, role, input) {
    const routeKeys = ['routeChoice','appliedRouteId','proposedDeparture','proposedDestination','proposedMonthlyFeeEtb','proposedMonthlyFeeBasis','vehicleType'];
    const allowed = role === 'DRIVER' ? ['phone','emergencyContactName','emergencyContactPhone','vehicleColor', ...routeKeys] : ['phone','address','registrationNumber'];
    if (Object.keys(input).some(key => !allowed.includes(key))) fail(400, 'Only onboarding profile fields may be changed.');
    const normalized = {};
    for (const [key, value] of Object.entries(input)) {
      if (routeKeys.includes(key)) continue;
      if (['phone','emergencyContactPhone'].includes(key)) { normalized[key] = normalizePhone(value); if (!normalized[key]) fail(400, 'Enter a valid Ethiopian phone number.'); }
      else normalized[key] = text(value, key, key === 'address' ? 300 : key === 'vehicleColor' ? 40 : 100, false);
    }
    await transact(async tx => {
      const { account, record } = await lock(tx, id, role);
      let changed = false, phoneChanged = false, routeChanged = false;
      if (routeKeys.some(key => Object.hasOwn(input, key))) {
        if (role !== 'DRIVER' || companyId(account)) fail(400, 'Route proposals are only available for daily commute applicants.');
        const old = routePreference(record), vehicle = await tx.prepare('SELECT * FROM vehicles WHERE driverId=? ORDER BY id LIMIT 1').get(id);
        if (tx.isPostgres) await tx.prepare('SELECT code FROM vehicle_types WHERE code=? FOR SHARE').get(String(input.vehicleType || vehicle?.vehicleType || '').toUpperCase());
        const type = await require('./master-data').getVehicleType(String(input.vehicleType || vehicle?.vehicleType || '').toUpperCase(), tx);
        if (!type || !vehicle) fail(400, 'Choose an active registered vehicle type.');
        if (vehicle.vehicleType !== type.code) fail(400, 'Use the registered vehicle type. Contact the administrator to change vehicle details.');
        const typedEndpoints = !Object.hasOwn(input, 'routeChoice') && (Object.hasOwn(input, 'proposedDeparture') || Object.hasOwn(input, 'proposedDestination'));
        const preference = await validatePreference({ routeChoice: typedEndpoints ? '' : old.mode, appliedRouteId: old.routeId, proposedDeparture: old.departure, proposedDestination: old.destination,
          vehicleType: type.code,
          proposedMonthlyFeeEtb: old.proposedMonthlyFeeEtb, proposedMonthlyFeeBasis: old.proposedMonthlyFeeBasis || 'PER_VEHICLE', ...Object.fromEntries(Object.entries(input).filter(([key]) => routeKeys.includes(key))) }, tx);
        routeChanged = JSON.stringify(old) !== JSON.stringify(preference);
        if (routeChanged) { await savePreference(id, preference, tx); changed = true; }
      }
      if (normalized.phone && (normalized.phone !== normalizePhone(account.phone) || normalized.phone !== record.normalized_phone)) {
        if (!(await phoneAvailable(normalized.phone, id, tx))) fail(409, 'This phone number is already registered.');
        await tx.prepare(role === 'COMPANY' ? 'UPDATE transport_companies SET phone=? WHERE id=?' : 'UPDATE users SET phone=? WHERE id=?').run(normalized.phone, id);
        await tx.prepare('UPDATE onboarding_accounts SET normalized_phone=? WHERE account_id=?').run(normalized.phone, id);
        phoneChanged = changed = true;
      }
      if (role === 'DRIVER') {
        const current = await tx.prepare('SELECT * FROM user_profiles WHERE user_id=?').get(id) || {};
        const values = [normalized.emergencyContactName ?? current.emergency_contact_name ?? '', normalized.emergencyContactPhone ?? current.emergency_contact_phone ?? '', normalized.vehicleColor ?? current.vehicle_color ?? ''];
        changed ||= values.some((value, index) => value !== [current.emergency_contact_name || '',current.emergency_contact_phone || '',current.vehicle_color || ''][index]);
        await tx.prepare(`INSERT INTO user_profiles(user_id,emergency_contact_name,emergency_contact_phone,vehicle_color,updated_at) VALUES(?,?,?,?,?) ON CONFLICT(user_id) DO UPDATE SET
          emergency_contact_name=excluded.emergency_contact_name,emergency_contact_phone=excluded.emergency_contact_phone,vehicle_color=excluded.vehicle_color,updated_at=excluded.updated_at`).run(id, ...values, time());
      } else {
        const address = normalized.address ?? record.company_address, number = normalized.registrationNumber ?? record.company_registration_number;
        changed ||= address !== record.company_address || number !== record.company_registration_number;
        await tx.prepare('UPDATE onboarding_accounts SET company_address=?,company_registration_number=? WHERE account_id=?').run(address, number, id);
      }
      if (changed) await invalidate(tx, id, role, phoneChanged, routeChanged);
    });
    return snapshot(id, role);
  }
  async function upload(id, role, kind, bytes, mime) {
    if (!REQUIRED[role]?.includes(kind)) fail(400, 'That document is not required for this account.');
    if (!Buffer.isBuffer(bytes) || !bytes.length) fail(400, 'Choose a JPEG, PNG or PDF file.');
    if (bytes.length > MAX_DOCUMENT_BYTES) fail(413, 'Each document must be 5 MB or smaller.');
    const detected = detectDocument(bytes);
    if (!detected || detected.mime !== mime || (kind === 'PHOTO' && detected.mime === 'application/pdf')) fail(415, 'Choose a valid JPEG, PNG or PDF matching its file type. Photos must be images.');
    const account = await loadAccount(id, role, db), record = await enrollment(id, role, db);
    if (!mayContinue(account) || !record) fail(403, 'Onboarding account unavailable.');
    requireAcceptance(account, record);
    const folder = directory(role), name = crypto.randomUUID() + '.' + detected.extension, destination = path.join(folder, name);
    await fs.mkdir(folder, { recursive: true, mode: 0o700 }); await fs.writeFile(destination, bytes, { flag: 'wx', mode: 0o600 });
    let previous;
    try {
      await transact(async tx => {
        const { account: currentAccount, record: currentRecord } = await lock(tx, id, role);
        requireAcceptance(currentAccount, currentRecord);
        const table = role === 'COMPANY' ? 'onboarding_company_documents' : 'user_profile_documents', key = role === 'COMPANY' ? 'company_id' : 'user_id';
        previous = await tx.prepare(`SELECT storage_name FROM ${table} WHERE ${key}=? AND kind=?`).get(id, kind);
        await tx.prepare(`INSERT INTO ${table}(${key},kind,storage_name,content_type,byte_size,uploaded_at) VALUES(?,?,?,?,?,?) ON CONFLICT(${key},kind) DO UPDATE SET storage_name=excluded.storage_name,content_type=excluded.content_type,byte_size=excluded.byte_size,uploaded_at=excluded.uploaded_at`)
          .run(id, kind, name, detected.mime, bytes.length, time());
        await invalidate(tx, id, role);
      });
    } catch (error) { await fs.unlink(destination).catch(() => {}); throw error; }
    if (previous && /^[a-f0-9-]+\.(png|jpg|pdf)$/.test(previous.storage_name)) await fs.unlink(path.join(folder, previous.storage_name)).catch(() => {});
    return snapshot(id, role);
  }
  async function readDocument(id, role, kind) {
    const doc = (await documents(id, role, db)).find(row => row.kind === kind);
    if (!doc || !/^[a-f0-9-]+\.(png|jpg|pdf)$/.test(doc.storage_name)) fail(404, 'Document unavailable.');
    try { return { bytes: await fs.readFile(path.join(directory(role), doc.storage_name)), contentType: doc.content_type, filename: kind.toLowerCase() + '.' + doc.storage_name.split('.').pop() }; }
    catch (error) { if (error.code === 'ENOENT') fail(404, 'Document unavailable. Please upload it again.'); throw error; }
  }
  async function submit(id, role) {
    await transact(async tx => {
      const { account, record } = await lock(tx, id, role);
      const initial = acceptance(account, record);
      if (initial.acceptanceRequired && completePreference(record) && !(record.acceptance_grandfathered === true || record.acceptance_grandfathered === 1)) {
        const refreshed = await currentPreference(id, record, tx);
        if (JSON.stringify(refreshed) !== JSON.stringify(routePreference(record))) {
          await savePreference(id, refreshed, tx);
          if (initial.acceptedAt) { await invalidate(tx, id, role, false, true); return; }
        }
      }
      if (initial.acceptanceRequired && !initial.acceptedAt) {
        if (!completePreference(record)) fail(428, 'Choose or propose a daily route and propose a monthly vehicle fee before submitting your application.');
        await tx.prepare("UPDATE onboarding_accounts SET initial_submitted_at=?,acceptance_status='PENDING',review_status='AWAITING_ACCEPTANCE',review_note='',submitted_at=NULL,reviewed_at=NULL,reviewed_by=NULL,updated_at=? WHERE account_id=?").run(time(), time(), id);
        return;
      }
      const ready = await getOnboardingReadiness(id, role, tx, { requireSubmission: false });
      if (!ready.reviewReady) fail(428, 'Complete every required approval step, document and profile field before submitting.');
      if (ready.reviewStatus === 'APPROVED') return;
      await tx.prepare("UPDATE onboarding_accounts SET submitted_at=?,review_status='SUBMITTED',review_note='',reviewed_at=NULL,reviewed_by=NULL,updated_at=? WHERE account_id=?").run(time(), time(), id);
    });
    return snapshot(id, role);
  }
  async function mayReview(principal, id, role) {
    const account = await loadAccount(id, role, db), tracked = await enrollment(id, role, db);
    if (!account || !tracked) return false;
    return reviewAllowed(principal, account, role);
  }
  function reviewAllowed(principal, account, role) {
    if (principal.role === 'ADMIN') return role === 'COMPANY' || !companyId(account);
    return principal.role === 'COMPANY' && role === 'DRIVER' && companyId(account) === principal.id;
  }
  async function applications(principal, limit = 100, cursor = '') {
    const rows = principal.role === 'ADMIN'
      ? await db.prepare("SELECT o.account_id,o.role FROM onboarding_accounts o LEFT JOIN users u ON u.id=o.account_id AND o.role='DRIVER' WHERE o.account_id>? AND (o.role='COMPANY' OR (o.role='DRIVER' AND COALESCE(u.companyId,'')='')) ORDER BY o.account_id LIMIT ?").all(cursor, limit + 1)
      : await db.prepare("SELECT o.account_id,o.role FROM onboarding_accounts o JOIN users u ON u.id=o.account_id WHERE o.account_id>? AND o.role='DRIVER' AND u.companyId=? ORDER BY o.account_id LIMIT ?").all(cursor, principal.id, limit + 1);
    const result = [];
    for (const row of rows.slice(0, limit)) {
      const item = await snapshot(row.account_id, row.role, { review: true });
      result.push({ ...item.account, onboarding: item.onboarding });
    }
    return { applications: result, nextCursor: rows.length > limit ? result.at(-1).id : null };
  }
  async function review(principal, id, role, decision, note = '') {
    if (!(await mayReview(principal, id, role))) fail(404, 'Application unavailable.');
    if (!['ACCEPT','APPROVE','REJECT','CORRECTIONS','SUSPEND','REACTIVATE'].includes(decision)) fail(400, 'Choose ACCEPT, APPROVE, CORRECTIONS, REJECT, SUSPEND or REACTIVATE.');
    text(note, 'review note', 500, false);
    if (decision === 'CORRECTIONS') text(note, 'correction instructions', 500);
    await transact(async tx => {
      const { account, record } = await lock(tx, id, role, { allowSuspended: ['SUSPEND','REACTIVATE'].includes(decision) });
      if (!reviewAllowed(principal, account, role)) fail(404, 'Application unavailable.');
      let changed = true;
      if (decision === 'ACCEPT') {
        if (principal.role !== 'ADMIN' || role !== 'DRIVER' || companyId(account)) fail(403, 'Only an administrator may accept a daily commute driver application.');
        changed = await acceptInitialOnboarding(id, principal.id, note, tx);
      } else if (['SUSPEND','REACTIVATE'].includes(decision)) {
        if (role !== 'DRIVER') fail(400, 'Use driver suspension controls for driver accounts only.');
        changed = (await setDriverSuspension(id, decision === 'SUSPEND', tx)).changed;
      } else if (decision === 'CORRECTIONS') {
        if (record.review_status === 'CORRECTIONS_REQUESTED' && record.review_note === note.trim()) changed = false;
        else await requestOnboardingCorrections(id, role, principal.id, note, tx);
      } else {
        const approved = decision === 'APPROVE';
        if (record.review_status === (approved ? 'APPROVED' : 'REJECTED') && account.status === (approved ? 'ACTIVE' : 'PENDING') && record.review_note === note.trim()) changed = false;
        else {
          await markOnboardingReviewed(id, role, approved ? 'APPROVED' : 'REJECTED', principal.id, note, tx);
          if (role === 'COMPANY') await tx.prepare('UPDATE transport_companies SET status=?,updated_at=CURRENT_TIMESTAMP WHERE id=?').run(approved ? 'ACTIVE' : 'PENDING', id);
          else await tx.prepare("UPDATE users SET status=? WHERE id=? AND role='DRIVER'").run(approved ? 'ACTIVE' : 'PENDING', id);
        }
      }
      if (changed) {
        await tx.prepare('INSERT INTO audit_logs(action,userId,role,details) VALUES(?,?,?,?)').run('ONBOARDING_' + decision, principal.id, principal.role, `${decision} ${role} application ${id}`);
        await require('./application-notifications').queueApplicationDecision(tx, { accountId: id, role,
          decision: decision === 'REACTIVATE' ? 'RESTORE' : decision, eventKey: crypto.randomUUID() });
      }
    });
    if (db === DB) void require('./application-notifications').flushApplicationNotifications().catch(() => {});
    return snapshot(id, role, { review: true });
  }
  return { register, login, snapshot, requestOtp, verifyOtp, updateProfile, upload, readDocument, submit, mayReview, applications, review };
}
module.exports = { createOnboardingService, initOnboardingSchema, getOnboardingReadiness, isOperationallyEligible,
  markOnboardingReviewed, acceptInitialOnboarding, requestOnboardingCorrections, setDriverSuspension, enrollPendingDriver,
  invalidateOnboarding, normalizePhone, phoneAvailable, findAccountByPhone, OnboardingError, REQUIRED, MAX_DOCUMENT_BYTES };
