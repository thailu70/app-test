const express = require('express');
const router = express.Router();
const rateLimit = require('express-rate-limit');
const authLimiter = rateLimit({ windowMs: 15 * 60 * 1000, limit: 10, standardHeaders: true, legacyHeaders: false });
const bcrypt = require('bcryptjs');
const crypto = require('crypto');
const { DB } = require('../db');
const { signToken, authenticate } = require('../middleware/auth');
const { sendSms } = require('../services/afro-sms');
const sha256 = value => crypto.createHash('sha256').update(String(value)).digest('hex');
const normalizePhone = value => String(value || '').trim();

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

router.post('/request-otp', authLimiter, async (req, res) => {
  const phone = normalizePhone(req.body.phone), purpose = String(req.body.purpose || 'REGISTRATION').toUpperCase();
  if (!phone || !['REGISTRATION', 'LOGIN'].includes(purpose)) return res.status(400).json({ success: false, error: 'Phone number and OTP purpose are required.' });
  const code = String(crypto.randomInt(100000, 1000000));
  const id = 'otp_' + crypto.randomUUID().replace(/-/g, '').slice(0, 20);
  const expiresAt = new Date(Date.now() + 10 * 60 * 1000).toISOString();
  await DB.prepare("UPDATE auth_otps SET expires_at=CURRENT_TIMESTAMP WHERE phone=? AND purpose=? AND verified_at IS NULL").run(phone, purpose);
  await DB.prepare('INSERT INTO auth_otps(id,phone,purpose,code_hash,expires_at) VALUES(?,?,?,?,?)').run(id, phone, purpose, sha256(code), expiresAt);
  const delivery = await sendSms(phone, `RoutePass verification code: ${code}. It expires in 10 minutes.`);
  res.json({ success: true, message: delivery.sent ? 'Verification code sent by SMS.' : 'Verification code created, but SMS delivery is not configured.', delivery: delivery.sent ? 'SENT' : 'NOT_CONFIGURED', testCode: process.env.NODE_ENV === 'test' ? code : undefined });
});

