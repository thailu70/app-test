'use strict';
const crypto = require('crypto');
const bcrypt = require('bcryptjs');
const { DB } = require('../db');
const { initAccountSessionSchema, revokeAccountSessions } = require('./account-sessions');
const { isAccountClosed } = require('./account-deletion');
const ROLES = new Set(['DRIVER', 'PASSENGER', 'COMPANY']);
const REQUEST_MESSAGE = 'If an eligible account matches these details, a password reset code will be sent by SMS. Check your phone, or contact info@passo.live for help.';
class AccountRecoveryError extends Error { constructor(status, message) { super(message); this.status = status; } }
const schemas = new WeakMap();
async function initAccountRecoverySchema(db = DB) {
  await initAccountSessionSchema(db);
  if (!schemas.has(db)) schemas.set(db, Promise.resolve(db.exec(`CREATE TABLE IF NOT EXISTS account_recovery_challenges (
    account_id TEXT NOT NULL, role TEXT NOT NULL, phone TEXT NOT NULL, challenge_id TEXT NOT NULL,
    code_hash TEXT NOT NULL, password_fingerprint TEXT NOT NULL, expires_at TEXT NOT NULL,
    requested_at TEXT NOT NULL, attempts INTEGER NOT NULL DEFAULT 0, sent_at TEXT, consumed_at TEXT,
    PRIMARY KEY(account_id,role)
  ); CREATE TABLE IF NOT EXISTS account_recovery_limits (
    request_key TEXT PRIMARY KEY, window_start TEXT NOT NULL, last_request_at TEXT NOT NULL, request_count INTEGER NOT NULL
  )`)).catch(error => { schemas.delete(db); throw error; }));
  return schemas.get(db);
}
function normalizeRecoveryPhone(value) {
  if (typeof value !== 'string') return null;
  const match = /^(?:\+251|251|0)?([79]\d{8})$/.exec(value.trim().replace(/[\s()-]/g, ''));
  return match ? '+251' + match[1] : null;
}
function createAccountRecoveryService({ db = DB, now = Date.now, sendSms = process.env.NODE_ENV === 'test' ? async () => ({ sent: false }) : (...args) => require('./afro-sms').sendSms(...args) } = {}) {
  let tail = Promise.resolve();
  const deliveries = new Set();
  const write = fn => db.isPostgres ? db.transaction(fn) : (tail = tail.catch(() => {}).then(() => db.transaction(fn)));
  const at = () => new Date(now()).toISOString();
  const hash = (...parts) => crypto.createHmac('sha256', require('../middleware/auth').JWT_SECRET).update(JSON.stringify(parts)).digest('hex');
  const same = (left, right) => typeof left === 'string' && typeof right === 'string' && left.length === right.length && crypto.timingSafeEqual(Buffer.from(left), Buffer.from(right));
  async function find(tx, phone, role, lock = false) {
    const table = role === 'COMPANY' ? 'transport_companies' : 'users';
    const national = phone.slice(4), clean = "REPLACE(REPLACE(REPLACE(REPLACE(phone,' ',''),'-',''),'(',''),')','')";
    return tx.prepare(`SELECT * FROM ${table} WHERE ${clean} IN (?,?,?,?)${role === 'COMPANY' ? '' : ' AND role=?'}${lock && tx.isPostgres ? ' FOR UPDATE' : ''}`)
      .get(phone, phone.slice(1), '0' + national, national, ...(role === 'COMPANY' ? [] : [role]));
  }
  async function eligible(account, role, tx) {
    return account && ['ACTIVE', 'PENDING', ...(role === 'DRIVER' ? ['SUSPENDED'] : [])].includes(account.status) && Boolean(account.passwordHash || account.password_hash) && !await isAccountClosed(account.id, role, tx);
  }
  async function request(input = {}) {
    await initAccountRecoverySchema(db);
    const role = String(input.role || '').toUpperCase(), phone = normalizeRecoveryPhone(input.phone);
    const generic = { success: true, message: REQUEST_MESSAGE, retryAfterSeconds: 60 };
    if (!ROLES.has(role) || !phone) return generic;
    const code = String(crypto.randomInt(100000, 1000000)), challengeId = crypto.randomUUID();
    const target = await write(async tx => {
      const requestKey = hash('recovery-limit', role, phone);
      if (tx.isPostgres) await tx.prepare('SELECT pg_advisory_xact_lock(hashtext(?))').get(requestKey);
      const limit = await tx.prepare('SELECT * FROM account_recovery_limits WHERE request_key=?').get(requestKey);
      const sameWindow = limit && now() - Date.parse(limit.window_start) < 3600000;
      if (limit && (now() - Date.parse(limit.last_request_at) < 60000 || sameWindow && limit.request_count >= 5)) return null;
      await tx.prepare(`INSERT INTO account_recovery_limits(request_key,window_start,last_request_at,request_count) VALUES(?,?,?,?)
        ON CONFLICT(request_key) DO UPDATE SET window_start=excluded.window_start,last_request_at=excluded.last_request_at,request_count=excluded.request_count`)
        .run(requestKey, sameWindow ? limit.window_start : at(), at(), sameWindow ? limit.request_count + 1 : 1);
      const account = await find(tx, phone, role, true);
      if (!await eligible(account, role, tx)) return null;
      await tx.prepare(`INSERT INTO account_recovery_challenges(account_id,role,phone,challenge_id,code_hash,password_fingerprint,expires_at,requested_at,attempts,sent_at,consumed_at)
        VALUES(?,?,?,?,?,?,?,?,0,NULL,NULL) ON CONFLICT(account_id,role) DO UPDATE SET phone=excluded.phone,challenge_id=excluded.challenge_id,
        code_hash=excluded.code_hash,password_fingerprint=excluded.password_fingerprint,expires_at=excluded.expires_at,requested_at=excluded.requested_at,attempts=0,sent_at=NULL,consumed_at=NULL`)
        .run(account.id, role, phone, challengeId, hash('recovery-code', account.id, role, phone, challengeId, code), hash('password', account.passwordHash || account.password_hash), new Date(now() + 10 * 60000).toISOString(), at());
      return { id: account.id };
    });
    if (target) {
      // Do not expose membership through provider-dependent response timing.
      // Ownership is accepted only after this particular challenge was delivered.
      const pending = new Promise(resolve => setImmediate(resolve)).then(async () => {
        let delivery;
        try { delivery = await sendSms(phone, `PASSO password reset code: ${code}. Valid for 10 minutes. Do not share it. If you did not request this, ignore this message.`); } catch (_) { delivery = null; }
        await db.prepare('UPDATE account_recovery_challenges SET sent_at=?,consumed_at=? WHERE account_id=? AND role=? AND challenge_id=? AND consumed_at IS NULL')
          .run(delivery?.sent ? at() : null, delivery?.sent ? null : at(), target.id, role, challengeId);
      }).catch(() => {});
      deliveries.add(pending); void pending.finally(() => deliveries.delete(pending));
    }
    return generic;
  }
  async function reset(input = {}) {
    await initAccountRecoverySchema(db);
    const role = String(input.role || '').toUpperCase(), phone = normalizeRecoveryPhone(input.phone), code = String(input.code || '');
    const invalid = () => new AccountRecoveryError(400, 'The reset code is invalid, expired or already used. Request a new code if needed.');
    if (!ROLES.has(role) || !phone || !/^\d{6}$/.test(code)) throw invalid();
    if (typeof input.newPassword !== 'string' || input.newPassword.length < 10 || Buffer.byteLength(input.newPassword, 'utf8') > 72) throw new AccountRecoveryError(400, 'Use a password with at least 10 characters and at most 72 bytes.');
    const updated = await write(async tx => {
      const account = await find(tx, phone, role, true);
      if (!await eligible(account, role, tx)) return false;
      const challenge = await tx.prepare('SELECT * FROM account_recovery_challenges WHERE account_id=? AND role=?' + (tx.isPostgres ? ' FOR UPDATE' : '')).get(account.id, role);
      if (!challenge || !challenge.sent_at || challenge.consumed_at || challenge.attempts >= 5 || Date.parse(challenge.expires_at) <= now() || challenge.phone !== phone || !same(challenge.password_fingerprint, hash('password', account.passwordHash || account.password_hash))) return false;
      await tx.prepare('UPDATE account_recovery_challenges SET attempts=attempts+1 WHERE account_id=? AND role=?').run(account.id, role);
      if (!same(challenge.code_hash, hash('recovery-code', account.id, role, phone, challenge.challenge_id, code))) return false;
      const passwordHash = await bcrypt.hash(input.newPassword, 12);
      await tx.prepare(role === 'COMPANY' ? 'UPDATE transport_companies SET password_hash=? WHERE id=?' : 'UPDATE users SET passwordHash=? WHERE id=? AND role=?')
        .run(passwordHash, account.id, ...(role === 'COMPANY' ? [] : [role]));
      await tx.prepare('UPDATE account_recovery_challenges SET consumed_at=? WHERE account_id=? AND role=?').run(at(), account.id, role);
      await revokeAccountSessions(account.id, role, tx, at());
      await tx.prepare('INSERT INTO audit_logs(action,userId,role,details) VALUES(?,?,?,?)').run('PASSWORD_RECOVERED', account.id, role, 'Password reset by SMS ownership proof; existing sessions revoked.');
      return true;
    });
    if (!updated) throw invalid();
    return { success: true, message: 'Password updated. Sign in with your new password.' };
  }
  return { request, reset, waitForDeliveries: () => Promise.all([...deliveries]) };
}
module.exports = { initAccountRecoverySchema, createAccountRecoveryService, AccountRecoveryError, normalizeRecoveryPhone, REQUEST_MESSAGE };
