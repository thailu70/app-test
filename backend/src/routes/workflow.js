const express = require('express');
const crypto = require('crypto');
const { DB } = require('../db');
const { authenticate, requireRole } = require('../middleware/auth');

const router = express.Router();
const haversineMeters = (aLat, aLng, bLat, bLng) => {
  const rad = (v) => v * Math.PI / 180;
  const dLat = rad(bLat - aLat);
  const dLng = rad(bLng - aLng);
  const a = Math.sin(dLat / 2) ** 2 + Math.cos(rad(aLat)) * Math.cos(rad(bLat)) * Math.sin(dLng / 2) ** 2;
  return 6371000 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
};

async function notifySchedulePassengers(req, schedule, type, title, message) {
  const subs = await DB.prepare("SELECT passengerId FROM subscriptions WHERE vehicleId = ? AND routeId = ? AND subscriptionStatus = 'ACTIVE'").all(schedule.vehicle_id, schedule.route_id);
  for (const sub of subs) {
    await DB.prepare('INSERT INTO routepass_notifications (id, user_id, schedule_id, type, title, message, created_at) VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)')
      .run(`ntf_${crypto.randomUUID().replace(/-/g, '').slice(0, 16)}`, sub.passengerId, schedule.id, type, title, message);
  }
  if (req.app.locals.broadcastWs) {
    req.app.locals.broadcastWs({ type, scheduleId: schedule.id, tripId: schedule.trip_id || null, routeId: schedule.route_id, vehicleId: schedule.vehicle_id, title, message, timestamp: new Date().toISOString() }, schedule.route_id);
  }
}

router.post('/admin/schedules', authenticate, requireRole('ADMIN'), async (req, res) => {
  try {
    const { routeId, vehicleId, direction, departureStopId, scheduledDepartureAt } = req.body;
    if (!routeId || !vehicleId || !departureStopId || !scheduledDepartureAt || !['HOME_TO_WORK', 'WORK_TO_HOME'].includes(direction)) {
      return res.status(400).json({ success: false, error: 'routeId, vehicleId, direction, departureStopId and scheduledDepartureAt are required.' });
    }
    const vehicle = await DB.prepare('SELECT * FROM vehicles WHERE id = ?').get(vehicleId);
    const stop = await DB.prepare('SELECT * FROM route_stops WHERE id = ? AND routeId = ?').get(departureStopId, routeId);
    if (!vehicle || vehicle.assignedRouteId !== routeId) return res.status(409).json({ success: false, error: 'The vehicle must be assigned to this route first.' });
    if (!vehicle.driverId) return res.status(409).json({ success: false, error: 'The vehicle must have an owner-driver registered.' });
    if (!stop) return res.status(400).json({ success: false, error: 'Departure stop must belong to the selected route.' });
    const departure = new Date(scheduledDepartureAt);
    if (Number.isNaN(departure.getTime())) return res.status(400).json({ success: false, error: 'Invalid scheduled departure time.' });
    const id = `sch_${crypto.randomUUID().replace(/-/g, '').slice(0, 16)}`;
    await DB.prepare(`INSERT INTO route_schedule_assignments (id, route_id, vehicle_id, driver_id, direction, departure_stop_id, scheduled_departure_at, status, created_by, created_at)
      VALUES (?, ?, ?, ?, ?, ?, ?, 'ASSIGNED', ?, CURRENT_TIMESTAMP)`)
      .run(id, routeId, vehicleId, vehicle.driverId, direction, departureStopId, departure.toISOString(), req.user.id);
    const schedule = await DB.prepare('SELECT * FROM route_schedule_assignments WHERE id = ?').get(id);
    res.status(201).json({ success: true, schedule });
  } catch (err) {
    console.error('[Workflow] schedule assignment failed:', err.message);
    res.status(500).json({ success: false, error: 'Could not assign route schedule.' });
  }
});

router.get('/driver/schedules', authenticate, requireRole('DRIVER'), async (req, res) => {
  try {
    const schedules = await DB.prepare(`SELECT s.*, r.name AS route_name, v.plateNumber AS vehicle_plate,
      st.name AS departure_stop_name, st.nameAm AS departure_stop_name_am, st.lat AS departure_lat, st.lng AS departure_lng
      FROM route_schedule_assignments s
      JOIN routes r ON r.id = s.route_id
      JOIN vehicles v ON v.id = s.vehicle_id
      JOIN route_stops st ON st.id = s.departure_stop_id
      WHERE s.driver_id = ? AND s.status IN ('ASSIGNED', 'ARRIVED', 'IN_PROGRESS')
      ORDER BY s.scheduled_departure_at ASC`).all(req.user.id);
    res.json({ success: true, schedules });
  } catch (err) {
    console.error('[Workflow] schedule list failed:', err.message);
    res.status(500).json({ success: false, error: 'Could not load assigned schedules.' });
  }
});

