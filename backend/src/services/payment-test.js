'use strict';
const crypto = require('node:crypto');
const { DB } = require('../db');
const { normalizePhone } = require('./onboarding');
const TEST_PHONE = '+251912469923';
const PURPOSE = 'TEST_PAYMENT';
const isTestPaymentPhone = value => normalizePhone(value) === TEST_PHONE;
const fail = (status, message) => { throw Object.assign(new Error(message), { status }); };
const sha = value => crypto.createHash('sha256').update(String(value)).digest('hex');
const schema = new WeakMap();
async function initPaymentTestSchema(db = DB) {
  if (!schema.has(db)) schema.set(db, Promise.resolve().then(() => db.exec(`CREATE TABLE IF NOT EXISTS payment_test_phone_limits (
    phone TEXT PRIMARY KEY,requested_at TEXT NOT NULL,window_start TEXT NOT NULL,request_count INTEGER NOT NULL
  ); CREATE TABLE IF NOT EXISTS payment_test_challenges (
    booking_id TEXT PRIMARY KEY REFERENCES intercity_bookings(id),purpose TEXT NOT NULL,phone TEXT NOT NULL,
    amount_minor INTEGER NOT NULL,challenge_id TEXT NOT NULL,code_hash TEXT NOT NULL,requested_at TEXT NOT NULL,
    expires_at TEXT NOT NULL,sent_at TEXT,attempts INTEGER NOT NULL DEFAULT 0,verified_at TEXT,
    proof_hash TEXT,proof_expires_at TEXT,consumed_at TEXT
  ); CREATE TABLE IF NOT EXISTS intercity_checkout_attempts (
    booking_id TEXT PRIMARY KEY REFERENCES intercity_bookings(id),order_id TEXT NOT NULL,
    phase TEXT NOT NULL,lease_until TEXT,updated_at TEXT NOT NULL
  );`)).catch(error => { schema.delete(db); throw error; }));
  return schema.get(db);
}
function amountMinor(booking) {
  const amount = Number(booking.amount_etb);
  if (!Number.isFinite(amount) || amount <= 0) fail(400, 'Invalid booking amount.');
  return Math.round(amount * 100);
}
function createPaymentTestService({ db = DB, sendSms = require('./afro-sms').sendSms, now = Date.now, transact = fn => db.transaction(fn) } = {}) {
  const time = () => new Date(now()).toISOString();
  const digest = (booking, challenge, code) => crypto.createHmac('sha256', require('../middleware/auth').JWT_SECRET)
    .update([PURPOSE, booking.id, TEST_PHONE, amountMinor(booking), 'ETB', challenge, code].join(':')).digest('hex');
  function eligible(booking, phone) {
    if (!isTestPaymentPhone(phone)) fail(400, 'This test payment is available only for the authorized test phone.');
    if (booking.payment_status === 'PAID') fail(409, 'This booking is already paid.');
    if (booking.status !== 'HOLD' || booking.order_id || Date.parse(booking.expires_at) <= now()) fail(409, 'Use an active unpaid seat reservation before requesting test verification.');
  }
  async function requestOtp(booking, phone) {
    eligible(booking, phone); await initPaymentTestSchema(db);
    const code = String(crypto.randomInt(100000, 1000000)), challenge = crypto.randomUUID();
    await transact(async tx => {
      // A constant advisory key serializes the one permitted phone, including
      // first insertion and requests for different guest reservations.
      if (tx.isPostgres) await tx.prepare('SELECT pg_advisory_xact_lock(hashtext(?))').get('routepass:payment-test:' + TEST_PHONE);
      const previous = await tx.prepare('SELECT * FROM payment_test_phone_limits WHERE phone=?').get(TEST_PHONE);
      if (previous && now() - Date.parse(previous.requested_at) < 60000) fail(429, 'Wait 60 seconds before requesting another code.');
      const sameWindow = previous && now() - Date.parse(previous.window_start) < 3600000;
      if (sameWindow && previous.request_count >= 5) fail(429, 'Too many test verification requests. Try again in an hour.');
      await tx.prepare('INSERT INTO payment_test_phone_limits(phone,requested_at,window_start,request_count) VALUES(?,?,?,?) ON CONFLICT(phone) DO UPDATE SET requested_at=excluded.requested_at,window_start=excluded.window_start,request_count=excluded.request_count')
        .run(TEST_PHONE, time(), sameWindow ? previous.window_start : time(), sameWindow ? previous.request_count + 1 : 1);
      await tx.prepare(`INSERT INTO payment_test_challenges(booking_id,purpose,phone,amount_minor,challenge_id,code_hash,requested_at,expires_at)
        VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(booking_id) DO UPDATE SET purpose=excluded.purpose,phone=excluded.phone,amount_minor=excluded.amount_minor,
        challenge_id=excluded.challenge_id,code_hash=excluded.code_hash,requested_at=excluded.requested_at,expires_at=excluded.expires_at,
        sent_at=NULL,attempts=0,verified_at=NULL,proof_hash=NULL,proof_expires_at=NULL,consumed_at=NULL`)
        .run(booking.id, PURPOSE, TEST_PHONE, amountMinor(booking), challenge, digest(booking, challenge, code), time(), new Date(now() + 10 * 60000).toISOString());
    });
    let delivery; try { delivery = await sendSms(TEST_PHONE, `PASSO test payment code: ${code}. It authorizes only your current intercity reservation and expires in 10 minutes.`); } catch (_) { delivery = null; }
    if (!delivery?.sent) { await db.prepare('UPDATE payment_test_challenges SET consumed_at=? WHERE booking_id=? AND challenge_id=?').run(time(), booking.id, challenge); fail(503, 'The verification SMS could not be sent. Try again after 60 seconds.'); }
    await db.prepare('UPDATE payment_test_challenges SET sent_at=? WHERE booking_id=? AND challenge_id=? AND consumed_at IS NULL').run(time(), booking.id, challenge);
    return { delivery: 'SENT', retryAfterSeconds: 60 };
  }
  async function verifyOtp(booking, phone, code) {
    eligible(booking, phone); await initPaymentTestSchema(db);
    if (typeof code !== 'string' || !/^\d{6}$/.test(code)) fail(400, 'Enter the six-digit SMS code.');
    const token = crypto.randomBytes(32).toString('hex');
    const verified = await transact(async tx => {
      const row = await tx.prepare('SELECT * FROM payment_test_challenges WHERE booking_id=?' + (tx.isPostgres ? ' FOR UPDATE' : '')).get(booking.id);
      if (!row || row.purpose !== PURPOSE || row.phone !== TEST_PHONE || row.amount_minor !== amountMinor(booking) || !row.sent_at || row.consumed_at || row.verified_at || row.attempts >= 5 || Date.parse(row.expires_at) <= now()) return false;
      await tx.prepare('UPDATE payment_test_challenges SET attempts=attempts+1 WHERE booking_id=?').run(booking.id);
      if (!crypto.timingSafeEqual(Buffer.from(row.code_hash, 'hex'), Buffer.from(digest(booking, row.challenge_id, code), 'hex'))) return false;
      await tx.prepare('UPDATE payment_test_challenges SET verified_at=?,proof_hash=?,proof_expires_at=? WHERE booking_id=?').run(time(), sha(token), new Date(now() + 5 * 60000).toISOString(), booking.id);
      return true;
    });
    if (!verified) fail(400, 'Invalid, expired or already-used verification code.');
    return { testPaymentVerificationToken: token, expiresInSeconds: 300 };
  }
  async function consume(tx, booking, phone, token) {
    eligible(booking, phone);
    if (typeof token !== 'string' || !/^[a-f0-9]{64}$/.test(token)) fail(428, 'Verify ownership of the authorized test phone by SMS before continuing.');
    const row = await tx.prepare('SELECT * FROM payment_test_challenges WHERE booking_id=?' + (tx.isPostgres ? ' FOR UPDATE' : '')).get(booking.id);
    if (!row || row.purpose !== PURPOSE || row.phone !== TEST_PHONE || Number(row.amount_minor) !== amountMinor(booking) || !row.sent_at || !row.verified_at || row.consumed_at || Date.parse(row.proof_expires_at) <= now() || row.proof_hash !== sha(token)) fail(428, 'The test payment verification expired or belongs to another reservation. Verify again.');
    const changed = await tx.prepare('UPDATE payment_test_challenges SET consumed_at=?,proof_hash=NULL WHERE booking_id=? AND consumed_at IS NULL AND proof_hash=?').run(time(), booking.id, sha(token));
    if (changed.changes !== 1) fail(428, 'This verification has already been used.');
  }
  return { requestOtp, verifyOtp, consume };
}
let commuterWrites = Promise.resolve();
async function grantCommuterTestPass({ passengerId, route, key, signedQr }, db = DB) {
  const operation = () => db.transaction(async tx => {
    const passenger = await tx.prepare("SELECT id,phone FROM users WHERE id=? AND role='PASSENGER' AND status='ACTIVE'" + (tx.isPostgres ? ' FOR UPDATE' : '')).get(passengerId);
    if (!passenger || !isTestPaymentPhone(passenger.phone)) fail(403, 'The current account is not authorized for test payment.');
    const amount = Number(route.basePriceEtb);
    const prior = await tx.prepare('SELECT * FROM telebirr_payment_orders WHERE idempotency_key=?').get(key);
    if (prior) {
      if (prior.passenger_id !== passengerId || prior.route_id !== route.id || Number(prior.amount_etb) !== amount || !String(prior.merchant_order_id).startsWith('RPT')) fail(409, 'This idempotency key belongs to a different payment.');
      return { success: true, status: 'PAID', paymentMode: 'TEST_AUTO', idempotentReplay: true, subscriptionId: prior.subscription_id };
    }
    if (await tx.prepare('SELECT id FROM payment_transactions WHERE idempotencyKey=?').get(key)) fail(409, 'This idempotency key is already in use.');
    const current = await tx.prepare('SELECT * FROM subscriptions WHERE passengerId=? ORDER BY updatedAt DESC,id DESC LIMIT 1').get(passengerId);
    const subId = current?.id || 'sub_' + crypto.randomUUID().replace(/-/g, '').slice(0, 24), instant = Date.now();
    const oldEnd = Date.parse(current?.endDate || '');
    const extend = current?.routeId === route.id && current.paymentStatus === 'PAID' && current.subscriptionStatus === 'ACTIVE' && Number.isFinite(oldEnd) && oldEnd > instant;
    const expiry = (extend ? oldEnd : instant) + 30 * 86400000, days = Math.ceil((expiry - instant) / 86400000);
    const start = new Date(instant).toISOString().slice(0, 10), end = new Date(expiry).toISOString().slice(0, 10), qr = signedQr(subId, passengerId, route.id, expiry);
    if (current) await tx.prepare("UPDATE subscriptions SET routeId=?,priceEtb=?,paymentStatus='PAID',subscriptionStatus='ACTIVE',startDate=?,endDate=?,daysRemaining=?,qrToken=?,vehicleId=?,pickupStopId=?,destinationStopId=?,morningSchedule=?,eveningSchedule=?,updatedAt=CURRENT_TIMESTAMP WHERE id=?")
      .run(route.id, amount, start, end, days, qr, current.routeId === route.id ? current.vehicleId || '' : '', current.routeId === route.id ? current.pickupStopId || '' : '', current.routeId === route.id ? current.destinationStopId || '' : '', route.morningDeparture, route.eveningDeparture, subId);
    else await tx.prepare("INSERT INTO subscriptions(id,passengerId,routeId,pickupStopId,destinationStopId,morningSchedule,eveningSchedule,startDate,endDate,priceEtb,paymentStatus,subscriptionStatus,daysRemaining,qrToken) VALUES(?,?,?,'','',?,?,?,?,?,'PAID','ACTIVE',?,?)")
      .run(subId, passengerId, route.id, route.morningDeparture, route.eveningDeparture, start, end, amount, days, qr);
    const orderId = 'RPT' + crypto.randomBytes(16).toString('hex');
    await tx.prepare("INSERT INTO telebirr_payment_orders(merchant_order_id,idempotency_key,passenger_id,subscription_id,route_id,amount_etb,status) VALUES(?,?,?,?,?,?,'PAID')").run(orderId, key, passengerId, subId, route.id, amount);
    await tx.prepare("INSERT INTO payment_transactions(id,passengerId,referenceNumber,idempotencyKey,amountEtb,provider,phoneNumber,status,notes) VALUES(?,?,?,?,?,'TEST_AUTO',?,'VERIFIED',?)")
      .run('tx_' + crypto.randomUUID().slice(0, 24), passengerId, orderId, key, amount, TEST_PHONE, 'Authorized test payment for 0912469923; no money collected');
    await tx.prepare('INSERT INTO audit_logs(action,userId,role,details) VALUES(?,?,?,?)').run('TEST_PAYMENT_GRANTED', passengerId, 'PASSENGER', 'Authorized test payment ' + orderId + '; no money collected');
    return { success: true, status: 'PAID', paymentMode: 'TEST_AUTO', idempotentReplay: false, subscriptionId: subId, message: 'Authorized test payment confirmed. No money was collected.' };
  });
  if (db.isPostgres) return operation();
  const result = commuterWrites.then(operation); commuterWrites = result.catch(() => {}); return result;
}
module.exports = { TEST_PHONE, PURPOSE, isTestPaymentPhone, initPaymentTestSchema, createPaymentTestService, grantCommuterTestPass };
