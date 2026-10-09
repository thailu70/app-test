const express = require('express');
const router = express.Router();
const rateLimit = require('express-rate-limit');
const authLimiter = rateLimit({ windowMs: 15 * 60 * 1000, limit: 10, standardHeaders: true, legacyHeaders: false });
const bcrypt = require('bcryptjs');
const crypto = require('crypto');
const { DB } = require('../db');
const { signToken, authenticate } = require('../middleware/auth');

function resolveAdminSecret() {
  if (process.env.ADMIN_REGISTRATION_SECRET && process.env.ADMIN_REGISTRATION_SECRET.trim().length > 0) {
    return process.env.ADMIN_REGISTRATION_SECRET.trim();
  }
  if (process.env.NODE_ENV === 'production') {
    throw new Error('[FATAL SECURITY ERROR] ADMIN_REGISTRATION_SECRET is required in production.');
  }
  return 'routepass_admin_invite_secret_2026';
}

const ADMIN_REGISTRATION_SECRET = resolveAdminSecret();

/**
 * POST /api/auth/register
 * Register a new user (PASSENGER, DRIVER, or ADMIN).
 * Note: Admin cannot freely register - requires adminSecret.
 * Registration does NOT activate a subscription. Subscriptions start as PENDING.
 */
router.post('/register', authLimiter, async (req, res) => {
  try {
    const {
      fullName,
      phone,
      email,
      password,
      role,
      adminSecret = '',
      licenseNumber = '',
      companyName = '',
      assignedVehiclePlate = '',
      appliedRouteId = '',
      appliedRouteName = ''
    } = req.body;

    if (!fullName || !phone || !password || !role) {
      return res.status(400).json({
        success: false,
        error: 'Missing required fields: fullName, phone, password, role are mandatory.'
      });
    }

    const normalizedRole = role.toUpperCase();
    if (!['PASSENGER', 'DRIVER', 'ADMIN'].includes(normalizedRole)) {
      return res.status(400).json({
        success: false,
        error: "Invalid role. Must be 'PASSENGER', 'DRIVER', or 'ADMIN'."
      });
    }

    // Drivers must be created or approved by an administrator; public self-registration
    // must never grant a transport-operating role or client-selected assignments.
    if (normalizedRole === 'DRIVER') {
      return res.status(403).json({
        success: false,
        error: 'Driver registration requires administrator approval.'
      });
    }

    // Security: Admin accounts cannot freely register!
    if (normalizedRole === 'ADMIN') {
      if (!adminSecret || adminSecret !== ADMIN_REGISTRATION_SECRET) {
        return res.status(403).json({
          success: false,
          error: 'Unauthorized: Admin registration is restricted. A valid Admin Authorization Secret is required.',
          errorAm: 'የአስተዳዳሪ ምዝገባ የተከለከለ ነው። ትክክለኛ የአስተዳዳሪ ሚስጥራዊ ቁልፍ ያስፈልጋል።'
        });
      }
    }

    // Check if phone is already registered
    const existing = await DB.prepare('SELECT id FROM users WHERE phone = ?').get(phone.trim());
    if (existing) {
      return res.status(409).json({
        success: false,
        error: `User with phone number ${phone} is already registered.`
      });
    }

    const salt = bcrypt.genSaltSync(10);
    const passwordHash = bcrypt.hashSync(password, salt);

    const userId = `usr_${normalizedRole.toLowerCase().slice(0, 3)}_${crypto.randomUUID().slice(0, 8)}`;
    const finalEmail = email?.trim() || `${phone.trim()}@transport.et`;

    await DB.prepare(`
      INSERT INTO users (id, role, fullName, phone, email, passwordHash, status, licenseNumber, companyName, assignedVehiclePlate, appliedRouteId, appliedRouteName)
      VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?)
    `).run(
      userId,
      normalizedRole,
      fullName.trim(),
      phone.trim(),
      finalEmail,
      passwordHash,
      normalizedRole === 'ADMIN' ? licenseNumber.trim() : '',
      normalizedRole === 'ADMIN' ? companyName.trim() : '',
      '',
      normalizedRole === 'PASSENGER' ? appliedRouteId.trim() : '',
      normalizedRole === 'PASSENGER' ? appliedRouteName.trim() : ''
    );

    // CRITICAL: Registration does NOT activate a subscription!
    // If passenger selected a route during signup, create a PENDING unpaid subscription.
    let initialSub = null;
    if (normalizedRole === 'PASSENGER' && appliedRouteId) {
      const route = await DB.prepare('SELECT * FROM routes WHERE id = ?').get(appliedRouteId);
      const subId = `sub_${userId}_${Date.now().toString(36)}`;

      await DB.prepare(`
        INSERT INTO subscriptions (id, passengerId, routeId, pickupStopId, destinationStopId, morningSchedule, eveningSchedule, startDate, endDate, priceEtb, paymentStatus, subscriptionStatus, vehicleId, qrToken, daysRemaining)
        VALUES (?, ?, ?, 'stop_atlas', 'stop_merkato', ?, ?, '', '', ?, 'UNPAID', 'PENDING', '', NULL, 0)
      `).run(
        subId,
        userId,
        appliedRouteId,
        route?.morningDeparture || '06:30',
        route?.eveningDeparture || '17:30',
        route?.basePriceEtb || 2500.0
      );

      initialSub = {
        id: subId,
        routeId: appliedRouteId,
        status: 'PENDING',
        paymentStatus: 'UNPAID',
        daysRemaining: 0,
        qrToken: null
      };
    }

    const token = signToken({
      id: userId,
      role: normalizedRole,
      fullName: fullName.trim(),
      phone: phone.trim()
    });

    res.status(201).json({
      success: true,
      token,
      user: {
        id: userId,
        role: normalizedRole,
        fullName: fullName.trim(),
        phone: phone.trim(),
        email: finalEmail,
        status: 'ACTIVE',
        assignedVehiclePlate: assignedVehiclePlate.trim(),
        appliedRouteId: appliedRouteId.trim(),
        appliedRouteName: appliedRouteName.trim()
      },
      subscription: initialSub,
      message: normalizedRole === 'PASSENGER' && appliedRouteId
        ? 'Account created. Subscription is PENDING payment via Telebirr.'
        : 'Registration successful.'
    });
  } catch (err) {
    console.error('[RoutePass] request failed:', err);
    res.status(500).json({ success: false, error: 'Internal server error.' });
  }
});

