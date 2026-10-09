const express = require('express');
const router = express.Router();
const { DB } = require('../db');
const { authenticate, requireRole } = require('../middleware/auth');

// Vehicle Type Standard Capacities
const VEHICLE_TYPE_CAPACITIES = {
  MINIVAN_8: 8,
  MINIBUS_14: 14,
  HIGER_24: 24,
  ANBESSA_BUS_30: 30
};

/**
 * GET /api/vehicles
 * List all active vehicles with capacity metrics
 */
router.get('/', async (req, res) => {
  try {
    const vehicles = await DB.prepare(`
      SELECT v.*, r.name as routeName, r.nameAm as routeNameAm
      FROM vehicles v
      LEFT JOIN routes r ON v.assignedRouteId = r.id
      ORDER BY v.plateNumber ASC
    `).all();

    res.json({
      success: true,
      count: vehicles.length,
      vehicles: vehicles.map(v => ({
        ...v,
        isFull: v.currentOccupancy >= v.capacityLimit,
        availableSeats: Math.max(0, v.capacityLimit - v.currentOccupancy),
        occupancyPercentage: Math.round((v.currentOccupancy / v.capacityLimit) * 100)
      }))
    });
  } catch (err) {
    console.error('[RoutePass] request failed:', err);
    res.status(500).json({ success: false, error: 'Internal server error.' });
  }
});

/**
 * GET /api/vehicles/tracking
 * Return only the caller's owned vehicle (driver) or the vehicle for the caller's active
 * paid subscription (passenger). Only genuine GPS reports are returned as a live location.
 */
router.get('/tracking', authenticate, async (req, res) => {
  try {
    let row = null;
    if (req.user.role === 'DRIVER') {
      row = await DB.prepare(`
        SELECT v.*, r.name AS routeName, l.latitude, l.longitude, l.speed,
               l.current_stop AS currentStop, l.updated_at AS lastGpsAt
        FROM vehicles v
        LEFT JOIN routes r ON r.id = v.assignedRouteId
        LEFT JOIN vehicle_live_locations l ON l.vehicle_id = v.id
        WHERE v.driverId = ?
        ORDER BY v.updatedAt DESC LIMIT 1
      `).get(req.user.id);
    } else if (req.user.role === 'PASSENGER') {
      const sub = await DB.prepare(`
        SELECT * FROM subscriptions
        WHERE passengerId = ? AND subscriptionStatus = 'ACTIVE'
          AND paymentStatus = 'PAID' AND daysRemaining > 0
        ORDER BY updatedAt DESC LIMIT 1
      `).get(req.user.id);
      if (!sub) {
        return res.json({ success: true, vehicle: null, message: 'An active paid subscription is required to track a vehicle.' });
      }

      if (sub.vehicleId) {
        row = await DB.prepare(`
          SELECT v.*, r.name AS routeName, l.latitude, l.longitude, l.speed,
                 l.current_stop AS currentStop, l.updated_at AS lastGpsAt
          FROM vehicles v
          LEFT JOIN routes r ON r.id = v.assignedRouteId
          LEFT JOIN vehicle_live_locations l ON l.vehicle_id = v.id
          WHERE v.id = ? AND v.assignedRouteId = ? AND v.driverId IS NOT NULL
          LIMIT 1
        `).get(sub.vehicleId, sub.routeId);
      } else {
        return res.json({
          success: true,
          vehicle: null,
          message: 'The administrator has not assigned a driver-owned vehicle to your subscription yet.'
        });
      }
    } else {
      return res.status(403).json({ success: false, error: 'Only drivers and passengers can access vehicle tracking.' });
    }

    if (!row) return res.json({ success: true, vehicle: null, message: 'No driver vehicle is assigned yet.' });
    res.json({
      success: true,
      vehicle: {
        id: row.id,
        plateNumber: row.plateNumber,
        model: row.model,
        vehicleType: row.vehicleType,
        capacityLimit: row.capacityLimit,
        currentOccupancy: row.currentOccupancy,
        assignedRouteId: row.assignedRouteId,
        routeName: row.routeName || '',
        driverId: row.driverId,
        driverName: row.driverName || '',
        latitude: row.latitude == null ? null : Number(row.latitude),
        longitude: row.longitude == null ? null : Number(row.longitude),
        speed: row.speed == null ? null : Number(row.speed),
        currentStop: row.currentStop || '',
        lastGpsAt: row.lastGpsAt || null,
        hasGpsLocation: row.latitude != null && row.longitude != null && Boolean(row.lastGpsAt)
      }
    });
  } catch (err) {
    console.error('[RoutePass] vehicle tracking query failed:', err);
    res.status(500).json({ success: false, error: 'Could not load vehicle tracking.' });
  }
});

