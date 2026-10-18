const express = require('express');
const router = express.Router();
const crypto = require('crypto');
const { DB } = require('../db');
const { authenticate, requireRole } = require('../middleware/auth');
const { adminScope } = require('../services/commute-scope');
const { generateSignedQrToken } = require('./subscriptions');
const { sendSms } = require('../services/afro-sms');

/**
 * All endpoints here require ADMIN role
 */
router.use(authenticate, requireRole('ADMIN'), adminScope);

router.get('/features', (_req, res) => {
  res.json({
    manualTestRechargeEnabled: process.env.NODE_ENV === 'test' && process.env.ALLOW_MANUAL_TEST_RECHARGE === 'true',
    adminPassActivationEnabled: process.env.ALLOW_ADMIN_PASS_ACTIVATION === 'true'
  });
});

router.get('/integrations/afro-sms', async (_req, res) => {
  const rows = await DB.prepare("SELECT key, CASE WHEN value='' THEN FALSE ELSE TRUE END AS configured FROM app_settings WHERE key IN ('AFRO_SMS_API_TOKEN','AFRO_SMS_IDENTIFIER','AFRO_SMS_URL','AFRO_SMS_SENDER')").all();
  res.json({ success: true, provider: 'AFRO_SMS', configured: Object.fromEntries(rows.map(row => [row.key, Boolean(row.configured)])) });
});
router.put('/integrations/afro-sms', async (req, res) => {
  const token = String(req.body.apiToken || '').trim().slice(0, 500);
  const identifier = String(req.body.identifier || '').trim().slice(0, 120);
  if (!token) return res.status(400).json({ success: false, error: 'Afro SMS API token is required.' });
  if (!identifier) return res.status(400).json({ success: false, error: 'AfroMessage Identifier ID is required.' });
  const values = [['AFRO_SMS_API_TOKEN', token], ['AFRO_SMS_IDENTIFIER', identifier], ['AFRO_SMS_URL', String(req.body.url || 'https://api.afromessage.com/api/send').trim().slice(0, 500)], ['AFRO_SMS_SENDER', String(req.body.sender || 'RoutePass').trim().slice(0, 40)]];
  for (const [key, value] of values) await DB.prepare("INSERT INTO app_settings(key,value,updated_at) VALUES(?,?,CURRENT_TIMESTAMP) ON CONFLICT(key) DO UPDATE SET value=excluded.value,updated_at=CURRENT_TIMESTAMP").run(key, value);
  res.json({ success: true, provider: 'AFRO_SMS', message: 'Afro SMS settings saved securely.' });
});
router.post('/integrations/afro-sms/test', async (req, res) => {
  const phone = String(req.body.phone || '').trim();
  const message = String(req.body.message || 'RoutePass test SMS: Afro SMS is connected.').trim().slice(0, 320);
  if (!phone) return res.status(400).json({ success: false, error: 'A test phone number is required.' });
  const result = await sendSms(phone, message);
  if (!result.sent) return res.status(502).json({ success: false, error: result.reason || result.error || (result.skipped ? 'Save the Afro SMS token and Identifier ID before testing.' : 'Afro SMS rejected the test message.') });
  res.json({ success: true, message: 'AfroMessage accepted the test SMS for delivery.', messageId: result.messageId || null });
});

/**
 * GET /api/admin/stats
 * Overview dashboard metrics
 */
