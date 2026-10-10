'use strict';
const express = require('express');
const crypto = require('node:crypto');
const { DB } = require('../db');
const { authenticate, requireRole } = require('../middleware/auth');
const telebirr = require('../services/telebirr');

const router = express.Router();
const PAYMENT_MODE = process.env.PAYMENT_MODE || 'TEST';
const QR_KEY = String(process.env.QR_SIGNING_KEY || process.env.JWT_SECRET || 'routepass_production_qr_hmac_secret_2026').trim();

function signedQr(subId, passengerId, routeId, expiry) {
  const payload = subId + ':' + passengerId + ':' + routeId + ':' + expiry;
  const mac = crypto.createHmac('sha256', QR_KEY).update(payload).digest('hex').slice(0, 16);
  return 'RP1:' + payload + ':' + mac;
}

async function settleOrder(order, status) {
  if (!status.paid) {
    if (status.failed) {
      await DB.prepare("UPDATE telebirr_payment_orders SET status='FAILED', updated_at=CURRENT_TIMESTAMP WHERE merchant_order_id=? AND status='PENDING'").run(order.merchant_order_id);
      return { status: 'FAILED', subscriptionId: order.subscription_id };
    }
    return { status: 'PENDING', subscriptionId: order.subscription_id };
  }

  const due = Number(order.amount_etb), received = Number(status.amount);
  if (!Number.isFinite(received) || Math.abs(received - due) > 0.01 ||
      String(status.currency || 'ETB').toUpperCase() !== 'ETB' ||
      String(status.merchantOrderId) !== String(order.merchant_order_id)) {
    throw new Error('Provider order reference, amount, or currency did not match');
  }

  return DB.transaction(async tx => {
    const current = await tx.prepare('SELECT * FROM telebirr_payment_orders WHERE merchant_order_id=?').get(order.merchant_order_id);
    if (!current) throw new Error('Payment order not found');
    if (current.status === 'PAID') return { status: 'PAID', subscriptionId: current.subscription_id };
    if (current.status !== 'PENDING') return { status: current.status, subscriptionId: current.subscription_id };
    const claimed = await tx.prepare("UPDATE telebirr_payment_orders SET status='PAID' WHERE merchant_order_id=? AND status='PENDING'").run(current.merchant_order_id);
    if (!claimed.changes) return { status: 'PAID', subscriptionId: current.subscription_id };

    const sub = await tx.prepare('SELECT * FROM subscriptions WHERE id=?').get(current.subscription_id);
    if (!sub) throw new Error('Subscription for payment order not found');
    const now = new Date();
    const oldEnd = sub.endDate ? new Date(sub.endDate) : null;
    const extend = String(sub.routeId) === String(current.route_id) && sub.subscriptionStatus === 'ACTIVE' &&
      sub.paymentStatus === 'PAID' && oldEnd && Number.isFinite(oldEnd.getTime()) && oldEnd.getTime() > now.getTime();
    const expires = new Date((extend ? oldEnd.getTime() : now.getTime()) + 30 * 86400000);
    const startDate = now.toISOString().slice(0, 10);
    const endDate = expires.toISOString().slice(0, 10);
    const days = Math.max(1, Math.ceil((expires.getTime() - now.getTime()) / 86400000));
    const qr = signedQr(sub.id, sub.passengerId, current.route_id, expires.getTime());
    const txn = 'pay_' + crypto.randomUUID().replace(/-/g, '').slice(0, 20);
    const payer = await tx.prepare('SELECT phone FROM users WHERE id=?').get(current.passenger_id);

    await tx.prepare("UPDATE subscriptions SET routeId=?, priceEtb=?, paymentStatus='PAID', subscriptionStatus='ACTIVE', startDate=?, endDate=?, daysRemaining=?, qrToken=?, updatedAt=CURRENT_TIMESTAMP WHERE id=?")
      .run(current.route_id, due, startDate, endDate, days, qr, current.subscription_id);
    await tx.prepare("INSERT INTO payment_transactions (id, passengerId, referenceNumber, idempotencyKey, amountEtb, provider, phoneNumber, status, notes) VALUES (?, ?, ?, ?, ?, 'Telebirr', ?, 'VERIFIED', ?)")
      .run(txn, current.passenger_id, current.merchant_order_id, current.idempotency_key, due,
        payer ? payer.phone : null, 'Confirmed server-to-server through Telebirr queryOrder');
    await tx.prepare('UPDATE telebirr_payment_orders SET payment_order_id=?, transaction_id=?, updated_at=CURRENT_TIMESTAMP WHERE merchant_order_id=?')
      .run(status.paymentOrderId || null, status.transactionId || null, current.merchant_order_id);
    await tx.prepare('INSERT INTO audit_logs (action, userId, role, details) VALUES (?, ?, ?, ?)')
      .run('TELEBIRR_PAYMENT_VERIFIED', current.passenger_id, 'PASSENGER', 'Telebirr payment verified for ETB ' + due);
    return { status: 'PAID', subscriptionId: current.subscription_id };
  });
}

