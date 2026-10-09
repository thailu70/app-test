const express = require('express');
const router = express.Router();
const crypto = require('crypto');
const bcrypt = require('bcryptjs');
const { DB } = require('../db');
const { authenticate, requireRole } = require('../middleware/auth');

/**
 * All endpoints here require ADMIN role
 */
router.use(authenticate, requireRole('ADMIN'));

/**
 * GET /api/admin/stats
 * Overview dashboard metrics
 */
router.get('/stats', async (req, res) => {
  try {
    const totalPassengers = (await DB.prepare("SELECT COUNT(*) as c FROM users WHERE role = 'PASSENGER'").get()).c;
    const totalDrivers = (await DB.prepare("SELECT COUNT(*) as c FROM users WHERE role = 'DRIVER'").get()).c;
    const activeRoutes = (await DB.prepare('SELECT COUNT(*) as c FROM routes WHERE active = 1').get()).c;
    const activeVehicles = (await DB.prepare("SELECT COUNT(*) as c FROM vehicles WHERE status != 'MAINTENANCE'").get()).c;
    const activeSubscriptions = (await DB.prepare("SELECT COUNT(*) as c FROM subscriptions WHERE subscriptionStatus = 'ACTIVE'").get()).c;
    const totalRevenue = (await DB.prepare("SELECT COALESCE(SUM(amountEtb), 0) as s FROM payment_transactions WHERE status = 'COMPLETED'").get()).s;
    const todayCheckins = (await DB.prepare("SELECT COUNT(*) as c FROM checkin_records WHERE status = 'BOARDED'").get()).c;
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
 * POST /api/admin/drivers
 * Provision a driver only through an authenticated administrator. The server controls
 * role, status, vehicle assignment and route assignment; public signup cannot do this.
 */
router.post('/drivers', async (req, res) => {
  try {
    const { fullName, phone, email = '', password, licenseNumber, companyName, vehicleId, routeId } = req.body;
    if (!fullName || !phone || !password || !licenseNumber || !companyName || !vehicleId || !routeId) {
      return res.status(400).json({ success: false, error: 'Name, phone, password, license, company, vehicle and route are required.' });
    }
    if (typeof password !== 'string' || password.length < 12) {
      return res.status(400).json({ success: false, error: 'Driver passwords must contain at least 12 characters.' });
    }
    const cleanPhone = String(phone).trim();
    const existingUser = await DB.prepare('SELECT id FROM users WHERE phone = ?').get(cleanPhone);
    if (existingUser) return res.status(409).json({ success: false, error: 'Phone number is already registered.' });

    const vehicle = await DB.prepare('SELECT * FROM vehicles WHERE id = ?').get(vehicleId);
    if (!vehicle) return res.status(404).json({ success: false, error: 'Vehicle not found.' });
    const route = await DB.prepare('SELECT * FROM routes WHERE id = ? AND active = 1').get(routeId);
    if (!route) return res.status(404).json({ success: false, error: 'Active route not found.' });

    const driverId = `usr_drv_${crypto.randomUUID().replace(/-/g, '').slice(0, 12)}`;
    const passwordHash = await bcrypt.hash(password, 12);
    const cleanName = String(fullName).trim();
    const finalEmail = String(email || '').trim() || `${cleanPhone}@transport.et`;

    await DB.transaction(async (tx) => {
      if (vehicle.driverId) {
        await tx.prepare("UPDATE users SET assignedVehiclePlate = '', appliedRouteId = '', appliedRouteName = '' WHERE id = ? AND role = 'DRIVER'").run(vehicle.driverId);
      }
      await tx.prepare(`
        INSERT INTO users (id, role, fullName, phone, email, passwordHash, status, licenseNumber, companyName, assignedVehiclePlate, appliedRouteId, appliedRouteName)
        VALUES (?, 'DRIVER', ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?)
      `).run(driverId, cleanName, cleanPhone, finalEmail, passwordHash, String(licenseNumber).trim(), String(companyName).trim(), vehicle.plateNumber, route.id, route.name);
      await tx.prepare(`
        UPDATE vehicles
        SET driverId = ?, driverName = ?, assignedRouteId = ?, status = 'IN_SERVICE', updatedAt = CURRENT_TIMESTAMP
        WHERE id = ?
      `).run(driverId, cleanName, route.id, vehicle.id);
      await tx.prepare('INSERT INTO audit_logs (action, userId, role, details) VALUES (?, ?, ?, ?)')
        .run('DRIVER_CREATED_AND_ASSIGNED', req.user.id, 'ADMIN', `Created driver ${driverId}; vehicle ${vehicle.plateNumber}; route ${route.name}`);
    });

    res.status(201).json({
      success: true,
      driver: {
        id: driverId, role: 'DRIVER', fullName: cleanName, phone: cleanPhone,
        email: finalEmail, status: 'ACTIVE', licenseNumber: String(licenseNumber).trim(),
        companyName: String(companyName).trim(), assignedVehiclePlate: vehicle.plateNumber,
        appliedRouteId: route.id, appliedRouteName: route.name
      }
    });
  } catch (err) {
    console.error('[Admin] driver provisioning failed:', err);
    if (err.code === '23505' || String(err.code || '').startsWith('SQLITE_CONSTRAINT')) {
      return res.status(409).json({ success: false, error: 'A conflicting driver or assignment already exists.' });
    }
    res.status(500).json({ success: false, error: 'Driver provisioning failed.' });
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
      WHERE role = 'DRIVER'
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
      SELECT s.*, u.fullName as passengerName, u.phone as passengerPhone, r.name as routeName
      FROM subscriptions s
      LEFT JOIN users u ON s.passengerId = u.id
      LEFT JOIN routes r ON s.routeId = r.id
      ORDER BY s.updatedAt DESC
    `).all();

    res.json({ success: true, subscriptions: list });
  } catch (err) {
    console.error('[RoutePass] request failed:', err);
    res.status(500).json({ success: false, error: 'Internal server error.' });
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

    await DB.prepare('UPDATE complaints SET status = ? WHERE id = ?').run(status, id);
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
      SELECT * FROM audit_logs ORDER BY timestamp DESC LIMIT 100
    `).all();

    res.json({ success: true, logs });
  } catch (err) {
    console.error('[RoutePass] request failed:', err);
    res.status(500).json({ success: false, error: 'Internal server error.' });
  }
});

module.exports = router;