router.get('/stats', async (req, res) => {
  try {
    const totalPassengers = (await DB.prepare("SELECT COUNT(*) as c FROM users WHERE role = 'PASSENGER' AND COALESCE(companyId,'')=''").get()).c;
    const totalDrivers = (await DB.prepare("SELECT COUNT(*) as c FROM users WHERE role = 'DRIVER' AND COALESCE(companyId,'')=''").get()).c;
    const activeRoutes = (await DB.prepare(`SELECT COUNT(*) as c FROM routes WHERE active = TRUE AND COALESCE(companyId,'')='' AND COALESCE(routeType,'DAILY_COMMUTE')='DAILY_COMMUTE'`).get()).c;
    const activeVehicles = (await DB.prepare("SELECT COUNT(*) as c FROM vehicles WHERE status != 'MAINTENANCE' AND COALESCE(companyId,'')=''").get()).c;
    const activeSubscriptions = (await DB.prepare("SELECT COUNT(*) as c FROM subscriptions WHERE subscriptionStatus = 'ACTIVE' AND routeId IN (SELECT id FROM routes WHERE COALESCE(companyId,'')='' AND COALESCE(routeType,'DAILY_COMMUTE')='DAILY_COMMUTE')").get()).c;
    const totalRevenue = (await DB.prepare("SELECT COALESCE(SUM(amountEtb), 0) as s FROM payment_transactions WHERE status = 'COMPLETED' AND provider <> 'ADMIN_TEST' AND passengerId IN (SELECT id FROM users WHERE COALESCE(companyId,'')='')").get()).s;
    const todayCheckins = (await DB.prepare("SELECT COUNT(*) as c FROM checkin_records WHERE status = 'BOARDED' AND routeId IN (SELECT id FROM routes WHERE COALESCE(companyId,'')='' AND COALESCE(routeType,'DAILY_COMMUTE')='DAILY_COMMUTE')").get()).c;
    const openComplaints = (await DB.prepare("SELECT COUNT(*) as c FROM complaints WHERE status = 'OPEN'").get()).c;

    res.json({
      success: true,
      stats: {
        totalPassengers,
        totalDrivers,
        activeRoutes,
        activeVehicles,
        activeSubscriptions,
        totalRevenueEtb: totalRevenue,
        todayCheckins,
        openComplaints
      }
    });
  } catch (err) {
    console.error('[RoutePass] request failed:', err);
    res.status(500).json({ success: false, error: 'Internal server error.' });
  }
});

/**
 * Public driver self-registration is required. Admins may assign a route to a driver's
 * own registered vehicle, but cannot create a driver account or transfer vehicle ownership.
 */
router.post('/drivers', (req, res) => {
  res.status(410).json({
    success: false,
    error: 'Driver accounts must be self-registered by the vehicle owner. Use PATCH /api/admin/drivers/:id/route to assign a route.'
  });
});

/**
 * PATCH /api/admin/drivers/:id/approval
 * An administrator must approve a newly registered driver before route assignment.
 */
router.patch('/drivers/:id/approval', async (req, res) => {
  try {
    const status = String(req.body.status || '').trim().toUpperCase();
    if (!['ACTIVE', 'SUSPENDED'].includes(status)) {
      return res.status(400).json({ success: false, error: 'Set driver status to ACTIVE to approve or SUSPENDED to reject/suspend.' });
    }
    const driver = await DB.prepare("SELECT * FROM users WHERE id = ? AND role = 'DRIVER' AND COALESCE(companyId,'')=''").get(req.params.id);
    if (!driver) return res.status(404).json({ success: false, error: 'Driver account not found.' });

    await DB.transaction(async (tx) => {
      await tx.prepare("UPDATE users SET status = ? WHERE id = ? AND role = 'DRIVER' AND COALESCE(companyId,'')=''").run(status, driver.id);
      await tx.prepare('INSERT INTO audit_logs (action, userId, role, details) VALUES (?, ?, ?, ?)')
        .run(status === 'ACTIVE' ? 'DRIVER_APPROVED' : 'DRIVER_REJECTED', req.user.id, 'ADMIN', `${status === 'ACTIVE' ? 'Approved' : 'Rejected/suspended'} driver ${driver.id} (${driver.phone})`);
    });

    res.json({ success: true, driver: { id: driver.id, fullName: driver.fullName, status }, message: status === 'ACTIVE' ? 'Driver approved. You can now assign an active route.' : 'Driver registration rejected/suspended.' });
  } catch (err) {
    console.error('[Admin] driver approval failed:', err);
    res.status(500).json({ success: false, error: 'Could not update driver approval.' });
  }
});