/**
 * GET /api/vehicles/:id
 * Get single vehicle details
 */
router.get('/:id', async (req, res) => {
  try {
    const vehicle = await DB.prepare(`
      SELECT v.*, r.name as routeName, r.nameAm as routeNameAm
      FROM vehicles v
      LEFT JOIN routes r ON v.assignedRouteId = r.id
      WHERE v.id = ?
    `).get(req.params.id);

    if (!vehicle) {
      return res.status(404).json({ success: false, error: 'Vehicle not found' });
    }
    res.json({
      success: true,
      vehicle: {
        ...vehicle,
        isFull: vehicle.currentOccupancy >= vehicle.capacityLimit,
        availableSeats: Math.max(0, vehicle.capacityLimit - vehicle.currentOccupancy),
        occupancyPercentage: Math.round((vehicle.currentOccupancy / vehicle.capacityLimit) * 100)
      }
    });
  } catch (err) {
    console.error('[RoutePass] request failed:', err);
    res.status(500).json({ success: false, error: 'Internal server error.' });
  }
});

/**
 * PATCH /api/vehicles/:id/type
 * Driver or Admin: Update vehicle type and enforce passenger limit
 */
router.patch('/:id/type', authenticate, requireRole('ADMIN'), async (req, res) => {
  try {
    const { vehicleType, capacityLimit } = req.body;
    const vehicle = await DB.prepare('SELECT * FROM vehicles WHERE id = ?').get(req.params.id);

    if (!vehicle) {
      return res.status(404).json({ success: false, error: 'Vehicle not found' });
    }

    const standardCapacity = capacityLimit !== undefined ? parseInt(capacityLimit, 10) : (VEHICLE_TYPE_CAPACITIES[vehicleType] || 14);
    const newOccupancy = req.body.currentOccupancy !== undefined ? parseInt(req.body.currentOccupancy, 10) : Math.min(vehicle.currentOccupancy, standardCapacity);
    const newStatus = newOccupancy >= standardCapacity ? 'FULL' : 'IN_SERVICE';

    await DB.prepare(`
      UPDATE vehicles
      SET vehicleType = ?, capacityLimit = ?, currentOccupancy = ?, status = ?, updatedAt = CURRENT_TIMESTAMP
      WHERE id = ?
    `).run(vehicleType, standardCapacity, newOccupancy, newStatus, req.params.id);

    await DB.prepare(`
      INSERT INTO audit_logs (action, userId, role, details)
      VALUES ('VEHICLE_TYPE_UPDATED', ?, ?, ?)
    `).run(req.user.id, req.user.role, `Updated vehicle ${vehicle.plateNumber} to ${vehicleType} (Limit: ${standardCapacity} seats)`);

    const updated = await DB.prepare('SELECT * FROM vehicles WHERE id = ?').get(req.params.id);

    res.json({
      success: true,
      message: `Vehicle capacity limit set to ${standardCapacity} passengers based on ${vehicleType}.`,
      vehicle: updated
    });
  } catch (err) {
    console.error('[RoutePass] request failed:', err);
    res.status(500).json({ success: false, error: 'Internal server error.' });
  }
});

/**
 * POST /api/vehicles/my-location
 * The signed-in driver reports the GPS location of their own registered vehicle.
 */
