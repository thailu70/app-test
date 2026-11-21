'use strict';

const crypto = require('crypto');
const { DB } = require('../db');
const { normalizePhone, getTestLoginOtpConfig, testOtpRoleForPhone, isConfiguredTestOtpRecord } = require('./test-login-otp');

const schemas = new WeakMap();
const sha256 = value => crypto.createHash('sha256').update(String(value)).digest('hex');
const enabled = () => process.env.PASSO_SMS_GATEWAY_ENABLED !== 'false' &&
  !require('../review/isolation').getReviewRuntime();

function fail(status, message) {
  const error = new Error(message);
  error.status = status;
  throw error;
}

async function initPhoneSmsGatewaySchema(db = DB) {
  if (!schemas.has(db)) schemas.set(db, Promise.resolve().then(async () => {
    await db.exec(
      'CREATE TABLE IF NOT EXISTS phone_sms_pair_codes (' +
      'id TEXT PRIMARY KEY, code_hash TEXT NOT NULL, expires_at TIMESTAMP NOT NULL, used_at TIMESTAMP, created_at TIMESTAMP NOT NULL);' +
      'CREATE INDEX IF NOT EXISTS phone_sms_pair_code_expiry ON phone_sms_pair_codes(expires_at,used_at);' +
      'CREATE TABLE IF NOT EXISTS phone_sms_devices (' +
      "id TEXT PRIMARY KEY, token_hash TEXT NOT NULL UNIQUE, device_name TEXT NOT NULL DEFAULT 'Android phone', public_key TEXT," +
      'created_at TIMESTAMP NOT NULL, last_seen_at TIMESTAMP, revoked_at TIMESTAMP);' +
      'CREATE TABLE IF NOT EXISTS phone_sms_jobs (' +
      'id TEXT PRIMARY KEY, otp_id TEXT NOT NULL REFERENCES auth_otps(id) ON DELETE CASCADE,' +
      "phone TEXT NOT NULL, message TEXT NOT NULL DEFAULT '', encrypted_payload TEXT, encrypted_for_device TEXT," +
      "status TEXT NOT NULL DEFAULT 'PENDING', attempts INTEGER NOT NULL DEFAULT 0, expires_at TIMESTAMP NOT NULL," +
      'lease_token TEXT, lease_until TIMESTAMP, leased_by TEXT REFERENCES phone_sms_devices(id), sent_at TIMESTAMP, created_at TIMESTAMP NOT NULL);' +
      'CREATE UNIQUE INDEX IF NOT EXISTS phone_sms_job_otp ON phone_sms_jobs(otp_id);' +
      'CREATE INDEX IF NOT EXISTS phone_sms_job_queue ON phone_sms_jobs(status,expires_at,created_at);'
    );
    if (db.isPostgres) {
      await db.prepare('ALTER TABLE phone_sms_devices ADD COLUMN IF NOT EXISTS public_key TEXT').run();
      await db.prepare('ALTER TABLE phone_sms_jobs ADD COLUMN IF NOT EXISTS encrypted_payload TEXT').run();
      await db.prepare('ALTER TABLE phone_sms_jobs ADD COLUMN IF NOT EXISTS encrypted_for_device TEXT').run();
    } else {
      const deviceColumns = await db.prepare('PRAGMA table_info(phone_sms_devices)').all();
      if (!deviceColumns.some(column => column.name === 'public_key')) await db.prepare('ALTER TABLE phone_sms_devices ADD COLUMN public_key TEXT').run();
      const jobColumns = await db.prepare('PRAGMA table_info(phone_sms_jobs)').all();
      if (!jobColumns.some(column => column.name === 'encrypted_payload')) await db.prepare('ALTER TABLE phone_sms_jobs ADD COLUMN encrypted_payload TEXT').run();
      if (!jobColumns.some(column => column.name === 'encrypted_for_device')) await db.prepare('ALTER TABLE phone_sms_jobs ADD COLUMN encrypted_for_device TEXT').run();
    }
  }).catch(error => { schemas.delete(db); throw error; }));
  return schemas.get(db);
}

