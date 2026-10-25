const express = require('express');
const router = express.Router();
const rateLimit = require('express-rate-limit');
const authLimiter = rateLimit({ windowMs: 15 * 60 * 1000, limit: 10, standardHeaders: true, legacyHeaders: false });
const bcrypt = require('bcryptjs');
const crypto = require('crypto');
const { DB } = require('../db');
const { signToken, authenticate } = require('../middleware/auth');
const { sendSms } = require('../services/afro-sms');
const { accountPhonePolicy } = require('../services/account-phone-policy');
const { createOnboardingService, OnboardingError, isOperationallyEligible } = require('../services/onboarding');
const { loadCredentialSnapshot } = require('../services/account-sessions');
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
    } = req.body || {};

    if (typeof fullName !== 'string' || !fullName.trim() || typeof phone !== 'string' || !phone.trim() || !password || typeof role !== 'string' || !role.trim()) {
      return res.status(400).json({
        success: false,
        error: 'Missing required fields: fullName, phone, password, role are mandatory.'
      });
    }

    if (typeof password !== 'string' || password.length < 10) {
      return res.status(400).json({ success: false, error: 'Password must contain at least 10 characters.' });
    }

    if (Buffer.byteLength(password, 'utf8') > 72) return res.status(400).json({ success: false, error: 'Password must be at most 72 UTF-8 bytes.' });

    const normalizedRole = role.trim().toUpperCase();
    if (!['PASSENGER', 'DRIVER', 'ADMIN'].includes(normalizedRole)) {
      return res.status(400).json({
        success: false,
        error: "Invalid role. Must be 'PASSENGER', 'DRIVER', or 'ADMIN'."
      });
    }

    const normalizedPhone = require('../services/onboarding').normalizePhone(phone);
    if (!normalizedPhone) {
      return res.status(400).json({ success: false, error: 'Enter a valid Ethiopian mobile phone number.' });
    }

    // Account ownership verification is temporarily optional for passengers and
    // drivers. This policy never marks a number verified and does not apply to
    // password recovery, payment ownership proofs or provider confirmations.
    if (['PASSENGER', 'DRIVER'].includes(normalizedRole) && accountPhonePolicy({ role: normalizedRole }).phoneVerificationRequiredForRegistration) {
      const otp = await DB.prepare('SELECT id FROM auth_otps WHERE phone=? AND purpose=? AND verification_token_hash=? AND verified_at IS NOT NULL AND expires_at>CURRENT_TIMESTAMP ORDER BY created_at DESC LIMIT 1').get(phone.trim(), 'REGISTRATION', sha256(otpVerificationToken));
      if (!otp) return res.status(428).json({ success: false, error: 'Verify your phone number by SMS before completing registration.' });
      await DB.prepare('UPDATE auth_otps SET verification_token_hash=NULL WHERE id=?').run(otp.id);
    }

    // Older APKs also enter the shared application workflow. They never receive
    // an operational token or an untracked driver account from this endpoint.
    if (normalizedRole === 'DRIVER') {
      await require('../services/onboarding').initOnboardingSchema();
      let companyId = String(req.body.companyId || '').trim();
      if (!companyId && String(companyName || '').trim()) {
        const company = await DB.prepare("SELECT id FROM transport_companies WHERE LOWER(name)=LOWER(?) AND status='ACTIVE'").get(String(companyName).trim());
        if (!company) return res.status(400).json({ success: false, error: 'Choose an active transport company.' });
        companyId = company.id;
      }
      const result = await createOnboardingService().register({ ...req.body, role: 'DRIVER',
        serviceType: companyId ? 'INTERCITY' : 'DAILY_COMMUTE', companyId,
        routeChoice: req.body.routeChoice || (appliedRouteId ? 'EXISTING' : '') });
      return res.status(201).json({ success: true, token: null, user: result.account,
        onboarding: result.onboarding, subscription: null, applicationPath: '/join/?view=login',
        message: 'Driver application received. Sign in to your application to follow acceptance, documents and final approval. Phone verification is optional for now.' });
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
    const finalEmail = email?.trim() || `${normalizedPhone}@transport.et`;

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
        normalizedPhone,
        finalEmail,
        passwordHash,
        'ACTIVE',
        normalizedRole === 'ADMIN' ? String(licenseNumber).trim() : '',
        normalizedRole === 'ADMIN' ? String(companyName || '').trim() : '',
        '',
        normalizedRole === 'PASSENGER' ? String(appliedRouteId || '').trim() : '',
        normalizedRole === 'PASSENGER' ? String(appliedRouteName || '').trim() : '',
        null
      );

      if (normalizedRole === 'ADMIN' && String(companyName || '').trim().length >= 2) {
        const companyId = 'company_' + crypto.randomUUID().replace(/-/g, '').slice(0, 16);
        await tx.prepare("INSERT INTO transport_companies (id, name, status) VALUES (?, ?, 'ACTIVE')").run(companyId, String(companyName).trim());
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
      phone: normalizedPhone,
      sessionVersion: 0
    });

    res.status(201).json({
      success: true,
      token,
      user: {
        id: userId,
        role: normalizedRole,
        fullName: fullName.trim(),
        phone: normalizedPhone,
        email: finalEmail,
        status: normalizedRole === 'DRIVER' ? 'PENDING' : 'ACTIVE',
        assignedVehiclePlate: '',
        appliedRouteId: normalizedRole === 'PASSENGER' ? String(appliedRouteId || '').trim() : '',
        appliedRouteName: normalizedRole === 'PASSENGER' ? String(appliedRouteName || '').trim() : ''
      },
      subscription: initialSub,
      message: normalizedRole === 'PASSENGER' && appliedRouteId
        ? 'Account created. Your subscription remains pending until payment is confirmed.'
        : 'Registration successful.'
    });
  } catch (err) {
    if (err instanceof OnboardingError || (Number.isInteger(err.status) && err.status >= 400 && err.status < 500)) return res.status(err.status).json({ success: false, error: err.message });
    if (err.code === '23505' || err.code === 'SQLITE_CONSTRAINT_UNIQUE') return res.status(409).json({ success: false, error: 'That phone number or vehicle is already registered.' });
    console.error('[RoutePass] registration failed:', err.code || err.name || 'Error');
    res.status(500).json({ success: false, error: 'Registration is temporarily unavailable.' });
  }
});

