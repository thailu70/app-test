const express = require('express');
const router = express.Router();
const crypto = require('crypto');
const { DB } = require('../db');
const { authenticate, requireRole } = require('../middleware/auth');

/**
 * Helper to generate secure tamper-proof transit QR token
 */
function generateQrToken(subId, passengerId, fullName, routeName) {
  const nonce = crypto.randomBytes(4).toString('hex').toUpperCase();
  return `TN-${subId}-${passengerId}-${fullName}-${routeName}-ACTIVE-${nonce}`;
}

/**
 * GET /api/subscriptions/my-status
 * Check current passenger subscription.
 * Returns NOT_SUBSCRIBED message prompting to pay and subscribe if inactive!
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

    if (!sub || sub.subscriptionStatus !== 'ACTIVE' || sub.daysRemaining <= 0) {
      return res.json({
        success: true,
        isSubscribed: false,
        status: sub ? sub.subscriptionStatus : 'NOT_SUBSCRIBED',
        message: 'The passenger is not subscribed. Please pay and subscribe for the selected route to activate your pass.',
        messageAm: 'ተሳፋሪው አልተመዘገበም። እባክዎ ለተመረጠው መስመር በቴሌብር ከፍለው ይመዝገቡ።',
        subscription: sub || null
      });
    }

    res.json({
      success: true,
      isSubscribed: true,
      status: 'ACTIVE',
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
 * Register or update route subscription (initially pending/not subscribed until paid)
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
    const user = DB.prepare('SELECT fullName FROM users WHERE id = ?').get(passengerId);
    const fullName = user?.fullName || req.user.fullName;

    // Check if subscription exists
    const existing = DB.prepare('SELECT id FROM subscriptions WHERE passengerId = ?').get(passengerId);

    if (existing) {
      DB.prepare(`
        UPDATE subscriptions
        SET routeId = ?, pickupStopId = ?, destinationStopId = ?, priceEtb = ?,
            subscriptionStatus = 'NOT_SUBSCRIBED', paymentStatus = 'UNPAID', daysRemaining = 0,
            qrToken = NULL, updatedAt = CURRENT_TIMESTAMP
        WHERE id = ?
      `).run(routeId, pickupStopId, destinationStopId, route.basePriceEtb, existing.id);
    } else {
      DB.prepare(`
        INSERT INTO subscriptions (id, passengerId, routeId, pickupStopId, destinationStopId, morningSchedule, eveningSchedule, priceEtb, paymentStatus, subscriptionStatus, daysRemaining)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'UNPAID', 'NOT_SUBSCRIBED', 0)
      `).run(subId, passengerId, routeId, pickupStopId, destinationStopId, route.morningDeparture, route.eveningDeparture, route.basePriceEtb);
    }

    res.json({
      success: true,
      message: 'The passenger is not subscribed. Please pay and subscribe for the selected route to activate your pass.',
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
 * Simulates / processes Telebirr payment and activates the subscription
 */
