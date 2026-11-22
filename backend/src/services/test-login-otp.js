'use strict';

const crypto = require('crypto');

const RECORD_PREFIX = 'test:v1:';

function normalizePhone(value) {
  const text = String(value || '').trim().replace(/[\s()-]/g, '');
  const match = /^(?:\+251|251|0)?([79]\d{8})$/.exec(text);
  return match ? '+251' + match[1] : '';
}

function digestCode(code) {
  return crypto.createHash('sha256').update('PASSO_TEST_LOGIN_OTP:v1:' + String(code)).digest('hex');
}

function storedTestOtpHash(codeHash) { return RECORD_PREFIX + codeHash; }

function isTestOtpRecordHash(value) {
  return typeof value === 'string' && /^test:v1:[a-f0-9]{64}$/.test(value);
}

function safeEqualHex(a, b) {
  if (typeof a !== 'string' || typeof b !== 'string' || !/^[a-f0-9]{64}$/.test(a) || !/^[a-f0-9]{64}$/.test(b)) return false;
  return crypto.timingSafeEqual(Buffer.from(a, 'hex'), Buffer.from(b, 'hex'));
}

function getTestLoginOtpConfig({ env = process.env, now = Date.now(), isReviewRuntime } = {}) {
  if (env.PASSO_TEST_OTP_ENABLED !== 'true') return null;
  try {
    if ((isReviewRuntime || (() => require('../review/isolation').getReviewRuntime()))()) return null;
  } catch (_) { return null; }
  const code = String(env.PASSO_TEST_OTP_CODE || '').trim();
  const passengerPhone = normalizePhone(env.PASSO_TEST_OTP_PASSENGER_PHONE);
  const driverPhone = normalizePhone(env.PASSO_TEST_OTP_DRIVER_PHONE);
  const expiresAtMs = Date.parse(String(env.PASSO_TEST_OTP_EXPIRES_AT || ''));
  if (!/^\d{6}$/.test(code) || !passengerPhone || !driverPhone || passengerPhone === driverPhone ||
      !Number.isFinite(expiresAtMs) || expiresAtMs <= now || expiresAtMs > now + 7 * 24 * 60 * 60 * 1000) return null;
  return Object.freeze({ code, codeHash: digestCode(code), passengerPhone, driverPhone, expiresAtMs });
}

function testOtpRoleForPhone(config, phone) {
  if (!config) return '';
  const canonical = normalizePhone(phone);
  if (canonical === config.passengerPhone) return 'PASSENGER';
  if (canonical === config.driverPhone) return 'DRIVER';
  return '';
}

function isConfiguredTestOtpRecord(config, recordHash, phone, purpose = 'LOGIN', role = '') {
  if (!config || purpose !== 'LOGIN' || !isTestOtpRecordHash(recordHash)) return false;
  const mappedRole = testOtpRoleForPhone(config, phone);
  if (!mappedRole || (role && role !== mappedRole)) return false;
  return safeEqualHex(recordHash.slice(RECORD_PREFIX.length), config.codeHash);
}

function testOtpCodeMatches(config, recordHash, code, phone, purpose = 'LOGIN') {
  return isConfiguredTestOtpRecord(config, recordHash, phone, purpose) && safeEqualHex(config.codeHash, digestCode(code));
}

module.exports = { normalizePhone, getTestLoginOtpConfig, testOtpRoleForPhone,
  isTestOtpRecordHash, storedTestOtpHash, isConfiguredTestOtpRecord, testOtpCodeMatches };
