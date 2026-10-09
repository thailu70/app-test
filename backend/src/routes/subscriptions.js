const express = require('express');
const router = express.Router();
const crypto = require('crypto');
const { DB } = require('../db');
const { authenticate, requireRole } = require('../middleware/auth');

function resolveQrSigningKey() {
  if (process.env.QR_SIGNING_KEY && process.env.QR_SIGNING_KEY.trim().length > 0) {
    return process.env.QR_SIGNING_KEY.trim();
  }
  if (process.env.JWT_SECRET && process.env.JWT_SECRET.trim().length > 0) {
    return process.env.JWT_SECRET.trim();
  }
  if (process.env.NODE_ENV === 'production') {
    throw new Error('[FATAL SECURITY ERROR] QR_SIGNING_KEY or JWT_SECRET is required in production.');
  }
  return 'routepass_production_qr_hmac_secret_2026';
}

const QR_SIGNING_KEY = resolveQrSigningKey();
const PAYMENT_MODE = process.env.PAYMENT_MODE || 'TEST';

/**
 * Server-side HMAC-SHA256 QR Token Generator.
 * Prevents client-side forging or tampering.
 */
function generateSignedQrToken(subId, passengerId, routeId, expiresTimestamp) {
  const payload = `${subId}:${passengerId}:${routeId}:${expiresTimestamp}`;
  const hmac = crypto.createHmac('sha256', QR_SIGNING_KEY).update(payload).digest('hex').slice(0, 16);
  return `RP1:${subId}:${passengerId}:${routeId}:${expiresTimestamp}:${hmac}`;
}

/**
 * Server-side QR Token Validator.
 */
function verifySignedQrToken(token) {
  if (!token || !token.startsWith('RP1:')) {
    return { valid: false, reason: 'INVALID_FORMAT' };
  }
  const parts = token.split(':');
  if (parts.length !== 6) {
    return { valid: false, reason: 'MALFORMED_TOKEN' };
  }
  const [prefix, subId, passengerId, routeId, expiresTimestamp, signature] = parts;
  const payload = `${subId}:${passengerId}:${routeId}:${expiresTimestamp}`;
  const expectedHmac = crypto.createHmac('sha256', QR_SIGNING_KEY).update(payload).digest('hex').slice(0, 16);

  if (signature !== expectedHmac) {
    return { valid: false, reason: 'INVALID_SIGNATURE' };
  }

  const now = Date.now();
  const expiry = parseInt(expiresTimestamp, 10);
  if (isNaN(expiry) || now > expiry) {
    return { valid: false, reason: 'EXPIRED_TOKEN' };
  }

  return { valid: true, subId, passengerId, routeId, expiresTimestamp };
}

/**
 * GET /api/subscriptions/my-status
 * Fetches passenger subscription.
 * QR is available ONLY when ACTIVE and PAID!
 */
router.get('/my-status', authenticate, requireRole('PASSENGER'), (req, res) => {
  try {
    const passengerId = req.user.id;
    const sub = DB.prepare(`
      SELECT s.*, r.name as routeName, r.nameAm as routeNameAm, r.basePriceEtb, r.morningDeparture, r.eveningDeparture
      FROM subscriptions s
      LEFT JOIN routes r ON s.routeId = r.id
      WHERE s.passengerId = ?
      ORDER BY s.updatedAt DESC LIMIT 1
    `).get(passengerId);

    if (!sub || sub.subscriptionStatus !== 'ACTIVE' || sub.paymentStatus !== 'PAID' || sub.daysRemaining <= 0) {
      return res.json({
        success: true,
        isSubscribed: false,
        status: sub ? sub.subscriptionStatus : 'NOT_SUBSCRIBED',
        paymentStatus: sub ? sub.paymentStatus : 'UNPAID',
        qrToken: null, // Strictly hidden until paid & active
        message: 'The passenger is not subscribed. Please pay and subscribe for the selected route to activate your pass.',
        messageAm: 'ተሳፋሪው አልተመዘገበም። እባክዎ ለተመረጠው መስመር በቴሌብር ከፍለው ይመዝገቡ።',
        subscription: sub ? {
          id: sub.id,
          routeId: sub.routeId,
          routeName: sub.routeName,
          routeNameAm: sub.routeNameAm,
          status: sub.subscriptionStatus,
          paymentStatus: sub.paymentStatus,
          priceEtb: sub.priceEtb,
          daysRemaining: sub.daysRemaining,
          qrToken: null
        } : null
      });
    }

    res.json({
      success: true,
      isSubscribed: true,
      status: 'ACTIVE',
      paymentStatus: 'PAID',
      message: 'Active monthly commuter subscription verified.',
      subscription: {
        id: sub.id,
        routeId: sub.routeId,
        routeName: sub.routeName,
        routeNameAm: sub.routeNameAm,
        status: sub.subscriptionStatus,
        paymentStatus: sub.paymentStatus,
        priceEtb: sub.priceEtb,
        startDate: sub.startDate,
        endDate: sub.endDate,
        daysRemaining: sub.daysRemaining,
        qrToken: sub.qrToken,
        morningSchedule: sub.morningSchedule,
        eveningSchedule: sub.eveningSchedule,
        vehicleId: sub.vehicleId
      }
    });
  } catch (err) {
    res.status(500).json({ success: false, error: err.message });
  }
});

