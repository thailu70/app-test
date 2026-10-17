const express = require('express');
const router = express.Router();
const { DB } = require('../db');
const { authenticate, requireRole, resolveActivePrincipal } = require('../middleware/auth');

// Vehicle Type Standard Capacities
const VEHICLE_TYPE_CAPACITIES = {
  MINIVAN_8: 8,
  MINIBUS_14: 14,
  HIGER_24: 24,
  ANBESSA_BUS_30: 30
};
const driverLastGpsTime = new Map();

function consumeGpsRateLimit(driverId) {
  const now = Date.now();
  const last = driverLastGpsTime.get(driverId) || 0;
  if (now - last < 950) return false;
  driverLastGpsTime.set(driverId, now);
  return true;
}

setInterval(() => {
  const cutoff = Date.now() - 60_000;
  for (const [driverId, timestamp] of driverLastGpsTime.entries()) {
    if (timestamp < cutoff) driverLastGpsTime.delete(driverId);
  }
}, 60_000).unref();

async function optionalPrincipal(req, res) {
  const authorization = req.headers.authorization;
  if (!authorization) return null;
  if (!authorization.startsWith('Bearer ')) {
    res.status(401).json({ success: false, error: 'Invalid authentication token.' });
    return false;
  }
  try {
    const principal = await resolveActivePrincipal(authorization.slice(7));
    if (!principal) {
      res.status(401).json({ success: false, error: 'Invalid, expired, or inactive account.' });
      return false;
    }
    return principal;
  } catch (err) {
    console.error('[Vehicles] optional auth check failed:', err);
    res.status(503).json({ success: false, error: 'Authentication is temporarily unavailable.' });
    return false;
  }
}

function publicVehicle(vehicle) {
  return {
    id: vehicle.id,
    plateNumber: vehicle.plateNumber,
    model: vehicle.model,
    vehicleType: vehicle.vehicleType,
    capacityLimit: vehicle.capacityLimit,
    currentOccupancy: vehicle.currentOccupancy,
    assignedRouteId: vehicle.assignedRouteId,
    routeName: vehicle.routeName,
    routeNameAm: vehicle.routeNameAm,
    status: vehicle.status,
    isFull: vehicle.currentOccupancy >= vehicle.capacityLimit,
    availableSeats: Math.max(0, vehicle.capacityLimit - vehicle.currentOccupancy),
    occupancyPercentage: vehicle.capacityLimit > 0 ? Math.round((vehicle.currentOccupancy / vehicle.capacityLimit) * 100) : 0
  };
}

async function persistLocation(vehicleId, latitude, longitude, speed, currentStop) {
  await DB.transaction(async (tx) => {
    await tx.prepare(`
      INSERT INTO vehicle_live_locations (vehicle_id, latitude, longitude, speed, current_stop, updated_at)
      VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
      ON CONFLICT (vehicle_id) DO UPDATE SET
        latitude = excluded.latitude,
        longitude = excluded.longitude,
        speed = excluded.speed,
        current_stop = excluded.current_stop,
        updated_at = CURRENT_TIMESTAMP
    `).run(vehicleId, latitude, longitude, speed, currentStop);
    await tx.prepare('UPDATE vehicles SET currentLat = ?, currentLng = ?, updatedAt = CURRENT_TIMESTAMP WHERE id = ?')
      .run(latitude, longitude, vehicleId);
  });
}

/**
 * GET /api/vehicles
 * List all active vehicles with capacity metrics
 */
