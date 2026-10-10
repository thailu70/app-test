const express = require('express');
const crypto = require('crypto');
const rateLimit = require('express-rate-limit');
const { DB } = require('../db');
const { sha256, sendEthioTelecomSms } = require('../services/ethio-telecom-sms');

const router = express.Router();
const otpLimiter = rateLimit({ windowMs: 15 * 60 * 1000, limit: 5, standardHeaders: true, legacyHeaders: false });
const verifyLimiter = rateLimit({ windowMs: 15 * 60 * 1000, limit: 10, standardHeaders: true, legacyHeaders: false });
const cleanPhone = value => String(value || '').trim().replace(/[\s()-]/g, '');

router.post('/request', otpLimiter, async (req, res) => {
  try {
    const phone = cleanPhone(req.body.phone);
    if (!/^\+?\d{9,15}$/.test(phone)) {
      return res.status(400).json({ success: false, error: 'Enter a valid mobile number including country code when needed.' });
    }
    const existing = await DB.prepare('SELECT id FROM users WHERE phone = ?').get(phone);
    if (existing) return res.status(409).json({ success: false, error: 'This mobile number is already registered.' });

    const code = String(crypto.randomInt(0, 1000000)).padStart(6, '0');
    const id = `otp_${crypto.randomUUID()}`;
    const expiresAt = new Date(Date.now() + 5 * 60 * 1000).toISOString();
    await DB.prepare('INSERT INTO otp_challenges (id, phone, otp_hash, expires_at, attempts, verified, proof_hash) VALUES (?, ?, ?, ?, 0, 0, NULL)')
      .run(id, phone, sha256(code), expiresAt);

    try {
      await sendEthioTelecomSms(phone, `Your RoutePass verification code is ${code}. It expires in 5 minutes. Do not share this code.`);
    } catch (smsErr) {
      await DB.prepare('DELETE FROM otp_challenges WHERE id = ?').run(id);
      if (smsErr.code === 'SMS_NOT_CONFIGURED') {
        return res.status(503).json({ success: false, error: 'SMS verification is not configured. Contact the administrator.' });
      }
      console.error('[RoutePass] OTP SMS delivery failed:', smsErr.code || smsErr.message);
      return res.status(502).json({ success: false, error: 'Could not deliver verification code. Try again later.' });
    }
    return res.status(201).json({ success: true, challengeId: id, expiresInSeconds: 300, message: 'Verification code sent.' });
  } catch (err) {
    console.error('[RoutePass] OTP request failed:', err.message);
    return res.status(500).json({ success: false, error: 'Could not request verification code.' });
  }
});

router.post('/verify', verifyLimiter, async (req, res) => {
  try {
    const challengeId = String(req.body.challengeId || '');
    const code = String(req.body.code || '').trim();
    if (!challengeId || !/^\d{6}$/.test(code)) {
      return res.status(400).json({ success: false, error: 'Challenge ID and six-digit code are required.' });
    }
    const challenge = await DB.prepare('SELECT * FROM otp_challenges WHERE id = ?').get(challengeId);
    if (!challenge) return res.status(404).json({ success: false, error: 'Verification challenge not found. Request a new code.' });
    if (challenge.verified) return res.status(409).json({ success: false, error: 'This code has already been used. Request a new code.' });
    if (new Date(challenge.expiresAt || challenge.expires_at).getTime() <= Date.now()) {
      await DB.prepare('DELETE FROM otp_challenges WHERE id = ?').run(challengeId);
      return res.status(410).json({ success: false, error: 'Code expired. Request a new code.' });
    }
    if (Number(challenge.attempts) >= 5) {
      await DB.prepare('DELETE FROM otp_challenges WHERE id = ?').run(challengeId);
      return res.status(429).json({ success: false, error: 'Too many incorrect attempts. Request a new code.' });
    }
    if (sha256(code) !== (challenge.otpHash || challenge.otp_hash)) {
      await DB.prepare('UPDATE otp_challenges SET attempts = attempts + 1 WHERE id = ?').run(challengeId);
      return res.status(400).json({ success: false, error: 'Incorrect verification code.' });
    }
    const proof = crypto.randomBytes(32).toString('hex');
    await DB.prepare('UPDATE otp_challenges SET verified = 1, proof_hash = ? WHERE id = ? AND verified = 0')
      .run(sha256(proof), challengeId);
    return res.json({ success: true, otpProof: proof, phone: challenge.phone, message: 'Mobile number verified. Complete registration.' });
  } catch (err) {
    console.error('[RoutePass] OTP verification failed:', err.message);
    return res.status(500).json({ success: false, error: 'Could not verify code.' });
  }
});

module.exports = router;