/**
 * POST /api/auth/login
 * Role-isolated login using phone and password
 */
router.post('/login', authLimiter, async (req, res) => {
  try {
    const { phone, password, role } = req.body;

    if (!phone || !password) {
      return res.status(400).json({
        success: false,
        error: 'Phone number and password are required.'
      });
    }

    const cleanPhone = phone.trim();
    const altPhone = cleanPhone.startsWith('+251')
      ? '0' + cleanPhone.slice(4)
      : (cleanPhone.startsWith('0') ? '+251' + cleanPhone.slice(1) : cleanPhone);

    const user = await DB.prepare('SELECT * FROM users WHERE phone = ? OR phone = ?').get(cleanPhone, altPhone);

    if (!user) {
      return res.status(401).json({
        success: false,
        error: 'Invalid mobile number or credentials.'
      });
    }

    if (role && user.role !== role.toUpperCase()) {
      return res.status(403).json({
        success: false,
        error: `Access Denied: This account is registered as ${user.role}, not ${role.toUpperCase()}. Please switch to the ${user.role.toLowerCase()} portal.`
      });
    }

    const isMatch = bcrypt.compareSync(password, user.passwordHash);
    if (!isMatch) {
      return res.status(401).json({
        success: false,
        error: 'Invalid password. Please check your credentials.'
      });
    }

    const token = signToken({
      id: user.id,
      role: user.role,
      fullName: user.fullName,
      phone: user.phone
    });

    res.json({
      success: true,
      token,
      user: {
        id: user.id,
        role: user.role,
        fullName: user.fullName,
        phone: user.phone,
        email: user.email,
        status: user.status,
        licenseNumber: user.licenseNumber,
        companyName: user.companyName,
        assignedVehiclePlate: user.assignedVehiclePlate,
        appliedRouteId: user.appliedRouteId,
        appliedRouteName: user.appliedRouteName
      }
    });
  } catch (err) {
    console.error('[RoutePass] request failed:', err);
    res.status(500).json({ success: false, error: 'Internal server error.' });
  }
});

/**
 * GET /api/auth/me
 * Retrieve authenticated user profile
 */
router.get('/me', authenticate, async (req, res) => {
  try {
    const user = await DB.prepare('SELECT * FROM users WHERE id = ?').get(req.user.id);
    if (!user) {
      return res.status(404).json({ success: false, error: 'User not found.' });
    }

    res.json({
      success: true,
      user: {
        id: user.id,
        role: user.role,
        fullName: user.fullName,
        phone: user.phone,
        email: user.email,
        status: user.status,
        licenseNumber: user.licenseNumber,
        companyName: user.companyName,
        assignedVehiclePlate: user.assignedVehiclePlate,
        appliedRouteId: user.appliedRouteId,
        appliedRouteName: user.appliedRouteName
      }
    });
  } catch (err) {
    console.error('[RoutePass] request failed:', err);
    res.status(500).json({ success: false, error: 'Internal server error.' });
  }
});

module.exports = router;
