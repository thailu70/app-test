'use strict';
const crypto = require('crypto');
const bcrypt = require('bcryptjs');
const fs = require('fs/promises');
const path = require('path');
const { DB } = require('../db');

class AccountDeletionError extends Error {
  constructor(status, message, blockers = []) { super(message); this.status = status; this.blockers = blockers; }
}
const RETAINED = Object.freeze([
  'Payment, refund and transaction records needed for accounting, fraud prevention and disputes.',
  'Historical journey, boarding and safety records needed to resolve transport or safety disputes.',
  'Existing support complaints and security audit records needed to resolve outstanding issues.',
  'A non-login account identifier and deletion receipt that prevent deleted credentials from being restored.',
  'Copies in protected backups until those backups are replaced; restored systems must reapply deletion records.'
]);
const CONSEQUENCES = Object.freeze([
  'This permanently closes your account, signs you out and prevents its reuse.',
  'Your sign-in phone, password, email, profile details, emergency contacts and uploaded documents are removed from the live account.',
  'Current subscriptions and boarding passes are cancelled. Deletion does not automatically issue a refund; contact support about unused paid travel.',
  'Historical financial, safety and support records may be retained for the reasons listed below. Guest intercity tickets are separate from an app account; contact the privacy team to request their removal.'
]);
const tasks = new WeakMap();
async function initAccountDeletionSchema(db = DB) {
  if (!tasks.has(db)) tasks.set(db, Promise.resolve(db.exec(`
    CREATE TABLE IF NOT EXISTS account_deletion_requests (
      id TEXT PRIMARY KEY, account_id TEXT NOT NULL, role TEXT NOT NULL,
      status TEXT NOT NULL, requested_at TEXT NOT NULL, completed_at TEXT,
      UNIQUE(account_id,role)
    );
    CREATE TABLE IF NOT EXISTS account_deletion_files (
      request_id TEXT NOT NULL REFERENCES account_deletion_requests(id),
      folder TEXT NOT NULL, storage_name TEXT NOT NULL, deleted_at TEXT,
      PRIMARY KEY(request_id,folder,storage_name)
    );
  `)).catch(error => { tasks.delete(db); throw error; }));
  return tasks.get(db);
}
async function hasTable(db, name) {
  return Boolean(await db.prepare(db.isPostgres
    ? "SELECT tablename AS name FROM pg_tables WHERE schemaname=current_schema() AND tablename=?"
    : "SELECT name FROM sqlite_master WHERE type='table' AND name=?").get(name));
}
async function isAccountClosed(id, role, db = DB) {
  // Some isolated feature tests and older development databases have not run the
  // additive migration yet. Never swallow connection or other query failures.
  try { return Boolean(await db.prepare('SELECT id FROM account_deletion_requests WHERE account_id=? AND role=?').get(id, role)); }
  catch (error) {
    if (error.code === '42P01' || /no such table: account_deletion_requests/.test(error.message || '')) return false;
    throw error;
  }
}
async function account(db, id, role, lock = false) {
  const suffix = lock && db.isPostgres ? ' FOR UPDATE' : '';
  return role === 'COMPANY'
    ? db.prepare('SELECT * FROM transport_companies WHERE id=?' + suffix).get(id)
    : db.prepare('SELECT * FROM users WHERE id=? AND role=?' + suffix).get(id, role);
}
async function blockersFor(db, id, role, at = Date.now()) {
  const blockers = [];
  // Arrival is valid through 30 minutes after the published departure, so a
  // recently overdue scheduled journey still needs its assigned operator.
  const departureCutoff = new Date(at - 30 * 60000).toISOString();
  if (role === 'ADMIN') return [{ code: 'ADMIN_ACCOUNT', message: 'Administrator accounts require an administrator handover. Contact the privacy team.' }];
  if (role === 'DRIVER') {
    if (await db.prepare("SELECT id FROM trips WHERE driverId=? AND status='IN_PROGRESS' LIMIT 1").get(id)) blockers.push({ code: 'ACTIVE_TRIP', message: 'Complete the active trip before closing your account.' });
    if (await hasTable(db, 'intercity_departures') && await db.prepare("SELECT id FROM intercity_departures WHERE driver_id=? AND (status='BOARDING' OR (status='SCHEDULED' AND departure_at>=?)) LIMIT 1").get(id, departureCutoff)) blockers.push({ code: 'COMMITTED_DEPARTURE', message: 'Ask your company or administrator to reassign or cancel your scheduled journeys first.' });
    const assignedPasses = await db.prepare(`SELECT s.* FROM subscriptions s JOIN vehicles v ON v.id=s.vehicleId JOIN users p ON p.id=s.passengerId
      WHERE v.driverId=? AND s.subscriptionStatus='ACTIVE' AND s.paymentStatus='PAID' AND p.status='ACTIVE'
      AND NOT EXISTS (SELECT 1 FROM subscriptions newer WHERE newer.passengerId=s.passengerId AND
        (newer.updatedAt>s.updatedAt OR (newer.updatedAt=s.updatedAt AND newer.id>s.id)))`).all(id);
    if (assignedPasses.some(pass => require('./subscription-days').remainingSubscriptionDays(pass) > 0)) blockers.push({ code: 'ASSIGNED_PASSENGERS', message: 'Ask your administrator to reassign your active passengers before closing your account.' });
  }
  if (role === 'COMPANY') {
    if (await db.prepare("SELECT id FROM users WHERE companyId=? AND role='DRIVER' AND status IN ('ACTIVE','PENDING') LIMIT 1").get(id)) blockers.push({ code: 'COMPANY_DRIVERS', message: 'Arrange the transfer or release of company drivers with an administrator first.' });
    if (await db.prepare("SELECT id FROM vehicles WHERE companyId=? AND (COALESCE(driverId,'')<>'' OR currentOccupancy>0) LIMIT 1").get(id)) blockers.push({ code: 'COMPANY_ASSIGNMENTS', message: 'Release company vehicle assignments and finish boarding before closing the company account.' });
    if (await hasTable(db, 'intercity_departures') && await db.prepare("SELECT id FROM intercity_departures WHERE company_id=? AND (status='BOARDING' OR (status='SCHEDULED' AND departure_at>=?)) LIMIT 1").get(id, departureCutoff)) blockers.push({ code: 'COMPANY_DEPARTURES', message: 'Resolve upcoming company journeys and their passengers with an administrator first.' });
  }
  return blockers;
}
function createAccountDeletionService({ db = DB, uploadRoot = process.env.UPLOAD_DIR || path.join(process.env.DATA_DIR || path.join(__dirname, '..', '..', 'data'), 'uploads'), now = Date.now } = {}) {
  const root = path.resolve(uploadRoot);
  let transactionTail = Promise.resolve();
  const transact = fn => {
    if (db.isPostgres) return db.transaction(fn);
    const operation = transactionTail.then(() => db.transaction(fn));
    transactionTail = operation.catch(() => {});
    return operation;
  };
  async function inspect(id, role) {
    await initAccountDeletionSchema(db);
    const current = await account(db, id, role);
    if (!current || await isAccountClosed(id, role, db)) throw new AccountDeletionError(401, 'This account is no longer available.');
    const blockers = await blockersFor(db, id, role, now());
    return { eligible: !blockers.length, blockers, consequences: [...CONSEQUENCES], retainedCategories: [...RETAINED],
      privacyUrl: 'https://passo.live/privacy/', requestUrl: 'https://passo.live/delete-account/', supportEmail: 'thailu70@gmail.com' };
  }
  async function cleanFiles(requestId) {
    const rows = await db.prepare('SELECT * FROM account_deletion_files WHERE request_id=? AND deleted_at IS NULL').all(requestId);
    for (const row of rows) {
      // Only generated private upload basenames and the two known directories are
      // eligible. No recursive deletion, user supplied paths or symlink traversal.
      if (!['profiles','onboarding-companies'].includes(row.folder) || !/^[a-f0-9-]+\.(png|jpg|pdf)$/.test(row.storage_name)) continue;
      const table = row.folder === 'profiles' ? 'user_profile_documents' : 'onboarding_company_documents';
      if (await hasTable(db, table) && await db.prepare(`SELECT storage_name FROM ${table} WHERE storage_name=? LIMIT 1`).get(row.storage_name)) continue;
      const folder = path.join(root, row.folder), target = path.join(folder, row.storage_name);
      try {
        const rootReal = await fs.realpath(root), folderReal = await fs.realpath(folder);
        if (path.dirname(folderReal) !== rootReal) continue;
        const stat = await fs.lstat(target);
        if (!stat.isFile() || stat.isSymbolicLink()) continue;
        await fs.unlink(target);
      } catch (error) { if (error.code !== 'ENOENT') continue; }
      await db.prepare('UPDATE account_deletion_files SET deleted_at=? WHERE request_id=? AND folder=? AND storage_name=?').run(new Date(now()).toISOString(), requestId, row.folder, row.storage_name);
    }
    const pending = await db.prepare('SELECT COUNT(*) AS count FROM account_deletion_files WHERE request_id=? AND deleted_at IS NULL').get(requestId);
    const status = Number(pending.count) ? 'PENDING_CLEANUP' : 'COMPLETED_WITH_RETENTION';
    await db.prepare('UPDATE account_deletion_requests SET status=?,completed_at=? WHERE id=?').run(status, status === 'PENDING_CLEANUP' ? null : new Date(now()).toISOString(), requestId);
    return status;
  }
  async function erase(id, role, input) {
    await initAccountDeletionSchema(db);
    if (input?.confirmation !== 'DELETE' || typeof input?.password !== 'string' || !input.password || input.password.length > 256) throw new AccountDeletionError(400, 'Enter your current password and confirm DELETE.');
    if (!['PASSENGER','DRIVER','COMPANY'].includes(role)) throw new AccountDeletionError(403, 'Administrator account closure requires a handover. Contact the privacy team.');
    const before = await account(db, id, role);
    if (!before || await isAccountClosed(id, role, db) || !['ACTIVE','PENDING'].includes(before.status) || !await bcrypt.compare(input.password, before.passwordHash || before.password_hash || '')) throw new AccountDeletionError(401, 'Current password is incorrect or the account is unavailable.');
    const replacementHash = await bcrypt.hash(crypto.randomBytes(48).toString('hex'), 12), requestedAt = new Date(now()).toISOString();
    const requestId = 'delete_' + crypto.randomUUID().replace(/-/g, '');
    await transact(async tx => {
      // Vehicle-first order matches boarding/start-trip locks. A queued trip sees
      // its driver removed after closure; an already committed trip blocks closure.
      if (role === 'DRIVER' || role === 'COMPANY') await tx.prepare('SELECT id FROM vehicles WHERE ' + (role === 'COMPANY' ? 'companyId' : 'driverId') + '=? ORDER BY id' + (tx.isPostgres ? ' FOR UPDATE' : '')).all(id);
      const current = await account(tx, id, role, true);
      if (!current || await isAccountClosed(id, role, tx) || (current.passwordHash || current.password_hash) !== (before.passwordHash || before.password_hash) || !['ACTIVE','PENDING'].includes(current.status)) throw new AccountDeletionError(401, 'The account changed. Sign in again before requesting deletion.');
      const blockers = await blockersFor(tx, id, role, now());
      if (blockers.length) throw new AccountDeletionError(409, blockers[0].message, blockers);
      await tx.prepare('INSERT INTO account_deletion_requests(id,account_id,role,status,requested_at) VALUES(?,?,?,?,?)').run(requestId, id, role, 'PENDING_CLEANUP', requestedAt);
      const documentTable = role === 'COMPANY' ? 'onboarding_company_documents' : 'user_profile_documents', documentKey = role === 'COMPANY' ? 'company_id' : 'user_id';
      if (await hasTable(tx, documentTable)) {
        const docs = await tx.prepare(`SELECT storage_name FROM ${documentTable} WHERE ${documentKey}=?`).all(id);
        for (const doc of docs) await tx.prepare('INSERT INTO account_deletion_files(request_id,folder,storage_name) VALUES(?,?,?) ON CONFLICT DO NOTHING').run(requestId, role === 'COMPANY' ? 'onboarding-companies' : 'profiles', doc.storage_name);
        await tx.prepare(`DELETE FROM ${documentTable} WHERE ${documentKey}=?`).run(id);
      }
      if (await hasTable(tx, 'user_profiles')) await tx.prepare('DELETE FROM user_profiles WHERE user_id=?').run(id);
      if (await hasTable(tx, 'onboarding_accounts')) await tx.prepare('DELETE FROM onboarding_accounts WHERE account_id=? AND role=?').run(id, role);
      if (await hasTable(tx, 'auth_otps')) {
        const normalized = require('./onboarding').normalizePhone(current.phone);
        const local = normalized ? '0' + normalized.slice(4) : current.phone;
        await tx.prepare('DELETE FROM auth_otps WHERE phone IN (?,?,?,?)').run(current.phone || '', normalized || '', local || '', normalized?.slice(1) || '');
      }
      if (role === 'COMPANY') {
        await tx.prepare("UPDATE transport_companies SET name=?,phone=NULL,representative_name='',password_hash=?,status='INACTIVE',updated_at=CURRENT_TIMESTAMP WHERE id=?").run('Closed company ' + requestId.slice(-12), replacementHash, id);
        if (await hasTable(tx, 'transport_company_access_codes')) await tx.prepare('UPDATE transport_company_access_codes SET revoked=TRUE WHERE company_id=?').run(id);
        await tx.prepare('UPDATE routes SET active=FALSE WHERE companyId=?').run(id);
      } else {
        await tx.prepare("UPDATE users SET fullName='Deleted account',phone=?,email=NULL,passwordHash=?,status='SUSPENDED',licenseNumber='',companyName='',assignedVehiclePlate='',appliedRouteId='',appliedRouteName='' WHERE id=?").run('closed_' + requestId.slice(-20), replacementHash, id);
        await tx.prepare("UPDATE subscriptions SET subscriptionStatus='CANCELLED',qrToken=NULL,daysRemaining=0,vehicleId='',updatedAt=CURRENT_TIMESTAMP WHERE passengerId=?").run(id);
        await tx.prepare("UPDATE checkin_records SET passengerName='Deleted account' WHERE passengerId=?").run(id);
        await tx.prepare("UPDATE complaints SET passengerName='Deleted account' WHERE passengerId=?").run(id);
      }
      if (role === 'DRIVER') {
        await tx.prepare('DELETE FROM vehicle_live_locations WHERE vehicle_id IN (SELECT id FROM vehicles WHERE driverId=?)').run(id);
        await tx.prepare("UPDATE vehicles SET driverId=NULL,driverName='',assignedRouteId=NULL,currentLat=NULL,currentLng=NULL,currentOccupancy=0,status='OFF_DUTY',updatedAt=CURRENT_TIMESTAMP WHERE driverId=?").run(id);
      }
      if (await hasTable(tx, 'company_route_assignments')) await tx.prepare("UPDATE company_route_assignments SET status='INACTIVE' WHERE " + (role === 'COMPANY' ? 'company_id' : 'driver_id') + '=?').run(id);
    });
    let status = 'PENDING_CLEANUP';
    try { status = await cleanFiles(requestId); } catch (_) { /* The persisted queue is retried on startup; closure still stands. */ }
    return { id: requestId, status, sessionRevoked: true, retainedCategories: [...RETAINED],
      message: status === 'PENDING_CLEANUP' ? 'Your account is closed. Private file deletion is queued for retry. Keep this receipt and contact the privacy team for updates.' : 'Your account is closed and live profile data removed. The listed historical records are retained for the stated purposes.' };
  }
  async function retryPendingFiles() {
    await initAccountDeletionSchema(db);
    const rows = await db.prepare("SELECT id FROM account_deletion_requests WHERE status='PENDING_CLEANUP' ORDER BY requested_at LIMIT 100").all();
    for (const row of rows) await cleanFiles(row.id);
    return rows.length;
  }
  return { inspect, erase, retryPendingFiles };
}
module.exports = { createAccountDeletionService, initAccountDeletionSchema, isAccountClosed, AccountDeletionError };
