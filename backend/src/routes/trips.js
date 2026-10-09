const express = require('express');
const router = express.Router();
const crypto = require('crypto');
const { DB } = require('../db');
const { authenticate, requireRole } = require('../middleware/auth');

/**
 * POST /api/trips/start
 * Driver starts a scheduled transit trip on a route.
 */
router.post('/start', authenticate, requireRole('DRIVER'), async (req, res) => {
  try {
    const driverId = req.user.id;
    const { routeId, direction = 'OUTBOUND', vehicleId } = req.body;

    if (!routeId) {
      return res.status(400).json({ success: false, error: 'Route ID is required to start a trip.' });
    }

    // Identify vehicle
    const driverUser = await DB.prepare('SELECT assignedVehiclePlate FROM users WHERE id = ?').get(driverId);
    let vehicle;
    if (vehicleId) {
      vehicle = await DB.prepare('SELECT * FROM vehicles WHERE id = ?').get(vehicleId);
    } else {
      vehicle = await DB.prepare('SELECT * FROM vehicles WHERE plateNumber = ? OR driverId = ? LIMIT 1')
        .get(driverUser?.assignedVehiclePlate || '', driverId);
    }

    if (!vehicle) {
      return res.status(404).json({ success: false, error: 'Vehicle not found for driver.' });
    }
    if (vehicle.driverId !== driverId) {
      return res.status(403).json({ success: false, error: 'This vehicle is not assigned to your driver account.' });
    }
    if (vehicle.assignedRouteId && vehicle.assignedRouteId !== routeId) {
      return res.status(403).json({ success: false, error: 'The requested route is not assigned to this vehicle.' });
    }

    const existingTrip = await DB.prepare("SELECT id FROM trips WHERE vehicleId = ? AND status = 'IN_PROGRESS' LIMIT 1").get(vehicle.id);
    if (existingTrip) {
      return res.status(409).json({ success: false, error: 'This vehicle already has an active trip.' });
    }

    // Get initial route stop
    const firstStop = await DB.prepare('SELECT stopName FROM route_stops WHERE routeId = ? ORDER BY stopOrder ASC LIMIT 1').get(routeId);
    const initialStop = firstStop?.stopName || 'Terminal Hub';

    const tripId = `trip_${Date.now().toString(36)}_${crypto.randomUUID().slice(0, 4)}`;

    // Reset vehicle occupancy and mark in service
    await DB.prepare(`
      UPDATE vehicles
      SET currentOccupancy = 0, status = 'IN_SERVICE', assignedRouteId = ?, updatedAt = CURRENT_TIMESTAMP
      WHERE id = ?
    `).run(routeId, vehicle.id);

    // Insert new active trip
    await DB.prepare(`
      INSERT INTO trips (id, driverId, vehicleId, routeId, direction, currentStop, currentOccupancy, status)
      VALUES (?, ?, ?, ?, ?, ?, 0, 'IN_PROGRESS')
    `).run(tripId, driverId, vehicle.id, routeId, direction, initialStop);

    // Broadcast trip start
    if (req.app.locals.broadcastWs) {
      req.app.locals.broadcastWs({
        type: 'TRIP_STARTED',
        tripId,
        routeId,
        vehicleId: vehicle.id,
        plateNumber: vehicle.plateNumber,
        direction,
        currentStop: initialStop,
        timestamp: new Date().toISOString()
      });
    }

    res.json({
      success: true,
      message: 'Trip successfully started.',
      trip: {
        id: tripId,
        driverId,
        vehicleId: vehicle.id,
        plateNumber: vehicle.plateNumber,
        routeId,
        direction,
        currentStop: initialStop,
        currentOccupancy: 0,
        capacityLimit: vehicle.capacityLimit,
        status: 'IN_PROGRESS',
        startTime: new Date().toISOString()
      }
    });
  } catch (err) {
    console.error('[RoutePass] request failed:', err);
    res.status(500).json({ success: false, error: 'Internal server error.' });
  }
});

/**
 * GET /api/trips/active
 * Get active trip for the logged-in driver.
 */
