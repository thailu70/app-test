'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');

process.env.NODE_ENV = 'test';
process.env.JWT_SECRET = 'phone-gateway-test-only-secret-'.repeat(2);
process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), 'passo-phone-sms-'));
process.env.PASSO_TEST_OTP_ENABLED = 'true';
process.env.PASSO_TEST_OTP_CODE = '123456';
process.env.PASSO_TEST_OTP_PASSENGER_PHONE = '+251911000001';
process.env.PASSO_TEST_OTP_DRIVER_PHONE = '+251911000002';
process.env.PASSO_TEST_OTP_EXPIRES_AT = new Date(Date.now() + 24 * 60 * 60 * 1000).toISOString();
if (process.env.MOBILITY_TEST_POSTGRES === 'true') {
  if (process.env.CI !== 'true' || !process.env.DATABASE_URL) throw new Error('Disposable CI PostgreSQL configuration is required.');
} else delete process.env.DATABASE_URL;

const { DB } = require('../src/db');
const { initMasterDataSchema } = require('../src/services/master-data');
const gateway = require('../src/services/phone-sms-gateway');
const { sendOtpSms, sendTransactionalSms } = require('../src/services/afro-sms');

test('phone gateway sends encrypted OTPs and trip SMS to any valid Ethiopian number', async () => {
  await initMasterDataSchema(DB);
  await DB.exec('CREATE TABLE IF NOT EXISTS auth_otps (id TEXT PRIMARY KEY,phone TEXT NOT NULL,purpose TEXT NOT NULL,code_hash TEXT NOT NULL,expires_at TIMESTAMP NOT NULL,verified_at TIMESTAMP)');
  await DB.exec("CREATE TABLE IF NOT EXISTS ride_sms_outbox (id TEXT PRIMARY KEY,event_key TEXT NOT NULL UNIQUE,ride_id TEXT NOT NULL,phone TEXT NOT NULL,message TEXT NOT NULL,status TEXT NOT NULL DEFAULT 'PENDING',attempts INTEGER NOT NULL DEFAULT 0,next_attempt_at TEXT NOT NULL,lease_token TEXT,lease_until TEXT,sent_at TEXT,created_at TEXT NOT NULL,gateway_job_id TEXT,failure_code TEXT)");
  await gateway.initPhoneSmsGatewaySchema(DB);
  const now = Date.now();
  await gateway.savePilotSettings({ deliveryMode: 'PHONE_GATEWAY', pilotNumbers: [] }, { db: DB });

  const keyPair = crypto.generateKeyPairSync('rsa', { modulusLength: 2048 });
  const publicKey = keyPair.publicKey.export({ type: 'spki', format: 'pem' }).toString();
  const pairCode = await gateway.createPairCode({ db: DB, now: () => now });
  const device = await gateway.pairDevice({ code: pairCode.code, deviceName: 'Gateway fixture', publicKey, db: DB, now: () => now });
  assert.equal(await gateway.authenticateDevice(device.token, { db: DB, now: () => now, smsPermissionGranted: true }), device.deviceId);
  // ACK calls do not carry the permission field; a normal heartbeat without a
  // capability report must preserve the last reported permission state.
  assert.equal(await gateway.authenticateDevice(device.token, { db: DB, now: () => now + 500 }), device.deviceId);
  assert.equal((await DB.prepare('SELECT sms_permission_granted FROM phone_sms_devices WHERE id=?').get(device.deviceId)).sms_permission_granted, 1);

  // A later-heartbeating paired device without Android SEND_SMS permission
  // must not receive encrypted jobs or starve the working handset.
  const deniedKeyPair = crypto.generateKeyPairSync('rsa', { modulusLength: 2048 });
  const deniedPublicKey = deniedKeyPair.publicKey.export({ type: 'spki', format: 'pem' }).toString();
  const deniedPairCode = await gateway.createPairCode({ db: DB, now: () => now + 1000 });
  const deniedDevice = await gateway.pairDevice({ code: deniedPairCode.code, deviceName: 'Permission denied fixture', publicKey: deniedPublicKey, db: DB, now: () => now + 1000 });
  assert.equal(await gateway.authenticateDevice(deniedDevice.token, { db: DB, now: () => now + 2000, smsPermissionGranted: false }), deniedDevice.deviceId);

  const phone = '+251911000001';
  const message = 'PASSO taxi trip started: Bole to CMC. Open Passo to view your trip.';
  const eventKey = 'ride-start:fixture-ride:' + phone;
  const queued = await sendTransactionalSms(eventKey, phone, message, { db: DB, fallbackSendSms: async () => { throw new Error('Queued pilot SMS must not use Afro fallback.'); } });
  assert.equal(queued.sent, true);
  assert.equal(queued.queued, true);
  assert.ok(queued.messageJobId);

  const rows = await DB.prepare('SELECT id,event_key,phone,encrypted_payload,status FROM phone_sms_message_jobs').all();
  assert.equal(rows.length, 1, 'The same event key creates exactly one message job.');
  assert.equal(rows[0].phone, phone);
  assert.notEqual(rows[0].event_key, eventKey, 'Stored event identifiers are hashed.');
  assert.equal(rows[0].status, 'PENDING');
  assert.equal((await DB.prepare('SELECT encrypted_for_device FROM phone_sms_message_jobs WHERE id=?').get(rows[0].id)).encrypted_for_device, device.deviceId);
  assert.equal(rows[0].encrypted_payload.includes(message), false, 'The stored payload contains ciphertext only.');
  assert.ok(rows[0].encrypted_payload.startsWith('P2.'), 'Longer SMS uses authenticated hybrid encryption.');
  const envelope = JSON.parse(Buffer.from(rows[0].encrypted_payload.slice(3), 'base64').toString('utf8'));
  const aesKey = crypto.privateDecrypt({ key: keyPair.privateKey, padding: crypto.constants.RSA_PKCS1_OAEP_PADDING, oaepHash: 'sha256' }, Buffer.from(envelope.key, 'base64'));
  const encrypted = Buffer.from(envelope.ciphertext, 'base64'), iv = Buffer.from(envelope.iv, 'base64');
  const decipher = crypto.createDecipheriv('aes-256-gcm', aesKey, iv);
  decipher.setAuthTag(encrypted.subarray(encrypted.length - 16));
  const clear = Buffer.concat([decipher.update(encrypted.subarray(0, encrypted.length - 16)), decipher.final()]);
  assert.deepEqual(JSON.parse(clear.toString('utf8')), { phone, message });
  aesKey.fill(0); iv.fill(0); encrypted.fill(0); clear.fill(0);

  await DB.prepare("INSERT INTO ride_sms_outbox(id,event_key,ride_id,phone,message,status,attempts,next_attempt_at,created_at,gateway_job_id) VALUES(?,?,?,?,?,'QUEUED',1,?,?,?)")
    .run('ride_sms_fixture', eventKey, 'fixture-ride', phone, message, new Date(now).toISOString(), new Date(now).toISOString(), queued.messageJobId);
  const transactionNow = Date.now() + 60000;
  const transactionJob = await gateway.claimNextJob(device.deviceId, { db: DB, now: () => transactionNow });
  assert.equal(transactionJob.id, queued.messageJobId);
  assert.equal(await gateway.acknowledgeJob(device.deviceId, transactionJob.id, transactionJob.leaseToken, true, { db: DB, now: () => transactionNow }), true);
  assert.equal((await DB.prepare('SELECT status FROM ride_sms_outbox WHERE id=?').get('ride_sms_fixture')).status, 'SENT',
    'A trip SMS is marked sent only after the paired phone confirms its network send callback.');
  const duplicateSent = await gateway.queueServiceMessage({ eventKey, phone, message, db: DB, now: () => now });
  assert.deepEqual(duplicateSent, { queued: false, sent: true, messageJobId: queued.messageJobId });

  const rejectedEvent = 'ride-start:fixture-rejected:' + phone;
  const rejectedMessage = 'PASSO taxi trip started: Bole to CMC. Open Passo to view your trip.';
  const rejectedQueue = await gateway.queueServiceMessage({ eventKey: rejectedEvent, phone, message: rejectedMessage, db: DB, now: () => now });
  await DB.prepare("INSERT INTO ride_sms_outbox(id,event_key,ride_id,phone,message,status,attempts,next_attempt_at,created_at,gateway_job_id) VALUES(?,?,?,?,?,'QUEUED',1,?,?,?)")
    .run('ride_sms_rejected', rejectedEvent, 'fixture-rejected', phone, rejectedMessage, new Date(now).toISOString(), new Date(now).toISOString(), rejectedQueue.messageJobId);
  for (let attempt = 0; attempt < 3; attempt++) {
    const attemptNow = transactionNow + attempt * 60000;
    const rejectedJob = await gateway.claimNextJob(device.deviceId, { db: DB, now: () => attemptNow });
    assert.equal(rejectedJob.id, rejectedQueue.messageJobId);
    assert.equal(await gateway.acknowledgeJob(device.deviceId, rejectedJob.id, rejectedJob.leaseToken, false,
      { db: DB, now: () => attemptNow, failureCode: 'sms_no_service' }), true);
    if (attempt === 0) {
      const retryingJob = await DB.prepare('SELECT status,failure_code FROM phone_sms_message_jobs WHERE id=?').get(rejectedQueue.messageJobId);
      assert.equal(retryingJob.status, 'PENDING');
      assert.equal(retryingJob.failure_code, 'sms_no_service', 'The first failure remains visible while delivery retries.');
      assert.equal((await gateway.adminStatus({ db: DB, now: () => attemptNow })).latestFailure.failureCode, 'sms_no_service');
    }
  }
  const failedServiceJob = await DB.prepare('SELECT status,failure_code FROM phone_sms_message_jobs WHERE id=?').get(rejectedQueue.messageJobId);
  assert.equal(failedServiceJob.status, 'FAILED');
  assert.equal(failedServiceJob.failure_code, 'sms_no_service');
  const failedRideSms = await DB.prepare('SELECT status,failure_code FROM ride_sms_outbox WHERE id=?').get('ride_sms_rejected');
  assert.equal(failedRideSms.status, 'FAILED');
  assert.equal(failedRideSms.failure_code, 'sms_no_service');
  const smsStatus = await gateway.adminStatus({ db: DB, now: () => now + 2 * 60000 });
  assert.equal(smsStatus.latestFailure.failureCode, 'sms_no_service');
  const deliveryDiagnostic = JSON.stringify({ counts: smsStatus.deliveryCounts, failure: smsStatus.latestFailure });
  assert.equal(deliveryDiagnostic.includes(phone), false, 'The delivery diagnostic never exposes recipient numbers or message text.');
  assert.equal(deliveryDiagnostic.includes(rejectedMessage), false);

  const otpPhone = '+251912000003', otpCode = '654321', otpExpiry = new Date(Date.now() + 10 * 60 * 1000).toISOString();
  await DB.prepare('INSERT INTO auth_otps(id,phone,purpose,code_hash,expires_at) VALUES(?,?,?,?,?)')
    .run('otp_non_pilot', otpPhone, 'LOGIN', crypto.createHash('sha256').update(otpCode).digest('hex'), otpExpiry);
  assert.deepEqual(await gateway.queueOtp({ otpId: 'otp_non_pilot', phone: otpPhone, code: otpCode, db: DB, now: () => now }), { queued: true });
  const otpJob = await gateway.claimNextJob(device.deviceId, { db: DB });
  assert.ok(otpJob);
  assert.equal((await DB.prepare('SELECT encrypted_for_device FROM phone_sms_jobs WHERE id=?').get(otpJob.id)).encrypted_for_device, device.deviceId);
  const decryptedOtp = crypto.privateDecrypt({ key: keyPair.privateKey, padding: crypto.constants.RSA_PKCS1_OAEP_PADDING, oaepHash: 'sha256' }, Buffer.from(otpJob.encryptedPayload, 'base64'));
  assert.deepEqual(JSON.parse(decryptedOtp.toString('utf8')), {
    phone: otpPhone,
    message: `PASSO sign-in verification code: ${otpCode}. It expires in 10 minutes. Do not share it.`
  });
  decryptedOtp.fill(0);
  assert.equal(await gateway.acknowledgeJob(device.deviceId, otpJob.id, otpJob.leaseToken, true, { db: DB }), true);

  for (const [purpose, label, id, code] of [
    ['REGISTRATION', 'registration', 'otp_registration', '234567'],
    ['CARGO_REQUEST', 'cargo request', 'otp_cargo', '345678'],
    ['GROUP_REQUEST', 'group travel request', 'otp_group', '456789']
  ]) {
    await DB.prepare('INSERT INTO auth_otps(id,phone,purpose,code_hash,expires_at) VALUES(?,?,?,?,?)')
      .run(id, otpPhone, purpose, crypto.createHash('sha256').update(code).digest('hex'), otpExpiry);
    assert.deepEqual(await gateway.queueOtp({ otpId: id, phone: otpPhone, code, db: DB, now: () => now + 2000 }), { queued: true });
    const publicRequestOtp = await gateway.claimNextJob(device.deviceId, { db: DB, now: () => now + 2000 });
    assert.ok(publicRequestOtp);
    const decoded = crypto.privateDecrypt({ key: keyPair.privateKey, padding: crypto.constants.RSA_PKCS1_OAEP_PADDING, oaepHash: 'sha256' }, Buffer.from(publicRequestOtp.encryptedPayload, 'base64'));
    assert.equal(JSON.parse(decoded.toString('utf8')).message,
      `PASSO ${label} verification code: ${code}. It expires in 10 minutes. Do not share it.`);
    decoded.fill(0);
    assert.equal(await gateway.acknowledgeJob(device.deviceId, publicRequestOtp.id, publicRequestOtp.leaseToken, true, { db: DB, now: () => now + 2000 }), true);
  }

  const retryPhone = '+251912000006', retryCode = '678901', retryId = 'otp_retry_after_sim_rejection';
  await DB.prepare('INSERT INTO auth_otps(id,phone,purpose,code_hash,expires_at) VALUES(?,?,?,?,?)')
    .run(retryId, retryPhone, 'LOGIN', crypto.createHash('sha256').update(retryCode).digest('hex'), otpExpiry);
  await gateway.queueOtp({ otpId: retryId, phone: retryPhone, code: retryCode, db: DB, now: () => now + 2500 });
  const rejectedAttempt = await gateway.claimNextJob(device.deviceId, { db: DB, now: () => now + 2500 });
  assert.ok(rejectedAttempt);
  assert.equal(await gateway.acknowledgeJob(device.deviceId, rejectedAttempt.id, rejectedAttempt.leaseToken, false, { db: DB, now: () => now + 2500 }), true);
  const retryState = await DB.prepare('SELECT status,encrypted_payload,attempts FROM phone_sms_jobs WHERE id=?').get(rejectedAttempt.id);
  assert.equal(retryState.status, 'PENDING', 'A transient SIM rejection remains retryable.');
  assert.ok(retryState.encrypted_payload, 'The encrypted OTP is retained for the retry.');
  const retryAttempt = await gateway.claimNextJob(device.deviceId, { db: DB, now: () => now + 3000 });
  assert.equal(retryAttempt.id, rejectedAttempt.id);
  assert.equal(await gateway.acknowledgeJob(device.deviceId, retryAttempt.id, retryAttempt.leaseToken, true, { db: DB, now: () => now + 3000 }), true);
  assert.equal((await DB.prepare('SELECT status FROM phone_sms_jobs WHERE id=?').get(retryAttempt.id)).status, 'SENT');

  const additionalNumber = '+251912000004';
  const accepted = await gateway.queueServiceMessage({ eventKey: 'all-numbers-fixture', phone: additionalNumber, message, db: DB, now: () => now });
  assert.equal(accepted.queued, true, 'Any valid Ethiopian number can use the selected phone gateway.');
  const duplicate = await gateway.queueServiceMessage({ eventKey: 'all-numbers-fixture', phone: additionalNumber, message, db: DB, now: () => now });
  assert.deepEqual(duplicate, accepted, 'Repeated delivery events remain idempotent.');
  const totalMessages = await DB.prepare('SELECT COUNT(*) AS count FROM phone_sms_message_jobs').get();
  assert.equal(totalMessages.count, 3, 'Each event creates one encrypted message, even when the recipient is outside the old pilot list.');
  const claimed = await gateway.claimNextJob(device.deviceId, { db: DB });
  assert.ok(claimed);
  const currentRows = await DB.prepare('SELECT id FROM phone_sms_message_jobs').all();
  assert.ok(currentRows.some(row => row.id === claimed.id));
  assert.equal(await gateway.acknowledgeJob(device.deviceId, claimed.id, claimed.leaseToken, true, { db: DB }), true);
  const nextClaim = await gateway.claimNextJob(device.deviceId, { db: DB });
  assert.equal(nextClaim, null);
  const final = await DB.prepare('SELECT status,encrypted_payload,sent_at,failure_code FROM phone_sms_message_jobs').all();
  assert.equal(final.length, 3);
  assert.equal(final.filter(row => row.status === 'SENT' && row.encrypted_payload === '' && row.sent_at).length, 2);
  assert.ok(final.some(row => row.status === 'FAILED' && row.failure_code === 'sms_no_service'));

  await gateway.savePilotSettings({ deliveryMode: 'AFRO_SMS', pilotNumbers: [] }, { db: DB });
  const fallbackPhone = '+251912000005', fallbackCode = '567890', fallbackId = 'otp_afro_fallback';
  await DB.prepare('INSERT INTO auth_otps(id,phone,purpose,code_hash,expires_at) VALUES(?,?,?,?,?)')
    .run(fallbackId, fallbackPhone, 'LOGIN', crypto.createHash('sha256').update(fallbackCode).digest('hex'), otpExpiry);
  const otpFallback = await sendOtpSms({ otpId: fallbackId, phone: fallbackPhone, code: fallbackCode,
    message: `PASSO sign-in verification code: ${fallbackCode}. It expires in 10 minutes. Do not share it.` }, {
    db: DB, fallbackSendSms: async () => ({ sent: false, error: 'Recipient is not on the provider trial allowlist.' })
  });
  assert.deepEqual(otpFallback, { sent: true, queued: true, phoneGateway: true }, 'A rejected provider recipient falls back to the paired SIM.');
  const transactionFallback = await sendTransactionalSms('fallback-trip-message', fallbackPhone,
    'PASSO taxi trip started: Bole to CMC. Open Passo to view your trip.', {
      db: DB, fallbackSendSms: async () => ({ sent: false, error: 'Recipient is not on the provider trial allowlist.' })
    });
  assert.equal(transactionFallback.sent, true, 'Rejected long-form provider delivery queues on the paired SIM.');
  assert.equal(transactionFallback.queued, true);
  assert.ok(transactionFallback.messageJobId);

  const longGsm = 'PASSO ' + 'A'.repeat(1524);
  assert.equal(gateway.smsSegmentCount(longGsm), 10);
  assert.equal((await gateway.queueServiceMessage({ eventKey: 'max-gsm-message', phone: fallbackPhone, message: longGsm, db: DB, now: () => now })).queued, true);
  const longGsmRow = await DB.prepare('SELECT LENGTH(encrypted_payload) AS length FROM phone_sms_message_jobs WHERE event_key=?')
    .get(crypto.createHash('sha256').update('max-gsm-message').digest('hex'));
  assert.ok(Number(longGsmRow.length) > 3000, 'A valid 10-part SMS expands beyond the companion app’s former payload ceiling.');
  const longUnicode = 'PASSO ' + 'ሀ'.repeat(661);
  assert.equal(gateway.smsSegmentCount(longUnicode), 10);
  assert.equal((await gateway.queueServiceMessage({ eventKey: 'max-unicode-message', phone: fallbackPhone, message: longUnicode, db: DB, now: () => now })).queued, true);
  const longUnicodeRow = await DB.prepare('SELECT LENGTH(encrypted_payload) AS length FROM phone_sms_message_jobs WHERE event_key=?')
    .get(crypto.createHash('sha256').update('max-unicode-message').digest('hex'));
  assert.ok(Number(longUnicodeRow.length) > 3000, 'Valid multipart Amharic SMS also exceeds the old companion payload ceiling.');
  const tooLongUnicode = 'PASSO ' + 'ሀ'.repeat(665);
  assert.equal(gateway.smsSegmentCount(tooLongUnicode), 11);
  assert.deepEqual(await gateway.queueServiceMessage({ eventKey: 'too-long-unicode-message', phone: fallbackPhone, message: tooLongUnicode, db: DB, now: () => now }), { queued: false }, 'Messages beyond the phone carrier-safe ten-part limit are rejected before queuing.');
});