async function getDeliveryMode({ db = DB } = {}) {
  const row = await db.prepare("SELECT value FROM app_settings WHERE key='OTP_PILOT_DELIVERY_MODE'").get();
  const saved = String(row?.value || '').trim().toUpperCase();
  if (saved === 'AFRO_SMS' || saved === 'PHONE_GATEWAY') return saved;
  return String(process.env.PASSO_TEST_OTP_DELIVERY || '').toUpperCase() === 'PHONE_GATEWAY' ? 'PHONE_GATEWAY' : 'AFRO_SMS';
}

async function setDeliveryMode(mode, { db = DB } = {}) {
  const value = String(mode || '').trim().toUpperCase();
  if (!['AFRO_SMS', 'PHONE_GATEWAY'].includes(value)) fail(400, 'Choose Afro SMS or This phone (pilot).');
  if (value === 'PHONE_GATEWAY' && !enabled()) fail(503, 'Enable the pilot phone gateway in the private server settings first.');
  await db.prepare("INSERT INTO app_settings(key,value,updated_at) VALUES('OTP_PILOT_DELIVERY_MODE',?,CURRENT_TIMESTAMP) ON CONFLICT(key) DO UPDATE SET value=excluded.value,updated_at=CURRENT_TIMESTAMP").run(value);
  return value;
}

async function getPilotPhoneNumbers({ db = DB } = {}) {
  const config = getTestLoginOtpConfig();
  const builtIn = config ? [config.passengerPhone, config.driverPhone] : [];
  return [...new Set([...builtIn, ...(await getAdditionalPilotPhoneNumbers({ db }))])];
}

async function getAdditionalPilotPhoneNumbers({ db = DB } = {}) {
  const row = await db.prepare("SELECT value FROM app_settings WHERE key='OTP_PILOT_PHONE_NUMBERS'").get();
  let configured = [];
  try { configured = JSON.parse(String(row?.value || '[]')); } catch (_) { configured = []; }
  if (!Array.isArray(configured)) configured = [];
  const builtIn = getTestLoginOtpConfig();
  const reserved = new Set(builtIn ? [builtIn.passengerPhone, builtIn.driverPhone] : []);
  return [...new Set(configured.map(normalizePhone).filter(phone => phone && !reserved.has(phone)))];
}

function canonicalizePilotPhoneNumbers(numbers) {
  if (!Array.isArray(numbers) || numbers.length > 50) fail(400, 'Add up to 50 pilot phone numbers.');
  const canonical = numbers.map(normalizePhone);
  if (canonical.some(phone => !phone)) fail(400, 'Enter valid Ethiopian mobile numbers.');
  const config = getTestLoginOtpConfig();
  const builtIn = new Set(config ? [config.passengerPhone, config.driverPhone] : []);
  return [...new Set(canonical)].filter(phone => !builtIn.has(phone));
}

async function savePilotPhoneNumbers(numbers, { db = DB } = {}) {
  const extras = canonicalizePilotPhoneNumbers(numbers);
  await db.prepare("INSERT INTO app_settings(key,value,updated_at) VALUES('OTP_PILOT_PHONE_NUMBERS',?,CURRENT_TIMESTAMP) ON CONFLICT(key) DO UPDATE SET value=excluded.value,updated_at=CURRENT_TIMESTAMP")
    .run(JSON.stringify(extras));
  return getPilotPhoneNumbers({ db });
}

async function savePilotSettings({ deliveryMode, pilotNumbers }, { db = DB } = {}) {
  const mode = String(deliveryMode || '').trim().toUpperCase();
  if (!['AFRO_SMS', 'PHONE_GATEWAY'].includes(mode)) fail(400, 'Choose Afro SMS or This phone (pilot).');
  if (mode === 'PHONE_GATEWAY' && !enabled()) fail(503, 'Enable the pilot phone gateway in the private server settings first.');
  const extras = canonicalizePilotPhoneNumbers(pilotNumbers);
  await db.transaction(async tx => {
    await tx.prepare("INSERT INTO app_settings(key,value,updated_at) VALUES('OTP_PILOT_DELIVERY_MODE',?,CURRENT_TIMESTAMP) ON CONFLICT(key) DO UPDATE SET value=excluded.value,updated_at=CURRENT_TIMESTAMP").run(mode);
    await tx.prepare("INSERT INTO app_settings(key,value,updated_at) VALUES('OTP_PILOT_PHONE_NUMBERS',?,CURRENT_TIMESTAMP) ON CONFLICT(key) DO UPDATE SET value=excluded.value,updated_at=CURRENT_TIMESTAMP").run(JSON.stringify(extras));
  });
  return { deliveryMode: mode, pilotNumbers: await getPilotPhoneNumbers({ db }) };
}

