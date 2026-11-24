'use strict';

const crypto = require('crypto');
const { DB } = require('../db');
const { normalizePhone, getTestLoginOtpConfig, testOtpRoleForPhone, isConfiguredTestOtpRecord } = require('./test-login-otp');

const schemas = new WeakMap();
const sha256 = value => crypto.createHash('sha256').update(String(value)).digest('hex');
const GSM7_BASIC = new Set(Array.from("@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà"));
const GSM7_EXTENDED = new Set(Array.from("^{}\\[~]|€\f"));

function smsSegmentCount(message) {
  let septets = 0, gsm7 = true;
  for (const character of message) {
    if (GSM7_BASIC.has(character)) septets++;
    else if (GSM7_EXTENDED.has(character)) septets += 2;
    else { gsm7 = false; break; }
  }
  if (gsm7) return septets <= 160 ? 1 : Math.ceil(septets / 153);
  const utf16Units = message.length;
  return utf16Units <= 70 ? 1 : Math.ceil(utf16Units / 67);
}
const enabled = () => process.env.PASSO_SMS_GATEWAY_ENABLED !== 'false' &&
  !require('../review/isolation').getReviewRuntime();

function fail(status, message) {
  const error = new Error(message);
  error.status = status;
  throw error;
}

function encryptGatewayPayload(publicKey, payload) {
  const key = crypto.randomBytes(32), iv = crypto.randomBytes(12);
  try {
    const cipher = crypto.createCipheriv('aes-256-gcm', key, iv);
    const ciphertext = Buffer.concat([cipher.update(payload), cipher.final(), cipher.getAuthTag()]);
    const wrappedKey = crypto.publicEncrypt({ key: publicKey, padding: crypto.constants.RSA_PKCS1_OAEP_PADDING, oaepHash: 'sha256' }, key);
    const envelope = JSON.stringify({ version: 2, key: wrappedKey.toString('base64'), iv: iv.toString('base64'), ciphertext: ciphertext.toString('base64') });
    return 'P2.' + Buffer.from(envelope, 'utf8').toString('base64');
  } finally { key.fill(0); iv.fill(0); }
}

