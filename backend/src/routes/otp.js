const express = require('express');
const crypto = require('crypto');
const rateLimit = require('express-rate-limit');
const { DB } = require('../db');

const router = express.Router();
const requestLimiter = rateLimit({ windowMs: 15 * 60 * 1000, limit: 3, standardHeaders: true, legacyHeaders: false });
const verifyLimiter = rateLimit({ windowMs: 15 * 60 * 1000, limit: 10, standardHeaders: true, legacyHeaders: false });
let schemaReady;

function normalizePhone(input) {
  let value = String(input || '').trim().replace(/[\s()-]/g, '');
  if (value.startsWith('00')) value = '+' + value.slice(2);
  if (value.startsWith('0') && value.length === 10) value = '+251' + value.slice(1);
  else if (/^[79]\d{8}$/.test(value)) value = '+251' + value;
  else if (/^251[79]\d{8}$/.test(value)) value = '+' + value;
  if (!/^\+251[79]\d{8}$/.test(value)) return null;
  return value;
}

function hashOtp(challengeId, phone, code) {
  const secret = process.env.OTP_HASH_SECRET || (process.env.NODE_ENV !== 'production' ? process.env.JWT_SECRET || 'local-routepass-otp-secret' : '');
  if (!secret || secret.length < 32) throw new Error('OTP_HASH_SECRET must be configured with at least 32 characters.');
  return crypto.createHmac('sha256', secret).update(challengeId + ':' + phone + ':' + code).digest('hex');
}

async function ensureOtpSchema() {
  if (!schemaReady) {
    schemaReady = Promise.resolve(DB.exec(`
      CREATE TABLE IF NOT EXISTS otp_challenges (
        id TEXT PRIMARY KEY,
        phone TEXT NOT NULL,
        purpose TEXT NOT NULL,
        code_hash TEXT NOT NULL,
        expires_at TEXT NOT NULL,
        attempts INTEGER NOT NULL DEFAULT 0,
        max_attempts INTEGER NOT NULL DEFAULT 5,
        sent_at TEXT NOT NULL,
        verified_at TEXT,
        consumed_at TEXT,
        created_at TEXT NOT NULL
      );
      CREATE INDEX IF NOT EXISTS idx_otp_phone_purpose ON otp_challenges(phone, purpose, created_at);
    `));
  }
  return schemaReady;
}

async function sendEthioTelecomSms(phone, message) {
  const baseUrl = String(process.env.ETHIO_SMS_API_BASE_URL || '').trim().replace(/\/$/, '');
  const tenantId = String(process.env.ETHIO_SMS_TENANT_ID || '').trim();
  const accessToken = String(process.env.ETHIO_SMS_ACCESS_TOKEN || '').trim();
  if (!baseUrl || !tenantId || !accessToken) {
    const error = new Error('Ethio Telecom SMS service is not configured.');
    error.code = 'SMS_NOT_CONFIGURED';
    throw error;
  }
  const url = baseUrl + '/v2/' + encodeURIComponent(tenantId) + '/notifications/sms';
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 10000);
  try {
    const response = await fetch(url, {
      method: 'POST',
      headers: { Authorization: 'Bearer ' + accessToken, 'Content-Type': 'application/json', Accept: 'application/json' },
      body: JSON.stringify({ endpoint: 'tel:' + phone, message }),
      signal: controller.signal
    });
    if (!response.ok) {
      // Do not log provider body: it may contain internal IDs or account metadata.
      const error = new Error('Ethio Telecom SMS provider rejected the request.');
      error.code = 'SMS_PROVIDER_REJECTED';
      throw error;
    }
    // Provider returns a request/message ID; never return it or credentials to the client.
    await response.text();
  } finally {
    clearTimeout(timeout);
  }
}

