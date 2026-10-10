const express = require('express');
const router = express.Router();
const crypto = require('crypto');
const { DB } = require('../db');
const { authenticate, requireRole } = require('../middleware/auth');

function distanceMeters(lat1, lon1, lat2, lon2) {
  const rad = value => value * Math.PI / 180;
  const dLat = rad(lat2 - lat1), dLon = rad(lon2 - lon1);
  const a = Math.sin(dLat / 2) ** 2 + Math.cos(rad(lat1)) * Math.cos(rad(lat2)) * Math.sin(dLon / 2) ** 2;
  return 6371000 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}

async function notifyPassengers(req, title, message, event) {
  const id = `notif_${crypto.randomUUID()}`;
  await DB.prepare('INSERT INTO notifications (id, title, message, targetAudience, type, senderName) VALUES (?, ?, ?, ?, ?, ?)')
    .run(id, title, message, 'PASSENGERS', 'SERVICE', 'RoutePass Dispatch');
  if (req.app.locals.broadcastWs) req.app.locals.broadcastWs(event);
}

/**
 * POST /api/trips/start
 * Driver starts a scheduled transit trip on a route.
 */
router.post('/start', authenticate, requireRole('DRIVER'), async (req, res) => {
  try {
    const driverId = req.user.id;
    const { routeId, direction = 'OUTBOUND', vehicleId, latitude, longitude, arrivalConfirmed } = req.body;
    if (!['OUTBOUND', 'INBOUND'].includes(String(direction).toUpperCase())) {
      return res.status(400).json({ success: false, error: 'Direction must be OUTBOUND (home to work/school) or INBOUND (work/school to home).' });
    }
    if (arrivalConfirmed !== true || !Number.isFinite(Number(latitude)) || !Number.isFinite(Number(longitude)) || Math.abs(Number(latitude)) > 90 || Math.abs(Number(longitude)) > 180) {
      return res.status(400).json({ success: false, code: 'ARRIVAL_CONFIRMATION_REQUIRED', error: 'Enable GPS, arrive at the assigned departure location, and confirm arrival before starting this route.' });
    }

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
      return res.status(404).json({ success: false, error: 'No vehicle registered to this driver was found.' });
    }
    if (vehicle.driverId !== driverId) {
      return res.status(403).json({ success: false, error: 'This vehicle is not owned by your driver account.' });
    }
    if (!vehicle.assignedRouteId) {
      return res.status(403).json({ success: false, error: 'Your vehicle has no assigned route yet. Contact the RoutePass administrator.' });
    }
    if (vehicle.assignedRouteId && vehicle.assignedRouteId !== routeId) {
      return res.status(403).json({ success: false, error: 'The requested route is not assigned to this vehicle.' });
    }

    const route = await DB.prepare("SELECT * FROM routes WHERE id = ? AND active = TRUE").get(routeId);
    if (!route) {
      return res.status(404).json({ success: false, error: 'Active route not found.' });
    }

    // Enforce the administrator-configured commute timetable in production. The allowed
    // grace windows are explicit server settings so operations can tune them intentionally.
    if (process.env.NODE_ENV === 'production' && process.env.ENFORCE_DEPARTURE_SCHEDULE !== 'false') {
      const scheduledTime = String(String(direction).toUpperCase() === 'INBOUND' ? route.eveningDeparture : route.morningDeparture || '').slice(0, 5);
      const match = /^(\\d{2}):(\\d{2})$/.exec(scheduledTime);
      if (!match || Number(match[1]) > 23 || Number(match[2]) > 59) {
        return res.status(409).json({ success: false, code: 'DEPARTURE_SCHEDULE_MISSING', error: 'The assigned route has no valid departure time. Contact the administrator.' });
      }
      const parts = new Intl.DateTimeFormat('en-GB', { timeZone: 'Africa/Addis_Ababa', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).formatToParts(new Date());
      const hour = Number(parts.find(part => part.type === 'hour')?.value);
      const minute = Number(parts.find(part => part.type === 'minute')?.value);
      const nowMinutes = hour * 60 + minute;
      const scheduleMinutes = Number(match[1]) * 60 + Number(match[2]);
      let difference = nowMinutes - scheduleMinutes;
      if (difference > 720) difference -= 1440;
      if (difference < -720) difference += 1440;
      const earlyWindow = Math.max(0, Number.parseInt(process.env.ROUTEPASS_DEPARTURE_EARLY_WINDOW_MINUTES || '30', 10));
      const lateWindow = Math.max(0, Number.parseInt(process.env.ROUTEPASS_DEPARTURE_LATE_WINDOW_MINUTES || '60', 10));
      if (difference < -earlyWindow || difference > lateWindow) {
        return res.status(403).json({
          success: false, code: 'OUTSIDE_DEPARTURE_TIME_WINDOW', scheduledDeparture: scheduledTime,
          direction: String(direction).toUpperCase(),
          error: `This route is scheduled to depart at ${scheduledTime} Ethiopia time. Arrive at the assigned departure location within the permitted departure window.`
        });
      }
    }

    // The first stop for OUTBOUND, or final stop for INBOUND, is the admin-configured departure geofence. A driver must explicitly
    // confirm arrival while their submitted GPS fix is within 50 metres of that stop.
    const firstStop = await DB.prepare(`SELECT stopName, latitude, longitude FROM route_stops WHERE routeId = ? ORDER BY stopOrder ${String(direction).toUpperCase() === 'INBOUND' ? 'DESC' : 'ASC'} LIMIT 1`).get(routeId);
    if (!firstStop || firstStop.latitude == null || firstStop.longitude == null) {
      return res.status(409).json({ success: false, error: 'The assigned route has no configured departure stop coordinates. Ask an administrator to configure it.' });
    }
    const distanceToDepartureMeters = distanceMeters(Number(latitude), Number(longitude), Number(firstStop.latitude), Number(firstStop.longitude));
    if (distanceToDepartureMeters > 50) {
      return res.status(403).json({ success: false, code: 'OUTSIDE_DEPARTURE_GEOFENCE', distanceMeters: Math.round(distanceToDepartureMeters), radiusMeters: 50, departureLocation: firstStop.stopName, error: 'You are outside the 50-metre departure area. Move to the assigned pickup location before confirming arrival.' });
    }
    const initialStop = firstStop.stopName || 'Terminal Hub';
    const tripId = `trip_${Date.now().toString(36)}_${crypto.randomUUID().replace(/-/g, '').slice(0, 12)}`;

    // Lock the assigned vehicle before checking active trips. This serializes simultaneous
    // start requests for the same vehicle and keeps occupancy reset + trip creation atomic.
    const tripStart = await DB.transaction(async (tx) => {
      const currentVehicle = await tx.prepare(tx.isPostgres
        ? 'SELECT * FROM vehicles WHERE id = ? FOR UPDATE'
        : 'SELECT * FROM vehicles WHERE id = ?').get(vehicle.id);
      if (!currentVehicle || currentVehicle.driverId !== driverId) {
        return { status: 'ASSIGNMENT_CHANGED' };
      }
      const existingTrip = await tx.prepare("SELECT id FROM trips WHERE vehicleId = ? AND status = 'IN_PROGRESS' LIMIT 1").get(currentVehicle.id);
      if (existingTrip) return { status: 'TRIP_ALREADY_ACTIVE' };

      await tx.prepare(`
        UPDATE vehicles
        SET currentOccupancy = 0, status = 'IN_SERVICE', assignedRouteId = ?, updatedAt = CURRENT_TIMESTAMP
        WHERE id = ?
      `).run(routeId, currentVehicle.id);
      await tx.prepare(`
        INSERT INTO trips (id, driverId, vehicleId, routeId, direction, currentStop, currentOccupancy, status)
        VALUES (?, ?, ?, ?, ?, ?, 0, 'IN_PROGRESS')
      `).run(tripId, driverId, currentVehicle.id, routeId, direction, initialStop);
      return { status: 'STARTED', vehicle: currentVehicle };
    });

    if (tripStart.status === 'ASSIGNMENT_CHANGED') {
      return res.status(403).json({ success: false, error: 'Vehicle assignment changed; refresh your assigned vehicle.' });
    }
    if (tripStart.status === 'TRIP_ALREADY_ACTIVE') {
      return res.status(409).json({ success: false, error: 'This vehicle already has an active trip.' });
    }

    // Persist arrival notification for passengers and broadcast the real-time event.
    await notifyPassengers(req, 'Vehicle has arrived', `Vehicle ${vehicle.plateNumber} has arrived at ${initialStop}. Please board now.`, {
        type: 'VEHICLE_ARRIVED',
        tripId,
        routeId,
        vehicleId: vehicle.id,
        plateNumber: vehicle.plateNumber,
        direction,
        currentStop: initialStop,
        departureLocation: initialStop,
        distanceToDepartureMeters: Math.round(distanceToDepartureMeters),
        timestamp: new Date().toISOString()
      });

    res.json({
      success: true,
      message: 'Arrival confirmed within 50 metres. Passengers have been notified that the vehicle is ready for boarding.',
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
        startTime: new Date().toISOString(),
        departureLocation: initialStop,
        distanceToDepartureMeters: Math.round(distanceToDepartureMeters)
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

    const vehicle = await DB.prepare('SELECT plateNumber FROM vehicles WHERE id = ?').get(trip.vehicleId);
    await notifyPassengers(req, 'Route completed', `Route ${trip.routeId} has been completed by vehicle ${vehicle?.plateNumber || trip.vehicleId}.`, {
      type: 'TRIP_COMPLETED', tripId: id, vehicleId: trip.vehicleId, routeId: trip.routeId,
      direction: trip.direction, timestamp: new Date().toISOString()
    });

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