async function initPhoneSmsGatewaySchema(db = DB) {
  if (!schemas.has(db)) schemas.set(db, Promise.resolve().then(async () => {
    await db.exec(
      'CREATE TABLE IF NOT EXISTS phone_sms_pair_codes (' +
      'id TEXT PRIMARY KEY, code_hash TEXT NOT NULL, expires_at TIMESTAMP NOT NULL, used_at TIMESTAMP, created_at TIMESTAMP NOT NULL);' +
      'CREATE INDEX IF NOT EXISTS phone_sms_pair_code_expiry ON phone_sms_pair_codes(expires_at,used_at);' +
      'CREATE TABLE IF NOT EXISTS phone_sms_devices (' +
      "id TEXT PRIMARY KEY, token_hash TEXT NOT NULL UNIQUE, device_name TEXT NOT NULL DEFAULT 'Android phone', public_key TEXT," +
      'sms_permission_granted BOOLEAN NOT NULL DEFAULT FALSE, created_at TIMESTAMP NOT NULL, last_seen_at TIMESTAMP, revoked_at TIMESTAMP);' +
      'CREATE TABLE IF NOT EXISTS phone_sms_jobs (' +
      'id TEXT PRIMARY KEY, otp_id TEXT NOT NULL REFERENCES auth_otps(id) ON DELETE CASCADE,' +
      "phone TEXT NOT NULL, message TEXT NOT NULL DEFAULT '', encrypted_payload TEXT, encrypted_for_device TEXT," +
      "status TEXT NOT NULL DEFAULT 'PENDING', attempts INTEGER NOT NULL DEFAULT 0, expires_at TIMESTAMP NOT NULL," +
      'lease_token TEXT, lease_until TIMESTAMP, leased_by TEXT REFERENCES phone_sms_devices(id), sent_at TIMESTAMP, created_at TIMESTAMP NOT NULL, failure_code TEXT);' +
      'CREATE UNIQUE INDEX IF NOT EXISTS phone_sms_job_otp ON phone_sms_jobs(otp_id);' +
      'CREATE INDEX IF NOT EXISTS phone_sms_job_queue ON phone_sms_jobs(status,expires_at,created_at);' +
      'CREATE TABLE IF NOT EXISTS phone_sms_message_jobs (' +
      'id TEXT PRIMARY KEY, event_key TEXT NOT NULL UNIQUE, phone TEXT NOT NULL, encrypted_payload TEXT NOT NULL,' +
      'encrypted_for_device TEXT NOT NULL REFERENCES phone_sms_devices(id), status TEXT NOT NULL DEFAULT \'PENDING\',' +
      'attempts INTEGER NOT NULL DEFAULT 0, next_attempt_at TIMESTAMP NOT NULL, expires_at TIMESTAMP NOT NULL,' +
      'lease_token TEXT, lease_until TIMESTAMP, leased_by TEXT REFERENCES phone_sms_devices(id), sent_at TIMESTAMP, created_at TIMESTAMP NOT NULL, failure_code TEXT);' +
      'CREATE INDEX IF NOT EXISTS phone_sms_message_queue ON phone_sms_message_jobs(status,next_attempt_at,expires_at,created_at);'
    );
    if (db.isPostgres) {
      await db.prepare('ALTER TABLE phone_sms_devices ADD COLUMN IF NOT EXISTS public_key TEXT').run();
      await db.prepare('ALTER TABLE phone_sms_devices ADD COLUMN IF NOT EXISTS sms_permission_granted BOOLEAN NOT NULL DEFAULT FALSE').run();
      await db.prepare('ALTER TABLE phone_sms_jobs ADD COLUMN IF NOT EXISTS encrypted_payload TEXT').run();
      await db.prepare('ALTER TABLE phone_sms_jobs ADD COLUMN IF NOT EXISTS encrypted_for_device TEXT').run();
      await db.prepare('ALTER TABLE phone_sms_jobs ADD COLUMN IF NOT EXISTS failure_code TEXT').run();
      await db.prepare('ALTER TABLE phone_sms_message_jobs ADD COLUMN IF NOT EXISTS failure_code TEXT').run();
    } else {
      const deviceColumns = await db.prepare('PRAGMA table_info(phone_sms_devices)').all();
      if (!deviceColumns.some(column => column.name === 'public_key')) await db.prepare('ALTER TABLE phone_sms_devices ADD COLUMN public_key TEXT').run();
      if (!deviceColumns.some(column => column.name === 'sms_permission_granted')) await db.prepare('ALTER TABLE phone_sms_devices ADD COLUMN sms_permission_granted BOOLEAN NOT NULL DEFAULT FALSE').run();
      const jobColumns = await db.prepare('PRAGMA table_info(phone_sms_jobs)').all();
      if (!jobColumns.some(column => column.name === 'encrypted_payload')) await db.prepare('ALTER TABLE phone_sms_jobs ADD COLUMN encrypted_payload TEXT').run();
      if (!jobColumns.some(column => column.name === 'encrypted_for_device')) await db.prepare('ALTER TABLE phone_sms_jobs ADD COLUMN encrypted_for_device TEXT').run();
      if (!jobColumns.some(column => column.name === 'failure_code')) await db.prepare('ALTER TABLE phone_sms_jobs ADD COLUMN failure_code TEXT').run();
      const messageColumns = await db.prepare('PRAGMA table_info(phone_sms_message_jobs)').all();
      if (!messageColumns.some(column => column.name === 'failure_code')) await db.prepare('ALTER TABLE phone_sms_message_jobs ADD COLUMN failure_code TEXT').run();
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
  if (!['AFRO_SMS', 'PHONE_GATEWAY'].includes(value)) fail(400, 'Choose Afro SMS or Phone gateway.');
  if (value === 'PHONE_GATEWAY' && !enabled()) fail(503, 'Enable the phone gateway in the private server settings first.');
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
  if (!Array.isArray(numbers) || numbers.length > 50) fail(400, 'Add up to 50 phone numbers.');
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
  if (!['AFRO_SMS', 'PHONE_GATEWAY'].includes(mode)) fail(400, 'Choose Afro SMS or Phone gateway.');
  if (mode === 'PHONE_GATEWAY' && !enabled()) fail(503, 'Enable the phone gateway in the private server settings first.');
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

async function authenticateDevice(token, { db = DB, now = Date.now, smsPermissionGranted } = {}) {
  if (!enabled() || typeof token !== 'string' || token.length < 32 || token.length > 200) return null;
  await initPhoneSmsGatewaySchema(db);
  const timestamp = new Date(now()).toISOString();
  const id = await db.transaction(async tx => {
    const row = await tx.prepare('SELECT id FROM phone_sms_devices WHERE token_hash=? AND revoked_at IS NULL LIMIT 1')
      .get(sha256(token));
    if (!row) return '';
    if (typeof smsPermissionGranted === 'boolean') {
      const smsPermission = db.isPostgres ? smsPermissionGranted : (smsPermissionGranted ? 1 : 0);
      await tx.prepare('UPDATE phone_sms_devices SET last_seen_at=?,sms_permission_granted=? WHERE id=? AND revoked_at IS NULL').run(timestamp, smsPermission, row.id);
    } else {
      await tx.prepare('UPDATE phone_sms_devices SET last_seen_at=? WHERE id=? AND revoked_at IS NULL').run(timestamp, row.id);
    }
    return row.id;
  });
  return id || null;
}

async function adminStatus({ db = DB, now = Date.now } = {}) {
  await initPhoneSmsGatewaySchema(db);
  const devices = await db.prepare('SELECT id,device_name,created_at,last_seen_at,revoked_at,sms_permission_granted FROM phone_sms_devices ORDER BY created_at DESC LIMIT 20').all();
  const cutoff = now() - 2 * 60 * 1000;
  const counts = await db.prepare('SELECT status,COUNT(*) AS count FROM phone_sms_message_jobs GROUP BY status').all();
  const latestFailure = await db.prepare(
    "SELECT failure_code,created_at FROM (SELECT failure_code,created_at FROM phone_sms_message_jobs WHERE status='FAILED' AND failure_code IS NOT NULL UNION ALL SELECT failure_code,created_at FROM phone_sms_jobs WHERE status='FAILED' AND failure_code IS NOT NULL) failures ORDER BY created_at DESC LIMIT 1"
  ).get();
  return {
    enabled: enabled(),
    deliveryMode: await getDeliveryMode({ db }),
    pilotNumbers: await getPilotPhoneNumbers({ db }),
    additionalPilotNumbers: await getAdditionalPilotPhoneNumbers({ db }),
    deliveryCounts: Object.fromEntries(counts.map(row => [String(row.status || '').toLowerCase(), Number(row.count || 0)])),
    latestFailure: latestFailure ? { failureCode: latestFailure.failure_code, occurredAt: latestFailure.created_at } : null,
    devices: devices.map(device => ({
      ...device,
      smsPermissionGranted: Boolean(device.sms_permission_granted),
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
    await tx.prepare("UPDATE phone_sms_message_jobs SET status='CANCELLED',encrypted_payload='',lease_token=NULL,lease_until=NULL WHERE encrypted_for_device=? AND status IN ('PENDING','SENDING')").run(deviceId);
    return revoked.changes;
  });
  return result === 1;
}

async function cancelUnsentForPhone(phone, { db = DB } = {}) {
  await initPhoneSmsGatewaySchema(db);
  const otp = await db.prepare("UPDATE phone_sms_jobs SET status='CANCELLED',message='',encrypted_payload=NULL,lease_token=NULL,lease_until=NULL WHERE phone=? AND status IN ('PENDING','SENDING')").run(phone);
  const messages = await db.prepare("UPDATE phone_sms_message_jobs SET status='CANCELLED',encrypted_payload='',lease_token=NULL,lease_until=NULL WHERE phone=? AND status IN ('PENDING','SENDING')").run(phone);
  return { changes: Number(otp.changes || 0) + Number(messages.changes || 0) };
}

async function queueOtp({ otpId, phone, code, db = DB, now = Date.now } = {}) {
  if (!enabled()) return { queued: false, reason: 'Phone SMS gateway is disabled.' };
  await initPhoneSmsGatewaySchema(db);
  const normalized = normalizePhone(phone);
  const codeValue = String(code || '').trim();
  if (!normalized || !/^\d{6}$/.test(codeValue)) {
    return { queued: false, reason: 'Enter a valid Ethiopian mobile number and active six-digit code.' };
  }
  const otp = await db.prepare('SELECT code_hash,purpose,expires_at,verified_at FROM auth_otps WHERE id=? AND phone=? LIMIT 1').get(otpId, normalized);
  const staticTestRecord = isConfiguredTestOtpRecord(getTestLoginOtpConfig(), otp?.code_hash, normalized, 'LOGIN');
  const dynamicCodeMatches = otp?.code_hash === sha256(codeValue);
  if (!otp || !['LOGIN', 'REGISTRATION', 'CARGO_REQUEST', 'GROUP_REQUEST'].includes(otp.purpose) || otp.verified_at || Date.parse(otp.expires_at) <= now() ||
      (!staticTestRecord && !dynamicCodeMatches)) {
    return { queued: false, reason: 'Only active sign-in and public-request codes can use the phone gateway.' };
  }
  const device = await db.prepare('SELECT id,public_key FROM phone_sms_devices WHERE public_key IS NOT NULL AND sms_permission_granted=TRUE AND revoked_at IS NULL AND last_seen_at>? ORDER BY last_seen_at DESC LIMIT 1')
    .get(new Date(now() - 2 * 60 * 1000).toISOString());
  if (!device) return { queued: false, reason: 'The phone SMS gateway is offline.' };
  const label = otp.purpose === 'CARGO_REQUEST' ? 'cargo request' : otp.purpose === 'GROUP_REQUEST' ? 'group travel request' : otp.purpose === 'REGISTRATION' ? 'registration' : 'sign-in';
  const payload = Buffer.from(JSON.stringify({
    phone: normalized,
    message: `PASSO ${label} verification code: ${codeValue}. It expires in 10 minutes. Do not share it.`
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

/** Queues encrypted, branded trip-update SMS for any valid Ethiopian mobile number. */
async function queueServiceMessage({ eventKey, phone, message, db = DB, now = Date.now } = {}) {
  if (!enabled()) return { queued: false };
  await initPhoneSmsGatewaySchema(db);
  const normalized = normalizePhone(phone), sourceKey = String(eventKey || '').trim(), body = String(message || '').trim();
  if (!normalized || !sourceKey || sourceKey.length > 400 || /[\u0000-\u001f\u007f]/.test(sourceKey) || !body || body.length > 1530 ||
      smsSegmentCount(body) > 10 || !/^(?:PASSO(?:[\p{L}\p{N}]+|[ :])|RoutePass(?::| ))[^\r\n]{1,1530}$/u.test(body)) return { queued: false };
  const key = crypto.createHash('sha256').update(sourceKey, 'utf8').digest('hex');
  let existing = await db.prepare('SELECT id,status,failure_code FROM phone_sms_message_jobs WHERE event_key=?').get(key);
  if (!existing) {
    const device = await db.prepare('SELECT id,public_key FROM phone_sms_devices WHERE public_key IS NOT NULL AND sms_permission_granted=TRUE AND revoked_at IS NULL AND last_seen_at>? ORDER BY last_seen_at DESC LIMIT 1')
      .get(new Date(now() - 2 * 60 * 1000).toISOString());
    if (!device) return { queued: false };
    const payload = Buffer.from(JSON.stringify({ phone: normalized, message: body }), 'utf8');
    let encryptedPayload;
    try { encryptedPayload = encryptGatewayPayload(device.public_key, payload); }
    catch (_) { return { queued: false }; }
    finally { payload.fill(0); }
    const timestamp = new Date(now()).toISOString();
    await db.prepare('INSERT INTO phone_sms_message_jobs(id,event_key,phone,encrypted_payload,encrypted_for_device,status,attempts,next_attempt_at,expires_at,created_at) VALUES(?,?,?,?,?,\'PENDING\',0,?,?,?) ON CONFLICT(event_key) DO NOTHING')
      .run('smsmsg_' + crypto.randomUUID().replace(/-/g, '').slice(0, 18), key, normalized, encryptedPayload, device.id,
        timestamp, new Date(now() + 24 * 60 * 60 * 1000).toISOString(), timestamp);
    existing = await db.prepare('SELECT id,status,failure_code FROM phone_sms_message_jobs WHERE event_key=?').get(key);
  }
  if (!existing) return { queued: false };
  if (existing.status === 'SENT') return { queued: false, sent: true, messageJobId: existing.id };
  if (['FAILED', 'CANCELLED', 'EXPIRED'].includes(existing.status)) {
    return { queued: false, failed: true, failureCode: existing.failure_code || `message_${String(existing.status).toLowerCase()}`, messageJobId: existing.id };
  }
  return { queued: true, messageJobId: existing.id, status: existing.status };
}

async function claimNextJob(deviceId, { db = DB, now = Date.now } = {}) {
  await initPhoneSmsGatewaySchema(db);
  const timestamp = new Date(now()).toISOString();
  return db.transaction(async tx => {
    await tx.prepare("UPDATE phone_sms_jobs SET status='PENDING',lease_token=NULL,lease_until=NULL,leased_by=NULL WHERE status='SENDING' AND lease_until<=? AND expires_at>? AND attempts<3")
      .run(timestamp, timestamp);
    await tx.prepare("UPDATE phone_sms_jobs SET status='FAILED',message='',encrypted_payload=NULL,failure_code='gateway_ack_timeout',lease_token=NULL,lease_until=NULL,leased_by=NULL WHERE status='SENDING' AND attempts>=3 AND lease_until<=?")
      .run(timestamp);
    await tx.prepare("UPDATE phone_sms_jobs SET status='EXPIRED',message='',encrypted_payload=NULL,lease_token=NULL,lease_until=NULL WHERE status IN ('PENDING','SENDING') AND expires_at<=?").run(timestamp);
    await tx.prepare("UPDATE phone_sms_message_jobs SET status='PENDING',lease_token=NULL,lease_until=NULL,leased_by=NULL,next_attempt_at=? WHERE status='SENDING' AND lease_until<=? AND expires_at>? AND attempts<3")
      .run(timestamp, timestamp, timestamp);
    await tx.prepare("UPDATE phone_sms_message_jobs SET status='FAILED',encrypted_payload='',failure_code='gateway_ack_timeout',lease_token=NULL,lease_until=NULL,leased_by=NULL WHERE status='SENDING' AND attempts>=3 AND lease_until<=?")
      .run(timestamp);
    await tx.prepare("UPDATE phone_sms_jobs SET failure_code='gateway_job_expired' WHERE status='EXPIRED' AND failure_code IS NULL AND expires_at<=?").run(timestamp);
    await tx.prepare("UPDATE phone_sms_message_jobs SET status='EXPIRED',encrypted_payload='',failure_code='gateway_job_expired',lease_token=NULL,lease_until=NULL,leased_by=NULL WHERE status IN ('PENDING','SENDING') AND expires_at<=?").run(timestamp);
    await tx.prepare("UPDATE ride_sms_outbox SET status='FAILED',failure_code='gateway_ack_timeout',lease_token=NULL,lease_until=NULL WHERE status='QUEUED' AND gateway_job_id IN (SELECT id FROM phone_sms_message_jobs WHERE status='FAILED' AND failure_code='gateway_ack_timeout')").run();
    await tx.prepare("UPDATE ride_sms_outbox SET status='FAILED',failure_code='gateway_job_expired',lease_token=NULL,lease_until=NULL WHERE status='QUEUED' AND gateway_job_id IN (SELECT id FROM phone_sms_message_jobs WHERE status='EXPIRED')").run();
    const row = await tx.prepare(
      "SELECT j.id,j.encrypted_payload FROM phone_sms_jobs j JOIN auth_otps o ON o.id=j.otp_id " +
      'JOIN phone_sms_devices d ON d.id=? AND d.revoked_at IS NULL ' +
      "WHERE j.encrypted_for_device=d.id AND d.sms_permission_granted=TRUE AND j.status='PENDING' AND j.expires_at>? AND o.expires_at>? AND o.verified_at IS NULL AND j.attempts<3 " +
      'ORDER BY j.created_at,j.id LIMIT 1' + (tx.isPostgres ? ' FOR UPDATE OF j SKIP LOCKED' : '')
    ).get(deviceId, timestamp, timestamp);
    if (row?.encrypted_payload) {
      const leaseToken = crypto.randomBytes(24).toString('hex');
      const leaseUntil = new Date(now() + 3 * 60 * 1000).toISOString();
      const updated = await tx.prepare("UPDATE phone_sms_jobs SET status='SENDING',attempts=attempts+1,lease_token=?,lease_until=?,leased_by=? WHERE id=? AND status='PENDING'")
        .run(leaseToken, leaseUntil, deviceId, row.id);
      if (updated.changes === 1) return { id: row.id, encryptedPayload: row.encrypted_payload, leaseToken };
    }
    const message = await tx.prepare("SELECT j.id,j.encrypted_payload FROM phone_sms_message_jobs j JOIN phone_sms_devices d ON d.id=j.encrypted_for_device WHERE j.encrypted_for_device=? AND d.revoked_at IS NULL AND d.sms_permission_granted=TRUE AND j.status='PENDING' AND j.next_attempt_at<=? AND j.expires_at>? AND j.attempts<3 ORDER BY j.created_at,j.id LIMIT 1" +
      (tx.isPostgres ? ' FOR UPDATE OF j SKIP LOCKED' : '')).get(deviceId, timestamp, timestamp);
    if (!message?.encrypted_payload) return null;
    const leaseToken = crypto.randomBytes(24).toString('hex');
    const updated = await tx.prepare("UPDATE phone_sms_message_jobs SET status='SENDING',attempts=attempts+1,lease_token=?,lease_until=?,leased_by=? WHERE id=? AND status='PENDING'")
      .run(leaseToken, new Date(now() + 3 * 60 * 1000).toISOString(), deviceId, message.id);
    if (updated.changes !== 1) return null;
    return { id: message.id, encryptedPayload: message.encrypted_payload, leaseToken };
  });
}

async function acknowledgeJob(deviceId, jobId, leaseToken, success, { db = DB, now = Date.now, failureCode = 'sms_delivery_failed' } = {}) {
  await initPhoneSmsGatewaySchema(db);
  const timestampParameter = db.isPostgres ? 'CAST(? AS TIMESTAMP)' : '?';
  const sentAt = success ? new Date(now()).toISOString() : null;
  const code = /^[a-z][a-z0-9_]{0,63}$/.test(String(failureCode || '').trim().toLowerCase())
    ? String(failureCode).trim().toLowerCase() : 'sms_delivery_failed';
  return db.transaction(async tx => {
    const otp = await tx.prepare(
      "UPDATE phone_sms_jobs SET status=CASE WHEN ?=1 THEN 'SENT' WHEN attempts>=3 THEN 'FAILED' ELSE 'PENDING' END," +
      "message='',encrypted_payload=CASE WHEN ?=1 OR attempts>=3 THEN NULL ELSE encrypted_payload END," +
      `sent_at=${timestampParameter},failure_code=CASE WHEN ?=1 THEN NULL WHEN attempts>=3 THEN ? ELSE NULL END,lease_token=NULL,lease_until=NULL,leased_by=NULL ` +
      "WHERE id=? AND leased_by=? AND lease_token=? AND status='SENDING'"
    ).run(success ? 1 : 0, success ? 1 : 0, sentAt, success ? 1 : 0, code,
      jobId, deviceId, String(leaseToken || ''));
    if (otp.changes === 1) return true;

    const retryAt = new Date(now() + 60000).toISOString();
    const service = await tx.prepare(
      "UPDATE phone_sms_message_jobs SET status=CASE WHEN ?=1 THEN 'SENT' WHEN attempts>=3 THEN 'FAILED' ELSE 'PENDING' END," +
      "encrypted_payload=CASE WHEN ?=1 OR attempts>=3 THEN '' ELSE encrypted_payload END," +
      `sent_at=${timestampParameter},failure_code=CASE WHEN ?=1 THEN NULL WHEN attempts>=3 THEN ? ELSE NULL END,` +
      `next_attempt_at=CASE WHEN ?=1 OR attempts>=3 THEN next_attempt_at ELSE ${timestampParameter} END,` +
      "lease_token=NULL,lease_until=NULL,leased_by=NULL WHERE id=? AND leased_by=? AND lease_token=? AND status='SENDING'"
    ).run(success ? 1 : 0, success ? 1 : 0, sentAt, success ? 1 : 0, code, success ? 1 : 0, retryAt,
      jobId, deviceId, String(leaseToken || ''));
    if (service.changes !== 1) return false;

    const job = await tx.prepare('SELECT status,failure_code,sent_at FROM phone_sms_message_jobs WHERE id=?').get(jobId);
    if (job?.status === 'SENT') {
      await tx.prepare("UPDATE ride_sms_outbox SET status='SENT',sent_at=?,failure_code=NULL,lease_token=NULL,lease_until=NULL WHERE gateway_job_id=? AND status='QUEUED'")
        .run(job.sent_at || sentAt, jobId);
    } else if (job?.status === 'FAILED') {
      await tx.prepare("UPDATE ride_sms_outbox SET status='FAILED',failure_code=?,lease_token=NULL,lease_until=NULL WHERE gateway_job_id=? AND status='QUEUED'")
        .run(job.failure_code || code, jobId);
    }
    return true;
  });
}

module.exports = {
  initPhoneSmsGatewaySchema, createPairCode, pairDevice, authenticateDevice, adminStatus,
  getDeliveryMode, setDeliveryMode, getPilotPhoneNumbers, getAdditionalPilotPhoneNumbers,
  savePilotPhoneNumbers, savePilotSettings, isPilotPhone, smsSegmentCount,
  revokeDevice, cancelUnsentForPhone, queueOtp, queueTestOtp: queueOtp, queueServiceMessage, claimNextJob, acknowledgeJob
};