async function refreshOrder(orderId, force = false) {
  const order = await DB.prepare('SELECT * FROM telebirr_payment_orders WHERE merchant_order_id=?').get(orderId);
  if (!order) return { status: 'NOT_FOUND' };
  if (order.status !== 'PENDING') return { status: order.status, subscriptionId: order.subscription_id };
  const queriedAt = order.last_query_at ? new Date(order.last_query_at).getTime() : 0;
  if (!force && Number.isFinite(queriedAt) && Date.now() - queriedAt < 12000) return { status: 'PENDING', subscriptionId: order.subscription_id };
  await DB.prepare('UPDATE telebirr_payment_orders SET last_query_at=CURRENT_TIMESTAMP WHERE merchant_order_id=? AND status=?').run(orderId, 'PENDING');
  const provider = await telebirr.queryOrderStatus(order.merchant_order_id, order.prepay_id);
  return settleOrder(order, provider);
}

router.post('/pay', authenticate, requireRole('PASSENGER'), async (req, res, next) => {
  // Leave the existing deterministic simulator in place for automated TEST-mode development.
  if (PAYMENT_MODE !== 'PRODUCTION') return next();
  const missing = telebirr.missingConfiguration();
  if (!telebirr.config().publicKey) missing.push('TELEBIRR_PUBLIC_KEY');
  if (missing.length) return res.status(503).json({
    success: false, code: 'LIVE_TELEBIRR_NOT_CONFIGURED',
    error: 'H5 checkout needs merchant credentials configured on the VPS.',
    missingSettings: missing
  });
  if (req.body && (req.body.pin || req.body.telebirrPin)) return res.status(400).json({
    success: false, error: 'RoutePass never collects a Telebirr PIN. Complete payment on the official Telebirr checkout page.'
  });

  try {
    const passengerId = req.user.id;
    const request = req.body || {};
    const key = String(request.idempotencyKey || req.headers['x-idempotency-key'] || '').trim();
    if (!/^[A-Za-z0-9._-]{8,100}$/.test(key)) return res.status(400).json({ success: false, error: 'A valid idempotency key is required.' });
    const passenger = await DB.prepare("SELECT * FROM users WHERE id=? AND role='PASSENGER'").get(passengerId);
    if (!passenger) return res.status(404).json({ success: false, error: 'Passenger account not found.' });
    const routeId = String(request.routeId || passenger.appliedRouteId || '').trim();
    if (!routeId) return res.status(400).json({ success: false, error: 'Select a route before checkout.' });
    const route = await DB.prepare('SELECT * FROM routes WHERE id=? AND active=TRUE').get(routeId);
    if (!route) return res.status(404).json({ success: false, error: 'Active route not found.' });
    const amount = Number(route.basePriceEtb);
    if (!Number.isFinite(amount) || amount <= 0) return res.status(409).json({ success: false, error: 'Invalid route fare.' });

    const prior = await DB.prepare('SELECT * FROM telebirr_payment_orders WHERE idempotency_key=?').get(key);
    if (prior) {
      if (String(prior.passenger_id) !== String(passengerId) || String(prior.route_id) !== String(route.id) || Math.abs(Number(prior.amount_etb) - amount) > 0.01) {
        return res.status(409).json({ success: false, error: 'Idempotency key already belongs to a different order.' });
      }
      if (prior.status === 'PAID') return res.json({ success: true, status: 'PAID', paymentMode: 'TELEBIRR_H5', merchantOrderId: prior.merchant_order_id });
      if (prior.status === 'PENDING' && prior.checkout_url) return res.json({
        success: true, status: 'PENDING', paymentMode: 'TELEBIRR_H5', merchantOrderId: prior.merchant_order_id,
        checkoutUrl: prior.checkout_url, message: 'Continue the existing Telebirr checkout.'
      });
      return res.status(409).json({ success: false, error: 'This order cannot be restarted. Use a new idempotency key.' });
    }

    let sub = await DB.prepare('SELECT * FROM subscriptions WHERE passengerId=? ORDER BY updatedAt DESC LIMIT 1').get(passengerId);
    if (!sub) {
      const id = 'sub_' + passengerId + '_' + Date.now().toString(36);
      await DB.prepare("INSERT INTO subscriptions (id, passengerId, routeId, pickupStopId, destinationStopId, morningSchedule, eveningSchedule, priceEtb, paymentStatus, subscriptionStatus, daysRemaining, qrToken) VALUES (?, ?, ?, '', '', ?, ?, ?, 'UNPAID', 'PENDING', 0, NULL)")
        .run(id, passengerId, route.id, route.morningDeparture, route.eveningDeparture, amount);
      sub = await DB.prepare('SELECT * FROM subscriptions WHERE id=?').get(id);
    } else if (!(sub.subscriptionStatus === 'ACTIVE' && sub.paymentStatus === 'PAID')) {
      await DB.prepare("UPDATE subscriptions SET routeId=?, priceEtb=?, morningSchedule=?, eveningSchedule=?, paymentStatus='UNPAID', subscriptionStatus='PENDING', daysRemaining=0, qrToken=NULL, updatedAt=CURRENT_TIMESTAMP WHERE id=?")
        .run(route.id, amount, route.morningDeparture, route.eveningDeparture, sub.id);
      sub = await DB.prepare('SELECT * FROM subscriptions WHERE id=?').get(sub.id);
    }

    const orderId = telebirr.merchantOrderId();
    await DB.prepare("INSERT INTO telebirr_payment_orders (merchant_order_id, idempotency_key, passenger_id, subscription_id, route_id, amount_etb, status) VALUES (?, ?, ?, ?, ?, ?, 'PENDING')")
      .run(orderId, key, passengerId, sub.id, route.id, amount);
    try {
      const checkout = await telebirr.createCheckout(orderId, amount, 'RoutePass monthly pass - ' + route.name);
      await DB.prepare('UPDATE telebirr_payment_orders SET prepay_id=?, checkout_url=?, updated_at=CURRENT_TIMESTAMP WHERE merchant_order_id=?')
        .run(checkout.prepayId, checkout.checkoutUrl, orderId);
      return res.status(201).json({
        success: true, status: 'PENDING', paymentMode: 'TELEBIRR_H5',
        merchantOrderId: orderId, checkoutUrl: checkout.checkoutUrl, amountEtb: amount, currency: 'ETB',
        message: 'Complete payment on Telebirr checkout. The pass activates only after server verification.'
      });
    } catch (e) {
      await DB.prepare("UPDATE telebirr_payment_orders SET status='FAILED', updated_at=CURRENT_TIMESTAMP WHERE merchant_order_id=?").run(orderId);
      console.error('[Telebirr] Checkout initialization failed:', e.message);
      return res.status(502).json({ success: false, code: 'TELEBIRR_CHECKOUT_FAILED', error: 'Could not start Telebirr checkout. No pass was activated.' });
    }
  } catch (e) {
    console.error('[Telebirr] Payment request failed:', e.message);
    return res.status(500).json({ success: false, error: 'Could not start Telebirr checkout.' });
  }
});

