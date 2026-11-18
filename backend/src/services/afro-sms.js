'use strict';

const { DB } = require('../db');
const AFROMESSAGE_SEND_URL = 'https://api.afromessage.com/api/send';

async function setting(key, db = DB) {
  try {
    const row = await db.prepare('SELECT value FROM app_settings WHERE key=?').get(key);
    return row?.value || '';
  } catch (_) { return ''; }
}

function normalizePhone(value) {
  const raw = String(value || '').trim().replace(/[\s()-]/g, '');
  if (/^09\d{8}$/.test(raw) || /^07\d{8}$/.test(raw)) return '+251' + raw.slice(1);
  if (/^251[79]\d{8}$/.test(raw)) return '+' + raw;
  return raw;
}

function safeProviderError(errors, recipient, originalPhone) {
  const joined = Array.isArray(errors) ? errors.join(' ') : String(errors || '');
  let redacted = joined;
  for (const value of [String(originalPhone || ''), String(originalPhone || '').replace(/[\s()-]/g, ''), recipient]) {
    if (value) redacted = redacted.replaceAll(value, '[phone]');
  }
  return redacted
    .replace(/[\u0000-\u001f\u007f]+/g, ' ')
    .replace(/\b\d{6}\b/g, '[code]')
    .replace(/\s+/g, ' ')
    .trim()
    .slice(0, 240);
}

function createAfroSmsService({ db = DB, fetchImpl = (...args) => fetch(...args), env = process.env,
  isReviewRuntime = () => require('../review/isolation').getReviewRuntime() } = {}) {
  async function sendSms(phone, message) {
    if (isReviewRuntime()) return { sent: false, skipped: true, reason: 'Synthetic review environment never sends SMS.' };
    const token = (await setting('AFRO_SMS_API_TOKEN', db)) || env.AFRO_SMS_API_TOKEN || '';
    const sender = (await setting('AFRO_SMS_SENDER', db)) || env.AFRO_SMS_SENDER || '';
    const identifier = (await setting('AFRO_SMS_IDENTIFIER', db)) || env.AFRO_SMS_IDENTIFIER || '';
    const to = normalizePhone(phone);
    if (!token) return { sent: false, skipped: true, reason: 'AfroMessage API token is not configured in Admin > Settings > Afro SMS.' };
    if (!/^\+[1-9]\d{7,14}$/.test(to)) return { sent: false, skipped: true, reason: 'Enter a valid mobile number with a country code.' };
    if (!String(message || '').trim()) return { sent: false, skipped: true, reason: 'SMS message is empty.' };

    const body = { to, message: String(message).trim() };
    if (identifier) body.from = identifier;
    // AfroMessage applies its account default when no verified sender is supplied.
    if (sender) body.sender = sender;
    try {
      const response = await fetchImpl(AFROMESSAGE_SEND_URL, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Accept: 'application/json', Authorization: 'Bearer ' + token },
        body: JSON.stringify(body),
        signal: AbortSignal.timeout(10000)
      });
      let payload = null;
      try { payload = await response.json(); } catch (_) {}
      const acknowledged = payload?.acknowledge === 'success';
      if (!response.ok || !acknowledged) {
        const providerError = safeProviderError(payload?.response?.errors, to, phone);
        return { sent: false, status: response.status, error: providerError || (!response.ok ? 'AfroMessage returned HTTP ' + response.status + '.' : 'AfroMessage did not accept the message.') };
      }
      return { sent: true, status: response.status, messageId: payload?.response?.message_id || payload?.response?.messageId || '' };
    } catch (error) {
      return { sent: false, error: error?.name === 'TimeoutError' || error?.name === 'AbortError' ? 'AfroMessage request timed out.' : 'Could not connect to AfroMessage.' };
    }
  }
  return { sendSms };
}

const { sendSms } = createAfroSmsService();
module.exports = { sendSms, normalizePhone, createAfroSmsService, AFROMESSAGE_SEND_URL };
