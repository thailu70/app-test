const express = require('express');
const router = express.Router();
const crypto = require('crypto');
const { DB } = require('../db');
const { authenticate, requireRole } = require('../middleware/auth');

/**
 * POST /api/checkins/scan
 * Driver scans passenger QR boarding pass:
 * 1. Validates QR code authenticity & subscription status.
 * 2. Enforces vehicle capacity limit based on vehicle type (e.g. 14 / 24 / 8 seats).
 * 3. Records check-in attendance and updates occupancy count.
 */
router.post('/scan', authenticate, requireRole('DRIVER'), (req, res) => {
  try {
    const driverId = req.user.id;
    const {
      qrToken,
      tripId = `trip_${Date.now().toString(36)}`,
      currentStop = 'Bole Medhanialem',
      vehicleId
    } = req.body;

    if (!qrToken) {
      return res.status(400).json({
        success: false,
        status: 'INVALID_QR',
        error: 'QR token is required.'
      });
    }

    // 1. Identify driver's vehicle
    const driverUser = DB.prepare('SELECT assignedVehiclePlate FROM users WHERE id = ?').get(driverId);
    let vehicle;
    if (vehicleId) {
      vehicle = DB.prepare('SELECT * FROM vehicles WHERE id = ?').get(vehicleId);
    } else {
      vehicle = DB.prepare('SELECT * FROM vehicles WHERE plateNumber = ? OR driverId = ? LIMIT 1')
        .get(driverUser?.assignedVehiclePlate || '3-AA-34921', driverId);
    }

    if (!vehicle) {
      return res.status(404).json({
        success: false,
        status: 'VEHICLE_NOT_FOUND',
        error: 'No active vehicle registered for this driver.'
      });
    }

    // 2. CHECK VEHICLE CAPACITY LIMIT (Strict limit based on vehicle type)
    if (vehicle.currentOccupancy >= vehicle.capacityLimit) {
      // Record denied attempt
      const checkinId = `chk_denied_${crypto.randomUUID().slice(0, 8)}`;
      DB.prepare(`
        INSERT INTO checkin_records (id, tripId, passengerId, passengerName, routeId, stopName, status, vehicleId, driverId)
        VALUES (?, ?, 'UNKNOWN', 'Passenger', ?, ?, 'DENIED_CAPACITY_FULL', ?, ?)
      `).run(checkinId, tripId, vehicle.assignedRouteId || 'route_bole_merkato', currentStop, vehicle.id, driverId);

      return res.status(409).json({
        success: false,
        status: 'DENIED_CAPACITY_FULL',
        error: `VEHICLE FULL: Capacity limit reached (${vehicle.currentOccupancy}/${vehicle.capacityLimit} seats). Cannot board additional passengers based on ${vehicle.vehicleType} vehicle type limit.`,
        errorAm: `ተሽከርካሪው ሞልቷል፡ የተሳፋሪ ገደብ ተደርሷል (${vehicle.currentOccupancy}/${vehicle.capacityLimit})። ተጨማሪ ተሳፋሪ መጫን አይቻልም።`,
        vehicle: {
          plateNumber: vehicle.plateNumber,
          vehicleType: vehicle.vehicleType,
          capacityLimit: vehicle.capacityLimit,
          currentOccupancy: vehicle.currentOccupancy
        }
      });
    }

    // 3. Find and validate passenger subscription
    const sub = DB.prepare('SELECT * FROM subscriptions WHERE qrToken = ?').get(qrToken);

    if (!sub) {
      return res.status(404).json({
        success: false,
        status: 'NOT_SUBSCRIBED',
        error: 'Passenger is not subscribed. Please pay and subscribe for the selected route.',
        errorAm: 'ተሳፋሪው አልተመዘገበም። እባክዎ ለተመረጠው መስመር በቴሌብር ከፍለው ይመዝገቡ።'
      });
    }

    if (sub.subscriptionStatus !== 'ACTIVE' || sub.daysRemaining <= 0) {
      return res.status(403).json({
        success: false,
        status: 'SUBSCRIPTION_EXPIRED',
        error: 'The passenger is not subscribed (pass expired). Please pay and subscribe for the selected route.',
        errorAm: 'የተሳፋሪው ፈቃድ አልቋል። እባክዎ በቴሌብር ከፍለው ያድሱ።'
      });
    }

    // 4. Check if passenger already boarded on this trip
    const alreadyBoarded = DB.prepare("SELECT id FROM checkin_records WHERE tripId = ? AND passengerId = ? AND status = 'BOARDED'").get(tripId, sub.passengerId);
    if (alreadyBoarded) {
      return res.status(409).json({
        success: false,
        status: 'ALREADY_CHECKED_IN',
        error: 'Passenger has already boarded this scheduled trip.'
      });
    }

    const passenger = DB.prepare('SELECT fullName, phone FROM users WHERE id = ?').get(sub.passengerId);
    const passengerName = passenger?.fullName || 'Verified Commuter';

    // 5. Record successful check-in
    const checkinId = `chk_${crypto.randomUUID().slice(0, 8)}`;
    DB.prepare(`
      INSERT INTO checkin_records (id, tripId, passengerId, passengerName, routeId, stopName, status, vehicleId, driverId)
      VALUES (?, ?, ?, ?, ?, ?, 'BOARDED', ?, ?)
    `).run(checkinId, tripId, sub.passengerId, passengerName, sub.routeId, currentStop, vehicle.id, driverId);

    // 6. Increment vehicle occupancy
    const newOccupancy = vehicle.currentOccupancy + 1;
    const isNowFull = newOccupancy >= vehicle.capacityLimit;

    DB.prepare(`
      UPDATE vehicles
      SET currentOccupancy = ?, status = ?, updatedAt = CURRENT_TIMESTAMP
      WHERE id = ?
    `).run(newOccupancy, isNowFull ? 'FULL' : 'IN_SERVICE', vehicle.id);

    // Broadcast check-in event to WebSockets
    if (req.app.locals.broadcastWs) {
      req.app.locals.broadcastWs({
        type: 'PASSENGER_BOARDED',
        checkinId,
        passengerName,
        stopName: currentStop,
        vehicleId: vehicle.id,
        currentOccupancy: newOccupancy,
        capacityLimit: vehicle.capacityLimit,
        isFull: isNowFull
      });
    }

    res.json({
      success: true,
      status: 'VERIFIED_BOARDED',
      message: 'Boarding pass verified. Passenger boarded successfully.',
      passenger: {
        id: sub.passengerId,
        fullName: passengerName,
        phone: passenger?.phone || '',
        daysRemaining: sub.daysRemaining
      },
      checkin: {
        id: checkinId,
        tripId,
        stopName: currentStop,
        timestamp: new Date().toISOString()
      },
      vehicleOccupancy: {
        current: newOccupancy,
        capacityLimit: vehicle.capacityLimit,
        availableSeats: Math.max(0, vehicle.capacityLimit - newOccupancy),
        isFull: isNowFull
      }
    });
  } catch (err) {
    res.status(500).json({ success: false, error: err.message });
  }
});

/**
 * GET /api/checkins/trip/:tripId
 * List all check-ins for a trip
 */
router.get('/trip/:tripId', authenticate, (req, res) => {
  try {
    const list = DB.prepare(`
      SELECT * FROM checkin_records WHERE tripId = ? ORDER BY timestamp DESC
    `).all(req.params.tripId);

    res.json({
      success: true,
      count: list.length,
      checkins: list
    });
  } catch (err) {
    res.status(500).json({ success: false, error: err.message });
  }
});

module.exports = router;