router.get('/active', authenticate, requireRole('DRIVER'), async (req, res) => {
  try {
    const driverId = req.user.id;
    const trip = await DB.prepare(`
      SELECT t.*, v.plateNumber, v.vehicleType, v.capacityLimit, r.name as routeName, r.nameAm as routeNameAm
      FROM trips t
      JOIN vehicles v ON t.vehicleId = v.id
      JOIN routes r ON t.routeId = r.id
      WHERE t.driverId = ? AND t.status = 'IN_PROGRESS'
      ORDER BY t.startTime DESC LIMIT 1
    `).get(driverId);

    if (!trip) {
      return res.json({ success: true, hasActiveTrip: false, trip: null });
    }

    res.json({
      success: true,
      hasActiveTrip: true,
      trip: {
        id: trip.id,
        driverId: trip.driverId,
        vehicleId: trip.vehicleId,
        plateNumber: trip.plateNumber,
        vehicleType: trip.vehicleType,
        capacityLimit: trip.capacityLimit,
        routeId: trip.routeId,
        routeName: trip.routeName,
        routeNameAm: trip.routeNameAm,
        direction: trip.direction,
        currentStop: trip.currentStop,
        currentOccupancy: trip.currentOccupancy,
        status: trip.status,
        startTime: trip.startTime
      }
    });
  } catch (err) {
    console.error('[RoutePass] request failed:', err);
    res.status(500).json({ success: false, error: 'Internal server error.' });
  }
});

/**
 * POST /api/trips/:id/stop-arrival
 * Driver records arrival at a scheduled route stop.
 */
router.post('/:id/stop-arrival', authenticate, requireRole('DRIVER'), async (req, res) => {
  try {
    const { id } = req.params;
    const { stopName } = req.body;

    if (!stopName) {
      return res.status(400).json({ success: false, error: 'Stop name is required.' });
    }

    const trip = await DB.prepare('SELECT * FROM trips WHERE id = ?').get(id);
    if (!trip) {
      return res.status(404).json({ success: false, error: 'Trip not found.' });
    }
    if (trip.driverId !== req.user.id && req.user.role !== 'ADMIN') {
      return res.status(403).json({ success: false, error: 'Access denied for this trip.' });
    }

    await DB.prepare(`
      UPDATE trips SET currentStop = ?, updatedAt = CURRENT_TIMESTAMP WHERE id = ?
    `).run(stopName, id);

    // Broadcast stop arrival to all commuters on route
    if (req.app.locals.broadcastWs) {
      req.app.locals.broadcastWs({
        type: 'VEHICLE_STOP_ARRIVAL',
        tripId: id,
        vehicleId: trip.vehicleId,
        routeId: trip.routeId,
        stopName,
        timestamp: new Date().toISOString()
      });
    }

    res.json({
      success: true,
      message: `Arrived at stop: ${stopName}`,
      tripId: id,
      currentStop: stopName
    });
  } catch (err) {
    console.error('[RoutePass] request failed:', err);
    res.status(500).json({ success: false, error: 'Internal server error.' });
  }
});

/**
 * POST /api/trips/:id/end
 * End trip and finalize stats
 */
router.post('/:id/end', authenticate, requireRole('DRIVER'), async (req, res) => {
  try {
    const { id } = req.params;
    const trip = await DB.prepare('SELECT * FROM trips WHERE id = ?').get(id);

    if (!trip) {
      return res.status(404).json({ success: false, error: 'Trip not found.' });
    }
    if (trip.driverId !== req.user.id) {
      return res.status(403).json({ success: false, error: 'Access denied for this trip.' });
    }
    if (trip.status !== 'IN_PROGRESS') {
      return res.status(409).json({ success: false, error: 'This trip is not active.' });
    }

    await DB.prepare(`
      UPDATE trips SET status = 'COMPLETED', endTime = CURRENT_TIMESTAMP, updatedAt = CURRENT_TIMESTAMP WHERE id = ?
    `).run(id);

    // Reset vehicle occupancy
    await DB.prepare(`
      UPDATE vehicles SET currentOccupancy = 0, status = 'IN_SERVICE', updatedAt = CURRENT_TIMESTAMP WHERE id = ?
    `).run(trip.vehicleId);

    if (req.app.locals.broadcastWs) {
      req.app.locals.broadcastWs({
        type: 'TRIP_COMPLETED',
        tripId: id,
        vehicleId: trip.vehicleId,
        timestamp: new Date().toISOString()
      });
    }

    res.json({
      success: true,
      message: 'Trip completed successfully.',
      tripId: id
    });
  } catch (err) {
    console.error('[RoutePass] request failed:', err);
    res.status(500).json({ success: false, error: 'Internal server error.' });
  }
});

module.exports = router;