async function isPilotPhone(phone, { db = DB } = {}) {
  const canonical = normalizePhone(phone);
  if (!canonical) return false;
  return (await getPilotPhoneNumbers({ db })).includes(canonical);
}

async function createPairCode({ db = DB, now = Date.now } = {}) {
  if (!enabled()) fail(503, 'Phone SMS gateway is disabled.');
  await initPhoneSmsGatewaySchema(db);
  const code = String(crypto.randomInt(10000000, 100000000));
  const createdAt = new Date(now()).toISOString();
  const expiresAt = new Date(now() + 10 * 60 * 1000).toISOString();
  await db.transaction(async tx => {
    await tx.prepare('UPDATE phone_sms_pair_codes SET used_at=? WHERE used_at IS NULL').run(createdAt);
    await tx.prepare('INSERT INTO phone_sms_pair_codes(id,code_hash,expires_at,created_at) VALUES(?,?,?,?)')
      .run('pair_' + crypto.randomUUID().replace(/-/g, ''), sha256(code), expiresAt, createdAt);
  });
  return { code, expiresInSeconds: 600 };
}

async function pairDevice({ code, deviceName, publicKey, db = DB, now = Date.now } = {}) {
  if (!enabled()) fail(503, 'Phone SMS gateway is disabled.');
  await initPhoneSmsGatewaySchema(db);
  const normalizedCode = String(code || '').trim();
  if (!/^\d{8}$/.test(normalizedCode)) fail(400, 'Enter the 8-digit pairing code from Passo Admin.');
  const pem = String(publicKey || '').trim();
  if (!pem || pem.length > 10000) fail(400, 'The phone did not provide a valid encryption key.');
  let parsedKey;
  try { parsedKey = crypto.createPublicKey(pem); } catch (_) { fail(400, 'The phone encryption key is invalid.'); }
  if (parsedKey.asymmetricKeyType !== 'rsa' || (parsedKey.asymmetricKeyDetails?.modulusLength || 0) < 2048) {
    fail(400, 'The phone encryption key must be RSA 2048-bit or stronger.');
  }
  const canonicalPublicKey = parsedKey.export({ type: 'spki', format: 'pem' }).toString();
  const token = crypto.randomBytes(32).toString('base64url');
  const id = 'smsdev_' + crypto.randomUUID().replace(/-/g, '').slice(0, 16);
  const timestamp = new Date(now()).toISOString();
  const name = String(deviceName || 'Android phone').trim().replace(/[\u0000-\u001f\u007f]/g, '').slice(0, 80) || 'Android phone';
  const paired = await db.transaction(async tx => {
    const row = await tx.prepare('SELECT id FROM phone_sms_pair_codes WHERE code_hash=? AND used_at IS NULL AND expires_at>? ORDER BY created_at DESC LIMIT 1' + (tx.isPostgres ? ' FOR UPDATE' : ''))
      .get(sha256(normalizedCode), timestamp);
    if (!row) return false;
    const consumed = await tx.prepare('UPDATE phone_sms_pair_codes SET used_at=? WHERE id=? AND used_at IS NULL AND expires_at>?')
      .run(timestamp, row.id, timestamp);
    if (consumed.changes !== 1) return false;
    await tx.prepare('INSERT INTO phone_sms_devices(id,token_hash,device_name,public_key,created_at,last_seen_at) VALUES(?,?,?,?,?,?)')
      .run(id, sha256(token), name, canonicalPublicKey, timestamp, timestamp);
    return true;
  });
  if (!paired) fail(401, 'Pairing code is invalid or expired. Request a new one.');
  return { deviceId: id, token, deviceName: name };
}

async function authenticateDevice(token, { db = DB, now = Date.now } = {}) {
  if (!enabled() || typeof token !== 'string' || token.length < 32 || token.length > 200) return null;
  await initPhoneSmsGatewaySchema(db);
  const timestamp = new Date(now()).toISOString();
  const id = await db.transaction(async tx => {
    const row = await tx.prepare('SELECT id FROM phone_sms_devices WHERE token_hash=? AND revoked_at IS NULL LIMIT 1')
      .get(sha256(token));
    if (!row) return '';
    await tx.prepare('UPDATE phone_sms_devices SET last_seen_at=? WHERE id=? AND revoked_at IS NULL').run(timestamp, row.id);
    return row.id;
  });
  return id || null;
}

