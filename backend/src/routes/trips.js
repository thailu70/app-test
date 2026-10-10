const express = require('express');
const router = express.Router();
const crypto = require('crypto');
const { DB } = require('../db');
const { authenticate, requireRole } = require('../middleware/auth');

const DEPARTURE_RADIUS_METERS = 50;
function distanceMeters(lat1, lon1, lat2, lon2) {
  const toRad = (value) => value * Math.PI / 180;
  const dLat = toRad(lat2 - lat1);
  const dLon = toRad(lon2 - lon1);
  const a = Math.sin(dLat / 2) ** 2 +
    Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.sin(dLon / 2) ** 2;
  return 6371000 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}
function todayAtEthiopiaTime(hhmm, now = new Date()) {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Africa/Addis_Ababa', year: 'numeric', month: '2-digit', day: '2-digit'
  }).formatToParts(now);
  const datePart = (type) => parts.find((part) => part.type === type)?.value;
  const date = \`\${datePart('year')}-\${datePart('month')}-\${datePart('day')}\`;
  const time = String(hhmm || '').slice(0, 5);
  if (!/^([01][0-9]|2[0-3]):[0-5][0-9]$/.test(time)) return null;
  return new Date(\`\${date}T\${time}:00+03:00\`);
}
async function evaluateArrival(driverId, routeId, direction, requestedVehicleId) {
  const driver = await DB.prepare("SELECT id, status FROM users WHERE id = ? AND role = 'DRIVER'").get(driverId);
  if (!driver || driver.status !== 'ACTIVE') return { error: 'DRIVER_NOT_APPROVED', message: 'Your driver account must be approved before route departure.' };
  const vehicle = requestedVehicleId
    ? await DB.prepare('SELECT * FROM vehicles WHERE id = ? AND driverId = ?').get(requestedVehicleId, driverId)
    : await DB.prepare('SELECT * FROM vehicles WHERE driverId = ? LIMIT 1').get(driverId);
  if (!vehicle) return { error: 'VEHICLE_NOT_FOUND', message: 'No registered vehicle belongs to this driver.' };
  if (!vehicle.assignedRouteId) return { error: 'ROUTE_NOT_ASSIGNED', message: 'Ask the administrator to assign a route to your vehicle.' };
  if (routeId && vehicle.assignedRouteId !== routeId) return { error: 'ROUTE_NOT_ASSIGNED', message: 'The requested route is not assigned to this vehicle.' };
  const route = await DB.prepare('SELECT * FROM routes WHERE id = ? AND active = TRUE').get(vehicle.assignedRouteId);
  if (!route) return { error: 'ROUTE_NOT_ACTIVE', message: 'The assigned route is not active.' };
  const routeMode = String(route.directionMode || route.direction_mode || 'TWO_WAY').toUpperCase();
  const oneWayDirection = String(route.oneWayDirection || route.one_way_direction || 'OUTBOUND').toUpperCase();
  if (routeMode === 'ONE_WAY' && direction !== oneWayDirection) {
    return { error: 'DIRECTION_NOT_ALLOWED', message: \`This is a one-way route assigned for \${oneWayDirection.toLowerCase()} travel only.\` };
  }
  const stops = await DB.prepare('SELECT * FROM route_stops WHERE routeId = ? ORDER BY stopOrder ASC').all(route.id);
  if (!stops.length) return { error: 'DEPARTURE_STOP_NOT_CONFIGURED', message: 'The administrator must configure route stops before departure.' };
  const departureStop = direction === 'INBOUND' ? stops[stops.length - 1] : stops[0];
  const schedule = direction === 'INBOUND'
    ? (departureStop.scheduledEveningTime || route.eveningDeparture || '17:30')
    : (departureStop.scheduledMorningTime || route.morningDeparture || '06:30');
  const scheduledAt = todayAtEthiopiaTime(schedule);
  if (!scheduledAt) return { error: 'SCHEDULE_INVALID', message: 'The route departure time is not configured correctly.' };
  const live = await DB.prepare('SELECT * FROM vehicle_live_locations WHERE vehicle_id = ?').get(vehicle.id);
  const updatedAt = live?.updated_at || live?.updatedAt;
  const gpsAgeMs = updatedAt ? Date.now() - new Date(updatedAt).getTime() : Infinity;
  const gpsFresh = Number.isFinite(gpsAgeMs) && gpsAgeMs >= -30000 && gpsAgeMs <= 120000;
  const latitude = live ? Number(live.latitude) : NaN;
  const longitude = live ? Number(live.longitude) : NaN;
  const stopLat = Number(departureStop.latitude);
  const stopLng = Number(departureStop.longitude);
  const hasCoordinates = Number.isFinite(latitude) && Number.isFinite(longitude) &&
    Number.isFinite(stopLat) && Number.isFinite(stopLng);
  const distance = hasCoordinates ? distanceMeters(latitude, longitude, stopLat, stopLng) : null;
  const now = Date.now();
  const scheduleMs = scheduledAt.getTime();
  const scheduleWindowOpen = now >= scheduleMs - 30 * 60000 && now <= scheduleMs + 60 * 60000;
  const insideGeofence = distance !== null && distance <= DEPARTURE_RADIUS_METERS;
  const canConfirmArrival = gpsFresh && insideGeofence && scheduleWindowOpen;
  const minutesUntil = Math.ceil((scheduleMs - now) / 60000);
  const reminderDue = minutesUntil >= 0 && minutesUntil <= 30;
  return {
    vehicle, route, departureStop, direction, routeMode, oneWayDirection,
    scheduledAt, scheduledTime: String(schedule).slice(0, 5),
    distanceMeters: distance === null ? null : Math.round(distance),
    gpsFresh, gpsAgeSeconds: Number.isFinite(gpsAgeMs) ? Math.max(0, Math.floor(gpsAgeMs / 1000)) : null,
    insideGeofence, scheduleWindowOpen, canConfirmArrival, minutesUntil, reminderDue,
    reminderMessage: reminderDue ? \`Reminder: arrive at \${departureStop.stopName} for the \${direction.toLowerCase()} departure in \${minutesUntil} minute(s).\` : null
  };
}
async function notifyRoutePassengers(req, routeId, eventType, title, message, extra = {}) {
  const passengers = await DB.prepare("SELECT DISTINCT passengerId FROM subscriptions WHERE routeId = ? AND subscriptionStatus = 'ACTIVE'").all(routeId);
  for (const row of passengers) {
    const passengerId = row.passengerId || row.passenger_id;
    if (!passengerId) continue;
    await DB.prepare("INSERT INTO notifications (id, title, message, targetAudience, type, senderName, target_user_id) VALUES (?, ?, ?, 'PASSENGERS', 'SERVICE', 'RoutePass', ?)")
      .run(\`notif_\${crypto.randomUUID().replace(/-/g, '').slice(0, 12)}\`, title, message, passengerId);
  }
  if (req.app.locals.broadcastWs) {
    req.app.locals.broadcastWs({ type: eventType, routeId, targetRouteId: routeId, ...extra, timestamp: new Date().toISOString() });
  }
}



/**
 * GET /api/trips/readiness?routeId=...&direction=OUTBOUND|INBOUND
 * Evaluates the current GPS fix, departure geofence and local departure window.
 */
router.get('/readiness', authenticate, requireRole('DRIVER'), async (req, res) => {
  try {
    const direction = String(req.query.direction || 'OUTBOUND').toUpperCase();
    if (!['OUTBOUND', 'INBOUND'].includes(direction)) {
      return res.status(400).json({ success: false, error: 'Direction must be OUTBOUND or INBOUND.' });
    }
    const state = await evaluateArrival(req.user.id, String(req.query.routeId || ''), direction, String(req.query.vehicleId || '') || undefined);
    if (state.error) return res.status(state.error === 'VEHICLE_NOT_FOUND' ? 404 : 409).json({ success: false, code: state.error, error: state.message });
    res.json({
      success: true,
      canConfirmArrival: state.canConfirmArrival,
      routeId: state.route.id,
      routeName: state.route.name,
      direction,
      routeMode: state.routeMode,
      scheduledDepartureAt: state.scheduledAt.toISOString(),
      scheduledTime: state.scheduledTime,
      departureStop: { id: state.departureStop.id, name: state.departureStop.stopName, nameAm: state.departureStop.stopNameAm, latitude: Number(state.departureStop.latitude), longitude: Number(state.departureStop.longitude) },
      radiusMeters: DEPARTURE_RADIUS_METERS,
      distanceMeters: state.distanceMeters,
      insideGeofence: state.insideGeofence,
      gpsFresh: state.gpsFresh,
      gpsAgeSeconds: state.gpsAgeSeconds,
      scheduleWindowOpen: state.scheduleWindowOpen,
      minutesUntilDeparture: state.minutesUntil,
      reminderDue: state.reminderDue,
      reminderMessage: state.reminderMessage,
      message: !state.gpsFresh ? 'Waiting for a fresh GPS report. Enable location and stay on the driver screen.' :
        !state.insideGeofence ? \`Move within 50 metres of \${state.departureStop.stopName} before confirming arrival.\` :
        !state.scheduleWindowOpen ? 'Arrival confirmation is available from 30 minutes before until 60 minutes after the scheduled departure.' :
        'Your vehicle is at the assigned departure location. Confirm arrival to notify passengers.'
    });
  } catch (err) {
    console.error('[RoutePass] trip readiness failed:', err.code || 'TRIP_READINESS_ERROR');
    res.status(500).json({ success: false, error: 'Could not check departure readiness.' });
  }
});

/**
 * POST /api/trips/start
 * Driver starts a scheduled transit trip on a route.
 */
router.post('/start', authenticate, requireRole('DRIVER'), async (req, res) => {
  try {
    const driverId = req.user.id;
    const { routeId, direction = 'OUTBOUND', vehicleId, confirmedArrival = false } = req.body;
    const requestedDirection = String(direction).toUpperCase();
    if (!['OUTBOUND', 'INBOUND'].includes(requestedDirection)) return res.status(400).json({ success: false, error: 'Direction must be OUTBOUND or INBOUND.' });

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

    const route = await DB.prepare('SELECT * FROM routes WHERE id = ? AND active = TRUE').get(routeId);
    if (!route) {
      return res.status(404).json({ success: false, error: 'Active route not found.' });
    }

    // Production departures require explicit driver confirmation, fresh GPS within 50 m,
    // and the configured time window. Tests can opt out only with NODE_ENV=test.
    let arrival = null;
    const enforceArrival = process.env.NODE_ENV === 'production' || process.env.ENFORCE_SCHEDULED_DEPARTURE === 'true';
    if (enforceArrival) {
      if (confirmedArrival !== true) return res.status(409).json({ success: false, code: 'ARRIVAL_CONFIRMATION_REQUIRED', error: 'Check departure readiness and confirm arrival before starting this route.' });
      arrival = await evaluateArrival(driverId, routeId, requestedDirection, vehicle.id);
      if (arrival.error) return res.status(409).json({ success: false, code: arrival.error, error: arrival.message });
      if (!arrival.gpsFresh) return res.status(409).json({ success: false, code: 'GPS_STALE', error: 'A fresh GPS fix is required. Keep location enabled and try again.' });
      if (!arrival.insideGeofence) return res.status(409).json({ success: false, code: 'ARRIVAL_GEOFENCE_REQUIRED', distanceMeters: arrival.distanceMeters, radiusMeters: DEPARTURE_RADIUS_METERS, error: `Move within ${DEPARTURE_RADIUS_METERS} metres of ${arrival.departureStop.stopName} before confirming arrival.` });
      if (!arrival.scheduleWindowOpen) return res.status(409).json({ success: false, code: 'OUTSIDE_DEPARTURE_WINDOW', scheduledDepartureAt: arrival.scheduledAt.toISOString(), error: 'This route can depart from 30 minutes before until 60 minutes after its scheduled time.' });
    }

    const stopsForDirection = await DB.prepare(`SELECT * FROM route_stops WHERE routeId = ? ORDER BY stopOrder ${requestedDirection === 'INBOUND' ? 'DESC' : 'ASC'}`).all(routeId);
    const departureStop = stopsForDirection[0];
    const initialStop = departureStop?.stopName || 'Terminal Hub';
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
        INSERT INTO trips (id, driverId, vehicleId, routeId, requestedDirection, currentStop, currentOccupancy, status, arrivalConfirmedAt, scheduledDepartureAt, arrivalDistanceMeters)
        VALUES (?, ?, ?, ?, ?, ?, 0, 'IN_PROGRESS', ?, ?, ?)
      `).run(tripId, driverId, currentVehicle.id, routeId, requestedDirection, initialStop, arrival ? new Date().toISOString() : null, arrival ? arrival.scheduledAt.toISOString() : null, arrival ? arrival.distanceMeters : null);
      return { status: 'STARTED', vehicle: currentVehicle };
    });

    if (tripStart.status === 'ASSIGNMENT_CHANGED') {
      return res.status(403).json({ success: false, error: 'Vehicle assignment changed; refresh your assigned vehicle.' });
    }
    if (tripStart.status === 'TRIP_ALREADY_ACTIVE') {
      return res.status(409).json({ success: false, error: 'This vehicle already has an active trip.' });
    }

    // Persist arrival notification for active route subscribers and broadcast over authenticated WebSocket.
    if (arrival) {
      await notifyRoutePassengers(req, routeId, 'VEHICLE_ARRIVED', 'Your RoutePass vehicle has arrived',
        `${vehicle.plateNumber} has arrived at ${arrival.departureStop.stopName}. Please board now.`,
        { tripId, vehicleId: vehicle.id, plateNumber: vehicle.plateNumber, direction: requestedDirection, departureStop: arrival.departureStop.stopName });
    }

    // Broadcast trip start
    if (req.app.locals.broadcastWs) {
      req.app.locals.broadcastWs({
        type: 'TRIP_STARTED',
        tripId,
        routeId,
        vehicleId: vehicle.id,
        plateNumber: vehicle.plateNumber,
        requestedDirection,
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
        requestedDirection,
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

    await notifyRoutePassengers(req, trip.routeId, 'TRIP_COMPLETED', 'Your RoutePass route is complete',
      'The vehicle has completed its assigned route. Thank you for travelling with RoutePass.',
      { tripId: id, vehicleId: trip.vehicleId, direction: trip.direction });

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
