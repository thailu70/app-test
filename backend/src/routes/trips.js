const express = require('express');
const router = express.Router();
const crypto = require('crypto');
const { DB } = require('../db');
const { authenticate, requireRole } = require('../middleware/auth');
const { checkCommuteDeparture } = require('../services/departure-rules');
const { sendSms } = require('../services/afro-sms');

async function assignedCommuteVehicle(driverId, vehicleId) {
  const query = `SELECT v.*, r.name AS routeName, r.morningDeparture, r.eveningDeparture
    FROM vehicles v JOIN routes r ON r.id = v.assignedRouteId
    WHERE v.driverId = ? AND r.active = TRUE AND COALESCE(r.routeType, 'DAILY_COMMUTE') = 'DAILY_COMMUTE'
      AND COALESCE(r.companyId, '') = '' AND COALESCE(v.companyId, '') = ''`;
  return DB.prepare(query + (vehicleId ? ' AND v.id = ?' : '') + ' ORDER BY v.id LIMIT 1')
    .get(...(vehicleId ? [driverId, vehicleId] : [driverId]));
}

router.get('/assignments', authenticate, requireRole('DRIVER'), async (req, res) => {
  try {
    const vehicles = await DB.prepare(`SELECT v.*, r.name AS routeName, r.morningDeparture, r.eveningDeparture
      FROM vehicles v JOIN routes r ON r.id = v.assignedRouteId
      WHERE v.driverId = ? AND r.active = TRUE AND COALESCE(r.routeType, 'DAILY_COMMUTE') = 'DAILY_COMMUTE'
      AND COALESCE(r.companyId, '') = '' AND COALESCE(v.companyId, '') = '' ORDER BY v.plateNumber`).all(req.user.id);
    // Fetch the endpoint names with each assignment response. Route edits can replace stop IDs
    // without changing assignedRouteId, so a driver's existing local stop cache is insufficient.
    const endpointRows = await Promise.all([...new Set(vehicles.map(v => v.assignedRouteId))].map(async routeId => {
      const stops = await DB.prepare('SELECT id, stopName, stopNameAm FROM route_stops WHERE routeId = ? ORDER BY stopOrder ASC, id ASC').all(routeId);
      return [routeId, { departure: stops[0] || null, destination: stops.length > 1 ? stops[stops.length - 1] : null }];
    }));
    const endpoints = new Map(endpointRows);
    res.json({ success: true, vehicles: vehicles.map(vehicle => {
      const { departure, destination } = endpoints.get(vehicle.assignedRouteId);
      return { ...vehicle,
        departureStopId: departure?.id || null,
        departureStopName: departure?.stopName || '',
        departureStopNameAm: departure?.stopNameAm || '',
        destinationStopId: destination?.id || null,
        destinationStopName: destination?.stopName || '',
        destinationStopNameAm: destination?.stopNameAm || '' };
    }) });
  } catch (err) { console.error('[Driver assignments]', err); res.status(500).json({ success: false, error: 'Could not load driver assignments.' }); }
});

router.post('/departure-check', authenticate, requireRole('DRIVER'), async (req, res) => {
  try {
    const vehicle = await assignedCommuteVehicle(req.user.id, req.body.vehicleId);
    if (!vehicle || (req.body.routeId && req.body.routeId !== vehicle.assignedRouteId)) return res.status(403).json({ success: false, error: 'This commute route is not assigned to your vehicle.' });
    const direction = req.body.direction || 'OUTBOUND';
    if (!['OUTBOUND', 'INBOUND'].includes(direction)) return res.status(400).json({ success: false, error: 'Choose OUTBOUND or INBOUND.' });
    const route = await DB.prepare('SELECT * FROM routes WHERE id = ?').get(vehicle.assignedRouteId);
    const validation = await checkCommuteDeparture(DB, route, direction, req.body.location);
    res.json({ success: true, validation });
  } catch (err) { console.error('[Departure preview]', err); res.status(500).json({ success: false, error: 'Could not validate departure.' }); }
});