/**
 * PATCH /api/admin/drivers/:id/route
 * Assign an active route to a driver's own registered vehicle after approval.
 */
router.patch('/drivers/:id/route', async (req, res) => {
  try {
    const routeId = String(req.body.routeId || '').trim();
    if (!routeId) return res.status(400).json({ success: false, error: 'An active route is required.' });

    const driver = await DB.prepare("SELECT * FROM users WHERE id = ? AND role = 'DRIVER' AND COALESCE(companyId,'')=''").get(req.params.id);
    if (!driver) return res.status(404).json({ success: false, error: 'Driver account not found.' });
    if (driver.status !== 'ACTIVE') return res.status(409).json({ success: false, error: 'Approve this driver before assigning a route.' });
    const route = await DB.prepare(`SELECT * FROM routes WHERE id = ? AND active = TRUE AND COALESCE(companyId,'')='' AND COALESCE(routeType,'DAILY_COMMUTE')='DAILY_COMMUTE'`).get(routeId);
    if (!route) return res.status(404).json({ success: false, error: 'Active route not found.' });
    const vehicle = await DB.prepare(`SELECT * FROM vehicles WHERE driverId = ? LIMIT 1`).get(driver.id);
    if (!vehicle) return res.status(409).json({ success: false, error: 'This driver has not registered an owned vehicle yet.' });

    await DB.transaction(async (tx) => {
      await tx.prepare(`
        UPDATE vehicles
        SET assignedRouteId = ?, status = 'IN_SERVICE', updatedAt = CURRENT_TIMESTAMP
        WHERE id = ? AND driverId = ?
      `).run(route.id, vehicle.id, driver.id);
      await tx.prepare('UPDATE users SET appliedRouteId = ?, appliedRouteName = ? WHERE id = ? AND role = \'DRIVER\'')
        .run(route.id, route.name, driver.id);
      await tx.prepare('INSERT INTO audit_logs (action, userId, role, details) VALUES (?, ?, ?, ?)')
        .run('DRIVER_ROUTE_ASSIGNED', req.user.id, 'ADMIN', `Assigned route ${route.name} to driver ${driver.id} and owner vehicle ${vehicle.plateNumber}`);
    });

    res.json({
      success: true,
      driver: { id: driver.id, fullName: driver.fullName, phone: driver.phone, licenseNumber: driver.licenseNumber },
      vehicle: { id: vehicle.id, plateNumber: vehicle.plateNumber, model: vehicle.model },
      route: { id: route.id, name: route.name, nameAm: route.nameAm }
    });
  } catch (err) {
    console.error('[Admin] driver route assignment failed:', err);
    res.status(500).json({ success: false, error: 'Could not assign route to driver.' });
  }
});

/**
 * GET /api/admin/routes
 * Return active and inactive routes for browser-based administration.
 */
router.get('/routes', async (req, res) => {
  try {
    const routes = await DB.prepare(`SELECT * FROM routes WHERE COALESCE(companyId,'')='' AND COALESCE(routeType,'DAILY_COMMUTE')='DAILY_COMMUTE' ORDER BY active DESC, name ASC`).all();
    res.json({ success: true, routes });
  } catch (err) {
    console.error('[Admin] route list failed:', err);
    res.status(500).json({ success: false, error: 'Could not load routes.' });
  }
});

/**
 * GET /api/admin/drivers
 * List all commercial transporters / drivers
 */
router.get('/drivers', async (req, res) => {
  try {
    const drivers = await DB.prepare(`
      SELECT id, fullName, phone, email, status, licenseNumber, companyName, assignedVehiclePlate, appliedRouteId, appliedRouteName, createdAt
      FROM users
      WHERE role = 'DRIVER' AND COALESCE(companyId,'')=''
      ORDER BY createdAt DESC
    `).all();

    res.json({ success: true, drivers });
  } catch (err) {
    console.error('[RoutePass] request failed:', err);
    res.status(500).json({ success: false, error: 'Internal server error.' });
  }
});