router.get('/', async (req, res) => {
  try {
    const principal = await optionalPrincipal(req, res);
    if (principal === false) return;
    const isAdmin = principal?.role === 'ADMIN';
    const isCompany = principal?.role === 'COMPANY';
    const vehicles = await DB.prepare(`
      SELECT v.*, r.name as routeName, r.nameAm as routeNameAm
      FROM vehicles v
      LEFT JOIN routes r ON v.assignedRouteId = r.id
      ${isCompany ? 'WHERE v.companyId = ?' : ''}
      ORDER BY v.plateNumber ASC
    `).all(...(isCompany ? [principal.companyId] : []));

    res.json({
      success: true,
      count: vehicles.length,
      vehicles: vehicles.map(v => isAdmin || isCompany ? {
        ...v,
        isFull: v.currentOccupancy >= v.capacityLimit,
        availableSeats: Math.max(0, v.capacityLimit - v.currentOccupancy),
        occupancyPercentage: v.capacityLimit > 0 ? Math.round((v.currentOccupancy / v.capacityLimit) * 100) : 0
      } : publicVehicle(v))
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
        SELECT v.*, r.name AS routeName, r.morningDeparture AS morningDeparture, l.latitude, l.longitude, l.speed,
               l.current_stop AS currentStop, l.updated_at AS lastGpsAt
        FROM vehicles v
        JOIN trips t ON t.vehicleId = v.id AND t.status = 'IN_PROGRESS' AND t.driverId = ?
        LEFT JOIN routes r ON r.id = t.routeId
        LEFT JOIN vehicle_live_locations l ON l.vehicle_id = v.id
        WHERE v.driverId = ?
        ORDER BY t.startTime DESC LIMIT 1
      `).get(req.user.id, req.user.id);
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
          SELECT v.*, t.routeId AS activeRouteId, r.name AS routeName, r.morningDeparture AS morningDeparture, l.latitude, l.longitude, l.speed,
                 l.current_stop AS currentStop, l.updated_at AS lastGpsAt
          FROM vehicles v
          JOIN trips t ON t.vehicleId = v.id AND t.routeId = ? AND t.status = 'IN_PROGRESS'
          LEFT JOIN routes r ON r.id = t.routeId
          LEFT JOIN vehicle_live_locations l ON l.vehicle_id = v.id
          WHERE v.id = ? AND v.assignedRouteId = ? AND v.driverId IS NOT NULL
          LIMIT 1
        `).get(sub.routeId, sub.vehicleId, sub.routeId);
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
    const gpsAt = row.lastGpsAt ? Date.parse(row.lastGpsAt) : NaN;
    const hasFreshGps = Number.isFinite(gpsAt) && Date.now() - gpsAt <= 2 * 60 * 1000 && Date.now() >= gpsAt;
    res.json({
      success: true,
      vehicle: {
        id: row.id,
        plateNumber: row.plateNumber,
        model: row.model,
        vehicleType: row.vehicleType,
        capacityLimit: row.capacityLimit,
        currentOccupancy: row.currentOccupancy,
        assignedRouteId: row.activeRouteId || row.assignedRouteId,
        routeName: row.routeName || '',
        morningDeparture: row.morningDeparture || '06:30',
        driverId: row.driverId,
        driverName: row.driverName || '',
        latitude: !hasFreshGps || row.latitude == null ? null : Number(row.latitude),
        longitude: !hasFreshGps || row.longitude == null ? null : Number(row.longitude),
        speed: !hasFreshGps || row.speed == null ? null : Number(row.speed),
        currentStop: row.currentStop || '',
        lastGpsAt: row.lastGpsAt || null,
        hasGpsLocation: hasFreshGps && row.latitude != null && row.longitude != null
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
    const principal = await optionalPrincipal(req, res);
    if (principal === false) return;
    const isAdmin = principal?.role === 'ADMIN';
    const isCompany = principal?.role === 'COMPANY';
    const vehicle = await DB.prepare(`
      SELECT v.*, r.name as routeName, r.nameAm as routeNameAm
      FROM vehicles v
      LEFT JOIN routes r ON v.assignedRouteId = r.id
      WHERE v.id = ? ${isCompany ? 'AND v.companyId = ?' : ''}
    `).get(...(isCompany ? [req.params.id, principal.companyId] : [req.params.id]));

    if (!vehicle) {
      return res.status(404).json({ success: false, error: 'Vehicle not found' });
    }
    res.json({
      success: true,
      vehicle: isAdmin || isCompany ? {
        ...vehicle,
        isFull: vehicle.currentOccupancy >= vehicle.capacityLimit,
        availableSeats: Math.max(0, vehicle.capacityLimit - vehicle.currentOccupancy),
        occupancyPercentage: vehicle.capacityLimit > 0 ? Math.round((vehicle.currentOccupancy / vehicle.capacityLimit) * 100) : 0
      } : publicVehicle(vehicle)
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
    const trip = await DB.prepare("SELECT id, routeId FROM trips WHERE vehicleId=? AND driverId=? AND status='IN_PROGRESS' ORDER BY startTime DESC LIMIT 1").get(vehicle.id, req.user.id);
    if (!trip) return res.status(409).json({ success: false, error: 'Start an assigned trip before sharing live GPS.' });
    if (!consumeGpsRateLimit(req.user.id)) return res.status(429).json({ success: false, error: 'GPS updates are limited to one per second.' });
    const stop = String(currentStop || '').slice(0, 100);
    await persistLocation(vehicle.id, lat, lng, velocity, stop);

    if (req.app.locals.broadcastWs) {
      req.app.locals.broadcastWs({
        type: 'VEHICLE_LOCATION_UPDATE',
        vehicleId: vehicle.id,
        routeId: trip.routeId,
        driverId: req.user.id,
        companyId: vehicle.companyId || '',
        latitude: lat,
        longitude: lng,
        speed: velocity,
        currentStop: stop,
        timestamp: new Date().toISOString()
      }, trip.routeId);
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

    const velocity = Number(speed || 0);
    if (!Number.isFinite(velocity) || velocity < 0 || velocity > 300) {
      return res.status(400).json({ success: false, error: 'Speed must be between 0 and 300 km/h.' });
    }
    const trip = await DB.prepare("SELECT id, routeId FROM trips WHERE vehicleId=? AND driverId=? AND status='IN_PROGRESS' ORDER BY startTime DESC LIMIT 1").get(vehicle.id, req.user.id);
    if (!trip) return res.status(409).json({ success: false, error: 'Start an assigned trip before sharing live GPS.' });
    if (!consumeGpsRateLimit(req.user.id)) return res.status(429).json({ success: false, error: 'GPS updates are limited to one per second.' });
    const lat = Number(latitude), lng = Number(longitude), stop = String(currentStop || '').slice(0, 100);
    await persistLocation(vehicle.id, lat, lng, velocity, stop);

    // Broadcast telemetry via global ws broadcast if available
    if (req.app.locals.broadcastWs) {
      req.app.locals.broadcastWs({
        type: 'VEHICLE_LOCATION_UPDATE',
        vehicleId: vehicle.id,
        routeId: trip.routeId,
        driverId: req.user.id,
        companyId: vehicle.companyId || '',
        latitude: lat,
        longitude: lng,
        speed: velocity,
        currentStop: stop,
        timestamp: new Date().toISOString()
      }, trip.routeId);
    }

    res.json({
      success: true,
      message: 'Telemetry updated successfully',
      location: { latitude: lat, longitude: lng }
    });
  } catch (err) {
    console.error('[RoutePass] request failed:', err);
    res.status(500).json({ success: false, error: 'Internal server error.' });
  }
});

module.exports = router;
