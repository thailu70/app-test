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
  let message = Array.isArray(payload) ? payload.join(' ') : String(payload?.message || payload?.error || payload || '');
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

const smsService = createAfroSmsService();
module.exports = { sendSms: smsService.sendSms, normalizePhone, createAfroSmsService,
  AFROMESSAGE_SEND_URL };