/**
 * GET /api/admin/subscriptions
 * List all subscriptions
 */
router.get('/subscriptions', async (req, res) => {
  try {
    const list = await DB.prepare(`
      SELECT s.*, u.fullName as passengerName, u.phone as passengerPhone,
             r.name as routeName, v.plateNumber as vehiclePlate, v.driverName as driverName,
             v.driverId as driverId
      FROM subscriptions s
      LEFT JOIN users u ON s.passengerId = u.id
      LEFT JOIN routes r ON s.routeId = r.id
      LEFT JOIN vehicles v ON s.vehicleId = v.id
      WHERE COALESCE(r.companyId,'')='' AND COALESCE(r.routeType,'DAILY_COMMUTE')='DAILY_COMMUTE'
      ORDER BY s.updatedAt DESC
    `).all();

    res.json({ success: true, subscriptions: list });
  } catch (err) {
    console.error('[RoutePass] request failed:', err);
    res.status(500).json({ success: false, error: 'Internal server error.' });
  }
});

/**
 * Temporary, explicitly enabled admin grants. These do not collect money or
 * create a payment transaction. Keep the legacy PAID eligibility marker so the
 * deployed Android clients and boarding validators accept an authorized pass.
 */
router.post('/subscriptions/:id/activate', async (req, res) => {
  if (process.env.ALLOW_ADMIN_PASS_ACTIVATION !== 'true') {
    return res.status(403).json({ success: false, code: 'ADMIN_ACTIVATION_DISABLED', error: 'Manual admin pass activation is disabled.' });
  }
  const days = req.body.days === undefined ? 30 : req.body.days;
  const reason = typeof req.body.reason === 'string' ? req.body.reason.trim() : '';
  if (!Number.isInteger(days) || days < 1 || days > 90) {
    return res.status(400).json({ success: false, error: 'Activation days must be an integer between 1 and 90.' });
  }
  if (reason.length < 3 || reason.length > 200) {
    return res.status(400).json({ success: false, error: 'Enter an activation reason of 3 to 200 characters.' });
  }
  const reject = (status, code, message) => { throw Object.assign(new Error(message), { status, code }); };
  try {
    const subscription = await DB.transaction(async tx => {
      const sub = await tx.prepare('SELECT * FROM subscriptions WHERE id = ?' + (DB.isPostgres ? ' FOR UPDATE' : '')).get(req.params.id);
      if (!sub) reject(404, 'SUBSCRIPTION_NOT_FOUND', 'Subscription not found.');
      const passenger = await tx.prepare("SELECT id FROM users WHERE id = ? AND role = 'PASSENGER' AND status = 'ACTIVE'").get(sub.passengerId);
      if (!passenger) reject(409, 'PASSENGER_INACTIVE', 'An active passenger account is required.');
      const route = await tx.prepare("SELECT id FROM routes WHERE id = ? AND active = TRUE AND COALESCE(companyId,'')='' AND COALESCE(routeType,'DAILY_COMMUTE')='DAILY_COMMUTE'").get(sub.routeId);
      if (!route) reject(409, 'ROUTE_INACTIVE', 'An active route is required.');
      const now = new Date();
      if (sub.subscriptionStatus === 'ACTIVE' && sub.paymentStatus === 'PAID' && new Date(sub.endDate).getTime() > now.getTime()) {
        reject(409, 'PASS_ALREADY_ACTIVE', 'This pass is already active. No extra days were added.');
      }
      const startDate = now.toISOString().slice(0, 10);
      const endDate = new Date(now.getTime() + days * 86400000).toISOString().slice(0, 10);
      const expiresAt = new Date(endDate + 'T00:00:00.000Z').getTime();
      const daysRemaining = Math.max(1, Math.ceil((expiresAt - now.getTime()) / 86400000));
      const qrToken = generateSignedQrToken(sub.id, sub.passengerId, sub.routeId, expiresAt);
      await tx.prepare(`UPDATE subscriptions SET paymentStatus = 'PAID', subscriptionStatus = 'ACTIVE',
        startDate = ?, endDate = ?, daysRemaining = ?, qrToken = ?, updatedAt = CURRENT_TIMESTAMP WHERE id = ?`)
        .run(startDate, endDate, daysRemaining, qrToken, sub.id);
      await tx.prepare('INSERT INTO audit_logs (action, userId, role, details) VALUES (?, ?, ?, ?)')
        .run('SUBSCRIPTION_ADMIN_ACTIVATED', req.user.id, 'ADMIN', JSON.stringify({ subscriptionId: sub.id, days, reason, noPaymentCollected: true }));
      return { id: sub.id, passengerId: sub.passengerId, routeId: sub.routeId, subscriptionStatus: 'ACTIVE', paymentStatus: 'PAID', startDate, endDate, daysRemaining, qrToken };
    });
    res.json({ success: true, activationSource: 'ADMIN_GRANT', noPaymentCollected: true, message: `Pass activated for ${days} days by an administrator. No payment was collected.`, subscription });
  } catch (err) {
    if (err.status) return res.status(err.status).json({ success: false, code: err.code, error: err.message });
    console.error('[Admin] pass activation failed:', err);
    res.status(500).json({ success: false, error: 'Could not activate the passenger pass.' });
  }
});

