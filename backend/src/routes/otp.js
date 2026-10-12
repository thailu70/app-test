const express = require('express');
const crypto = require('crypto');
const rateLimit = require('express-rate-limit');
const { DB } = require('../db');
const { sha256, requestAfroMessageOtp, verifyAfroMessageOtp } = require('../services/ethio-telecom-sms');

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

    const providerVerificationId = await requestAfroMessageOtp(phone);
    const id = `otp_${crypto.randomUUID()}`;
    const expiresAt = new Date(Date.now() + 5 * 60 * 1000).toISOString();
    // proof_hash temporarily holds the provider verification ID until it is replaced with the one-time registration proof.
    await DB.prepare('INSERT INTO otp_challenges (id, phone, otp_hash, expires_at, attempts, verified, proof_hash) VALUES (?, ?, ?, ?, 0, 0, ?)')
      .run(id, phone, sha256(providerVerificationId), expiresAt, providerVerificationId);

    return res.status(201).json({ success: true, challengeId: id, expiresInSeconds: 300, message: 'Verification code sent.' });
  } catch (smsErr) {
    if (smsErr.code === 'SMS_NOT_CONFIGURED') {
      return res.status(503).json({ success: false, error: 'SMS verification is not configured. Contact the administrator.' });
    }
    console.error('[RoutePass] AfroMessage OTP request failed:', smsErr.code || smsErr.message);
    return res.status(502).json({ success: false, error: 'Could not deliver verification code. Try again later.' });
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

    let verified;
    try {
      verified = await verifyAfroMessageOtp(challenge.phone, challenge.proofHash || challenge.proof_hash, code);
    } catch (providerErr) {
      console.error('[RoutePass] AfroMessage OTP verification provider error:', providerErr.code || providerErr.message);
      return res.status(502).json({ success: false, error: 'Could not verify the code with the SMS provider. Try again.' });
    }
    if (!verified) {
      await DB.prepare('UPDATE otp_challenges SET attempts = attempts + 1 WHERE id = ?').run(challengeId);
      return res.status(400).json({ success: false, error: 'Incorrect or expired verification code.' });
    }

    const proof = crypto.randomBytes(32).toString('hex');
    const update = await DB.prepare('UPDATE otp_challenges SET verified = 1, proof_hash = ? WHERE id = ? AND verified = 0')
      .run(sha256(proof), challengeId);
    if (update?.changes === 0) return res.status(409).json({ success: false, error: 'This code has already been used. Request a new code.' });
    return res.json({ success: true, otpProof: proof, phone: challenge.phone, message: 'Mobile number verified. Complete registration.' });
  } catch (err) {
    console.error('[RoutePass] OTP verification failed:', err.message);
    return res.status(500).json({ success: false, error: 'Could not verify code.' });
  }
});

module.exports = router;
