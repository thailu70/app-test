const express = require('express');
const router = express.Router();
const crypto = require('crypto');
const { DB } = require('../db');
const { authenticate, requireRole } = require('../middleware/auth');
const { verifySignedQrToken } = require('./subscriptions');

// Standard vehicle capacity limits
const VEHICLE_TYPE_CAPACITIES = {
  MINIVAN_8: 8,
  MINIBUS_14: 14,
  HIGER_24: 24,
  ANBESSA_BUS_30: 30
};

/**
 * POST /api/checkins/scan
 * Driver scans passenger QR boarding pass:
 * 1. Validates server-signed QR code.
 * 2. Validates passenger, subscription (ACTIVE), payment (PAID), expiry.
 * 3. Enforces vehicle capacity inside an atomic transaction (prevents race conditions).
 * 4. Checks duplicate check-in on this trip.
 * 5. Records check-in and updates vehicle occupancy.
 */
router.post('/scan', authenticate, requireRole('DRIVER'), async (req, res) => {
  try {
    const driverId = req.user.id;
    const {
      qrToken,
      tripId,
      currentStop = 'Bole Medhanialem',
      vehicleId
    } = req.body;

    if (!qrToken) {
      return res.status(400).json({
        success: false,
        status: 'INVALID_QR',
        error: 'QR boarding pass token is required.'
      });
    }

    // 1. Locate Driver's Vehicle
    const driverUser = await DB.prepare('SELECT assignedVehiclePlate FROM users WHERE id = ?').get(driverId);
    let vehicle;
    if (vehicleId) {
      vehicle = await DB.prepare('SELECT * FROM vehicles WHERE id = ?').get(vehicleId);
    } else {
      vehicle = await DB.prepare('SELECT * FROM vehicles WHERE plateNumber = ? OR driverId = ? LIMIT 1')
        .get(driverUser?.assignedVehiclePlate || '', driverId);
    }

    const assignedToDriver = vehicle && vehicle.driverId === driverId;
    if (vehicle && !assignedToDriver) {
      return res.status(403).json({ success: false, status: 'VEHICLE_NOT_ASSIGNED', error: 'This vehicle is not assigned to your driver account.' });
    }

    if (!vehicle) {
      return res.status(404).json({
        success: false,
        status: 'VEHICLE_NOT_FOUND',
        error: 'No active vehicle registered for this driver.'
      });
    }

    if (!tripId || typeof tripId !== 'string') {
      return res.status(400).json({ success: false, status: 'TRIP_REQUIRED', error: 'An active trip ID is required to board a passenger.' });
    }
    const trip = await DB.prepare("SELECT * FROM trips WHERE id = ? AND status = 'IN_PROGRESS'").get(tripId);
    if (!trip || trip.driverId !== driverId || trip.vehicleId !== vehicle.id) {
      return res.status(403).json({ success: false, status: 'TRIP_NOT_ASSIGNED', error: 'This active trip is not assigned to your driver and vehicle.' });
    }

    // 2. Validate QR authenticity & Subscription
    let sub = await DB.prepare('SELECT * FROM subscriptions WHERE qrToken = ?').get(qrToken);

    if (!sub && qrToken.startsWith('RP1:')) {
      const verified = verifySignedQrToken(qrToken);
      if (verified.valid) {
        sub = await DB.prepare('SELECT * FROM subscriptions WHERE id = ?').get(verified.subId);
      }
    }

    if (!sub) {
      return res.status(404).json({
        success: false,
        status: 'NOT_SUBSCRIBED',
        error: 'Passenger is not subscribed. Please pay and subscribe for the selected route.',
        errorAm: 'ተሳፋሪው አልተመዘገበም። እባክዎ ለተመረጠው መስመር በቴሌብር ከፍለው ይመዝገቡ።'
      });
    }

    // Subscription status and payment verification
    if (sub.routeId !== trip.routeId) {
      return res.status(403).json({ success: false, status: 'ROUTE_MISMATCH', error: 'Passenger subscription route does not match the active trip.' });
    }
    if (sub.subscriptionStatus !== 'ACTIVE' || sub.paymentStatus !== 'PAID' || sub.daysRemaining <= 0) {
      return res.status(403).json({
        success: false,
        status: 'SUBSCRIPTION_EXPIRED',
        error: 'Passenger pass is inactive or expired. Please pay and subscribe for the selected route.',
        errorAm: 'የተሳፋሪው ፈቃድ አልቋል ወይም አልተከፈለም። እባክዎ በቴሌብር ከፍለው ያድሱ።'
      });
    }

    // Check duplicate check-in on this trip
    const alreadyBoarded = await DB.prepare("SELECT id FROM checkin_records WHERE tripId = ? AND passengerId = ? AND status = 'BOARDED'").get(tripId, sub.passengerId);
    if (alreadyBoarded) {
      return res.status(409).json({
        success: false,
        status: 'ALREADY_CHECKED_IN',
        error: 'Passenger has already boarded this scheduled trip.'
      });
    }

    // 3. ATOMIC TRANSACTION: Check Capacity & Board Passenger
    const result = await DB.transaction(async (tx) => {
      // Re-read vehicle occupancy inside transaction
      const currentVehicle = await tx.prepare(tx.isPostgres ? 'SELECT * FROM vehicles WHERE id = ? FOR UPDATE' : 'SELECT * FROM vehicles WHERE id = ?').get(vehicle.id);
      if (!currentVehicle || currentVehicle.driverId !== driverId) {
        return { allowed: false, reason: 'VEHICLE_NOT_ASSIGNED' };
      }
      const currentTrip = await tx.prepare(tx.isPostgres
        ? 'SELECT * FROM trips WHERE id = ? FOR UPDATE'
        : 'SELECT * FROM trips WHERE id = ?').get(tripId);
      if (!currentTrip || currentTrip.status !== 'IN_PROGRESS' ||
          currentTrip.driverId !== driverId || currentTrip.vehicleId !== currentVehicle.id ||
          currentTrip.routeId !== sub.routeId) {
        return { allowed: false, reason: 'TRIP_NOT_ACTIVE' };
      }
      // Re-check inside the same transaction so two concurrent scans cannot board the same
      // passenger twice even when they arrived before either request committed.
      const duplicate = await tx.prepare("SELECT id FROM checkin_records WHERE tripId = ? AND passengerId = ? AND status = 'BOARDED' LIMIT 1").get(tripId, sub.passengerId);
      if (duplicate) {
        return { allowed: false, reason: 'ALREADY_CHECKED_IN' };
      }
      const capacity = currentVehicle.capacityLimit || VEHICLE_TYPE_CAPACITIES[currentVehicle.vehicleType] || 24;

      if (currentVehicle.currentOccupancy >= capacity) {
        // Record denied scan
        const checkinId = `chk_denied_${crypto.randomUUID().slice(0, 8)}`;
        await tx.prepare(`
          INSERT INTO checkin_records (id, tripId, passengerId, passengerName, routeId, stopName, status, vehicleId, driverId)
          VALUES (?, ?, 'UNKNOWN', 'Passenger', ?, ?, 'DENIED_CAPACITY_FULL', ?, ?)
        `).run(checkinId, tripId, currentVehicle.assignedRouteId || 'route_bole_merkato', currentStop, currentVehicle.id, driverId);

        return {
          allowed: false,
          currentOccupancy: currentVehicle.currentOccupancy,
          capacity
        };
      }

      // Passenger info
      const passenger = await tx.prepare('SELECT fullName, phone FROM users WHERE id = ?').get(sub.passengerId);
      const passengerName = passenger?.fullName || 'Verified Commuter';
      const checkinId = `chk_${crypto.randomUUID().slice(0, 8)}`;

      // Record successful check-in
      await tx.prepare(`
        INSERT INTO checkin_records (id, tripId, passengerId, passengerName, routeId, stopName, status, vehicleId, driverId)
        VALUES (?, ?, ?, ?, ?, ?, 'BOARDED', ?, ?)
      `).run(checkinId, tripId, sub.passengerId, passengerName, sub.routeId, currentStop, currentVehicle.id, driverId);

      // Increment vehicle occupancy
      const newOccupancy = currentVehicle.currentOccupancy + 1;
      const isNowFull = newOccupancy >= capacity;

      await tx.prepare(`
        UPDATE vehicles
        SET currentOccupancy = ?, status = ?, updatedAt = CURRENT_TIMESTAMP
        WHERE id = ?
      `).run(newOccupancy, isNowFull ? 'FULL' : 'IN_SERVICE', currentVehicle.id);

      // Update active trip occupancy if trip exists
      await tx.prepare(`
        UPDATE trips
        SET currentOccupancy = ?, currentStop = ?, updatedAt = CURRENT_TIMESTAMP
        WHERE id = ?
      `).run(newOccupancy, currentStop, tripId);

      return {
        allowed: true,
        checkinId,
        passengerName,
        passengerPhone: passenger?.phone || '',
        newOccupancy,
        capacity,
        isNowFull
      };
    });

    if (!result.allowed && result.reason === 'VEHICLE_NOT_ASSIGNED') {
      return res.status(403).json({
        success: false,
        status: 'VEHICLE_NOT_ASSIGNED',
        error: 'Vehicle assignment changed; refresh the assigned vehicle and retry.'
      });
    }
    if (!result.allowed && result.reason === 'TRIP_NOT_ACTIVE') {
      return res.status(409).json({
        success: false,
        status: 'TRIP_NOT_ACTIVE',
        error: 'The trip ended or changed during this check-in. Refresh and retry only on the assigned active trip.'
      });
    }
    if (!result.allowed && result.reason === 'ALREADY_CHECKED_IN') {
      return res.status(409).json({
        success: false,
        status: 'ALREADY_CHECKED_IN',
        error: 'Passenger has already boarded this scheduled trip.'
      });
    }
    if (!result.allowed) {
      return res.status(409).json({
        success: false,
        status: 'DENIED_CAPACITY_FULL',
        error: `VEHICLE FULL: Capacity limit reached (${result.currentOccupancy}/${result.capacity} seats). Cannot board additional passengers based on ${vehicle.vehicleType} vehicle limit.`,
        errorAm: `ተሽከርካሪው ሞልቷል፡ የተሳፋሪ ገደብ ተደርሷል (${result.currentOccupancy}/${result.capacity})። ተጨማሪ ተሳፋሪ መጫን አይቻልም።`,
        vehicle: {
          plateNumber: vehicle.plateNumber,
          vehicleType: vehicle.vehicleType,
          capacityLimit: result.capacity,
          currentOccupancy: result.currentOccupancy
        }
      });
    }

    // Persist a passenger-facing boarding notice as well as a real-time event.
    const boardingNoticeId = `notif_${crypto.randomUUID()}`;
    await DB.prepare('INSERT INTO notifications (id, title, message, targetAudience, type, senderName) VALUES (?, ?, ?, ?, ?, ?)')
      .run(boardingNoticeId, 'Boarding confirmed', `${result.passengerName}, your boarding has been confirmed on vehicle ${vehicle.plateNumber} at ${currentStop}.`, 'PASSENGERS', 'SERVICE', 'RoutePass Boarding');

    // Broadcast check-in event to WebSockets
    if (req.app.locals.broadcastWs) {
      req.app.locals.broadcastWs({
        type: 'PASSENGER_BOARDED',
        checkinId: result.checkinId,
        passengerId: sub.passengerId,
        passengerName: result.passengerName,
        stopName: currentStop,
        vehicleId: vehicle.id,
        currentOccupancy: result.newOccupancy,
        capacityLimit: result.capacity,
        isFull: result.isNowFull
      });
    }

    res.json({
      success: true,
      status: 'VERIFIED_BOARDED',
      message: 'Commuter boarding pass verified. Boarding granted.',
      messageAm: 'የተሳፋሪው ፈቃድ ተረጋግጧል፡ መሳፈር ተፈቅዷል!',
      passenger: {
        id: sub.passengerId,
        name: result.passengerName,
        phone: result.passengerPhone,
        subscriptionId: sub.id,
        daysRemaining: sub.daysRemaining
      },
      boardingDetails: {
        checkinId: result.checkinId,
        tripId,
        stopName: currentStop,
        timestamp: new Date().toISOString()
      },
      occupancy: {
        current: result.newOccupancy,
        maxCapacity: result.capacity,
        availableSeats: Math.max(0, result.capacity - result.newOccupancy),
        isFull: result.isNowFull
      }
    });
  } catch (err) {
    console.error('[RoutePass] request failed:', err);
    res.status(500).json({ success: false, error: 'Internal server error.' });
  }
});

