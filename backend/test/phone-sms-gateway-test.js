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
const { sendTransactionalSms } = require('../src/services/afro-sms');

test('phone gateway sends encrypted OTPs and trip SMS to any valid Ethiopian number', async () => {
  await initMasterDataSchema(DB);
  await DB.exec('CREATE TABLE IF NOT EXISTS auth_otps (id TEXT PRIMARY KEY,phone TEXT NOT NULL,purpose TEXT NOT NULL,code_hash TEXT NOT NULL,expires_at TIMESTAMP NOT NULL,verified_at TIMESTAMP)');
  await gateway.initPhoneSmsGatewaySchema(DB);
  const now = Date.now();
  await gateway.savePilotSettings({ deliveryMode: 'PHONE_GATEWAY', pilotNumbers: [] }, { db: DB });

  const keyPair = crypto.generateKeyPairSync('rsa', { modulusLength: 2048 });
  const publicKey = keyPair.publicKey.export({ type: 'spki', format: 'pem' }).toString();
  const pairCode = await gateway.createPairCode({ db: DB, now: () => now });
  const device = await gateway.pairDevice({ code: pairCode.code, deviceName: 'Gateway fixture', publicKey, db: DB, now: () => now });
  assert.equal(await gateway.authenticateDevice(device.token, { db: DB, now: () => now }), device.deviceId);

  const phone = '+251911000001';
  const message = 'PASSO taxi trip started: Bole to CMC. Open Passo to view your trip.';
  const eventKey = 'ride-start:fixture-ride:' + phone;
  const queued = await sendTransactionalSms(eventKey, phone, message, { db: DB, fallbackSendSms: async () => { throw new Error('Queued pilot SMS must not use Afro fallback.'); } });
  assert.deepEqual(queued, { sent: true, queued: true });
  await gateway.queueServiceMessage({ eventKey, phone, message, db: DB, now: () => now });

  const rows = await DB.prepare('SELECT id,event_key,phone,encrypted_payload,status FROM phone_sms_message_jobs').all();
  assert.equal(rows.length, 1, 'The same event key creates exactly one message job.');
  assert.equal(rows[0].phone, phone);
  assert.notEqual(rows[0].event_key, eventKey, 'Stored event identifiers are hashed.');
  assert.equal(rows[0].status, 'PENDING');
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

  const otpPhone = '+251912000003', otpCode = '654321', otpExpiry = new Date(Date.now() + 10 * 60 * 1000).toISOString();
  await DB.prepare('INSERT INTO auth_otps(id,phone,purpose,code_hash,expires_at) VALUES(?,?,?,?,?)')
    .run('otp_non_pilot', otpPhone, 'LOGIN', crypto.createHash('sha256').update(otpCode).digest('hex'), otpExpiry);
  assert.deepEqual(await gateway.queueOtp({ otpId: 'otp_non_pilot', phone: otpPhone, code: otpCode, db: DB, now: () => now }), { queued: true });
  const otpJob = await gateway.claimNextJob(device.deviceId, { db: DB });
  assert.ok(otpJob);
  const decryptedOtp = crypto.privateDecrypt({ key: keyPair.privateKey, padding: crypto.constants.RSA_PKCS1_OAEP_PADDING, oaepHash: 'sha256' }, Buffer.from(otpJob.encryptedPayload, 'base64'));
  assert.deepEqual(JSON.parse(decryptedOtp.toString('utf8')), {
    phone: otpPhone,
    message: `PASSO sign-in verification code: ${otpCode}. It expires in 10 minutes. Do not share it.`
  });
  decryptedOtp.fill(0);
  assert.equal(await gateway.acknowledgeJob(device.deviceId, otpJob.id, otpJob.leaseToken, true, { db: DB }), true);

  for (const [purpose, label, id, code] of [
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

  const additionalNumber = '+251912000004';
  const accepted = await gateway.queueServiceMessage({ eventKey: 'all-numbers-fixture', phone: additionalNumber, message, db: DB, now: () => now + 1000 });
  assert.deepEqual(accepted, { queued: true }, 'Any valid Ethiopian number can use the selected phone gateway.');
  const duplicate = await gateway.queueServiceMessage({ eventKey: 'all-numbers-fixture', phone: additionalNumber, message, db: DB, now: () => now + 1000 });
  assert.deepEqual(duplicate, { queued: true }, 'Repeated delivery events remain idempotent.');
  const totalMessages = await DB.prepare('SELECT COUNT(*) AS count FROM phone_sms_message_jobs').get();
  assert.equal(totalMessages.count, 2, 'Each event creates one encrypted message, even when the recipient is outside the old pilot list.');
  const claimed = await gateway.claimNextJob(device.deviceId, { db: DB });
  assert.ok(rows.some(row => row.id === claimed.id));
  assert.equal(await gateway.acknowledgeJob(device.deviceId, claimed.id, claimed.leaseToken, true, { db: DB }), true);
  const nextClaim = await gateway.claimNextJob(device.deviceId, { db: DB });
  assert.ok(nextClaim);
  assert.notEqual(nextClaim.id, claimed.id);
  assert.equal(await gateway.acknowledgeJob(device.deviceId, nextClaim.id, nextClaim.leaseToken, true, { db: DB }), true);
  const final = await DB.prepare('SELECT status,encrypted_payload FROM phone_sms_message_jobs').all();
  assert.equal(final.length, 2);
  assert.ok(final.every(row => row.status === 'SENT' && row.encrypted_payload === ''));
});