/**
 * POST /api/auth/login
 * Role-isolated login using phone and password
 */
router.post('/login', authLimiter, async (req, res) => {
  try {
    const { phone, password, role } = req.body || {};

    if (typeof phone !== 'string' || typeof password !== 'string' || !phone.trim() || !password) {
      return res.status(400).json({
        success: false,
        error: 'Phone number and password are required.'
      });
    }

    if (role !== undefined && (typeof role !== 'string' || !['ADMIN', 'DRIVER', 'PASSENGER'].includes(role.trim().toUpperCase()))) return res.status(400).json({ success: false, error: 'Choose a valid account role.' });
    const cleanPhone = phone.trim();
    const canonicalPhone = require('../services/onboarding').normalizePhone(cleanPhone);
    let user;
    if (canonicalPhone) {
      const national = canonicalPhone.slice(4);
      const matches = await DB.prepare("SELECT * FROM users WHERE REPLACE(REPLACE(REPLACE(REPLACE(phone,' ',''),'-',''),'(',''),')','') IN (?,?,?,?)")
        .all(canonicalPhone, canonicalPhone.slice(1), '0' + national, national);
      // Ambiguous historical spellings require support; never select another account.
      user = matches.length === 1 ? matches[0] : null;
    } else user = await DB.prepare('SELECT * FROM users WHERE phone=?').get(cleanPhone);

    if (!user) {
      return res.status(401).json({
        success: false,
        error: 'Invalid mobile number or credentials.'
      });
    }

    user = await loadCredentialSnapshot(user.id, user.role);
    if (!user) return res.status(401).json({ success: false, error: 'Invalid mobile number or credentials.' });

    if (role && user.role !== role.trim().toUpperCase()) {
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

    if (user.status && user.status !== 'ACTIVE') {
      const error = user.status === 'PENDING'
        ? 'Thank you for registering. Your account is awaiting administrator approval and route assignment. Please try signing in after the administrator contacts you.'
        : (user.status === 'REJECTED'
          ? 'Your driver registration was not approved. Please contact RoutePass administration.'
          : 'This account is inactive. Contact your transport administrator.');
      return res.status(403).json({ success: false, error, code: user.status, applicationPath: user.role === 'DRIVER' && user.status === 'PENDING' ? '/join/?view=login' : undefined });
    }
    if (user.role === 'DRIVER' && !(await isOperationallyEligible(user.id, 'DRIVER'))) {
      return res.status(403).json({ success: false, code: 'ONBOARDING_REQUIRED', error: 'Complete your driver application and final approval before opening the driver portal.', applicationPath: '/join/?view=login' });
    }

    const token = signToken({
      id: user.id,
      role: user.role,
      fullName: user.fullName,
      phone: user.phone,
      sessionVersion: Number(user.credential_session_version)
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
