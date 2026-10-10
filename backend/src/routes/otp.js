const express = require('express');
const crypto = require('crypto');
const rateLimit = require('express-rate-limit');
const { DB } = require('../db');

const router = express.Router();
const requestLimiter = rateLimit({ windowMs: 15 * 60 * 1000, limit: 3, standardHeaders: true, legacyHeaders: false });
const verifyLimiter = rateLimit({ windowMs: 15 * 60 * 1000, limit: 8, standardHeaders: true, legacyHeaders: false });
const normalizePhone = (value) => String(value || '').replace(/[\s()-]/g, '');
const digest = (phone, code) => crypto.createHash('sha256').update(`${phone}:${code}:${process.env.OTP_PEPPER || process.env.JWT_SECRET || 'routepass-otp'}`).digest('hex');

router.post('/request', requestLimiter, async (req, res) => {
  try {
    const phone = normalizePhone(req.body.phone);
    if (!/^\+?[0-9]{9,15}$/.test(phone)) return res.status(400).json({ success: false, error: 'Enter a valid phone number including country code.' });
    const smsUrl = process.env.SMS_API_URL;
    const smsToken = process.env.SMS_API_TOKEN;
    if (!smsUrl || !smsToken) return res.status(503).json({ success: false, code: 'OTP_SMS_NOT_CONFIGURED', error: 'Phone verification is temporarily unavailable. SMS delivery is not configured.' });

    const code = String(crypto.randomInt(0, 1000000)).padStart(6, '0');
    const expiresAt = new Date(Date.now() + 5 * 60 * 1000).toISOString();
    await DB.prepare(`INSERT INTO phone_otp_challenges (phone, otp_hash, expires_at, attempts, verified_at, created_at)
      VALUES (?, ?, ?, 0, NULL, CURRENT_TIMESTAMP)
      ON CONFLICT(phone) DO UPDATE SET otp_hash = excluded.otp_hash, expires_at = excluded.expires_at, attempts = 0, verified_at = NULL, created_at = CURRENT_TIMESTAMP`)
      .run(phone, digest(phone, code), expiresAt);

    const smsResponse = await fetch(smsUrl, {
      method: 'POST',
      headers: { 'content-type': 'application/json', authorization: `Bearer ${smsToken}` },
      body: JSON.stringify({ to: phone, phone, message: `Your RoutePass verification code is ${code}. It expires in 5 minutes.`, text: `Your RoutePass verification code is ${code}. It expires in 5 minutes.` }),
      signal: AbortSignal.timeout(10000)
    });
    if (!smsResponse.ok) {
      await DB.prepare('DELETE FROM phone_otp_challenges WHERE phone = ?').run(phone);
      console.error('[OTP] SMS provider rejected delivery:', smsResponse.status);
      return res.status(502).json({ success: false, error: 'Could not deliver verification code. Please try again.' });
    }
    return res.json({ success: true, message: 'Verification code sent. It expires in 5 minutes.', expiresInSeconds: 300 });
  } catch (err) {
    console.error('[OTP] Request failed:', err.message);
    return res.status(500).json({ success: false, error: 'Unable to send verification code.' });
  }
});

router.post('/verify', verifyLimiter, async (req, res) => {
  try {
    const phone = normalizePhone(req.body.phone);
    const code = String(req.body.code || '').trim();
    if (!/^\+?[0-9]{9,15}$/.test(phone) || !/^\d{6}$/.test(code)) return res.status(400).json({ success: false, error: 'Enter the phone number and six-digit verification code.' });
    const challenge = await DB.prepare('SELECT * FROM phone_otp_challenges WHERE phone = ?').get(phone);
    if (!challenge || challenge.verified_at || new Date(challenge.expires_at).getTime() < Date.now() || Number(challenge.attempts) >= 5) {
      return res.status(400).json({ success: false, error: 'Verification code expired or invalid. Request a new code.' });
    }
    if (challenge.otp_hash !== digest(phone, code)) {
      await DB.prepare('UPDATE phone_otp_challenges SET attempts = attempts + 1 WHERE phone = ?').run(phone);
      return res.status(400).json({ success: false, error: 'Incorrect verification code.' });
    }
    await DB.prepare('UPDATE phone_otp_challenges SET verified_at = CURRENT_TIMESTAMP WHERE phone = ?').run(phone);
    return res.json({ success: true, verified: true, message: 'Phone number verified.' });
  } catch (err) {
    console.error('[OTP] Verification failed:', err.message);
    return res.status(500).json({ success: false, error: 'Unable to verify phone number.' });
  }
});

module.exports = router;
