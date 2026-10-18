'use strict';
const { DB } = require('../db');
async function setting(key) { try { const row = await DB.prepare('SELECT value FROM app_settings WHERE key=?').get(key); return row?.value || ''; } catch (_) { return ''; } }
async function sendSms(phone, message) {
  const token = (await setting('AFRO_SMS_API_TOKEN')) || process.env.AFRO_SMS_API_TOKEN || '';
  const url = (await setting('AFRO_SMS_URL')) || process.env.AFRO_SMS_URL || 'https://api.afromessage.com/api/send';
  const sender = (await setting('AFRO_SMS_SENDER')) || process.env.AFRO_SMS_SENDER || 'RoutePass';
  if (!token || !phone || !message) return { sent: false, skipped: true };
  try { const response = await fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + token }, body: JSON.stringify({ to: phone, message, sender }), signal: AbortSignal.timeout(10000) }); return { sent: response.ok, status: response.status }; }
  catch (error) { console.error('[Afro SMS] send failed:', error.message); return { sent: false, error: error.message }; }
}
module.exports = { sendSms };