router.post('/schedules/:id/confirm-arrival', authenticate, requireRole('DRIVER'), async (req, res) => {
  try {
    const schedule = await DB.prepare('SELECT * FROM route_schedule_assignments WHERE id = ?').get(req.params.id);
    if (!schedule) return res.status(404).json({ success: false, error: 'Schedule not found.' });
    if (schedule.driver_id !== req.user.id) return res.status(403).json({ success: false, error: 'This schedule is not assigned to your driver account.' });
    if (schedule.status !== 'ASSIGNED') return res.status(409).json({ success: false, error: 'Arrival can only be confirmed once for an assigned schedule.' });
    const location = await DB.prepare('SELECT latitude, longitude, updated_at FROM vehicle_live_locations WHERE vehicle_id = ?').get(schedule.vehicle_id);
    const stop = await DB.prepare('SELECT lat, lng, name FROM route_stops WHERE id = ?').get(schedule.departure_stop_id);
    if (!location || !stop || !location.updated_at || Date.now() - new Date(location.updated_at).getTime() > 30000) {
      return res.status(409).json({ success: false, error: 'A fresh GPS location is required. Enable location and wait for a new report.' });
    }
    const distanceMeters = haversineMeters(Number(location.latitude), Number(location.longitude), Number(stop.lat), Number(stop.lng));
    if (distanceMeters > 50) return res.status(403).json({ success: false, code: 'OUTSIDE_DEPARTURE_GEOFENCE', distanceMeters: Math.round(distanceMeters), radiusMeters: 50, error: 'You must be within 50 metres of the assigned departure stop to confirm arrival.' });
    await DB.prepare("UPDATE route_schedule_assignments SET status = 'ARRIVED', arrival_confirmed_at = CURRENT_TIMESTAMP WHERE id = ?").run(schedule.id);
    await notifySchedulePassengers(req, schedule, 'VEHICLE_ARRIVED', 'Your vehicle has arrived', 'Your assigned vehicle is at the pickup point. Please board now.');
    res.json({ success: true, status: 'ARRIVED', distanceMeters: Math.round(distanceMeters), message: 'Arrival confirmed. Assigned passengers have been notified.' });
  } catch (err) {
    console.error('[Workflow] arrival confirmation failed:', err.message);
    res.status(500).json({ success: false, error: 'Could not confirm vehicle arrival.' });
  }
});

router.post('/schedules/:id/complete', authenticate, requireRole('DRIVER'), async (req, res) => {
  try {
    const schedule = await DB.prepare('SELECT * FROM route_schedule_assignments WHERE id = ?').get(req.params.id);
    if (!schedule) return res.status(404).json({ success: false, error: 'Schedule not found.' });
    if (schedule.driver_id !== req.user.id) return res.status(403).json({ success: false, error: 'This schedule is not assigned to your driver account.' });
    if (!['ARRIVED', 'IN_PROGRESS'].includes(schedule.status)) return res.status(409).json({ success: false, error: 'Only an arrived or in-progress schedule can be completed.' });
    await DB.prepare("UPDATE route_schedule_assignments SET status = 'COMPLETED', completed_at = CURRENT_TIMESTAMP WHERE id = ?").run(schedule.id);
    await notifySchedulePassengers(req, schedule, 'ROUTE_COMPLETED', 'Route completed', 'Your driver has confirmed completion of this scheduled journey.');
    res.json({ success: true, status: 'COMPLETED', message: 'Route completion confirmed and passengers notified.' });
  } catch (err) {
    console.error('[Workflow] completion failed:', err.message);
    res.status(500).json({ success: false, error: 'Could not complete this schedule.' });
  }
});

router.get('/notifications', authenticate, async (req, res) => {
  try {
    const notifications = await DB.prepare('SELECT * FROM routepass_notifications WHERE user_id = ? ORDER BY created_at DESC LIMIT 100').all(req.user.id);
    res.json({ success: true, notifications });
  } catch (err) {
    console.error('[Workflow] notification list failed:', err.message);
    res.status(500).json({ success: false, error: 'Could not load notifications.' });
  }
});

module.exports = router;