router.get('/roster', authenticate, requireRole('DRIVER'), async (req, res) => {
  try {
    const vehicle = await assignedCommuteVehicle(req.user.id, req.query.vehicleId);
    if (!vehicle) return res.status(403).json({ success: false, error: 'No commute vehicle and route are assigned to your driver account.' });
    const trip = await DB.prepare("SELECT * FROM trips WHERE driverId = ? AND vehicleId = ? AND routeId = ? AND status = 'IN_PROGRESS' ORDER BY startTime DESC LIMIT 1")
      .get(req.user.id, vehicle.id, vehicle.assignedRouteId);
    const subscriptions = await DB.prepare(`SELECT s.*, u.fullName, u.phone, u.status AS user_status,
      p.stopName AS pickup_name,
      CASE WHEN EXISTS (SELECT 1 FROM checkin_records c WHERE c.tripId = ? AND c.passengerId = s.passengerId AND c.status = 'BOARDED') THEN 1 ELSE 0 END AS boarded
      FROM subscriptions s JOIN users u ON u.id = s.passengerId
      LEFT JOIN route_stops p ON p.id = s.pickupStopId AND p.routeId = s.routeId
      WHERE s.vehicleId = ? AND s.routeId = ? AND u.role = 'PASSENGER'
      ORDER BY u.fullName ASC, s.updatedAt DESC`).all(trip?.id || '', vehicle.id, vehicle.assignedRouteId);
    const seen = new Set();
    const roster = subscriptions.filter(s => { if (seen.has(s.passengerId)) return false; seen.add(s.passengerId); return true; }).map(s => ({
      id: s.passengerId, passengerId: s.passengerId, passengerName: s.fullName, passengerPhone: s.phone,
      pickupStop: s.pickup_name || '', photoUrl: `/api/profiles/${encodeURIComponent(s.passengerId)}/photo`,
      paymentStatus: s.paymentStatus,
      status: s.boarded ? 'BOARDED' : s.user_status !== 'ACTIVE' ? 'INACTIVE' : s.subscriptionStatus === 'PENDING' ? 'PENDING'
        : s.subscriptionStatus === 'ACTIVE' && s.paymentStatus === 'PAID' && s.daysRemaining > 0 ? 'ASSIGNED' : 'INACTIVE'
    }));
    res.json({ success: true, vehicleId: vehicle.id, routeId: vehicle.assignedRouteId, routeName: vehicle.routeName, tripId: trip?.id || null, roster });
  } catch (err) { console.error('[Driver roster]', err); res.status(500).json({ success: false, error: 'Could not load the passenger roster.' }); }
});

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
    if (!['OUTBOUND', 'INBOUND'].includes(direction)) return res.status(400).json({ success: false, error: 'Choose OUTBOUND or INBOUND.' });

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

    const route = await DB.prepare("SELECT * FROM routes WHERE id = ? AND active = TRUE AND COALESCE(routeType, 'DAILY_COMMUTE') = 'DAILY_COMMUTE' AND COALESCE(companyId, '') = ''").get(routeId);
    if (!route) {
      return res.status(404).json({ success: false, error: 'Active route not found.' });
    }
    if (vehicle.companyId) return res.status(403).json({ success: false, error: 'Use the transport company journey workflow for this vehicle.' });
    const validation = await checkCommuteDeparture(DB, route, direction, req.body.location);
    if (!validation.allowed) return res.status(403).json({ success: false, error: validation.message, code: validation.code, validation });

    // Get initial route stop
    const firstStop = await DB.prepare(`SELECT stopName FROM route_stops WHERE routeId = ? ORDER BY stopOrder ${direction === 'INBOUND' ? 'DESC' : 'ASC'} LIMIT 1`).get(routeId);
    const initialStop = firstStop?.stopName || 'Terminal Hub';
    const tripId = `trip_${Date.now().toString(36)}_${crypto.randomUUID().replace(/-/g, '').slice(0, 12)}`;

    // Lock the assigned vehicle before checking active trips. This serializes simultaneous
    // start requests for the same vehicle and keeps occupancy reset + trip creation atomic.
    const tripStart = await DB.transaction(async (tx) => {
      const currentVehicle = await tx.prepare(tx.isPostgres
        ? 'SELECT * FROM vehicles WHERE id = ? FOR UPDATE'
        : 'SELECT * FROM vehicles WHERE id = ?').get(vehicle.id);
      if (!currentVehicle || currentVehicle.driverId !== driverId || currentVehicle.assignedRouteId !== routeId || currentVehicle.companyId) {
        return { status: 'ASSIGNMENT_CHANGED' };
      }
      const existingTrip = await tx.prepare("SELECT id FROM trips WHERE vehicleId = ? AND status = 'IN_PROGRESS' LIMIT 1").get(currentVehicle.id);
      if (existingTrip) return { status: 'TRIP_ALREADY_ACTIVE' };
      const currentRoute = await tx.prepare("SELECT * FROM routes WHERE id = ? AND active = TRUE AND COALESCE(routeType, 'DAILY_COMMUTE') = 'DAILY_COMMUTE' AND COALESCE(companyId, '') = ''").get(routeId);
      if (!currentRoute) return { status: 'ASSIGNMENT_CHANGED' };
      const currentValidation = await checkCommuteDeparture(tx, currentRoute, direction, req.body.location);
      if (!currentValidation.allowed) return { status: 'GATE_REJECTED', validation: currentValidation };

      await tx.prepare(`
        UPDATE vehicles
        SET currentOccupancy = 0, status = 'IN_SERVICE', assignedRouteId = ?, updatedAt = CURRENT_TIMESTAMP
        WHERE id = ?
      `).run(routeId, currentVehicle.id);
      await tx.prepare(`
        INSERT INTO trips (id, driverId, vehicleId, routeId, direction, currentStop, currentOccupancy, status)
        VALUES (?, ?, ?, ?, ?, ?, 0, 'IN_PROGRESS')
      `).run(tripId, driverId, currentVehicle.id, routeId, direction, initialStop);
      await tx.prepare(`INSERT INTO trip_departure_confirmations (trip_id,driver_id,latitude,longitude,accuracy_m,distance_m,radius_m,location_timestamp)
        VALUES (?,?,?,?,?,?,?,?)`).run(tripId, driverId, req.body.location.latitude, req.body.location.longitude,
          req.body.location.accuracyMeters, currentValidation.distanceMeters, currentValidation.radiusMeters, new Date(req.body.location.timestamp).toISOString());
      return { status: 'STARTED', vehicle: currentVehicle };
    });

    if (tripStart.status === 'ASSIGNMENT_CHANGED') {
      return res.status(403).json({ success: false, error: 'Vehicle assignment changed; refresh your assigned vehicle.' });
    }
    if (tripStart.status === 'TRIP_ALREADY_ACTIVE') {
      return res.status(409).json({ success: false, error: 'This vehicle already has an active trip.' });
    }
    if (tripStart.status === 'GATE_REJECTED') return res.status(403).json({ success: false, error: tripStart.validation.message, validation: tripStart.validation });

    // Broadcast trip start
    if (req.app.locals.broadcastWs) {
      req.app.locals.broadcastWs({
        type: 'TRIP_STARTED',
        tripId,
        driverId,
        companyId: vehicle.companyId || '',
        routeId,
        vehicleId: vehicle.id,
        plateNumber: vehicle.plateNumber,
        direction,
        currentStop: initialStop,
        timestamp: new Date().toISOString()
      });
    }
    const passengers = await DB.prepare("SELECT u.phone FROM subscriptions s JOIN users u ON u.id=s.passengerId WHERE s.vehicleId=? AND s.subscriptionStatus='ACTIVE' AND s.paymentStatus='PAID' AND s.daysRemaining>0 AND u.phone IS NOT NULL").all(vehicle.id);
    for (const passenger of passengers) sendSms(passenger.phone, `RoutePass: your ${route.name} transport has arrived. Please board now using your boarding QR code.`).catch(() => {});

    res.json({
      success: true,
      message: 'Departure arrival confirmed. Trip started and passenger boarding enabled.',
      validation,
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
      WHERE t.driverId = ? AND t.status = 'IN_PROGRESS' AND COALESCE(r.routeType, 'DAILY_COMMUTE') = 'DAILY_COMMUTE' AND COALESCE(r.companyId, '') = ''
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

    const trip = await DB.prepare('SELECT t.*, v.companyId FROM trips t JOIN vehicles v ON v.id=t.vehicleId WHERE t.id = ?').get(id);
    if (!trip) {
      return res.status(404).json({ success: false, error: 'Trip not found.' });
    }
    if (trip.driverId !== req.user.id) {
      return res.status(403).json({ success: false, error: 'Access denied for this trip.' });
    }
    if (trip.status !== 'IN_PROGRESS') return res.status(409).json({ success: false, error: 'This trip is not active.' });
    const routeStop = await DB.prepare('SELECT id FROM route_stops WHERE routeId = ? AND stopName = ?').get(trip.routeId, stopName);
    if (!routeStop) return res.status(400).json({ success: false, error: 'Choose a configured stop on this route.' });

    await DB.prepare(`
      UPDATE trips SET currentStop = ?, updatedAt = CURRENT_TIMESTAMP WHERE id = ?
    `).run(stopName, id);

    // Broadcast stop arrival to all commuters on route
    if (req.app.locals.broadcastWs) {
      req.app.locals.broadcastWs({
        type: 'VEHICLE_STOP_ARRIVAL',
        tripId: id,
        vehicleId: trip.vehicleId,
        driverId: trip.driverId,
        companyId: trip.companyId || '',
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
    const trip = await DB.prepare('SELECT t.*, v.companyId FROM trips t JOIN vehicles v ON v.id=t.vehicleId WHERE t.id = ?').get(id);

    if (!trip) {
      return res.status(404).json({ success: false, error: 'Trip not found.' });
    }
    if (trip.driverId !== req.user.id) {
      return res.status(403).json({ success: false, error: 'Access denied for this trip.' });
    }
    if (trip.status !== 'IN_PROGRESS') {
      return res.status(409).json({ success: false, error: 'This trip is not active.' });
    }

    // Use the same vehicle-then-trip lock order as scanning. A scan either commits
    // before completion or sees a completed trip; it cannot reopen boarding.
    const ended = await DB.transaction(async tx => {
      await tx.prepare(tx.isPostgres ? 'SELECT id FROM vehicles WHERE id = ? FOR UPDATE' : 'SELECT id FROM vehicles WHERE id = ?').get(trip.vehicleId);
      const current = await tx.prepare(tx.isPostgres ? 'SELECT * FROM trips WHERE id = ? FOR UPDATE' : 'SELECT * FROM trips WHERE id = ?').get(id);
      if (!current || current.driverId !== req.user.id || current.status !== 'IN_PROGRESS') return false;
      await tx.prepare("UPDATE trips SET status = 'COMPLETED', endTime = CURRENT_TIMESTAMP, updatedAt = CURRENT_TIMESTAMP WHERE id = ?").run(id);
      await tx.prepare("UPDATE vehicles SET currentOccupancy = 0, status = 'IN_SERVICE', updatedAt = CURRENT_TIMESTAMP WHERE id = ?").run(trip.vehicleId);
      return true;
    });
    if (!ended) return res.status(409).json({ success: false, error: 'This trip is no longer active.' });

    if (req.app.locals.broadcastWs) {
      req.app.locals.broadcastWs({
        type: 'TRIP_COMPLETED',
        tripId: id,
        driverId: trip.driverId,
        companyId: trip.companyId || '',
        routeId: trip.routeId,
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
