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
    if (req.user.role === 'DRIVER' && !(vehicle.driverId === req.user.id || (!vehicle.driverId && driver?.assignedVehiclePlate === vehicle.plateNumber))) {
      const driver = await DB.prepare('SELECT assignedVehiclePlate FROM users WHERE id = ?').get(req.user.id);
      if (!driver || driver.assignedVehiclePlate !== vehicle.plateNumber) {
        return res.status(403).json({ success: false, error: 'Vehicle is not assigned to this driver.' });
      }
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
router.patch('/:id/type', authenticate, requireRole('DRIVER', 'ADMIN'), async (req, res) => {
  try {
    const { vehicleType, capacityLimit } = req.body;
    const vehicle = await DB.prepare('SELECT * FROM vehicles WHERE id = ?').get(req.params.id);

    if (!vehicle) {
      return res.status(404).json({ success: false, error: 'Vehicle not found' });
    }

    if (req.user.role === 'DRIVER') {
      const driver = await DB.prepare('SELECT assignedVehiclePlate FROM users WHERE id = ?').get(req.user.id);
      const assigned = vehicle.driverId === req.user.id ||
        (!vehicle.driverId && driver?.assignedVehiclePlate === vehicle.plateNumber);
      if (!assigned) return res.status(403).json({ success: false, error: 'Vehicle is not assigned to this driver.' });
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
 * POST /api/vehicles/:id/location
 * Driver: Send GPS location telemetry (broadcasts to WebSocket clients)
 */
router.post('/:id/location', authenticate, requireRole('DRIVER'), async (req, res) => {
  try {
    const { latitude, longitude, speed = 0, currentStop = '' } = req.body;
    const vehicle = await DB.prepare('SELECT * FROM vehicles WHERE id = ?').get(req.params.id);
    if (!vehicle) return res.status(404).json({ success: false, error: 'Vehicle not found.' });
    const driver = await DB.prepare('SELECT assignedVehiclePlate FROM users WHERE id = ?').get(req.user.id);
    if (!(vehicle.driverId === req.user.id || (!vehicle.driverId && driver?.assignedVehiclePlate && vehicle.plateNumber === driver.assignedVehiclePlate))) {
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
        speed: parseFloat(speed),
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