/**
 * POST /api/admin/subscriptions/:id/recharge
 * Manual TEST recharge only. This is an audited admin override, not a Telebirr payment.
 */
router.post('/subscriptions/:id/recharge', async (req, res) => {
  try {
    if (process.env.NODE_ENV !== 'test' || process.env.ALLOW_MANUAL_TEST_RECHARGE !== 'true') {
      return res.status(403).json({
        success: false,
        code: 'MANUAL_TEST_RECHARGE_DISABLED',
        error: 'Manual recharge is disabled. Enable ALLOW_MANUAL_TEST_RECHARGE=true only on a test instance.'
      });
    }
    const days = Number.parseInt(req.body.days ?? 30, 10);
    if (!Number.isInteger(days) || days < 1 || days > 90) {
      return res.status(400).json({ success: false, error: 'Test recharge days must be between 1 and 90.' });
    }

    const sub = await DB.prepare(`SELECT * FROM subscriptions WHERE id = ?`).get(req.params.id);
    if (!sub) return res.status(404).json({ success: false, error: 'Subscription not found.' });
    const passenger = await DB.prepare("SELECT id, phone FROM users WHERE id = ? AND role = 'PASSENGER'").get(sub.passengerId);
    if (!passenger) return res.status(404).json({ success: false, error: 'Passenger account not found.' });

    const now = new Date();
    const previousEnd = sub.endDate ? new Date(sub.endDate) : null;
    const base = sub.subscriptionStatus === 'ACTIVE' && sub.paymentStatus === 'PAID' &&
      previousEnd && Number.isFinite(previousEnd.getTime()) && previousEnd.getTime() > now.getTime()
      ? previousEnd : now;
    const end = new Date(base.getTime() + days * 86400000);
    const startDate = now.toISOString().slice(0, 10);
    const endDate = end.toISOString().slice(0, 10);
    const daysRemaining = Math.max(1, Math.ceil((end.getTime() - now.getTime()) / 86400000));
    const qrToken = generateSignedQrToken(
      sub.id,
      sub.passengerId,
      sub.routeId,
      end.getTime()
    );
    const amount = Number(sub.priceEtb || 0);
    const reference = `RP-ADMIN-TEST-${Date.now()}-${crypto.randomBytes(3).toString('hex').toUpperCase()}`;
    const note = `MANUAL TEST RECHARGE by admin ${req.user.id}; NOT A TELEBIRR PAYMENT; ${days} days`;

    await DB.transaction(async (tx) => {
      await tx.prepare(`
        UPDATE subscriptions
        SET paymentStatus = 'PAID', subscriptionStatus = 'ACTIVE',
            startDate = ?, endDate = ?, daysRemaining = ?, qrToken = ?, updatedAt = CURRENT_TIMESTAMP
        WHERE id = ?
      `).run(startDate, endDate, daysRemaining, qrToken, sub.id);

      await tx.prepare(`
        INSERT INTO payment_transactions (id, passengerId, referenceNumber, idempotencyKey, amountEtb, provider, phoneNumber, status, notes)
        VALUES (?, ?, ?, ?, ?, 'ADMIN_TEST', ?, 'COMPLETED', ?)
      `).run(`pay_${crypto.randomUUID().replace(/-/g, '').slice(0, 12)}`, sub.passengerId, reference, reference, amount, passenger.phone, note);

      await tx.prepare('INSERT INTO audit_logs (action, userId, role, details) VALUES (?, ?, ?, ?)')
        .run('SUBSCRIPTION_MANUAL_TEST_RECHARGE', req.user.id, 'ADMIN', `Test-recharged subscription ${sub.id} for ${days} days, ETB ${amount}. Not a real payment.`);
    });

    res.json({
      success: true,
      testOnly: true,
      message: 'Subscription activated by manual admin test recharge. This is not a real payment.',
      subscription: { id: sub.id, passengerId: sub.passengerId, routeId: sub.routeId, paymentStatus: 'PAID', subscriptionStatus: 'ACTIVE', startDate, endDate, daysRemaining, qrToken },
      transaction: { referenceNumber: reference, provider: 'ADMIN_TEST', status: 'COMPLETED', amountEtb: amount, realPayment: false }
    });
  } catch (err) {
    console.error('[Admin] manual test recharge failed:', err);
    res.status(500).json({ success: false, error: 'Manual test recharge failed.' });
  }
});