router.post('/telebirr/pay', authenticate, requireRole('PASSENGER'), (req, res) => {
  try {
    const passengerId = req.user.id;
    const { routeId, phone = req.user.phone } = req.body;

    const user = DB.prepare('SELECT * FROM users WHERE id = ?').get(passengerId);
    const targetRouteId = routeId || user?.appliedRouteId || 'route_bole_merkato';
    const route = DB.prepare('SELECT * FROM routes WHERE id = ?').get(targetRouteId);

    if (!route) {
      return res.status(404).json({ success: false, error: 'Route not found.' });
    }

    const price = route.basePriceEtb || 2500.0;
    const subId = `sub_${passengerId}_${Date.now().toString(36)}`;
    const qrToken = generateQrToken(subId, passengerId, user?.fullName || 'Passenger', route.name);
    const txnRef = `TB-TRANS-${Date.now().toString(36).toUpperCase()}-${Math.floor(1000 + Math.random() * 9000)}`;

    const startDate = new Date().toLocaleDateString('en-GB', { day: '2-digit', month: 'short', year: 'numeric' });
    const endDate = new Date(Date.now() + 30 * 86400000).toLocaleDateString('en-GB', { day: '2-digit', month: 'short', year: 'numeric' });

    // Record Telebirr Transaction
    DB.prepare(`
      INSERT INTO payment_transactions (id, passengerId, referenceNumber, amountEtb, provider, status, notes)
      VALUES (?, ?, ?, ?, 'Telebirr', 'COMPLETED', ?)
    `).run(`pay_${txnRef}`, passengerId, txnRef, price, `Monthly pass for ${route.name}`);

    // Upsert subscription to ACTIVE
    const existing = DB.prepare('SELECT id FROM subscriptions WHERE passengerId = ?').get(passengerId);
    if (existing) {
      DB.prepare(`
        UPDATE subscriptions
        SET routeId = ?, morningSchedule = ?, eveningSchedule = ?, startDate = ?, endDate = ?,
            priceEtb = ?, paymentStatus = 'PAID', subscriptionStatus = 'ACTIVE', vehicleId = 'veh_higer_aa_34921',
            qrToken = ?, daysRemaining = 30, updatedAt = CURRENT_TIMESTAMP
        WHERE id = ?
      `).run(targetRouteId, route.morningDeparture, route.eveningDeparture, startDate, endDate, price, qrToken, existing.id);
    } else {
      DB.prepare(`
        INSERT INTO subscriptions (id, passengerId, routeId, pickupStopId, destinationStopId, morningSchedule, eveningSchedule, startDate, endDate, priceEtb, paymentStatus, subscriptionStatus, vehicleId, qrToken, daysRemaining)
        VALUES (?, ?, ?, 'stop_atlas', 'stop_merkato', ?, ?, ?, ?, ?, 'PAID', 'ACTIVE', 'veh_higer_aa_34921', ?, 30)
      `).run(subId, passengerId, targetRouteId, route.morningDeparture, route.eveningDeparture, startDate, endDate, price, qrToken);
    }

    // Update user appliedRouteId
    DB.prepare(`
      UPDATE users SET appliedRouteId = ?, appliedRouteName = ? WHERE id = ?
    `).run(targetRouteId, route.name, passengerId);

    // Audit log
    DB.prepare(`
      INSERT INTO audit_logs (action, userId, role, details)
      VALUES ('TELEBIRR_PAYMENT', ?, 'PASSENGER', ?)
    `).run(passengerId, `Paid ETB ${price} via Telebirr (${txnRef}) for ${route.name}`);

    res.json({
      success: true,
      message: 'Telebirr payment successful! Monthly subscription is now ACTIVE.',
      messageAm: 'የቴሌብር ክፍያ ተሳክቷል! ወርሃዊ የጉዞ ፈቃድዎ አሁን ነቅቷል።',
      transaction: {
        referenceNumber: txnRef,
        amountEtb: price,
        provider: 'Telebirr',
        date: new Date().toISOString()
      },
      subscription: {
        status: 'ACTIVE',
        routeId: route.id,
        routeName: route.name,
        daysRemaining: 30,
        startDate,
        endDate,
        qrToken
      }
    });
  } catch (err) {
    res.status(500).json({ success: false, error: err.message });
  }
});

/**
 * GET /api/subscriptions/verify-qr/:token
 * Verify transit QR token authenticity
 */
router.get('/verify-qr/:token', authenticate, (req, res) => {
  try {
    const rawToken = req.params.token;
    const sub = DB.prepare('SELECT * FROM subscriptions WHERE qrToken = ?').get(rawToken);

    if (!sub) {
      return res.status(404).json({
        success: false,
        valid: false,
        reason: 'QR code not recognized or invalid token.'
      });
    }

    if (sub.subscriptionStatus !== 'ACTIVE' || sub.daysRemaining <= 0) {
      return res.status(400).json({
        success: false,
        valid: false,
        reason: 'Subscription is expired or inactive. Renewal required.'
      });
    }

    const passenger = DB.prepare('SELECT fullName, phone FROM users WHERE id = ?').get(sub.passengerId);
    const route = DB.prepare('SELECT name, nameAm FROM routes WHERE id = ?').get(sub.routeId);

    res.json({
      success: true,
      valid: true,
      passenger: {
        id: sub.passengerId,
        fullName: passenger?.fullName || 'Passenger',
        phone: passenger?.phone || ''
      },
      route: {
        id: sub.routeId,
        name: route?.name || '',
        nameAm: route?.nameAm || ''
      },
      daysRemaining: sub.daysRemaining,
      status: sub.subscriptionStatus
    });
  } catch (err) {
    res.status(500).json({ success: false, error: err.message });
  }
});

module.exports = router;