/**
 * GET /api/checkins/recent
 * Retrieve recent boarding logs
 */
router.get('/recent', authenticate, requireRole('ADMIN'), async (req, res) => {
  try {
    const list = await DB.prepare(`
      SELECT c.*, v.plateNumber as vehiclePlate
      FROM checkin_records c
      LEFT JOIN vehicles v ON c.vehicleId = v.id
      ORDER BY c.timestamp DESC LIMIT 50
    `).all();

    res.json({ success: true, checkins: list });
  } catch (err) {
    console.error('[RoutePass] request failed:', err);
    res.status(500).json({ success: false, error: 'Internal server error.' });
  }
});

/**
 * GET /api/checkins/trip/:tripId
 * Get passenger list for a specific trip
 */
router.get('/trip/:tripId', authenticate, requireRole('ADMIN', 'DRIVER'), async (req, res) => {
  try {
    const { tripId } = req.params;
    if (req.user.role !== 'ADMIN') {
      const authorizedTrip = await DB.prepare('SELECT id FROM trips WHERE id = ? AND driverId = ?').get(tripId, req.user.id);
      if (!authorizedTrip) {
        return res.status(403).json({ success: false, error: 'Access denied for this trip.' });
      }
    }
    const records = await DB.prepare(`
      SELECT * FROM checkin_records
      WHERE tripId = ? AND status = 'BOARDED'
      ORDER BY timestamp ASC
    `).all(tripId);

    res.json({ success: true, tripId, count: records.length, passengers: records });
  } catch (err) {
    console.error('[RoutePass] request failed:', err);
    res.status(500).json({ success: false, error: 'Internal server error.' });
  }
});

module.exports = router;