/**
 * PATCH /api/admin/subscriptions/:id/assignment
 * Assign a passenger subscription to a driver-owned vehicle on the same route.
 */
router.patch('/subscriptions/:id/assignment', async (req, res) => {
  try {
    const vehicleId = String(req.body.vehicleId || '').trim();
    const sub = await DB.prepare(`SELECT * FROM subscriptions WHERE id = ?`).get(req.params.id);
    if (!sub) return res.status(404).json({ success: false, error: 'Subscription not found.' });

    if (!vehicleId) {
      await DB.prepare(`UPDATE subscriptions SET vehicleId = NULL, updatedAt = CURRENT_TIMESTAMP WHERE id = ?`).run(sub.id);
      await DB.prepare('INSERT INTO audit_logs (action, userId, role, details) VALUES (?, ?, ?, ?)')
        .run('SUBSCRIPTION_VEHICLE_UNASSIGNED', req.user.id, 'ADMIN', `Unassigned vehicle from subscription ${sub.id}`);
      return res.json({ success: true, vehicle: null, subscriptionId: sub.id });
    }

    const vehicle = await DB.prepare(`
      SELECT v.*, u.fullName AS driverName FROM vehicles v
      LEFT JOIN users u ON u.id = v.driverId AND u.role = 'DRIVER'
      WHERE v.id = ? AND v.driverId IS NOT NULL
    `).get(vehicleId);
    if (!vehicle) return res.status(404).json({ success: false, error: 'Choose a vehicle registered by a driver.' });
    if (vehicle.assignedRouteId !== sub.routeId) {
      return res.status(409).json({ success: false, error: 'The vehicle owner must be assigned to the passenger subscription route first.' });
    }

    await DB.transaction(async (tx) => {
      await tx.prepare('UPDATE subscriptions SET vehicleId = ?, updatedAt = CURRENT_TIMESTAMP WHERE id = ?').run(vehicle.id, sub.id);
      await tx.prepare('INSERT INTO audit_logs (action, userId, role, details) VALUES (?, ?, ?, ?)')
        .run('SUBSCRIPTION_VEHICLE_ASSIGNED', req.user.id, 'ADMIN', `Assigned owner vehicle ${vehicle.plateNumber} to subscription ${sub.id}`);
    });

    res.json({ success: true, subscriptionId: sub.id, vehicle: { id: vehicle.id, plateNumber: vehicle.plateNumber, driverName: vehicle.driverName || '', routeId: vehicle.assignedRouteId } });
  } catch (err) {
    console.error('[Admin] subscription vehicle assignment failed:', err);
    res.status(500).json({ success: false, error: 'Could not assign vehicle to passenger subscription.' });
  }
});

