'use strict';
const crypto = require('node:crypto');
const fs = require('node:fs');
const { DB } = require('../db');
const { getReviewRuntime } = require('../review/isolation');
const schemas = new WeakMap();
class MobilityPushError extends Error {
  constructor(message, status = 400) { super(message); this.status = status; }
}
async function initPushSchema(db = DB) {
  if (!schemas.has(db)) schemas.set(db, Promise.resolve().then(() => db.exec(`
    CREATE TABLE IF NOT EXISTS mobility_push_tokens (
      id TEXT PRIMARY KEY, token_hash TEXT NOT NULL, token_encrypted TEXT NOT NULL,
      scope TEXT NOT NULL, owner_id TEXT NOT NULL, latitude REAL, longitude REAL,
      gps_at TEXT, snapshot TEXT NOT NULL DEFAULT '', registered_at TEXT NOT NULL, updated_at TEXT NOT NULL
    );
    CREATE INDEX IF NOT EXISTS mobility_push_owner ON mobility_push_tokens(scope,owner_id);
    CREATE TABLE IF NOT EXISTS mobility_push_outbox (
      id TEXT PRIMARY KEY, event_key TEXT NOT NULL UNIQUE, token_id TEXT NOT NULL,
      request_id TEXT NOT NULL, event_type TEXT NOT NULL, kind TEXT NOT NULL, scope TEXT NOT NULL,
      status TEXT NOT NULL DEFAULT 'PENDING', attempts INTEGER NOT NULL DEFAULT 0,
      next_attempt_at TEXT NOT NULL, expires_at TEXT NOT NULL, lease_token TEXT, lease_until TEXT, created_at TEXT NOT NULL
    );
    CREATE INDEX IF NOT EXISTS mobility_push_delivery ON mobility_push_outbox(status,next_attempt_at);
  `)).catch(error => { schemas.delete(db); throw error; }));
  return schemas.get(db);
}
const hash = value => crypto.createHash('sha256').update(value).digest('hex');
function tokenValue(value) {
  if (typeof value !== 'string' || !/^[A-Za-z0-9_:.-]{20,2048}$/.test(value)) throw new MobilityPushError('Use a valid notification device token.');
  return value;
}
function createFcmTransport({ accountFile, fetchImpl = globalThis.fetch, now = Date.now } = {}) {
  const account = JSON.parse(fs.readFileSync(accountFile, 'utf8'));
  if (account.type !== 'service_account' || !/^[a-z][a-z0-9-]{4,61}[a-z0-9]$/.test(account.project_id || '') || typeof account.private_key !== 'string' || !account.private_key.includes('PRIVATE KEY') || !String(account.client_email || '').endsWith('.iam.gserviceaccount.com')) throw new Error('Invalid FCM service account configuration.');
  let access = null, pending = null, authorizationRetryAt = 0;
  async function accessToken() {
    if (access && access.until > now()) return access.value;
    if (authorizationRetryAt > now()) throw new Error('FCM authorization is temporarily unavailable.');
    if (!pending) pending = (async () => {
      const issued = Math.floor(now() / 1000);
      const header = Buffer.from(JSON.stringify({ alg: 'RS256', typ: 'JWT' })).toString('base64url');
      const claims = Buffer.from(JSON.stringify({ iss: account.client_email, scope: 'https://www.googleapis.com/auth/firebase.messaging', aud: 'https://oauth2.googleapis.com/token', iat: issued, exp: issued + 3600 })).toString('base64url');
      const unsigned = header + '.' + claims;
      const assertion = unsigned + '.' + crypto.sign('RSA-SHA256', Buffer.from(unsigned), account.private_key).toString('base64url');
      const response = await fetchImpl('https://oauth2.googleapis.com/token', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: new URLSearchParams({ grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer', assertion }), signal: AbortSignal.timeout(10000) });
      const data = await response.json();
      if (!response.ok || typeof data.access_token !== 'string') throw new Error('FCM authorization is unavailable.');
      access = { value: data.access_token, until: now() + Math.max(30, Math.min(3600, Number(data.expires_in) || 3600) - 60) * 1000 };
      return access.value;
    })().catch(error => { authorizationRetryAt = now() + 30000; throw error; }).finally(() => { pending = null; });
    return pending;
  }
  return { async send(token, notification, data) {
    const bearer = await accessToken();
    const response = await fetchImpl('https://fcm.googleapis.com/v1/projects/' + account.project_id + '/messages:send', {
      method: 'POST', headers: { Authorization: 'Bearer ' + bearer, 'Content-Type': 'application/json' }, signal: AbortSignal.timeout(10000),
      // Data-only messages let the app check its current account/guest capability before displaying.
      // Android's automatic notification payload would bypass logout and environment guards.
      body: JSON.stringify({ message: { token, data, android: { priority: 'high', ttl: '3600s', collapse_key: hash(data.requestId + ':' + data.eventType).slice(0, 32) } } })
    });
    const body = await response.json();
    if (response.ok && typeof body.name === 'string') return { sent: true };
    if (response.status === 401) access = null;
    const code = (body.error?.details || []).find(item => String(item['@type'] || '').endsWith('google.firebase.fcm.v1.FcmError'))?.errorCode;
    return { sent: false, invalidToken: ['UNREGISTERED', 'SENDER_ID_MISMATCH'].includes(code), permanent: response.status === 400 && code !== 'UNAVAILABLE' };
  } };
}
function createMobilityPushService({ db = DB, service, env = process.env, now = Date.now, provider, fetchImpl,
  customerBatchSize = 200, deliveryBatchSize = 20,
  driverIsEligible = driverId => require('./operational-onboarding').driverEligible(driverId, db),
  isReview = () => Boolean(getReviewRuntime()) || env.REVIEW_ENVIRONMENT === 'true' } = {}) {
  const mobility = () => service || require('./mobility').getMobilityService();
  const encryptionSecret = env.PUSH_TOKEN_ENCRYPTION_KEY || env.JWT_SECRET || require('../middleware/auth').JWT_SECRET;
  const encryptionKey = crypto.createHash('sha256').update('Passo mobility notification tokens v1\0' + encryptionSecret).digest();
  const customerLimit = Math.min(500, Math.max(1, Math.floor(Number(customerBatchSize)) || 200));
  const deliveryLimit = Math.min(100, Math.max(1, Math.floor(Number(deliveryBatchSize)) || 20));
  let transport = provider, configurationError = false, working = false, driverCursor = '', customerCursor = '';
  const instant = () => new Date(now()).toISOString();
  function health() {
    if (isReview()) return { enabled: false, reason: 'REVIEW_DISABLED' };
    if (!transport && !env.FCM_SERVICE_ACCOUNT_FILE) return { enabled: false, reason: 'NOT_CONFIGURED' };
    if (!transport && !configurationError) try { transport = createFcmTransport({ accountFile: env.FCM_SERVICE_ACCOUNT_FILE, fetchImpl, now }); } catch (_) { configurationError = true; }
    return { enabled: Boolean(transport) && !configurationError, reason: configurationError ? 'CONFIGURATION_UNAVAILABLE' : 'READY' };
  }
  function encrypt(value) {
    const iv = crypto.randomBytes(12), cipher = crypto.createCipheriv('aes-256-gcm', encryptionKey, iv);
    const encrypted = Buffer.concat([cipher.update(value, 'utf8'), cipher.final()]);
    return [iv, cipher.getAuthTag(), encrypted].map(x => x.toString('base64url')).join('.');
  }
  function decrypt(value) {
    const [iv, tag, bytes] = value.split('.').map(x => Buffer.from(x, 'base64url'));
    const decipher = crypto.createDecipheriv('aes-256-gcm', encryptionKey, iv); decipher.setAuthTag(tag);
    return Buffer.concat([decipher.update(bytes), decipher.final()]).toString('utf8');
  }
  async function init() { await mobility().init(); await initPushSchema(db); }
  async function requestSnapshot(requestId) {
    const row = await db.prepare('SELECT id,status,kind,driver_id,assigned_at FROM mobility_requests WHERE id=?').get(requestId);
    if (!row) return null;
    const quotes = await db.prepare("SELECT COUNT(*) AS count,MAX(updated_at) AS updated FROM mobility_quotes WHERE request_id=? AND status='OPEN'").get(requestId);
    return { status: row.status, kind: row.kind, driver: row.driver_id || '', assignedAt: row.assigned_at || '', quotes: Number(quotes.count), updated: quotes.updated || '' };
  }
  async function register(scope, owner, input, snapshot = '') {
    const token = tokenValue(input.token), tokenHash = hash(token), id = hash(scope + '\0' + owner + '\0' + tokenHash);
    let latitude = null, longitude = null, gpsAt = null;
    if (scope === 'DRIVER' && (input.latitude != null || input.longitude != null)) {
      latitude = Number(input.latitude); longitude = Number(input.longitude);
      if (input.latitude == null || input.longitude == null || !Number.isFinite(latitude) || !Number.isFinite(longitude) || Math.abs(latitude) > 90 || Math.abs(longitude) > 180) throw new MobilityPushError('Use a valid current map location.');
      gpsAt = instant();
    }
    await db.transaction(async tx => {
      if (scope === 'DRIVER') await tx.prepare("DELETE FROM mobility_push_tokens WHERE token_hash=? AND scope='DRIVER' AND owner_id<>?").run(tokenHash, owner);
      await tx.prepare(`INSERT INTO mobility_push_tokens(id,token_hash,token_encrypted,scope,owner_id,latitude,longitude,gps_at,snapshot,registered_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)
        ON CONFLICT(id) DO UPDATE SET token_encrypted=excluded.token_encrypted,latitude=COALESCE(excluded.latitude,mobility_push_tokens.latitude),longitude=COALESCE(excluded.longitude,mobility_push_tokens.longitude),gps_at=COALESCE(excluded.gps_at,mobility_push_tokens.gps_at),updated_at=excluded.updated_at`).run(id, tokenHash, encrypt(token), scope, owner, latitude, longitude, gpsAt, snapshot, instant(), instant());
      // A device should not accumulate unlimited old token registrations for one account/booking.
      const old = await tx.prepare('SELECT id FROM mobility_push_tokens WHERE scope=? AND owner_id=? ORDER BY updated_at DESC,id LIMIT 100').all(scope, owner);
      for (const item of old.slice(8)) await tx.prepare('DELETE FROM mobility_push_tokens WHERE id=?').run(item.id);
    });
    return { registered: true, ...health() };
  }
  async function registerDriver(driverId, input) {
    await init();
    if (!await db.prepare("SELECT id FROM users WHERE id=? AND role='DRIVER' AND status='ACTIVE'").get(driverId)) throw new MobilityPushError('Driver account unavailable.', 403);
    return register('DRIVER', driverId, input);
  }
  async function registerRequest(requestId, secret, input) {
    await init(); await mobility().request(requestId, secret);
    return register('REQUEST', requestId, input, JSON.stringify(await requestSnapshot(requestId)));
  }
  async function unregister(scope, owner, input) {
    const token = tokenValue(input.token);
    await db.prepare('DELETE FROM mobility_push_tokens WHERE scope=? AND owner_id=? AND token_hash=?').run(scope, owner, hash(token));
    return { registered: false, ...health() };
  }
  async function unregisterDriver(driverId, input) { await init(); return unregister('DRIVER', driverId, input); }
  async function unregisterRequest(requestId, secret, input) { await init(); await mobility().request(requestId, secret); return unregister('REQUEST', requestId, input); }
  async function enqueue(token, requestId, event, kind, revision) {
    const key = hash(token.id + '\0' + requestId + '\0' + event + '\0' + revision);
    await db.prepare("INSERT INTO mobility_push_outbox(id,event_key,token_id,request_id,event_type,kind,scope,next_attempt_at,expires_at,created_at) VALUES(?,?,?,?,?,?,?,?,?,?) ON CONFLICT(event_key) DO NOTHING")
      .run(crypto.randomUUID(), key, token.id, requestId, event, kind, token.scope, instant(), new Date(now() + 12 * 3600000).toISOString(), instant());
  }
  async function collect() {
    const earliest = new Date(now() - 30 * 86400000).toISOString();
    await db.prepare('DELETE FROM mobility_push_tokens WHERE updated_at<?').run(earliest);
    // Stable keyset paging gives older active bookings the same opportunity as newly registered ones.
    const customerTokens = await db.prepare("SELECT * FROM mobility_push_tokens WHERE scope='REQUEST' AND id>? ORDER BY id LIMIT ?").all(customerCursor, customerLimit);
    customerCursor = customerTokens.length === customerLimit ? customerTokens.at(-1).id : '';
    for (const token of customerTokens) {
      const current = await requestSnapshot(token.owner_id);
      if (!current) { await db.prepare('DELETE FROM mobility_push_tokens WHERE id=?').run(token.id); continue; }
      let previous; try { previous = JSON.parse(token.snapshot); } catch (_) { previous = null; }
      const serialized = JSON.stringify(current);
      if (previous && previous.status !== current.status) await enqueue(token, token.owner_id, 'STATUS_' + current.status, current.kind, serialized);
      else if (previous && current.status === 'REQUESTED' && current.quotes > 0 && (previous.quotes !== current.quotes || previous.updated !== current.updated)) await enqueue(token, token.owner_id, 'QUOTES', current.kind, serialized);
      await db.prepare('UPDATE mobility_push_tokens SET snapshot=? WHERE id=?').run(serialized, token.id);
    }
    const groups = await db.prepare("SELECT DISTINCT owner_id FROM mobility_push_tokens WHERE scope='DRIVER' AND owner_id>? ORDER BY owner_id LIMIT 50").all(driverCursor);
    driverCursor = groups.length === 50 ? groups.at(-1).owner_id : '';
    for (const { owner_id: driverId } of groups) {
      if (!await db.prepare("SELECT id FROM users WHERE id=? AND role='DRIVER' AND status='ACTIVE'").get(driverId) || !await driverIsEligible(driverId)) continue;
      const tokens = await db.prepare("SELECT * FROM mobility_push_tokens WHERE scope='DRIVER' AND owner_id=? ORDER BY updated_at DESC").all(driverId);
      const selected = await db.prepare("SELECT id,kind,assigned_at FROM mobility_requests WHERE driver_id=? AND status IN ('ASSIGNED','AT_PICKUP','LOADED','IN_TRANSIT','AT_DESTINATION') ORDER BY created_at DESC LIMIT 5").all(driverId);
      for (const token of tokens) for (const request of selected) await enqueue(token, request.id, 'SELECTED', request.kind, request.assigned_at || request.id);
      const location = tokens.find(t => t.gps_at && now() - Date.parse(t.gps_at) <= 86400000 && t.latitude != null && t.longitude != null);
      if (!location) continue;
      let result; try { result = await mobility().marketplace.opportunities(driverId, { latitude: Number(location.latitude), longitude: Number(location.longitude) }); } catch (_) { continue; }
      if (result.busy) continue;
      for (const job of (result.requests || []).filter(item => item.nearby && !item.myQuote)) for (const token of tokens) await enqueue(token, job.id, 'NEW_REQUEST', job.kind, driverId);
    }
  }
  function notification(event) {
    if (event === 'NEW_REQUEST') return { title: 'New transport request nearby', body: 'Open Passo to view the request and send your quote.' };
    if (event === 'SELECTED') return { title: 'Your quote was selected', body: 'Open Passo to view your confirmed transport task.' };
    if (event === 'QUOTES') return { title: 'You have transport quotes', body: 'Open Passo to compare driver offers for your request.' };
    const descriptions = { ASSIGNED: 'Your driver is assigned.', AT_PICKUP: 'Your driver has arrived at pickup.', LOADED: 'Collection has been confirmed.', IN_TRANSIT: 'Your transport service has started.', AT_DESTINATION: 'Your driver has arrived at the destination.', AWAITING_CONFIRMATION: 'Please confirm completion or report a problem.', COMPLETED: 'Your service is complete. You can now leave a review.', CANCELLED: 'Your transport request was cancelled.', DISPUTED: 'Your reported issue is awaiting review.' };
    return { title: 'Passo booking update', body: (descriptions[event.replace(/^STATUS_/, '')] || 'Your transport booking has an update.') + ' Open Passo for details.' };
  }
  async function flush() {
    if (working || !health().enabled) return { sent: 0, ...health() };
    working = true; let sent = 0;
    try {
      await init(); await collect();
      await db.prepare("UPDATE mobility_push_outbox SET status='EXPIRED' WHERE status='PENDING' AND expires_at<=?").run(instant());
      const queue = await db.prepare("SELECT * FROM mobility_push_outbox WHERE status='PENDING' AND next_attempt_at<=? AND (lease_until IS NULL OR lease_until<?) ORDER BY created_at,id LIMIT ?").all(instant(), instant(), deliveryLimit);
      for (const job of queue) {
        const token = await db.prepare('SELECT * FROM mobility_push_tokens WHERE id=?').get(job.token_id);
        if (!token) { await db.prepare("UPDATE mobility_push_outbox SET status='REVOKED' WHERE id=?").run(job.id); continue; }
        const current = await requestSnapshot(job.request_id);
        if (!current || job.scope === 'REQUEST' && (job.event_type === 'QUOTES' ? current.status !== 'REQUESTED' || current.quotes === 0 : job.event_type !== 'STATUS_' + current.status)
          || job.scope === 'DRIVER' && (!await db.prepare("SELECT id FROM users WHERE id=? AND role='DRIVER' AND status='ACTIVE'").get(token.owner_id) || !await driverIsEligible(token.owner_id))
          || job.event_type === 'SELECTED' && (current.driver !== token.owner_id || !['ASSIGNED','AT_PICKUP','LOADED','IN_TRANSIT','AT_DESTINATION'].includes(current.status))) {
          await db.prepare("UPDATE mobility_push_outbox SET status='EXPIRED' WHERE id=?").run(job.id); continue;
        }
        if (job.event_type === 'NEW_REQUEST') {
          if (current.status !== 'REQUESTED') { await db.prepare("UPDATE mobility_push_outbox SET status='EXPIRED' WHERE id=?").run(job.id); continue; }
          const postpone = () => db.prepare("UPDATE mobility_push_outbox SET next_attempt_at=? WHERE id=? AND status='PENDING'").run(new Date(now() + 60000).toISOString(), job.id);
          // Blocked request alerts must yield the bounded delivery batch to other customers/devices.
          if (!token.gps_at || now() - Date.parse(token.gps_at) > 86400000) { await postpone(); continue; }
          let feed; try { feed = await mobility().marketplace.opportunities(token.owner_id, { latitude: Number(token.latitude), longitude: Number(token.longitude) }); } catch (_) { await postpone(); continue; }
          if (feed.busy) { await postpone(); continue; }
          if (!(feed.requests || []).some(r => r.id === job.request_id && r.nearby && !r.myQuote)) { await db.prepare("UPDATE mobility_push_outbox SET status='EXPIRED' WHERE id=?").run(job.id); continue; }
        }
        const lease = crypto.randomUUID();
        const acquired = await db.prepare("UPDATE mobility_push_outbox SET lease_token=?,lease_until=? WHERE id=? AND status='PENDING' AND (lease_until IS NULL OR lease_until<?)").run(lease, new Date(now() + 30000).toISOString(), job.id, instant());
        if (!acquired.changes) continue;
        let result;
        try { result = await transport.send(decrypt(token.token_encrypted), notification(job.event_type), { requestId: job.request_id, eventType: job.event_type, scope: job.scope, kind: job.kind }); }
        catch (_) { result = { sent: false }; }
        if (result.sent) { await db.prepare("UPDATE mobility_push_outbox SET status='SENT',attempts=attempts+1,lease_token=NULL,lease_until=NULL WHERE id=? AND lease_token=?").run(job.id, lease); sent++; }
        else {
          if (result.invalidToken) await db.prepare('DELETE FROM mobility_push_tokens WHERE token_hash=?').run(token.token_hash);
          const attempts = Number(job.attempts) + 1, terminal = result.invalidToken ? 'REVOKED' : result.permanent || attempts >= 5 ? 'FAILED' : 'PENDING';
          await db.prepare('UPDATE mobility_push_outbox SET status=?,attempts=?,next_attempt_at=?,lease_token=NULL,lease_until=NULL WHERE id=? AND lease_token=?').run(terminal, attempts, new Date(now() + Math.min(900000, 60000 * 2 ** (attempts - 1))).toISOString(), job.id, lease);
        }
      }
      return { sent, ...health() };
    } finally { working = false; }
  }
  return { init, health, registerDriver, registerRequest, unregisterDriver, unregisterRequest, flush };
}
let singleton;
function getMobilityPushService() { return singleton ||= createMobilityPushService(); }
function startMobilityPushWorker() {
  const push = getMobilityPushService();
  if (process.env.NODE_ENV !== 'production' || !push.health().enabled) return () => {};
  const run = () => { void push.flush().catch(() => { console.error('[Mobility push] delivery cycle unavailable.'); }); };
  run(); const interval = setInterval(run, 5000); interval.unref?.();
  return () => clearInterval(interval);
}
module.exports = { MobilityPushError, initPushSchema, createFcmTransport, createMobilityPushService, getMobilityPushService, startMobilityPushWorker };
