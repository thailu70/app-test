'use strict';

const crypto = require('node:crypto');
const { normalizePhone } = require('../src/services/onboarding');

async function verifiedMobilityRequest(db, input) {
  await db.exec('CREATE TABLE IF NOT EXISTS auth_otps (id VARCHAR(100) PRIMARY KEY, phone VARCHAR(40) NOT NULL, purpose VARCHAR(40) NOT NULL, code_hash VARCHAR(128) NOT NULL, verification_token_hash VARCHAR(128), expires_at TIMESTAMP NOT NULL, verified_at TIMESTAMP, attempts INTEGER NOT NULL DEFAULT 0, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)');
  const token = crypto.randomBytes(32).toString('hex');
  const phone = normalizePhone(input.customerPhone);
  const purpose = input.kind === 'CARGO' ? 'CARGO_REQUEST' : 'GROUP_REQUEST';
  const tokenHash = crypto.createHash('sha256').update(token).digest('hex');
  const expiresAt = new Date(Date.now() + 10 * 60 * 1000).toISOString();
  const id = `otp_test_${crypto.randomBytes(16).toString('hex')}`;
  await db.prepare('INSERT INTO auth_otps(id,phone,purpose,code_hash,verification_token_hash,expires_at,verified_at) VALUES(?,?,?,?,?,?,?)')
    .run(id, phone, purpose, 'verified-test-fixture', tokenHash, expiresAt, new Date().toISOString());
  return { ...input, otpVerificationToken: token };
}

module.exports = { verifiedMobilityRequest };
