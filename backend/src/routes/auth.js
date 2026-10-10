const express = require('express');
const router = express.Router();
const rateLimit = require('express-rate-limit');
const authLimiter = rateLimit({ windowMs: 15 * 60 * 1000, limit: 10, standardHeaders: true, legacyHeaders: false });
const bcrypt = require('bcryptjs');
const crypto = require('crypto');
const { DB } = require('../db');
const { signToken, authenticate } = require('../middleware/auth');
const { sendEthioTelecomSms, otpDigest } = require('../services/ethiotelecom-sms');

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

function normalizeEthiopianPhone(value) {
  const digits = String(value || '').replace(/[\s()\-]/g, '');
  if (/^09[0-9]{8}$/.test(digits)) return '+251' + digits.slice(1);
  if (/^9[0-9]{8}$/.test(digits)) return '+251' + digits;
  if (/^\+251[79][0-9]{8}$/.test(digits)) return digits;
  if (/^251[79][0-9]{8}$/.test(digits)) return '+' + digits;
  return null;
}
const otpRequestLimiter = rateLimit({ windowMs: 60 * 60 * 1000, limit: 3, standardHeaders: true, legacyHeaders: false,
  message: { success: false, error: 'Too many OTP requests. Try again later.' } });
const otpVerifyLimiter = rateLimit({ windowMs: 15 * 60 * 1000, limit: 10, standardHeaders: true, legacyHeaders: false,
  message: { success: false, error: 'Too many verification attempts. Try again later.' } });

