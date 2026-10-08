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
router.get('/stats', (req, res) => {
  try {
    const totalPassengers = DB.prepare("SELECT COUNT(*) as c FROM users WHERE role = 'PASSENGER'").get().c;
    const totalDrivers = DB.prepare("SELECT COUNT(*) as c FROM users WHERE role = 'DRIVER'").get().c;
    const activeRoutes = DB.prepare('SELECT COUNT(*) as c FROM routes WHERE active = 1').get().c;
    const activeVehicles = DB.prepare("SELECT COUNT(*) as c FROM vehicles WHERE status != 'MAINTENANCE'").get().c;
    const activeSubscriptions = DB.prepare("SELECT COUNT(*) as c FROM subscriptions WHERE subscriptionStatus = 'ACTIVE'").get().c;
    const totalRevenue = DB.prepare("SELECT COALESCE(SUM(amountEtb), 0) as s FROM payment_transactions WHERE status = 'COMPLETED'").get().s;
    const todayCheckins = DB.prepare("SELECT COUNT(*) as c FROM checkin_records WHERE status = 'BOARDED'").get().c;
    const openComplaints = DB.prepare("SELECT COUNT(*) as c FROM complaints WHERE status = 'OPEN'").get().c;

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
    res.status(500).json({ success: false, error: err.message });
  }
});

/**
 * GET /api/admin/drivers
 * List all commercial transporters / drivers
 */
router.get('/drivers', (req, res) => {
  try {
    const drivers = DB.prepare(`
      SELECT id, fullName, phone, email, status, licenseNumber, companyName, assignedVehiclePlate, appliedRouteId, appliedRouteName, createdAt
      FROM users
      WHERE role = 'DRIVER'
      ORDER BY createdAt DESC
    `).all();

    res.json({ success: true, drivers });
  } catch (err) {
    res.status(500).json({ success: false, error: err.message });
  }
});

/**
 * GET /api/admin/subscriptions
 * List all subscriptions
 */
router.get('/subscriptions', (req, res) => {
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

/**
 * GET /api/admin/payments
 * List payment transaction history
 */
router.get('/payments', (req, res) => {
  try {
    const payments = DB.prepare(`
      SELECT p.*, u.fullName as passengerName, u.phone as passengerPhone
      FROM payment_transactions p
      LEFT JOIN users u ON p.passengerId = u.id
      ORDER BY p.date DESC
    `).all();

    res.json({ success: true, payments });
  } catch (err) {
    res.status(500).json({ success: false, error: err.message });
  }
});

/**
 * GET /api/admin/checkins
 * Full checkin audit log
 */
router.get('/checkins', (req, res) => {
  try {
    const list = DB.prepare(`
      SELECT c.*, v.plateNumber as vehiclePlate, r.name as routeName
      FROM checkin_records c
      LEFT JOIN vehicles v ON c.vehicleId = v.id
      LEFT JOIN routes r ON c.routeId = r.id
      ORDER BY c.timestamp DESC LIMIT 100
    `).all();

    res.json({ success: true, checkins: list });
  } catch (err) {
    res.status(500).json({ success: false, error: err.message });
  }
});

/**
 * GET /api/admin/complaints
 * Passenger incident reports
 */
router.get('/complaints', (req, res) => {
  try {
    const complaints = DB.prepare(`
      SELECT c.*, u.phone as passengerPhone
      FROM complaints c
      LEFT JOIN users u ON c.passengerId = u.id
      ORDER BY c.createdAt DESC
    `).all();

    res.json({ success: true, complaints });
  } catch (err) {
    res.status(500).json({ success: false, error: err.message });
  }
});

/**
 * PATCH /api/admin/complaints/:id
 * Resolve or update complaint status
 */
router.patch('/complaints/:id', (req, res) => {
  try {
    const { id } = req.params;
    const { status } = req.body;

    DB.prepare('UPDATE complaints SET status = ? WHERE id = ?').run(status, id);
    res.json({ success: true, message: 'Complaint status updated.', id, status });
  } catch (err) {
    res.status(500).json({ success: false, error: err.message });
  }
});

/**
 * GET /api/admin/audit-logs
 * System audit events
 */
router.get('/audit-logs', (req, res) => {
  try {
    const logs = DB.prepare(`
      SELECT * FROM audit_logs ORDER BY timestamp DESC LIMIT 100
    `).all();

    res.json({ success: true, logs });
  } catch (err) {
    res.status(500).json({ success: false, error: err.message });
  }
});

module.exports = router;