/**
 * GET /api/admin/payments
 * List payment transaction history
 */
router.get('/payments', async (req, res) => {
  try {
    const payments = await DB.prepare(`
      SELECT p.*, u.fullName as passengerName, u.phone as passengerPhone
      FROM payment_transactions p
      LEFT JOIN users u ON p.passengerId = u.id
      WHERE COALESCE(u.companyId,'')=''
      ORDER BY p.date DESC
    `).all();

    res.json({ success: true, payments });
  } catch (err) {
    console.error('[RoutePass] request failed:', err);
    res.status(500).json({ success: false, error: 'Internal server error.' });
  }
});

/**
 * GET /api/admin/checkins
 * Full checkin audit log
 */
router.get('/checkins', async (req, res) => {
  try {
    const list = await DB.prepare(`
      SELECT c.*, v.plateNumber as vehiclePlate, r.name as routeName
      FROM checkin_records c
      LEFT JOIN vehicles v ON c.vehicleId = v.id
      LEFT JOIN routes r ON c.routeId = r.id
      WHERE COALESCE(r.companyId,'')='' AND COALESCE(r.routeType,'DAILY_COMMUTE')='DAILY_COMMUTE'
      ORDER BY c.timestamp DESC LIMIT 100
    `).all();

    res.json({ success: true, checkins: list });
  } catch (err) {
    console.error('[RoutePass] request failed:', err);
    res.status(500).json({ success: false, error: 'Internal server error.' });
  }
});

/**
 * GET /api/admin/complaints
 * Passenger incident reports
 */
router.get('/complaints', async (req, res) => {
  try {
    const complaints = await DB.prepare(`
      SELECT c.*, u.phone as passengerPhone
      FROM complaints c
      LEFT JOIN users u ON c.passengerId = u.id
      ORDER BY c.createdAt DESC
    `).all();

    res.json({ success: true, complaints });
  } catch (err) {
    console.error('[RoutePass] request failed:', err);
    res.status(500).json({ success: false, error: 'Internal server error.' });
  }
});

/**
 * PATCH /api/admin/complaints/:id
 * Resolve or update complaint status
 */
router.patch('/complaints/:id', async (req, res) => {
  try {
    const { id } = req.params;
    const { status } = req.body;

    await DB.prepare(`UPDATE complaints SET status = ? WHERE id = ?`).run(status, id);
    res.json({ success: true, message: 'Complaint status updated.', id, status });
  } catch (err) {
    console.error('[RoutePass] request failed:', err);
    res.status(500).json({ success: false, error: 'Internal server error.' });
  }
});

/**
 * GET /api/admin/audit-logs
 * System audit events
 */
router.get('/audit-logs', async (req, res) => {
  try {
    const logs = await DB.prepare(`
      SELECT * FROM audit_logs WHERE role<>'COMPANY' AND (userId IS NULL OR userId NOT IN (SELECT id FROM users WHERE COALESCE(companyId,'')<>'')) AND action NOT LIKE 'INTERCITY_%' ORDER BY timestamp DESC LIMIT 100
    `).all();

    res.json({ success: true, logs });
  } catch (err) {
    console.error('[RoutePass] request failed:', err);
    res.status(500).json({ success: false, error: 'Internal server error.' });
  }
});


module.exports = router;