router.post('/otp/request', otpRequestLimiter, async (req, res) => {
  try {
    const phone = normalizeEthiopianPhone(req.body.phone);
    if (!phone) return res.status(400).json({ success: false, error: 'Enter a valid Ethiopian mobile number, for example 0912345678.' });
    const existing = await DB.prepare('SELECT id FROM users WHERE phone = ?').get(phone);
    if (existing) return res.status(409).json({ success: false, error: 'This phone number is already registered. Please sign in.' });
    const previous = await DB.prepare("SELECT last_sent_at FROM signup_otps WHERE phone = ? AND purpose = 'SIGNUP'").get(phone);
    if (previous?.last_sent_at) {
      const elapsed = Date.now() - new Date(previous.last_sent_at).getTime();
      if (Number.isFinite(elapsed) && elapsed < 60000) {
        return res.status(429).json({ success: false, error: 'Please wait before requesting another OTP.', retryAfterSeconds: Math.ceil((60000 - elapsed) / 1000) });
      }
    }
    const code = String(crypto.randomInt(0, 1000000)).padStart(6, '0');
    const nowIso = new Date().toISOString();
    const expiresAt = new Date(Date.now() + 5 * 60 * 1000).toISOString();
    const digest = otpDigest(phone, 'SIGNUP', code);
    await DB.transaction(async (tx) => {
      await tx.prepare("DELETE FROM signup_otps WHERE phone = ? AND purpose = 'SIGNUP'").run(phone);
      await tx.prepare("INSERT INTO signup_otps (phone, purpose, otp_hash, expires_at, attempts, verified_at, consumed_at, last_sent_at, created_at) VALUES (?, 'SIGNUP', ?, ?, 0, NULL, NULL, ?, ?)")
        .run(phone, digest, expiresAt, nowIso, nowIso);
    });
    if (process.env.NODE_ENV === 'test' && process.env.OTP_TEST_MODE === 'true') {
      return res.status(200).json({ success: true, expiresInSeconds: 300, message: 'Test OTP generated.', testCode: code });
    }
    await sendEthioTelecomSms({ phone, message: \`Your RoutePass sign-up verification code is \${code}. It expires in 5 minutes. Do not share this code.\` });
    return res.json({ success: true, expiresInSeconds: 300, message: 'If this number is eligible, a verification code has been sent by SMS.' });
  } catch (err) {
    if (['SMS_NOT_CONFIGURED','SMS_PROVIDER_UNAVAILABLE','SMS_PROVIDER_REJECTED','SMS_CONFIGURATION_INVALID','OTP_SECRET_NOT_CONFIGURED'].includes(err.code)) {
      return res.status(503).json({ success: false, code: err.code, error: 'Phone verification is temporarily unavailable. Please try again later.' });
    }
    console.error('[RoutePass] OTP request failed:', err.code || 'OTP_REQUEST_ERROR');
    return res.status(500).json({ success: false, error: 'Could not send the verification code.' });
  }
});

router.post('/otp/verify', otpVerifyLimiter, async (req, res) => {
  try {
    const phone = normalizeEthiopianPhone(req.body.phone);
    const code = String(req.body.code || '').trim();
    if (!phone || !/^[0-9]{6}$/.test(code)) {
      return res.status(400).json({ success: false, error: 'A valid Ethiopian phone number and six-digit OTP are required.' });
    }
    const record = await DB.prepare("SELECT * FROM signup_otps WHERE phone = ? AND purpose = 'SIGNUP'").get(phone);
    if (!record || record.consumed_at || !record.expires_at ||
        !Number.isFinite(new Date(record.expires_at).getTime()) ||
        new Date(record.expires_at).getTime() <= Date.now() || Number(record.attempts || 0) >= 5) {
      return res.status(400).json({ success: false, error: 'The OTP is invalid or expired. Request a new code.' });
    }
    const supplied = Buffer.from(otpDigest(phone, 'SIGNUP', code), 'hex');
    const expected = Buffer.from(String(record.otp_hash || ''), 'hex');
    const match = supplied.length === expected.length && crypto.timingSafeEqual(supplied, expected);
    if (!match) {
      const attempts = Number(record.attempts || 0) + 1;
      await DB.prepare('UPDATE signup_otps SET attempts = ?, consumed_at = ? WHERE phone = ? AND purpose = ?')
        .run(attempts, attempts >= 5 ? new Date().toISOString() : null, phone, 'SIGNUP');
      return res.status(400).json({ success: false, error: attempts >= 5 ? 'Too many incorrect OTP attempts. Request a new code.' : 'Incorrect OTP code.' });
    }
    await DB.prepare("UPDATE signup_otps SET verified_at = ? WHERE phone = ? AND purpose = 'SIGNUP' AND consumed_at IS NULL")
      .run(new Date().toISOString(), phone);
    return res.json({ success: true, verified: true, message: 'Phone number verified. You can complete sign-up now.' });
  } catch (err) {
    console.error('[RoutePass] OTP verification failed:', err.code || 'OTP_VERIFY_ERROR');
    return res.status(500).json({ success: false, error: 'Could not verify the code.' });
  }
});


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
      vehicleModel = '',
      vehicleType = 'MINIBUS_14',
      appliedRouteId = '',
      appliedRouteName = ''
    } = req.body;

    if (!fullName || !phone || !password || !role) {
      return res.status(400).json({
        success: false,
        error: 'Missing required fields: fullName, phone, password, role are mandatory.'
      });
    }

    const normalizedPhone = normalizeEthiopianPhone(phone);
    if (!normalizedPhone) return res.status(400).json({ success: false, error: 'Enter a valid Ethiopian mobile number.' });
    const otpTestBypass = process.env.NODE_ENV === 'test' && process.env.OTP_TEST_BYPASS === 'true';
    if (!otpTestBypass) {
      const verifiedOtp = await DB.prepare("SELECT * FROM signup_otps WHERE phone = ? AND purpose = 'SIGNUP'").get(normalizedPhone);
      if (!verifiedOtp || !verifiedOtp.verified_at || verifiedOtp.consumed_at ||
          !verifiedOtp.expires_at || new Date(verifiedOtp.expires_at).getTime() <= Date.now()) {
        return res.status(403).json({ success: false, code: 'OTP_VERIFICATION_REQUIRED', error: 'Verify your phone number by SMS OTP before completing sign-up.' });
      }
    }
    if (typeof password !== 'string' || password.length < 10) {
      return res.status(400).json({ success: false, error: 'Password must contain at least 10 characters.' });
    }

    const normalizedRole = role.toUpperCase();
    if (!['PASSENGER', 'DRIVER', 'ADMIN'].includes(normalizedRole)) {
      return res.status(400).json({
        success: false,
        error: "Invalid role. Must be 'PASSENGER', 'DRIVER', or 'ADMIN'."
      });
    }

    // Drivers register their own account and the vehicle they own. The server creates
    // the vehicle with no route; only an administrator may assign an approved route.
    const vehicleCapacities = {
      MINIVAN_8: 8,
      MINIBUS_14: 14,
      HIGER_24: 24,
      ANBESSA_BUS_30: 30
    };
    let driverVehiclePlate = '';
    let driverVehicleModel = '';
    let driverVehicleType = '';
    if (normalizedRole === 'DRIVER') {
      driverVehiclePlate = String(assignedVehiclePlate || '').trim().toUpperCase();
      driverVehicleModel = String(vehicleModel || '').trim();
      driverVehicleType = String(vehicleType || '').trim().toUpperCase();
      if (!licenseNumber || !String(licenseNumber).trim() || !driverVehiclePlate || !driverVehicleModel || !vehicleCapacities[driverVehicleType]) {
        return res.status(400).json({
          success: false,
          error: 'Driver registration requires a commercial licence number, vehicle plate, vehicle model and valid vehicle type.'
        });
      }
      const existingPlate = await DB.prepare('SELECT id FROM vehicles WHERE plateNumber = ?').get(driverVehiclePlate);
      if (existingPlate) {
        return res.status(409).json({ success: false, error: 'That vehicle plate is already registered. Contact support if you are the legal owner.' });
      }
    }

    // Allow secret-gated bootstrap only while no administrator exists.
    if (normalizedRole === 'ADMIN') {
      const existingAdmin = await DB.prepare("SELECT id FROM users WHERE role = 'ADMIN' LIMIT 1").get();
      if (existingAdmin) {
        return res.status(403).json({ success: false, error: 'An administrator already exists. Additional admin access must be provisioned offline.' });
      }
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
    const existing = await DB.prepare('SELECT id FROM users WHERE phone = ?').get(normalizedPhone);
    if (existing) {
      return res.status(409).json({
        success: false,
        error: `User with phone number ${phone} is already registered.`
      });
    }

    const passwordHash = await bcrypt.hash(password, 12);

    const userId = `usr_${normalizedRole.toLowerCase().slice(0, 3)}_${crypto.randomUUID().slice(0, 8)}`;
    const finalEmail = email?.trim() || `${normalizedPhone}@transport.et`;

    await DB.transaction(async (tx) => {
      if (!otpTestBypass) {
        const verifiedOtp = await tx.prepare("SELECT * FROM signup_otps WHERE phone = ? AND purpose = 'SIGNUP'").get(normalizedPhone);
        if (!verifiedOtp || !verifiedOtp.verified_at || verifiedOtp.consumed_at ||
            !verifiedOtp.expires_at || new Date(verifiedOtp.expires_at).getTime() <= Date.now()) {
          const otpError = new Error('OTP verification required');
          otpError.code = 'OTP_REQUIRED';
          throw otpError;
        }
        await tx.prepare("UPDATE signup_otps SET consumed_at = ? WHERE phone = ? AND purpose = 'SIGNUP' AND consumed_at IS NULL")
          .run(new Date().toISOString(), normalizedPhone);
      }
      await tx.prepare(`
        INSERT INTO users (id, role, fullName, phone, email, passwordHash, status, licenseNumber, companyName, assignedVehiclePlate, appliedRouteId, appliedRouteName)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      `).run(
        userId,
        normalizedRole,
        fullName.trim(),
        normalizedPhone,
        finalEmail,
        passwordHash,
        normalizedRole === 'DRIVER' ? 'PENDING' : 'ACTIVE',
        normalizedRole === 'DRIVER' ? String(licenseNumber).trim() : (normalizedRole === 'ADMIN' ? String(licenseNumber).trim() : ''),
        normalizedRole === 'DRIVER' ? String(companyName || '').trim() : (normalizedRole === 'ADMIN' ? String(companyName || '').trim() : ''),
        driverVehiclePlate,
        normalizedRole === 'PASSENGER' ? String(appliedRouteId || '').trim() : '',
        normalizedRole === 'PASSENGER' ? String(appliedRouteName || '').trim() : ''
      );

      if (normalizedRole === 'DRIVER') {
        const vehicleId = `veh_${crypto.randomUUID().replace(/-/g, '').slice(0, 12)}`;
        await tx.prepare(`
          INSERT INTO vehicles (id, plateNumber, model, vehicleType, capacityLimit, currentOccupancy, assignedRouteId, driverId, driverName, status)
          VALUES (?, ?, ?, ?, ?, 0, NULL, ?, ?, 'OFF_DUTY')
        `).run(
          vehicleId,
          driverVehiclePlate,
          driverVehicleModel,
          driverVehicleType,
          vehicleCapacities[driverVehicleType],
          userId,
          fullName.trim()
        );
      }
    });

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
      phone: normalizedPhone
    });

    res.status(201).json({
      success: true,
      token: normalizedRole === 'DRIVER' ? null : token,
      user: {
        id: userId,
        role: normalizedRole,
        fullName: fullName.trim(),
        phone: normalizedPhone,
        email: finalEmail,
        status: normalizedRole === 'DRIVER' ? 'PENDING' : 'ACTIVE',
        assignedVehiclePlate: driverVehiclePlate,
        appliedRouteId: normalizedRole === 'PASSENGER' ? String(appliedRouteId || '').trim() : '',
        appliedRouteName: normalizedRole === 'PASSENGER' ? String(appliedRouteName || '').trim() : ''
      },
      subscription: initialSub,
      message: normalizedRole === 'DRIVER'
        ? 'Thank you for registering. An administrator will review your licence and vehicle, approve your account, assign your route, and contact you when your account is ready.'
        : (normalizedRole === 'PASSENGER' && appliedRouteId
          ? 'Account created. Subscription is PENDING payment; ask an administrator for a manual test recharge during testing.'
          : 'Registration successful.')
    });
  } catch (err) {
    if (err.code === 'OTP_REQUIRED') return res.status(403).json({ success: false, code: 'OTP_VERIFICATION_REQUIRED', error: 'Verify your phone number by SMS OTP before completing sign-up.' });
    console.error('[RoutePass] registration failed:', err.code || 'REGISTRATION_ERROR');
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

    if (user.status && user.status !== 'ACTIVE') {
      const error = user.status === 'PENDING'
        ? 'Thank you for registering. Your account is awaiting administrator approval and route assignment. Please try signing in after the administrator contacts you.'
        : (user.status === 'REJECTED'
          ? 'Your driver registration was not approved. Please contact RoutePass administration.'
          : 'This account is inactive. Contact your transport administrator.');
      return res.status(403).json({ success: false, error, code: user.status });
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
