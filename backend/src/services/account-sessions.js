'use strict';
const { DB } = require('../db');
const schemas = new WeakMap();
async function initAccountSessionSchema(db = DB) {
  if (!schemas.has(db)) schemas.set(db, Promise.resolve(db.exec(`CREATE TABLE IF NOT EXISTS account_session_versions (
    account_id TEXT NOT NULL, role TEXT NOT NULL, version INTEGER NOT NULL DEFAULT 0,
    updated_at TEXT NOT NULL, PRIMARY KEY(account_id,role)
  )`)).catch(error => { schemas.delete(db); throw error; }));
  return schemas.get(db);
}
async function getSessionVersion(id, role, db = DB) {
  try { return Number((await db.prepare('SELECT version FROM account_session_versions WHERE account_id=? AND role=?').get(id, String(role || '').toUpperCase()))?.version || 0); }
  catch (error) {
    if (error.code === '42P01' || /no such table: account_session_versions/.test(error.message || '')) return 0;
    throw error;
  }
}
async function loadCredentialSnapshot(id, role, db = DB) {
  const normalizedRole = String(role || '').toUpperCase();
  if (!['ADMIN', 'PASSENGER', 'DRIVER', 'COMPANY'].includes(normalizedRole)) return null;
  await initAccountSessionSchema(db);
  const table = normalizedRole === 'COMPANY' ? 'transport_companies' : 'users';
  // The password hash and version must come from one database snapshot. Reading
  // a newer version after checking an older hash would revive a reset session.
  return db.prepare(`SELECT a.*,COALESCE(s.version,0) AS credential_session_version FROM ${table} a
    LEFT JOIN account_session_versions s ON s.account_id=a.id AND s.role=?
    WHERE a.id=?${normalizedRole === 'COMPANY' ? '' : ' AND a.role=?'}`)
    .get(normalizedRole, id, ...(normalizedRole === 'COMPANY' ? [] : [normalizedRole]));
}
async function sessionIsCurrent(decoded, db = DB) {
  if (!decoded || typeof decoded.id !== 'string' || !decoded.id || typeof decoded.role !== 'string') return false;
  const current = await getSessionVersion(decoded.id, decoded.role, db);
  return (decoded.sessionVersion === undefined ? 0 : decoded.sessionVersion) === current;
}
async function revokeAccountSessions(id, role, db = DB, at = new Date().toISOString()) {
  await db.prepare(`INSERT INTO account_session_versions(account_id,role,version,updated_at) VALUES(?,?,1,?)
    ON CONFLICT(account_id,role) DO UPDATE SET version=account_session_versions.version+1,updated_at=excluded.updated_at`)
    .run(id, String(role || '').toUpperCase(), at);
}
module.exports = { initAccountSessionSchema, getSessionVersion, loadCredentialSnapshot, sessionIsCurrent, revokeAccountSessions };