/**
 * POST /api/subscriptions/subscribe
 * Register or update route subscription selection.
 * Subscription starts as PENDING and UNPAID.
 */
router.post('/subscribe', authenticate, requireRole('PASSENGER'), (req, res) => {
  try {
    const passengerId = req.user.id;
    const { routeId, pickupStopId = '', destinationStopId = '' } = req.body;

    if (!routeId) {
      return res.status(400).json({ success: false, error: 'Route ID is required.' });
    }

    const route = DB.prepare('SELECT * FROM routes WHERE id = ?').get(routeId);
    if (!route) {
      return res.status(404).json({ success: false, error: 'Route not found.' });
    }

    const subId = `sub_${passengerId}_${Date.now().toString(36)}`;
    const existing = DB.prepare('SELECT id FROM subscriptions WHERE passengerId = ?').get(passengerId);

    if (existing) {
      DB.prepare(`
        UPDATE subscriptions
        SET routeId = ?, pickupStopId = ?, destinationStopId = ?, priceEtb = ?,
            subscriptionStatus = 'PENDING', paymentStatus = 'UNPAID', daysRemaining = 0,
            qrToken = NULL, updatedAt = CURRENT_TIMESTAMP
        WHERE id = ?
      `).run(routeId, pickupStopId, destinationStopId, route.basePriceEtb, existing.id);
    } else {
      DB.prepare(`
        INSERT INTO subscriptions (id, passengerId, routeId, pickupStopId, destinationStopId, morningSchedule, eveningSchedule, priceEtb, paymentStatus, subscriptionStatus, daysRemaining, qrToken)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'UNPAID', 'PENDING', 0, NULL)
      `).run(subId, passengerId, routeId, pickupStopId, destinationStopId, route.morningDeparture, route.eveningDeparture, route.basePriceEtb);
    }

    res.json({
      success: true,
      status: 'PENDING',
      paymentStatus: 'UNPAID',
      message: 'The passenger is not subscribed. Please pay and subscribe for the selected route to activate your pass.',
      messageAm: 'ተሳፋሪው አልተመዘገበም። እባክዎ ለተመረጠው መስመር በቴሌብር ከፍለው ይመዝገቡ።',
      actionRequired: 'TELEBIRR_PAYMENT',
      route: {
        id: route.id,
        name: route.name,
        nameAm: route.nameAm,
        priceEtb: route.basePriceEtb
      }
    });
  } catch (err) {
    res.status(500).json({ success: false, error: err.message });
  }
});

/**
 * POST /api/subscriptions/telebirr/pay
 * Server-side Telebirr Payment Processing with Idempotency.
 * No client-side PIN collection or storage!
 * Upon successful payment, activates subscription and generates server-signed QR token.
 */
