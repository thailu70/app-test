'use strict';
const crypto = require('crypto');
const bcrypt = require('bcryptjs');
const fs = require('fs/promises');
const path = require('path');
const { DB } = require('../db');
const { initProfileSchema } = require('./profile-schema');

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
async function getOnboardingReadiness(id, role, db = DB, { requireSubmission = true } = {}) {
  const record = await enrollment(id, role, db);
  if (!record) return { tracked: false, ready: true, missing: [], phoneVerified: false, submittedAt: null, reviewStatus: null };
  const account = await loadAccount(id, role, db);
  const currentPhone = normalizePhone(account?.phone);
  const phoneVerified = Boolean(currentPhone && record.phone_verified_at && record.phone_verified_for === currentPhone && record.normalized_phone === currentPhone);
  const missing = phoneVerified ? [] : ['PHONE_VERIFICATION'];
  const saved = await documents(id, role, db);
  for (const kind of REQUIRED[role] || []) if (!saved.some(doc => doc.kind === kind)) missing.push(kind);
  if (role === 'DRIVER') {
    const profile = await db.prepare('SELECT * FROM user_profiles WHERE user_id=?').get(id) || {};
    if (!profile.emergency_contact_name) missing.push('EMERGENCY_CONTACT_NAME');
    if (!normalizePhone(profile.emergency_contact_phone)) missing.push('EMERGENCY_CONTACT_PHONE');
    if (!profile.vehicle_color) missing.push('VEHICLE_COLOR');
  }
  if (requireSubmission && !record.submitted_at) missing.push('SUBMIT_FOR_REVIEW');
  return { tracked: true, ready: missing.length === 0, missing, phoneVerified,
    submittedAt: record.submitted_at || null, reviewStatus: record.review_status, reviewNote: record.review_note || '' };
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
  if (decision === 'APPROVED' && !(await getOnboardingReadiness(id, role, db)).ready) fail(428, 'The applicant must verify their phone, complete all required information and submit for review.');
  await db.prepare('UPDATE onboarding_accounts SET review_status=?,reviewed_by=?,reviewed_at=?,review_note=?,submitted_at=?,updated_at=? WHERE account_id=? AND role=?')
    .run(decision, reviewer, new Date().toISOString(), text(String(note), 'review note', 500, false), decision === 'REJECTED' ? null : record.submitted_at, new Date().toISOString(), id, role);
  return true;
}
async function invalidateOnboarding(id, role, db = DB, { phoneChanged = false } = {}) {
  if (!REQUIRED[role]) return false;
  const account = await loadAccount(id, role, db, true);
  if (!mayContinue(account)) return false;
  if (!(await enrollment(id, role, db, true))) return false;
  await db.prepare("UPDATE onboarding_accounts SET submitted_at=NULL,review_status='DRAFT',reviewed_by=NULL,reviewed_at=NULL,review_note='',updated_at=? WHERE account_id=?")
    .run(new Date().toISOString(), id);
  if (phoneChanged) {
    await db.prepare('UPDATE onboarding_accounts SET phone_verified_at=NULL,phone_verified_for=NULL WHERE account_id=?').run(id);
    await db.prepare('DELETE FROM onboarding_otps WHERE account_id=?').run(id);
  }
  if (role === 'COMPANY') await db.prepare("UPDATE transport_companies SET status='PENDING',updated_at=CURRENT_TIMESTAMP WHERE id=?").run(id);
  else await db.prepare("UPDATE users SET status='PENDING' WHERE id=? AND role='DRIVER'").run(id);
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
  async function lock(tx, id, role) {
    const account = await loadAccount(id, role, tx, true), record = await enrollment(id, role, tx, true);
    if (!account || !record) fail(401, 'Onboarding account unavailable.');
    if (!mayContinue(account)) fail(403, 'This account is disabled. Contact support.');
    return { account, record };
  }
  async function invalidate(tx, id, role, phoneChanged = false) {
    await invalidateOnboarding(id, role, tx, { phoneChanged });
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
    let name = '', company = null, plate = '', model = '', type = null;
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
        await tx.prepare("INSERT INTO users(id,role,fullName,phone,email,passwordHash,status,licenseNumber,companyName,companyId,assignedVehiclePlate) VALUES(?,'DRIVER',?,?,?,?,'PENDING',?,?,?,?)")
          .run(id, fullName, phone, phone + '@transport.et', hash, input.licenseNumber.trim(), company?.name?.slice(0, 100) || '', company?.id || null, plate);
        if (plate) await tx.prepare("INSERT INTO vehicles(id,plateNumber,model,vehicleType,capacityLimit,currentOccupancy,assignedRouteId,driverId,driverName,status) VALUES(?,?,?,?,?,0,NULL,?,?,'OFF_DUTY')")
          .run('veh_' + crypto.randomUUID().replace(/-/g, '').slice(0, 20), plate, model, type.code, type.capacity, id, fullName);
      }
      await tx.prepare('INSERT INTO onboarding_accounts(account_id,role,normalized_phone,created_at,updated_at) VALUES(?,?,?,?,?)').run(id, role, phone, time(), time());
    });
    return snapshot(id, role);
  }
  async function login(input) {
    const role = String(input.role || '').toUpperCase(), phone = normalizePhone(input.phone);
    if (!REQUIRED[role] || !phone || typeof input.password !== 'string' || Buffer.byteLength(input.password) > 72) fail(401, 'Invalid onboarding credentials.');
    const row = await db.prepare('SELECT account_id FROM onboarding_accounts WHERE role=? AND normalized_phone=?').get(role, phone);
    const account = row ? await loadAccount(row.account_id, role, db) : null;
    if (!mayContinue(account) || !(await bcrypt.compare(input.password, account.passwordHash || account.password_hash || ''))) fail(401, 'Invalid onboarding credentials.');
    return snapshot(account.id, role);
  }
  async function requestOtp(id, role) {
    const challenge = crypto.randomUUID(), code = String(crypto.randomInt(100000, 1000000));
    const account = await transact(async tx => {
      const { account, record } = await lock(tx, id, role), phone = normalizePhone(account.phone);
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
      const { account } = await lock(tx, id, role), otp = await tx.prepare('SELECT * FROM onboarding_otps WHERE account_id=?').get(id);
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
    const allowed = role === 'DRIVER' ? ['phone','emergencyContactName','emergencyContactPhone','vehicleColor'] : ['phone','address','registrationNumber'];
    if (Object.keys(input).some(key => !allowed.includes(key))) fail(400, 'Only onboarding profile fields may be changed.');
    const normalized = {};
    for (const [key, value] of Object.entries(input)) {
      if (['phone','emergencyContactPhone'].includes(key)) { normalized[key] = normalizePhone(value); if (!normalized[key]) fail(400, 'Enter a valid Ethiopian phone number.'); }
      else normalized[key] = text(value, key, key === 'address' ? 300 : key === 'vehicleColor' ? 40 : 100, false);
    }
    await transact(async tx => {
      const { account, record } = await lock(tx, id, role);
      let changed = false, phoneChanged = false;
      if (normalized.phone && normalized.phone !== normalizePhone(account.phone)) {
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
      if (changed) await invalidate(tx, id, role, phoneChanged);
    });
    return snapshot(id, role);
  }
  async function upload(id, role, kind, bytes, mime) {
    if (!REQUIRED[role]?.includes(kind)) fail(400, 'That document is not required for this account.');
    if (!Buffer.isBuffer(bytes) || !bytes.length) fail(400, 'Choose a JPEG, PNG or PDF file.');
    if (bytes.length > MAX_DOCUMENT_BYTES) fail(413, 'Each document must be 5 MB or smaller.');
    const detected = detectDocument(bytes);
    if (!detected || detected.mime !== mime || (kind === 'PHOTO' && detected.mime === 'application/pdf')) fail(415, 'Choose a valid JPEG, PNG or PDF matching its file type. Photos must be images.');
    const folder = directory(role), name = crypto.randomUUID() + '.' + detected.extension, destination = path.join(folder, name);
    await fs.mkdir(folder, { recursive: true, mode: 0o700 }); await fs.writeFile(destination, bytes, { flag: 'wx', mode: 0o600 });
    let previous;
    try {
      await transact(async tx => {
        await lock(tx, id, role);
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
      await lock(tx, id, role);
      const ready = await getOnboardingReadiness(id, role, tx, { requireSubmission: false });
      if (!ready.ready) fail(428, 'Complete phone verification and every required document and profile field before submitting.');
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
    if (!['APPROVE','REJECT'].includes(decision)) fail(400, 'Choose APPROVE or REJECT.');
    text(note, 'review note', 500, false);
    await transact(async tx => {
      const { account } = await lock(tx, id, role);
      if (!reviewAllowed(principal, account, role)) fail(404, 'Application unavailable.');
      await markOnboardingReviewed(id, role, decision === 'APPROVE' ? 'APPROVED' : 'REJECTED', principal.id, note, tx);
      if (role === 'COMPANY') await tx.prepare('UPDATE transport_companies SET status=?,updated_at=CURRENT_TIMESTAMP WHERE id=?').run(decision === 'APPROVE' ? 'ACTIVE' : 'PENDING', id);
      else await tx.prepare("UPDATE users SET status=? WHERE id=? AND role='DRIVER'").run(decision === 'APPROVE' ? 'ACTIVE' : 'PENDING', id);
      await tx.prepare('INSERT INTO audit_logs(action,userId,role,details) VALUES(?,?,?,?)').run('ONBOARDING_' + decision, principal.id, principal.role, `${decision} ${role} application ${id}`);
    });
    return snapshot(id, role);
  }
  return { register, login, snapshot, requestOtp, verifyOtp, updateProfile, upload, readDocument, submit, mayReview, applications, review };
}
module.exports = { createOnboardingService, initOnboardingSchema, getOnboardingReadiness, isOperationallyEligible,
  markOnboardingReviewed, invalidateOnboarding, normalizePhone, phoneAvailable, findAccountByPhone, OnboardingError, REQUIRED, MAX_DOCUMENT_BYTES };