router.post('/my-location', authenticate, requireRole('DRIVER'), async (req, res) => {
  try {
    const { latitude, longitude, speed = 0, currentStop = '' } = req.body;
    const lat = Number(latitude);
    const lng = Number(longitude);
    const velocity = Number(speed || 0);
    if (!Number.isFinite(lat) || lat < -90 || lat > 90 ||
        !Number.isFinite(lng) || lng < -180 || lng > 180 ||
        !Number.isFinite(velocity) || velocity < 0 || velocity > 300) {
      return res.status(400).json({ success: false, error: 'Valid latitude, longitude and speed are required.' });
    }

    const vehicle = await DB.prepare('SELECT * FROM vehicles WHERE driverId = ? LIMIT 1').get(req.user.id);
    if (!vehicle) return res.status(409).json({ success: false, error: 'Register your own vehicle before sharing GPS.' });

    await DB.prepare(`
      INSERT INTO vehicle_live_locations (vehicle_id, latitude, longitude, speed, current_stop, updated_at)
      VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
      ON CONFLICT (vehicle_id) DO UPDATE SET
        latitude = excluded.latitude,
        longitude = excluded.longitude,
        speed = excluded.speed,
        current_stop = excluded.current_stop,
        updated_at = CURRENT_TIMESTAMP
    `).run(vehicle.id, lat, lng, velocity, String(currentStop || '').slice(0, 100));

    await DB.prepare('UPDATE vehicles SET currentLat = ?, currentLng = ?, updatedAt = CURRENT_TIMESTAMP WHERE id = ?')
      .run(lat, lng, vehicle.id);

    if (req.app.locals.broadcastWs) {
      req.app.locals.broadcastWs({
        type: 'VEHICLE_LOCATION_UPDATE',
        vehicleId: vehicle.id,
        routeId: vehicle.assignedRouteId || '',
        latitude: lat,
        longitude: lng,
        speed: velocity,
        currentStop: String(currentStop || '').slice(0, 100),
        timestamp: new Date().toISOString()
      }, vehicle.assignedRouteId || null);
    }

    res.json({ success: true, vehicleId: vehicle.id, latitude: lat, longitude: lng, timestamp: new Date().toISOString() });
  } catch (err) {
    console.error('[RoutePass] driver GPS update failed:', err);
    res.status(500).json({ success: false, error: 'Could not update GPS location.' });
  }
});

/**
 * POST /api/vehicles/:id/location
 * Driver: Send GPS location telemetry (broadcasts to WebSocket clients)
 */
router.post('/:id/location', authenticate, requireRole('DRIVER'), async (req, res) => {
  try {
    const { latitude, longitude, speed = 0, currentStop = '' } = req.body;
    const vehicle = await DB.prepare('SELECT * FROM vehicles WHERE id = ?').get(req.params.id);
    if (!vehicle) return res.status(404).json({ success: false, error: 'Vehicle not found.' });
    if (vehicle.driverId !== req.user.id) {
      return res.status(403).json({ success: false, error: 'Vehicle is not assigned to this driver.' });
    }

    if (latitude === undefined || longitude === undefined || !Number.isFinite(Number(latitude)) || !Number.isFinite(Number(longitude)) || Number(latitude) < -90 || Number(latitude) > 90 || Number(longitude) < -180 || Number(longitude) > 180) {
      return res.status(400).json({ success: false, error: 'Latitude and longitude are required.' });
    }

    await DB.prepare(`
      UPDATE vehicles
      SET currentLat = ?, currentLng = ?, updatedAt = CURRENT_TIMESTAMP
      WHERE id = ?
    `).run(parseFloat(latitude), parseFloat(longitude), req.params.id);

    // Broadcast telemetry via global ws broadcast if available
    if (req.app.locals.broadcastWs) {
      req.app.locals.broadcastWs({
        type: 'VEHICLE_LOCATION_UPDATE',
        vehicleId: req.params.id,
        latitude: parseFloat(latitude),
        longitude: parseFloat(longitude),
        speed: Number.isFinite(Number(speed)) && Number(speed) >= 0 && Number(speed) <= 300 ? Number(speed) : 0,
        currentStop,
        timestamp: new Date().toISOString()
      });
    }

    res.json({
      success: true,
      message: 'Telemetry updated successfully',
      location: { latitude, longitude }
    });
  } catch (err) {
    console.error('[RoutePass] request failed:', err);
    res.status(500).json({ success: false, error: 'Internal server error.' });
  }
});

module.exports = router;
