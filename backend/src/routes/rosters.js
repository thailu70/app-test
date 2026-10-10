const express = require('express');
const { DB } = require('../db');
const { authenticate, requireRole } = require('../middleware/auth');

const router = express.Router();
router.use(authenticate);

// GET /api/rosters/my
// Driver gets only passengers subscribed to the route of the driver's own active assignment.
// Passenger gets the driver and vehicle for their active paid subscription.
router.get('/my', async (req, res) => {
  try {
    if (req.user.role === 'DRIVER') {
      const vehicle = await DB.prepare('SELECT id, plateNumber, model, assignedRouteId, driverId FROM vehicles WHERE driverId = ? LIMIT 1').get(req.user.id);
      if (!vehicle) return res.json({ success: true, role: 'DRIVER', trip: null, passengers: [] });
      const trip = await DB.prepare("SELECT id, routeId, status, direction, startedAt FROM trips WHERE driverId = ? AND vehicleId = ? AND status = 'IN_PROGRESS' ORDER BY startTime DESC LIMIT 1").get(req.user.id, vehicle.id);
      const routeId = trip?.routeId || vehicle.assignedRouteId;
      if (!routeId) return res.json({ success: true, role: 'DRIVER', trip: trip || null, passengers: [] });
      const passengers = await DB.prepare(`
        SELECT u.id AS passengerId, u.fullName AS passengerName, s.id AS subscriptionId,
          s.subscriptionStatus, s.paymentStatus, s.daysRemaining,
          c.status AS attendanceStatus, c.timestamp AS scannedAt
        FROM subscriptions s
        JOIN users u ON u.id = s.passengerId
        LEFT JOIN checkin_records c ON c.passengerId = s.passengerId AND c.tripId = ?
          AND c.status = 'BOARDED'
        WHERE s.vehicleId = ? AND s.routeId = ? AND s.subscriptionStatus = 'ACTIVE' AND s.paymentStatus = 'PAID'
          AND s.daysRemaining > 0 AND u.role = 'PASSENGER'
        ORDER BY u.fullName ASC
      `).all(trip?.id || '', vehicle.id, routeId);
      return res.json({
        success: true, role: 'DRIVER',
        trip: trip ? { id: trip.id, routeId: trip.routeId, status: trip.status, direction: trip.direction } : null,
        vehicle: { id: vehicle.id, plateNumber: vehicle.plateNumber, model: vehicle.model },
        passengers: passengers.map(p => ({
          id: p.passengerId, fullName: p.passengerName,
          subscriptionId: p.subscriptionId, subscriptionStatus: p.subscriptionStatus,
          paymentStatus: p.paymentStatus, daysRemaining: p.daysRemaining,
          attendance: p.attendanceStatus === 'BOARDED' ? 'PRESENT' : 'NOT_SCANNED',
          scannedAt: p.scannedAt || null,
          photoUrl: '/api/profile-media/' + encodeURIComponent(p.passengerId) + '/PROFILE_PHOTO'
        }))
      });
    }

    if (req.user.role === 'PASSENGER') {
      const assignment = await DB.prepare(`
        SELECT u.id AS driverId, u.fullName AS driverName, v.id AS vehicleId,
          v.plateNumber, v.model, s.routeId, s.id AS subscriptionId
        FROM subscriptions s
        JOIN vehicles v ON v.assignedRouteId = s.routeId
        JOIN users u ON u.id = v.driverId AND u.role = 'DRIVER' AND u.status = 'ACTIVE'
        WHERE s.passengerId = ? AND s.vehicleId = v.id AND s.subscriptionStatus = 'ACTIVE' AND s.paymentStatus = 'PAID'
          AND s.daysRemaining > 0
        ORDER BY v.updatedAt DESC LIMIT 1
      `).get(req.user.id);
      return res.json({
        success: true, role: 'PASSENGER',
        assignment: assignment ? {
          driver: { id: assignment.driverId, fullName: assignment.driverName,
            photoUrl: '/api/profile-media/' + encodeURIComponent(assignment.driverId) + '/PROFILE_PHOTO' },
          vehicle: { id: assignment.vehicleId, plateNumber: assignment.plateNumber, model: assignment.model },
          routeId: assignment.routeId, subscriptionId: assignment.subscriptionId
        } : null
      });
    }

    if (req.user.role === 'ADMIN') {
      return res.json({ success: true, role: 'ADMIN', message: 'Use the admin roster reporting endpoints.' });
    }
    return res.status(403).json({ success: false, error: 'Role not permitted.' });
  } catch (err) {
    console.error('[Rosters] query failed:', err);
    return res.status(500).json({ success: false, error: 'Could not load roster.' });
  }
});

module.exports = router;
