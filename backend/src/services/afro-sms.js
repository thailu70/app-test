'use strict';
const { DB } = require('../db');
async function setting(key) { try { const row = await DB.prepare('SELECT value FROM app_settings WHERE key=?').get(key); return row?.value || ''; } catch (_) { return ''; } }
function normalizePhone(value) {
  const raw = String(value || '').trim().replace(/[\s()-]/g, '');
  if (/^09\d{8}$/.test(raw)) return '+251' + raw.slice(1);
  if (/^07\d{8}$/.test(raw)) return '+251' + raw.slice(1);
  if (/^251\d{9}$/.test(raw)) return '+' + raw;
  return raw;
}
async function sendSms(phone, message) {
  const token = (await setting('AFRO_SMS_API_TOKEN')) || process.env.AFRO_SMS_API_TOKEN || '';
  const url = (await setting('AFRO_SMS_URL')) || process.env.AFRO_SMS_URL || 'https://api.afromessage.com/api/send';
  const sender = (await setting('AFRO_SMS_SENDER')) || process.env.AFRO_SMS_SENDER || 'RoutePass';
  const identifier = (await setting('AFRO_SMS_IDENTIFIER')) || process.env.AFRO_SMS_IDENTIFIER || '';
  if (!token || !phone || !message) return { sent: false, skipped: true, reason: 'SMS token, phone, and message are required.' };
  try {
    const response = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json', Authorization: 'Bearer ' + token },
      body: JSON.stringify({ to: normalizePhone(phone), message, ...(identifier ? { from: identifier } : {}), ...(sender ? { sender } : {}) }),
      signal: AbortSignal.timeout(10000)
    });
    let payload = null;
    try { payload = await response.json(); } catch (_) {}
    const acknowledged = payload?.acknowledge === 'success';
    const errors = Array.isArray(payload?.response?.errors) ? payload.response.errors.join(' ') : '';
    return { sent: response.ok && acknowledged, status: response.status, messageId: payload?.response?.message_id || payload?.response?.messageId || '', error: response.ok && !acknowledged ? (errors || 'AfroMessage rejected the request.') : undefined };
  } catch (error) { console.error('[Afro SMS] send failed:', error.message); return { sent: false, error: error.message }; }
}
module.exports = { sendSms };
