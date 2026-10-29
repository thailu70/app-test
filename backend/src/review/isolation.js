'use strict';
// This module has no database/network imports. The dedicated review entry point
// validates isolation BEFORE importing any production route or opening a DB.
const path = require('node:path');
const crypto = require('node:crypto');
let active;
const validated = new WeakSet();
const fail = message => { throw new Error('[Review isolation] ' + message); };
function validateReviewEnvironment(env = process.env, { test = false } = {}) {
  if (env.REVIEW_ENVIRONMENT !== 'true') fail('Explicit REVIEW_ENVIRONMENT=true is required.');
  if (env.PUBLIC_BASE_URL !== 'https://passo.live/review') fail('Only the dedicated /review origin is allowed.');
  if (env.REVIEW_SMS_MODE !== 'DISABLED' || env.PAYMENT_MODE !== 'PRODUCTION' || env.PAYMENT_TESTS_ENABLED !== 'false') fail('Outbound SMS and all payment test paths must be disabled.');
  if (env.ALLOW_MANUAL_TEST_RECHARGE !== 'false' || env.ALLOW_ADMIN_PASS_ACTIVATION !== 'false' || env.PAYMENT_TEST_PASSENGER_ID) fail('No manual/payment account bypass is allowed.');
  for (const [key, value] of Object.entries(env)) if (/^(TELEBIRR_|AFRO_SMS_)/.test(key) && String(value || '').trim()) fail('Provider credentials/configuration must be absent: ' + key);
  const secrets = ['JWT_SECRET', 'QR_SIGNING_KEY', 'ADMIN_REGISTRATION_SECRET', 'REVIEW_FIXTURE_SECRET', 'REVIEW_INSTANCE_ID'];
  for (const key of secrets) if (typeof env[key] !== 'string' || env[key].length < 48) fail(key + ' must be a dedicated generated secret.');
  if (new Set(secrets.map(key => env[key])).size !== secrets.length) fail('Secrets must be distinct.');
  if (typeof env.REVIEW_DEMO_PASSWORD !== 'string' || env.REVIEW_DEMO_PASSWORD.length < 20 || Buffer.byteLength(env.REVIEW_DEMO_PASSWORD)>72) fail('A private demo password of 20 characters to 72 bytes is required.');
  let url; try { url = new URL(env.DATABASE_URL); } catch (_) { fail('A dedicated PostgreSQL URL is required.'); }
  if (!['postgres:', 'postgresql:'].includes(url.protocol) || url.hostname !== 'review-db' || url.pathname !== '/passo_review' || url.username !== 'passo_review' || decodeURIComponent(url.password).length < 24 || url.search || url.hash) fail('The DB must use review-db / passo_review / passo_review with a private password.');
  const data = path.resolve(env.DATA_DIR || ''), uploads = path.resolve(env.UPLOAD_DIR || '');
  if (!test && (env.NODE_ENV !== 'production' || env.DATA_DIR !== '/app/review-data' || env.UPLOAD_DIR !== '/app/review-uploads')) fail('Use production runtime protections and dedicated review-only directories.');
  if (data === uploads || uploads === path.parse(uploads).root || data === path.parse(data).root) fail('Dedicated data/upload directories are required.');
  const config = Object.freeze({ publicBaseUrl: env.PUBLIC_BASE_URL, dataDirectory: data, uploadDirectory: uploads,
    password: env.REVIEW_DEMO_PASSWORD, fixtureSecret: env.REVIEW_FIXTURE_SECRET,
    marker: crypto.createHash('sha256').update(env.REVIEW_INSTANCE_ID).digest('hex') });
  validated.add(config); return config;
}
function activateReviewRuntime(config) {
  if (active || !validated.has(config)) fail('A previously validated, unique review configuration is required.');
  active = config;
}
function getReviewRuntime() { return active || null; }
async function assertReviewDatabase(db, config) {
  if (!config || config !== active) fail('Only the validated alternate entry point may initialize fixtures.');
  if (db.isPostgres) {
    const identity = await db.prepare('SELECT current_database() AS name,current_user AS principal').get();
    if (identity.name !== 'passo_review' || identity.principal !== 'passo_review') fail('Database identity is not the isolated reviewer database.');
  } else if (process.env.NODE_ENV !== 'test') fail('SQLite is allowed only by disposable regression tests.');
  await db.exec('CREATE TABLE IF NOT EXISTS review_environment_marker (id INTEGER PRIMARY KEY, marker TEXT NOT NULL)');
  const row = await db.prepare('SELECT marker FROM review_environment_marker WHERE id=1').get();
  if (row && row.marker !== config.marker) fail('Database ownership marker does not match this deployment.');
  if (!row) {
    // Fresh DBs must contain no accounts. Never adopt an existing deployment,
    // including another review database, merely because its hostname matches.
    const privateTables="('users','transport_companies','subscriptions','payment_transactions','intercity_bookings','mobility_bookings','mobility_requests','onboarding_accounts','user_profiles','user_profile_documents')";
    const tables = db.isPostgres
      ? await db.prepare("SELECT table_name AS name FROM information_schema.tables WHERE table_schema='public' AND table_name IN "+privateTables).all()
      : await db.prepare("SELECT name FROM sqlite_master WHERE type='table' AND name IN "+privateTables).all();
    for (const table of tables) if (Number((await db.prepare('SELECT COUNT(*) AS count FROM ' + table.name).get()).count) !== 0) fail('Refusing to adopt a database containing customer/account records.');
    await db.prepare('INSERT INTO review_environment_marker(id,marker) VALUES(1,?)').run(config.marker);
  }
}
module.exports = { validateReviewEnvironment, activateReviewRuntime, getReviewRuntime, assertReviewDatabase };
