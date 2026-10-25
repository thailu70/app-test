'use strict';
const crypto = require('crypto');
const { DB } = require('../db');
const schemas = new WeakMap();
const DECISIONS = Object.freeze({
  ACCEPT: 'Your initial driver application was accepted. Complete your details and documents for final review.',
  CORRECTIONS: 'Your application needs corrections. Sign in to read the review note and update the requested details or documents.',
  REJECT: 'Your initial application was returned. Sign in to read the review note before resubmitting.',
  APPROVE: 'Your application is approved. Sign in to the Passo app for your assignments.',
  SUSPEND: 'Your driver account is paused. Contact Passo support before operating.',
  RESTORE: 'Your driver account was restored. Sign in to check your current application and assignments.'
});
async function initApplicationNotificationSchema(db = DB) {
  if (!schemas.has(db)) schemas.set(db, Promise.resolve(db.exec(`CREATE TABLE IF NOT EXISTS application_notification_outbox (
    id TEXT PRIMARY KEY, event_key TEXT NOT NULL UNIQUE, account_id TEXT NOT NULL, role TEXT NOT NULL,
    decision TEXT NOT NULL, phone TEXT NOT NULL, message TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'PENDING',
    attempts INTEGER NOT NULL DEFAULT 0, next_attempt_at TEXT NOT NULL, created_at TEXT NOT NULL,
    sent_at TEXT, lease_until TEXT, lease_token TEXT, last_error TEXT NOT NULL DEFAULT ''
  ); CREATE INDEX IF NOT EXISTS application_notification_delivery ON application_notification_outbox(status,next_attempt_at)`))
    .catch(error => { schemas.delete(db); throw error; }));
  return schemas.get(db);
}
function phoneKey(value) {
  const match = /^(?:\+251|251|0)?([79]\d{8})$/.exec(String(value || '').trim().replace(/[\s()-]/g, ''));
  return match ? '+251' + match[1] : null;
}
function createApplicationNotificationService({ db = DB, now = Date.now, sendSms = process.env.NODE_ENV === 'test' ? async () => ({ sent: false }) : (...args) => require('./afro-sms').sendSms(...args) } = {}) {
  let flushing = false, tail = Promise.resolve();
  const write = fn => db.isPostgres ? db.transaction(fn) : (tail = tail.catch(() => {}).then(() => db.transaction(fn)));
  const at = () => new Date(now()).toISOString();
  async function queue(tx, { accountId, role, decision, eventKey }) {
    if (!['DRIVER', 'COMPANY'].includes(role) || !DECISIONS[decision]) throw new Error('Unsupported application notification.');
    const key = String(eventKey || '');
    if (!key || key.length > 200 || /[\u0000-\u001f]/.test(key)) throw new Error('An application notification event key is required.');
    const previous = await tx.prepare('SELECT id FROM application_notification_outbox WHERE event_key=?').get(key);
    if (previous) return previous.id;
    const account = role === 'COMPANY'
      ? await tx.prepare('SELECT phone FROM transport_companies WHERE id=?').get(accountId)
      : await tx.prepare("SELECT phone FROM users WHERE id=? AND role='DRIVER'").get(accountId);
    const phone = phoneKey(account?.phone);
    if (!phone) return null;
    const id = 'app_sms_' + crypto.randomUUID();
    const statusUrl = role === 'COMPANY' ? 'https://passo.live/company/' : 'https://passo.live/join/?view=login';
    const message = 'PASSO: ' + DECISIONS[decision] + ' Status: ' + statusUrl + ' Help: info@passo.live';
    // Newer review decisions replace older undelivered status messages. Historical
    // records are never scanned or converted into notifications on deployment.
    await tx.prepare("UPDATE application_notification_outbox SET status='SUPERSEDED',lease_token=NULL,lease_until=NULL WHERE account_id=? AND role=? AND status='PENDING'").run(accountId, role);
    await tx.prepare(`INSERT INTO application_notification_outbox(id,event_key,account_id,role,decision,phone,message,status,attempts,next_attempt_at,created_at)
      VALUES(?,?,?,?,?,?,?,'PENDING',0,?,?) ON CONFLICT(event_key) DO NOTHING`).run(id, key, accountId, role, decision, phone, message, at(), at());
    return id;
  }
  async function flush({ limit = 10 } = {}) {
    if (flushing) return { sent: 0, failed: 0 };
    flushing = true;
    const summary = { sent: 0, failed: 0 };
    try {
      await initApplicationNotificationSchema(db);
      for (let index = 0; index < Math.min(Math.max(1, limit), 50); index++) {
        const leased = await write(async tx => {
          const row = await tx.prepare(`SELECT * FROM application_notification_outbox WHERE attempts<10 AND
            ((status='PENDING' AND next_attempt_at<=?) OR (status='SENDING' AND lease_until<=?)) ORDER BY created_at,id LIMIT 1${tx.isPostgres ? ' FOR UPDATE SKIP LOCKED' : ''}`).get(at(), at());
          if (!row) return null;
          const account = row.role === 'COMPANY'
            ? await tx.prepare('SELECT phone FROM transport_companies WHERE id=?').get(row.account_id)
            : await tx.prepare("SELECT phone FROM users WHERE id=? AND role='DRIVER'").get(row.account_id);
          if (phoneKey(account?.phone) !== row.phone) {
            await tx.prepare("UPDATE application_notification_outbox SET status='CANCELLED',last_error='ACCOUNT_PHONE_CHANGED' WHERE id=?").run(row.id);
            return { cancelled: true };
          }
          const leaseToken = crypto.randomUUID();
          await tx.prepare("UPDATE application_notification_outbox SET status='SENDING',attempts=attempts+1,lease_token=?,lease_until=? WHERE id=?")
            .run(leaseToken, new Date(now() + 5 * 60000).toISOString(), row.id);
          return { ...row, attempts: row.attempts + 1, leaseToken };
        });
        if (!leased) break;
        if (leased.cancelled) continue;
        let delivered = false;
        try { delivered = (await sendSms(leased.phone, leased.message))?.sent === true; } catch (_) {}
        const retryAt = new Date(now() + Math.min(3600000, 60000 * 2 ** Math.min(leased.attempts - 1, 6))).toISOString();
        await db.prepare('UPDATE application_notification_outbox SET status=?,sent_at=?,next_attempt_at=?,lease_token=NULL,lease_until=NULL,last_error=? WHERE id=? AND lease_token=?')
          .run(delivered ? 'SENT' : leased.attempts >= 10 ? 'FAILED' : 'PENDING', delivered ? at() : null, retryAt, delivered ? '' : 'SMS_DELIVERY_FAILED', leased.id, leased.leaseToken);
        summary[delivered ? 'sent' : 'failed']++;
      }
    } catch (_) {
      // Review commits must survive provider outages or temporary worker errors.
      // A durable pending/leased row is retried by the next worker iteration.
      summary.failed++;
    } finally { flushing = false; }
    return summary;
  }
  return { queue, flush };
}
let defaultService;
const getDefault = () => defaultService ||= createApplicationNotificationService();
const queueApplicationDecision = (tx, input) => getDefault().queue(tx, input);
const flushApplicationNotifications = options => getDefault().flush(options);
function startApplicationNotificationWorker({ intervalMs = 60000 } = {}) {
  if (process.env.NODE_ENV === 'test') return () => {};
  const run = () => { void flushApplicationNotifications(); };
  const timer = setInterval(run, intervalMs); timer.unref?.(); run();
  return () => clearInterval(timer);
}
module.exports = { initApplicationNotificationSchema, createApplicationNotificationService, queueApplicationDecision, flushApplicationNotifications, startApplicationNotificationWorker };