async function adminStatus({ db = DB, now = Date.now } = {}) {
  await initPhoneSmsGatewaySchema(db);
  const devices = await db.prepare('SELECT id,device_name,created_at,last_seen_at,revoked_at FROM phone_sms_devices ORDER BY created_at DESC LIMIT 20').all();
  const cutoff = now() - 2 * 60 * 1000;
  return {
    enabled: enabled(),
    deliveryMode: await getDeliveryMode({ db }),
    pilotNumbers: await getPilotPhoneNumbers({ db }),
    additionalPilotNumbers: await getAdditionalPilotPhoneNumbers({ db }),
    devices: devices.map(device => ({
      ...device,
      online: !device.revoked_at && Number.isFinite(Date.parse(device.last_seen_at)) && Date.parse(device.last_seen_at) >= cutoff
    }))
  };
}

async function revokeDevice(deviceId, { db = DB, now = Date.now } = {}) {
  await initPhoneSmsGatewaySchema(db);
  const timestamp = new Date(now()).toISOString();
  const result = await db.transaction(async tx => {
    const revoked = await tx.prepare('UPDATE phone_sms_devices SET revoked_at=? WHERE id=? AND revoked_at IS NULL').run(timestamp, deviceId);
    await tx.prepare("UPDATE phone_sms_jobs SET status='CANCELLED',message='',encrypted_payload=NULL,lease_token=NULL,lease_until=NULL WHERE encrypted_for_device=? AND status IN ('PENDING','SENDING')").run(deviceId);
    return revoked.changes;
  });
  return result === 1;
}

async function cancelUnsentForPhone(phone, { db = DB } = {}) {
  await initPhoneSmsGatewaySchema(db);
  return db.prepare("UPDATE phone_sms_jobs SET status='CANCELLED',message='',encrypted_payload=NULL,lease_token=NULL,lease_until=NULL WHERE phone=? AND status IN ('PENDING','SENDING')").run(phone);
}

async function queueTestOtp({ otpId, phone, code, db = DB, now = Date.now } = {}) {
  if (!enabled()) return { queued: false, reason: 'Phone SMS gateway is disabled.' };
  await initPhoneSmsGatewaySchema(db);
  const normalized = normalizePhone(phone);
  const codeValue = String(code || '').trim();
  if (!normalized || !/^\d{6}$/.test(codeValue) || !(await isPilotPhone(normalized, { db }))) {
    return { queued: false, reason: 'Only approved pilot phone numbers can use the phone gateway.' };
  }
  const otp = await db.prepare('SELECT code_hash,purpose,expires_at,verified_at FROM auth_otps WHERE id=? AND phone=? LIMIT 1').get(otpId, normalized);
  const staticTestRecord = isConfiguredTestOtpRecord(getTestLoginOtpConfig(), otp?.code_hash, normalized, 'LOGIN');
  const dynamicCodeMatches = otp?.code_hash === sha256(codeValue);
  if (!otp || otp.purpose !== 'LOGIN' || otp.verified_at || Date.parse(otp.expires_at) <= now() ||
      (!staticTestRecord && !dynamicCodeMatches)) {
    return { queued: false, reason: 'Only active sign-in codes can use the phone gateway.' };
  }
  const device = await db.prepare('SELECT id,public_key FROM phone_sms_devices WHERE public_key IS NOT NULL AND revoked_at IS NULL AND last_seen_at>? ORDER BY last_seen_at DESC LIMIT 1')
    .get(new Date(now() - 2 * 60 * 1000).toISOString());
  if (!device) return { queued: false, reason: 'The phone SMS gateway is offline.' };
  const payload = Buffer.from(JSON.stringify({
    phone: normalized,
    message: 'PASSO sign-in verification code: ' + codeValue + '. It expires in 10 minutes. Do not share it.'
  }), 'utf8');
  let encryptedPayload;
  try {
    encryptedPayload = crypto.publicEncrypt({
      key: device.public_key,
      padding: crypto.constants.RSA_PKCS1_OAEP_PADDING,
      oaepHash: 'sha256'
    }, payload).toString('base64');
  } catch (_) {
    return { queued: false, reason: 'The phone encryption key could not be used.' };
  } finally { payload.fill(0); }
  const timestamp = new Date(now()).toISOString();
  await db.transaction(async tx => {
    await tx.prepare("UPDATE phone_sms_jobs SET status='CANCELLED',message='',encrypted_payload=NULL,lease_token=NULL,lease_until=NULL WHERE phone=? AND status IN ('PENDING','SENDING')").run(normalized);
    // OTP plaintext exists only in this request and the encrypted payload stored for its paired device.
    await tx.prepare('INSERT INTO phone_sms_jobs(id,otp_id,phone,message,encrypted_payload,encrypted_for_device,expires_at,created_at) VALUES(?,?,?,?,?,?,?,?)')
      .run('smsjob_' + crypto.randomUUID().replace(/-/g, '').slice(0, 18), otpId, normalized, '', encryptedPayload, device.id, otp.expires_at, timestamp);
  });
  return { queued: true };
}