router.post('/verify-otp', authLimiter, async (req, res) => {
  const phone = normalizePhone(req.body.phone), purpose = String(req.body.purpose || 'REGISTRATION').toUpperCase(), code = String(req.body.code || '').trim();
  const otp = await DB.prepare('SELECT * FROM auth_otps WHERE phone=? AND purpose=? AND verified_at IS NULL ORDER BY created_at DESC LIMIT 1').get(phone, purpose);
  if (!otp || Date.parse(otp.expires_at) < Date.now() || otp.attempts >= 5) return res.status(400).json({ success: false, error: 'This verification code is expired. Request a new code.' });
  await DB.prepare('UPDATE auth_otps SET attempts=attempts+1 WHERE id=?').run(otp.id);
  if (sha256(code) !== otp.code_hash) return res.status(400).json({ success: false, error: 'Incorrect verification code.' });
  const token = crypto.randomBytes(32).toString('hex');
  await DB.prepare('UPDATE auth_otps SET verified_at=CURRENT_TIMESTAMP, verification_token_hash=? WHERE id=?').run(sha256(token), otp.id);
  res.json({ success: true, verificationToken: token });
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
      ,otpVerificationToken = ''
    } = req.body;

    if (!fullName || !phone || !password || !role) {
      return res.status(400).json({
        success: false,
        error: 'Missing required fields: fullName, phone, password, role are mandatory.'
      });
    }

    if (process.env.NODE_ENV !== 'test' && ['PASSENGER', 'DRIVER'].includes(String(role).toUpperCase())) {
      const otp = await DB.prepare('SELECT id FROM auth_otps WHERE phone=? AND purpose=? AND verification_token_hash=? AND verified_at IS NOT NULL AND expires_at>CURRENT_TIMESTAMP ORDER BY created_at DESC LIMIT 1').get(String(phone).trim(), 'REGISTRATION', sha256(otpVerificationToken));
      if (!otp) return res.status(428).json({ success: false, error: 'Verify your phone number by SMS before completing registration.' });
      await DB.prepare('UPDATE auth_otps SET verification_token_hash=NULL WHERE id=?').run(otp.id);
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

    // Dual ownership model: no company selected means Daily Commute (driver-owned vehicle);
    // a selected company means Intercity (company-owned vehicle, assigned later by the company).
    const requestedCompanyName = String(companyName || '').trim();
    let registeredCompany = null;
    let driverVehiclePlate = '', driverVehicleModel = '', driverVehicleType = '', driverVehicleCapacity = 0;
    if (normalizedRole === 'DRIVER') {
      if (!licenseNumber || !String(licenseNumber).trim()) {
        return res.status(400).json({ success: false, error: 'Driver registration requires a valid driving licence number.' });
      }
      if (requestedCompanyName) {
        registeredCompany = await DB.prepare("SELECT id, name FROM transport_companies WHERE LOWER(name)=LOWER(?) AND status='ACTIVE'").get(requestedCompanyName);
        if (!registeredCompany) return res.status(400).json({ success: false, error: 'Select an active company from the City Travel company list.' });
        // Intercity company vehicles are added and assigned by the company, never by the driver.
      } else {
        driverVehiclePlate = String(assignedVehiclePlate || '').trim().toUpperCase();
        driverVehicleModel = String(vehicleModel || '').trim();
        driverVehicleType = String(vehicleType || '').trim().toUpperCase();
        const registeredType = await require('../services/master-data').getVehicleType(driverVehicleType);
        if (!driverVehiclePlate || !driverVehicleModel || !registeredType) {
          return res.status(400).json({ success: false, error: 'Daily Commute driver registration requires your own vehicle plate, model, and valid vehicle type.' });
        }
        driverVehicleCapacity = registeredType.capacity;
        const existingPlate = await DB.prepare('SELECT id FROM vehicles WHERE plateNumber = ?').get(driverVehiclePlate);
        if (existingPlate) return res.status(409).json({ success: false, error: 'That vehicle plate is already registered.' });
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
    if (normalizedRole === 'PASSENGER' && appliedRouteId) {
      const commuteRoute = await DB.prepare("SELECT id FROM routes WHERE id=? AND active=TRUE AND COALESCE(companyId,'')='' AND COALESCE(routeType,'DAILY_COMMUTE')='DAILY_COMMUTE'").get(appliedRouteId);
      if (!commuteRoute) return res.status(400).json({success:false,error:'Choose an active daily commute route. Intercity tickets do not require registration.'});
    }
    const normalizedPhone = require('../services/onboarding').normalizePhone(phone);
    const existing = await DB.prepare('SELECT id FROM users WHERE phone = ?').get(phone.trim());
    const phoneAvailable = !normalizedPhone || await require('../services/onboarding').phoneAvailable(normalizedPhone, null);
    if (existing || !phoneAvailable) {
      return res.status(409).json({
        success: false,
        error: `User with phone number ${phone} is already registered.`
      });
    }

    const passwordHash = await bcrypt.hash(password, 12);

    const userId = `usr_${normalizedRole.toLowerCase().slice(0, 3)}_${crypto.randomUUID().slice(0, 8)}`;
    const finalEmail = email?.trim() || `${phone.trim()}@transport.et`;

    const registration = await DB.transaction(async (tx) => {
      if (normalizedRole === 'ADMIN') {
        // A table has no row to lock while the first admin is absent. A transaction lock
        // serializes concurrent bootstrap attempts on PostgreSQL; SQLite's IMMEDIATE
        // transaction already serializes writers.
        if (tx.isPostgres) await tx.prepare("SELECT pg_advisory_xact_lock(hashtext('routepass-first-admin-bootstrap'))").get();
        const existingAdmin = await tx.prepare("SELECT id FROM users WHERE role = 'ADMIN' LIMIT 1").get();
        if (existingAdmin) return { status: 'ADMIN_EXISTS' };
      }

      await tx.prepare(`
        INSERT INTO users (id, role, fullName, phone, email, passwordHash, status, licenseNumber, companyName, assignedVehiclePlate, appliedRouteId, appliedRouteName, companyId)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      `).run(
        userId,
        normalizedRole,
        fullName.trim(),
        phone.trim(),
        finalEmail,
        passwordHash,
        normalizedRole === 'DRIVER' ? 'PENDING' : 'ACTIVE',
        normalizedRole === 'DRIVER' ? String(licenseNumber).trim() : (normalizedRole === 'ADMIN' ? String(licenseNumber).trim() : ''),
        normalizedRole === 'DRIVER' ? String(companyName || '').trim() : (normalizedRole === 'ADMIN' ? String(companyName || '').trim() : ''),
        driverVehiclePlate,
        normalizedRole === 'PASSENGER' ? String(appliedRouteId || '').trim() : '',
        normalizedRole === 'PASSENGER' ? String(appliedRouteName || '').trim() : '',
        normalizedRole === 'DRIVER' && registeredCompany ? registeredCompany.id : null
      );

      if (normalizedRole === 'ADMIN' && String(companyName || '').trim().length >= 2) {
        const companyId = 'company_' + crypto.randomUUID().replace(/-/g, '').slice(0, 16);
        await tx.prepare("INSERT INTO transport_companies (id, name, status) VALUES (?, ?, 'ACTIVE')").run(companyId, String(companyName).trim());
      }

      if (normalizedRole === 'DRIVER' && !registeredCompany) {
        const vehicleId = `veh_${crypto.randomUUID().replace(/-/g, '').slice(0, 12)}`;
        await tx.prepare(`
          INSERT INTO vehicles (id, plateNumber, model, vehicleType, capacityLimit, currentOccupancy, assignedRouteId, driverId, driverName, status)
          VALUES (?, ?, ?, ?, ?, 0, NULL, ?, ?, 'OFF_DUTY')
        `).run(vehicleId, driverVehiclePlate, driverVehicleModel, driverVehicleType, driverVehicleCapacity, userId, fullName.trim());
      }
      return { status: 'CREATED' };
    });
    if (registration.status === 'ADMIN_EXISTS') {
      return res.status(403).json({ success: false, error: 'An administrator already exists. Additional admin access must be provisioned offline.' });
    }

    // CRITICAL: Registration does NOT activate a subscription!
    // If passenger selected a route during signup, create a PENDING unpaid subscription.
    let initialSub = null;
    if (normalizedRole === 'PASSENGER' && appliedRouteId) {
      const route = await DB.prepare("SELECT * FROM routes WHERE id = ? AND COALESCE(companyId,'')='' AND COALESCE(routeType,'DAILY_COMMUTE')='DAILY_COMMUTE'").get(appliedRouteId);
      const subId = `sub_${userId}_${Date.now().toString(36)}`;

      await DB.prepare(`
        INSERT INTO subscriptions (id, passengerId, routeId, pickupStopId, destinationStopId, morningSchedule, eveningSchedule, startDate, endDate, priceEtb, paymentStatus, subscriptionStatus, vehicleId, qrToken, daysRemaining)
        VALUES (?, ?, ?, NULL, NULL, ?, ?, '', '', ?, 'UNPAID', 'PENDING', '', NULL, 0)
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
      token: normalizedRole === 'DRIVER' ? null : token,
      user: {
        id: userId,
        role: normalizedRole,
        fullName: fullName.trim(),
        phone: phone.trim(),
        email: finalEmail,
        status: normalizedRole === 'DRIVER' ? 'PENDING' : 'ACTIVE',
        assignedVehiclePlate: driverVehiclePlate,
        appliedRouteId: normalizedRole === 'PASSENGER' ? String(appliedRouteId || '').trim() : '',
        appliedRouteName: normalizedRole === 'PASSENGER' ? String(appliedRouteName || '').trim() : ''
      },
      subscription: initialSub,
      message: normalizedRole === 'DRIVER'
        ? (registeredCompany ? 'Thank you for registering for Intercity Travel. Your company will review your driver profile and assign a company-owned vehicle and route.' : 'Thank you for registering for Daily Commute. Your vehicle and driving licence will be reviewed before route assignment.')
        : (normalizedRole === 'PASSENGER' && appliedRouteId
          ? 'Account created. Subscription is PENDING payment; ask an administrator for a manual test recharge during testing.'
          : 'Registration successful.')
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

    if (typeof phone !== 'string' || typeof password !== 'string' || !phone.trim() || !password) {
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

    const isMatch = await bcrypt.compare(password, user.passwordHash);
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