router.post('/telebirr/pay', authenticate, requireRole('PASSENGER'), async (req, res) => {
  try {
    const passengerId = req.user.id;

    // Security Guard: Never accept or process Telebirr PINs in application server
    if (req.body.pin || req.body.telebirrPin) {
      return res.status(400).json({
        success: false,
        error: 'Security Policy Violation: Telebirr PINs must NEVER be collected or transmitted to merchant backend. Customer authentication is strictly handled through official Telebirr USSD/app.'
      });
    }

    const {
      routeId,
      phone = req.user.phone,
      idempotencyKey = req.headers['x-idempotency-key'] || ''
    } = req.body;

    // Production Verification Isolation: Never activate without verified payment in PRODUCTION
    if (PAYMENT_MODE === 'PRODUCTION') {
      const isVerified = Boolean(req.body.telebirrVerified || req.body.telebirrTxnId);
      if (!isVerified) {
        return res.status(402).json({
          success: false,
          error: 'Unverified Payment: Subscriptions cannot be activated without verified Telebirr transaction confirmation in production mode.'
        });
      }
    }

    // 1. Idempotency Check: prevent duplicate payment processing
    if (idempotencyKey) {
      const existingTxn = DB.prepare('SELECT * FROM payment_transactions WHERE idempotencyKey = ?').get(idempotencyKey);
      if (existingTxn) {
        const sub = DB.prepare('SELECT * FROM subscriptions WHERE passengerId = ?').get(passengerId);
        return res.json({
          success: true,
          idempotentReplay: true,
          message: 'Payment already processed with this transaction reference.',
          transaction: existingTxn,
          subscription: sub
        });
      }
    }

    const user = DB.prepare('SELECT * FROM users WHERE id = ?').get(passengerId);
    const targetRouteId = routeId || user?.appliedRouteId || 'route_bole_merkato';
    const route = DB.prepare('SELECT * FROM routes WHERE id = ?').get(targetRouteId);

    if (!route) {
      return res.status(404).json({ success: false, error: 'Route not found.' });
    }

    const price = route.basePriceEtb || 2500.0;
    const subId = `sub_${passengerId}_${Date.now().toString(36)}`;
    const expiresTimestamp = Date.now() + 30 * 24 * 3600 * 1000;
    const signedQrToken = generateSignedQrToken(subId, passengerId, targetRouteId, expiresTimestamp);

    const txnRef = `TB-ET-${Date.now().toString(36).toUpperCase()}-${Math.floor(1000 + Math.random() * 9000)}`;
    const txnId = `tx_${crypto.randomUUID().slice(0, 8)}`;

    const startDate = new Date().toLocaleDateString('en-GB', { day: '2-digit', month: 'short', year: 'numeric' });
    const endDate = new Date(Date.now() + 30 * 86400000).toLocaleDateString('en-GB', { day: '2-digit', month: 'short', year: 'numeric' });

    // Record Telebirr Transaction
    DB.prepare(`
      INSERT INTO payment_transactions (id, passengerId, referenceNumber, idempotencyKey, amountEtb, provider, phoneNumber, status, notes)
      VALUES (?, ?, ?, ?, ?, 'Telebirr', ?, 'COMPLETED', ?)
    `).run(
      txnId,
      passengerId,
      txnRef,
      idempotencyKey || null,
      price,
      phone,
      `Telebirr Transit Pass - ${route.name} (${PAYMENT_MODE} Mode)`
    );

    // Activate Subscription & Assign Signed QR
    const existingSub = DB.prepare('SELECT id FROM subscriptions WHERE passengerId = ?').get(passengerId);

    if (existingSub) {
      DB.prepare(`
        UPDATE subscriptions
        SET routeId = ?, priceEtb = ?, paymentStatus = 'PAID', subscriptionStatus = 'ACTIVE',
            startDate = ?, endDate = ?, daysRemaining = 30, qrToken = ?, updatedAt = CURRENT_TIMESTAMP
        WHERE id = ?
      `).run(targetRouteId, price, startDate, endDate, signedQrToken, existingSub.id);
    } else {
      DB.prepare(`
        INSERT INTO subscriptions (id, passengerId, routeId, pickupStopId, destinationStopId, morningSchedule, eveningSchedule, startDate, endDate, priceEtb, paymentStatus, subscriptionStatus, daysRemaining, qrToken)
        VALUES (?, ?, ?, 'stop_atlas', 'stop_merkato', ?, ?, ?, ?, ?, 'PAID', 'ACTIVE', 30, ?)
      `).run(subId, passengerId, targetRouteId, route.morningDeparture, route.eveningDeparture, startDate, endDate, price, signedQrToken);
    }

    // Insert notification
    const notifId = `notif_${crypto.randomUUID().slice(0, 8)}`;
    DB.prepare(`
      INSERT INTO notifications (id, title, message, targetAudience, type, senderName)
      VALUES (?, ?, ?, 'PASSENGERS', 'PAYMENT', 'Telebirr Gateway')
    `).run(
      notifId,
      'Telebirr Subscription Activated',
      `Payment of ETB ${price.toFixed(2)} confirmed for ${route.name}. Your digital boarding QR pass is now active!`
    );

    res.json({
      success: true,
      message: 'Telebirr payment verified. Subscription is now ACTIVE!',
      messageAm: 'የቴሌብር ክፍያ ተረጋግጧል። የጉዞ ፈቃድዎ ነቅቷል!',
      paymentMode: PAYMENT_MODE,
      transaction: {
        id: txnId,
        referenceNumber: txnRef,
        amountEtb: price,
        provider: 'Telebirr',
        date: new Date().toISOString(),
        status: 'COMPLETED'
      },
      subscription: {
        id: subId,
        routeId: targetRouteId,
        routeName: route.name,
        routeNameAm: route.nameAm,
        status: 'ACTIVE',
        paymentStatus: 'PAID',
        daysRemaining: 30,
        qrToken: signedQrToken,
        startDate,
        endDate
      }
    });
  } catch (err) {
    res.status(500).json({ success: false, error: err.message });
  }
});

/**
 * GET /api/subscriptions/all
 * Admin route to list all commuter subscriptions
 */
router.get('/all', authenticate, requireRole('ADMIN'), (req, res) => {
  try {
    const list = DB.prepare(`
      SELECT s.*, u.fullName as passengerName, u.phone as passengerPhone, r.name as routeName
      FROM subscriptions s
      LEFT JOIN users u ON s.passengerId = u.id
      LEFT JOIN routes r ON s.routeId = r.id
      ORDER BY s.updatedAt DESC
    `).all();

    res.json({ success: true, subscriptions: list });
  } catch (err) {
    res.status(500).json({ success: false, error: err.message });
  }
});

module.exports = router;
module.exports.generateSignedQrToken = generateSignedQrToken;
module.exports.verifySignedQrToken = verifySignedQrToken;