async function claimNextJob(deviceId, { db = DB, now = Date.now } = {}) {
  await initPhoneSmsGatewaySchema(db);
  const timestamp = new Date(now()).toISOString();
  return db.transaction(async tx => {
    await tx.prepare("UPDATE phone_sms_jobs SET status='PENDING',lease_token=NULL,lease_until=NULL,leased_by=NULL WHERE status='SENDING' AND lease_until<=? AND expires_at>? AND attempts<3")
      .run(timestamp, timestamp);
    await tx.prepare("UPDATE phone_sms_jobs SET status='FAILED',message='',encrypted_payload=NULL,lease_token=NULL,lease_until=NULL,leased_by=NULL WHERE status='SENDING' AND attempts>=3 AND lease_until<=?")
      .run(timestamp);
    await tx.prepare("UPDATE phone_sms_jobs SET status='EXPIRED',message='',encrypted_payload=NULL,lease_token=NULL,lease_until=NULL WHERE status IN ('PENDING','SENDING') AND expires_at<=?").run(timestamp);
    const row = await tx.prepare(
      "SELECT j.id,j.encrypted_payload FROM phone_sms_jobs j JOIN auth_otps o ON o.id=j.otp_id " +
      'JOIN phone_sms_devices d ON d.id=? AND d.revoked_at IS NULL ' +
      "WHERE j.encrypted_for_device=d.id AND j.status='PENDING' AND j.expires_at>? AND o.expires_at>? AND o.verified_at IS NULL AND j.attempts<3 " +
      'ORDER BY j.created_at,j.id LIMIT 1' + (tx.isPostgres ? ' FOR UPDATE OF j SKIP LOCKED' : '')
    ).get(deviceId, timestamp, timestamp);
    if (!row || !row.encrypted_payload) return null;
    const leaseToken = crypto.randomBytes(24).toString('hex');
    const leaseUntil = new Date(now() + 60 * 1000).toISOString();
    const updated = await tx.prepare("UPDATE phone_sms_jobs SET status='SENDING',attempts=attempts+1,lease_token=?,lease_until=?,leased_by=? WHERE id=? AND status='PENDING'")
      .run(leaseToken, leaseUntil, deviceId, row.id);
    if (updated.changes !== 1) return null;
    return { id: row.id, encryptedPayload: row.encrypted_payload, leaseToken };
  });
}

async function acknowledgeJob(deviceId, jobId, leaseToken, success, { db = DB, now = Date.now } = {}) {
  await initPhoneSmsGatewaySchema(db);
  const status = success ? 'SENT' : 'FAILED';
  const result = await db.prepare(
    "UPDATE phone_sms_jobs SET status=?,message='',encrypted_payload=NULL,sent_at=?,lease_token=NULL,lease_until=NULL WHERE id=? AND leased_by=? AND lease_token=? AND status='SENDING'"
  ).run(status, success ? new Date(now()).toISOString() : null, jobId, deviceId, String(leaseToken || ''));
  return result.changes === 1;
}

module.exports = {
  initPhoneSmsGatewaySchema, createPairCode, pairDevice, authenticateDevice, adminStatus,
  getDeliveryMode, setDeliveryMode, getPilotPhoneNumbers, getAdditionalPilotPhoneNumbers,
  savePilotPhoneNumbers, savePilotSettings, isPilotPhone,
  revokeDevice, cancelUnsentForPhone, queueTestOtp, claimNextJob, acknowledgeJob
};