router.post('/request', requestLimiter, async (req, res) => {
  try {
    await ensureOtpSchema();
    const phone = normalizePhone(req.body.phone);
    if (!phone) return res.status(400).json({ success: false, error: 'Enter a valid Ethiopian mobile number, e.g. 09XXXXXXXX or +2519XXXXXXXX.' });

    const localPhone = '0' + phone.slice(4);
    const existing = await DB.prepare('SELECT id FROM users WHERE phone = ? OR phone = ? OR phone = ? LIMIT 1').get(phone, localPhone, phone.slice(1));
    if (existing) return res.status(409).json({ success: false, error: 'This phone number is already registered. Please sign in.' });

    const now = Date.now();
    const previous = await DB.prepare("SELECT sent_at FROM otp_challenges WHERE phone = ? AND purpose = 'SIGNUP' ORDER BY created_at DESC LIMIT 1").get(phone);
    const cooldown = Math.max(15, Number(process.env.OTP_RESEND_COOLDOWN_SECONDS || 60));
    if (previous && now - Date.parse(previous.sent_at) < cooldown * 1000) {
      const retryAfterSeconds = Math.ceil((cooldown * 1000 - (now - Date.parse(previous.sent_at))) / 1000);
      return res.status(429).json({ success: false, error: 'Please wait before requesting another code.', retryAfterSeconds });
    }

    const code = String(crypto.randomInt(0, 1000000)).padStart(6, '0');
    const challengeId = crypto.randomUUID();
    const ttl = Math.min(600, Math.max(120, Number(process.env.OTP_TTL_SECONDS || 300)));
    const createdAt = new Date(now).toISOString();
    const expiresAt = new Date(now + ttl * 1000).toISOString();
    const digest = hashOtp(challengeId, phone, code);

    // Send first; only persist a usable challenge after provider accepts the message.
    await sendEthioTelecomSms(phone, 'Your RoutePass verification code is ' + code + '. It expires in ' + Math.ceil(ttl / 60) + ' minutes. Do not share this code.');
    await DB.prepare("UPDATE otp_challenges SET consumed_at = ? WHERE phone = ? AND purpose = 'SIGNUP' AND consumed_at IS NULL").run(createdAt, phone);
    await DB.prepare(`
      INSERT INTO otp_challenges (id, phone, purpose, code_hash, expires_at, attempts, max_attempts, sent_at, verified_at, consumed_at, created_at)
      VALUES (?, ?, 'SIGNUP', ?, ?, 0, ?, ?, NULL, NULL, ?)
    `).run(challengeId, phone, digest, expiresAt, Math.min(8, Math.max(3, Number(process.env.OTP_MAX_ATTEMPTS || 5))), createdAt, createdAt);

    res.status(202).json({ success: true, challengeId, phone, expiresInSeconds: ttl, message: 'Verification code sent if the SMS provider accepted the request.' });
  } catch (err) {
    if (err.code === 'SMS_NOT_CONFIGURED') return res.status(503).json({ success: false, error: 'SMS verification is temporarily unavailable. Please contact RoutePass support.' });
    console.error('[OTP] request failed:', err.code || 'OTP_REQUEST_ERROR');
    res.status(503).json({ success: false, error: 'Could not send a verification code. Please try again later.' });
  }
});

router.post('/verify', verifyLimiter, async (req, res) => {
  try {
    await ensureOtpSchema();
    const phone = normalizePhone(req.body.phone);
    const challengeId = String(req.body.challengeId || '').trim();
    const code = String(req.body.code || '').trim();
    if (!phone || !/^[0-9]{6}$/.test(code) || !challengeId) {
      return res.status(400).json({ success: false, error: 'Enter the six-digit code sent to your phone.' });
    }
    const challenge = await DB.prepare("SELECT * FROM otp_challenges WHERE id = ? AND phone = ? AND purpose = 'SIGNUP'").get(challengeId, phone);
    const now = new Date().toISOString();
    if (!challenge || challenge.consumed_at || challenge.verified_at || Date.parse(challenge.expires_at) <= Date.now()) {
      return res.status(400).json({ success: false, error: 'This verification code is invalid or expired. Request a new code.' });
    }
    if (Number(challenge.attempts) >= Number(challenge.max_attempts)) {
      await DB.prepare('UPDATE otp_challenges SET consumed_at = ? WHERE id = ?').run(now, challengeId);
      return res.status(429).json({ success: false, error: 'Too many incorrect attempts. Request a new code.' });
    }
    const expected = Buffer.from(challenge.code_hash, 'hex');
    const actual = Buffer.from(hashOtp(challengeId, phone, code), 'hex');
    const valid = expected.length === actual.length && crypto.timingSafeEqual(expected, actual);
    if (!valid) {
      await DB.prepare('UPDATE otp_challenges SET attempts = attempts + 1 WHERE id = ?').run(challengeId);
      return res.status(400).json({ success: false, error: 'Incorrect verification code.' });
    }
    await DB.prepare('UPDATE otp_challenges SET verified_at = ? WHERE id = ? AND consumed_at IS NULL AND verified_at IS NULL').run(now, challengeId);
    res.json({ success: true, verified: true, challengeId, phone, message: 'Phone number verified. Complete registration within 10 minutes.' });
  } catch (err) {
    console.error('[OTP] verification failed:', err.code || 'OTP_VERIFY_ERROR');
    res.status(500).json({ success: false, error: 'Could not verify the code. Please try again.' });
  }
});

router.normalizePhone = normalizePhone;
router.ensureOtpSchema = ensureOtpSchema;
router.hashOtp = hashOtp;
module.exports = router;