router.get('/status/:merchantOrderId', authenticate, requireRole('PASSENGER'), async (req, res) => {
  if (PAYMENT_MODE !== 'PRODUCTION') return res.status(503).json({ success: false, error: 'Live payment status is unavailable in test simulation mode.' });
  try {
    const orderId = String(req.params.merchantOrderId || '');
    if (!/^[A-Za-z0-9]{8,100}$/.test(orderId)) return res.status(400).json({ success: false, error: 'Invalid order reference.' });
    const order = await DB.prepare('SELECT * FROM telebirr_payment_orders WHERE merchant_order_id=? AND passenger_id=?').get(orderId, req.user.id);
    if (!order) return res.status(404).json({ success: false, error: 'Payment order not found.' });
    let status = order.status;
    if (status === 'PENDING') {
      try { status = (await refreshOrder(orderId, false)).status; }
      catch (e) { console.error('[Telebirr] Status check pending:', e.message); }
    }
    return res.json({ success: true, status, merchantOrderId: orderId,
      message: status === 'PAID' ? 'Telebirr payment verified; subscription active.' : status === 'FAILED' ? 'Payment did not complete.' : 'Waiting for Telebirr confirmation.' });
  } catch (e) {
    console.error('[Telebirr] Status check failed:', e.message);
    return res.status(500).json({ success: false, error: 'Could not refresh payment status.' });
  }
});

