'use strict';

const { DB } = require('../db');
const AFROMESSAGE_SEND_URL = 'https://api.afromessage.com/api/send';

async function setting(key, db = DB) {
  try { const row = await db.prepare('SELECT value FROM app_settings WHERE key=?').get(key); return row?.value || ''; }
  catch (_) { return ''; }
}

function normalizePhone(value) {
  const raw = String(value || '').trim().replace(/[\s()-]/g, '');
  if (/^09\d{8}$/.test(raw)) return '+251' + raw.slice(1);
  if (/^07\d{8}$/.test(raw)) return '+251' + raw.slice(1);
  if (/^251[79]\d{8}$/.test(raw)) return '+' + raw;
  if (/^\+251[79]\d{8}$/.test(raw)) return raw;
  return '';
}

function safeError(payload, phone, token) {
  const describe = value => {
    if (Array.isArray(value)) return value.map(describe).filter(Boolean).join(' ');
    if (value && typeof value === 'object') {
      return describe(value.message || value.error || value.description || value.detail || value.reason || '');
    }
    return typeof value === 'string' || typeof value === 'number' ? String(value) : '';
  };
  let message = describe(payload);
  const normalized = normalizePhone(phone);
  for (const value of [String(phone || ''), String(phone || '').replace(/[\s()-]/g, ''), normalized]) {
    if (value) message = message.replaceAll(value, '[phone]');
  }
  if (token) message = message.replaceAll(token, '[redacted]');
  return message.replace(/[\u0000-\u001f\u007f]+/g, ' ').replace(/\b\d{6}\b/g, '[code]').replace(/\s+/g, ' ').trim().slice(0, 240);
}

function createAfroSmsService({ db = DB, fetchImpl = (...args) => fetch(...args), env = process.env,
  isReviewRuntime = () => require('../review/isolation').getReviewRuntime() } = {}) {
  async function sendSms(phone, message) {
    if (isReviewRuntime()) return { sent: false, skipped: true, reason: 'Synthetic review environment never sends SMS.' };
    const token = (await setting('AFRO_SMS_API_TOKEN', db)) || env.AFRO_SMS_API_TOKEN || '';
    const url = (await setting('AFRO_SMS_URL', db)) || env.AFRO_SMS_URL || AFROMESSAGE_SEND_URL;
    const sender = (await setting('AFRO_SMS_SENDER', db)) || env.AFRO_SMS_SENDER || '';
    const identifier = (await setting('AFRO_SMS_IDENTIFIER', db)) || env.AFRO_SMS_IDENTIFIER || '';
    const recipient = normalizePhone(phone);
    if (!token || !recipient || !String(message || '').trim()) return { sent: false, skipped: true, reason: 'SMS token, valid phone number, and message are required.' };
    try {
      const response = await fetchImpl(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Accept: 'application/json', Authorization: 'Bearer ' + token },
        body: JSON.stringify({ to: recipient, message: String(message).trim(), ...(identifier ? { from: identifier } : {}), ...(sender ? { sender } : {}) }),
        signal: AbortSignal.timeout(10000)
      });
      let payload = null;
      try { payload = await response.json(); } catch (_) {}
      if (response.ok && payload?.acknowledge === 'success') {
        return { sent: true, status: response.status, messageId: payload?.response?.message_id || payload?.response?.messageId || '' };
      }
      const detail = safeError(payload?.response?.errors || payload, phone, token);
      return { sent: false, status: response.status, error: detail || (!response.ok ? `AfroMessage returned HTTP ${response.status}.` : 'AfroMessage did not accept the message.') };
    } catch (_) { return { sent: false, error: 'Could not connect to AfroMessage.' }; }
  }
  return { sendSms };
}

async function sendWithPhoneGateway(queue) {
  try {
    const result = await queue();
    if (result?.queued) return { sent: true, queued: true, phoneGateway: true,
      ...(result.messageJobId ? { messageJobId: result.messageJobId } : {}),
      ...(result.status ? { gatewayStatus: result.status } : {}) };
    if (result?.sent) return { sent: true, queued: false, phoneGateway: true,
      ...(result.messageJobId ? { messageJobId: result.messageJobId } : {}) };
    if (result?.failed) return { sent: false, queued: false, failed: true, phoneGateway: true,
      messageJobId: result.messageJobId, failureCode: result.failureCode || 'sms_delivery_failed' };
    return null;
  } catch (_) { return null; }
}

/**
 * Deliver sign-in and public-request codes through the selected provider, then
 * use the paired SIM if the provider rejects that recipient or message. This
 * lets the service reach valid Ethiopian numbers that are not on an SMS
 * provider's trial allowlist.
 */
async function sendOtpSms({ otpId, phone, code, message }, { db = DB, fallbackSendSms = smsService.sendSms } = {}) {
  const gateway = require('./phone-sms-gateway');
  let mode = 'AFRO_SMS';
  try { mode = await gateway.getDeliveryMode({ db }); } catch (_) { /* Try the primary SMS provider. */ }
  const queue = () => gateway.queueOtp({ otpId, phone, code, db });

  if (mode === 'PHONE_GATEWAY') {
    const queued = await sendWithPhoneGateway(queue);
    if (queued?.queued) return { sent: true, queued: true };
    if (queued?.failed) return queued;
  }
  let providerResult = null;
  try { providerResult = await fallbackSendSms(phone, message); } catch (_) { /* Try the paired phone below. */ }
  if (providerResult?.sent) return providerResult;
  if (mode !== 'PHONE_GATEWAY') {
    const queued = await sendWithPhoneGateway(queue);
    if (queued) return queued;
  }
  return providerResult || { sent: false, error: 'The SMS could not be sent.' };
}

/** Account, trip and service messages can fall back between the configured provider and paired SIM. */
async function sendTransactionalSms(eventKey, phone, message, { db = DB, fallbackSendSms = smsService.sendSms } = {}) {
  const gateway = require('./phone-sms-gateway');
  let mode = 'AFRO_SMS';
  try { mode = await gateway.getDeliveryMode({ db }); } catch (_) { /* Try the primary SMS provider. */ }
  const queue = () => gateway.queueServiceMessage({ eventKey, phone, message, db });

  if (mode === 'PHONE_GATEWAY') {
    const queued = await sendWithPhoneGateway(queue);
    if (queued) return queued;
  }
  let providerResult = null;
  try { providerResult = await fallbackSendSms(phone, message); } catch (_) { /* Try the paired phone below. */ }
  if (providerResult?.sent) return providerResult;
  if (mode !== 'PHONE_GATEWAY') {
    const queued = await sendWithPhoneGateway(queue);
    if (queued) return queued;
  }
  return providerResult || { sent: false, error: 'The SMS could not be sent.' };
}

const smsService = createAfroSmsService();
module.exports = { sendSms: smsService.sendSms, sendOtpSms, sendTransactionalSms, normalizePhone, createAfroSmsService,
  AFROMESSAGE_SEND_URL };
