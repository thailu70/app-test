const express = require('express');
const router = express.Router();
const bcrypt = require('bcryptjs');
const crypto = require('crypto');
const { DB } = require('../db');
const { signToken, authenticate } = require('../middleware/auth');

/**
 * POST /api/auth/register
 * Independent registration for Passengers, Drivers, or Operators.
 */
router.post('/register', async (req, res) => {
  try {
    const {
      fullName,
      phone,
      email,
      password,
      role,
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

    // Check if phone already registered
    const existing = DB.prepare('SELECT id FROM users WHERE phone = ?').get(phone.trim());
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

    DB.prepare(`
      INSERT INTO users (id, role, fullName, phone, email, passwordHash, status, licenseNumber, companyName, assignedVehiclePlate, appliedRouteId, appliedRouteName)
      VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?)
    `).run(
      userId,
      normalizedRole,
      fullName.trim(),
      phone.trim(),
      finalEmail,
      passwordHash,
      licenseNumber.trim(),
      companyName.trim(),
      assignedVehiclePlate.trim(),
      appliedRouteId.trim(),
      appliedRouteName.trim()
    );

    // If Passenger registered for a route, initialize subscription
    let initialSub = null;
    if (normalizedRole === 'PASSENGER' && appliedRouteId) {
      const route = DB.prepare('SELECT * FROM routes WHERE id = ?').get(appliedRouteId);
      const subId = `sub_${userId}_${new Date().toISOString().slice(0, 7).replace('-', '')}`;
      const token = `TN-${subId}-${userId}-${fullName}-${appliedRouteName || 'Transit'}-ACTIVE-${Date.now().toString(36).toUpperCase()}`;

      DB.prepare(`
        INSERT INTO subscriptions (id, passengerId, routeId, pickupStopId, destinationStopId, morningSchedule, eveningSchedule, startDate, endDate, priceEtb, paymentStatus, subscriptionStatus, vehicleId, qrToken, daysRemaining)
        VALUES (?, ?, ?, 'stop_atlas', 'stop_merkato', ?, ?, ?, ?, ?, 'PAID', 'ACTIVE', 'veh_higer_aa_34921', ?, 30)
      `).run(
        subId,
        userId,
        appliedRouteId,
        route?.morningDeparture || '06:30',
        route?.eveningDeparture || '17:30',
        new Date().toLocaleDateString('en-GB', { day: '2-digit', month: 'short', year: 'numeric' }),
        new Date(Date.now() + 30 * 86400000).toLocaleDateString('en-GB', { day: '2-digit', month: 'short', year: 'numeric' }),
        route?.basePriceEtb || 2500.0,
        token
      );

      initialSub = {
        id: subId,
        routeId: appliedRouteId,
        status: 'ACTIVE',
        daysRemaining: 30,
        qrToken: token
      };
    }

    const token = signToken({
      id: userId,
      phone: phone.trim(),
      role: normalizedRole,
      fullName: fullName.trim()
    });

    res.status(201).json({
      success: true,
      message: `${normalizedRole} registered successfully`,
      token,
      user: {
        id: userId,
        role: normalizedRole,
        fullName: fullName.trim(),
        phone: phone.trim(),
        email: finalEmail,
        status: 'ACTIVE',
        assignedVehiclePlate,
        appliedRouteId,
        appliedRouteName
      },
      subscription: initialSub
    });
  } catch (err) {
    console.error('[Auth Register Error]:', err);
    res.status(500).json({ success: false, error: err.message });
  }
});

/**
 * POST /api/auth/login
 * Independent authentication with role isolation.
 */
router.post('/login', async (req, res) => {
  try {
    const { identifier, password, role } = req.body;

    if (!identifier || !password) {
      return res.status(400).json({
        success: false,
        error: 'Phone/email and password are required.'
      });
    }

    const cleanIdent = identifier.trim();
    let query = 'SELECT * FROM users WHERE (phone = ? OR email = ?)';
    const params = [cleanIdent, cleanIdent];

    if (role) {
      query += ' AND role = ?';
      params.push(role.toUpperCase());
    }

    const user = DB.prepare(query).get(...params);
    if (!user) {
      return res.status(401).json({
        success: false,
        error: 'Invalid credentials or user not found for this role.'
      });
    }

    const validPassword = bcrypt.compareSync(password, user.passwordHash);
    if (!validPassword) {
      return res.status(401).json({
        success: false,
        error: 'Invalid password or PIN.'
      });
    }

    const token = signToken({
      id: user.id,
      phone: user.phone,
      role: user.role,
      fullName: user.fullName
    });

    // Check user subscription if passenger
    let activeSub = null;
    if (user.role === 'PASSENGER') {
      activeSub = DB.prepare('SELECT * FROM subscriptions WHERE passengerId = ? ORDER BY updatedAt DESC LIMIT 1').get(user.id);
    }

    res.json({
      success: true,
      message: 'Logged in successfully',
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
      },
      subscription: activeSub
    });
  } catch (err) {
    console.error('[Auth Login Error]:', err);
    res.status(500).json({ success: false, error: err.message });
  }
});

/**
 * GET /api/auth/me
 * Returns profile of current authenticated user.
 */
router.get('/me', authenticate, (req, res) => {
  const user = DB.prepare('SELECT * FROM users WHERE id = ?').get(req.user.id);
  if (!user) {
    return res.status(404).json({ success: false, error: 'User not found' });
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
});

module.exports = router;