router.post('/webhook', async (req, res) => {
  if (PAYMENT_MODE !== 'PRODUCTION') return res.status(503).json({ success: false, error: 'Provider notifications are disabled in test mode.' });
  if (!telebirr.config().publicKey) return res.status(503).json({ success: false, error: 'Telebirr notification verification key is not configured.' });
  try {
    const n = telebirr.parseNotification(req.body);
    if (!telebirr.verifyNotification(n)) return res.status(401).json({ success: false, error: 'Invalid Telebirr notification signature.' });
    const id = String(n.merch_order_id || n.merchOrderId || '');
    if (!/^[A-Za-z0-9]{8,100}$/.test(id)) return res.status(400).json({ success: false, error: 'Invalid merchant order reference.' });
    const order = await DB.prepare('SELECT * FROM telebirr_payment_orders WHERE merchant_order_id=?').get(id);
    if (!order) return res.status(404).json({ success: false, error: 'Unknown payment order.' });
    const result = await refreshOrder(id, true);
    return res.status(200).json({ success: true, code: 0, message: 'SUCCESS', paymentStatus: result.status });
  } catch (e) {
    console.error('[Telebirr] Notification verification failed:', e.message);
    return res.status(503).json({ success: false, error: 'Notification could not be confirmed; provider may retry.' });
  }
});

router.get('/return', async (req, res) => {
  if (PAYMENT_MODE !== 'PRODUCTION') return res.status(503).type('html').send('<!doctype html><meta charset="utf-8"><p>RoutePass test payment.</p>');
  try {
    const id = String(req.query.merch_order_id || req.query.merchOrderId || req.query.merchantOrderId || req.query.out_trade_no || '');
    if (!/^[A-Za-z0-9]{8,100}$/.test(id)) return res.status(400).type('html').send('<!doctype html><meta charset="utf-8"><p>Payment reference not found. Return to RoutePass to check your pass.</p>');
    const result = await refreshOrder(id, true);
    const message = result.status === 'PAID' ? 'Telebirr confirmed your payment. Your RoutePass pass is active.' :
      result.status === 'FAILED' ? 'Payment did not complete. Return to RoutePass to try again.' :
      'Payment is still being checked. Return to RoutePass; the app will update when confirmation arrives.';
    return res.status(200).set('Cache-Control', 'no-store').type('html').send('<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>RoutePass payment</title><main style="font-family:system-ui;margin:3rem auto;max-width:40rem;padding:1rem"><h2>RoutePass payment</h2><p>' + message + '</p></main>');
  } catch (e) {
    console.error('[Telebirr] Return verification failed:', e.message);
    return res.status(200).type('html').send('<!doctype html><meta charset="utf-8"><p>We could not confirm payment yet. Return to RoutePass to check status.</p>');
  }
});

module.exports = router;
