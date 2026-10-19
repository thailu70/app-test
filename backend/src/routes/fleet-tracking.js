'use strict';

const express = require('express');
const { DB } = require('../db');
const { authenticate, requireRole } = require('../middleware/auth');
const router = express.Router();
const STALE_AFTER_SECONDS = 120;

// SQLite stores UTC without an offset; PostgreSQL can return a Date object.
function gpsTime(value) {
  if (value instanceof Date) return value.getTime();
  if (typeof value !== 'string' || !value.trim()) return NaN;
  const text = value.trim();
  return Date.parse(/^\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}:\d{2}(\.\d+)?$/.test(text) ? text.replace(' ', 'T') + 'Z' : text);
}

function locationView(row, active, now) {
  const latitude = row.latitude == null ? NaN : Number(row.latitude);
  const longitude = row.longitude == null ? NaN : Number(row.longitude);
  const timestamp = gpsTime(row.lastGpsAt);
  const valid = Number.isFinite(latitude) && latitude >= -90 && latitude <= 90 &&
    Number.isFinite(longitude) && longitude >= -180 && longitude <= 180 &&
    Number.isFinite(timestamp) && timestamp <= now;
  const ageSeconds = valid ? Math.floor((now - timestamp) / 1000) : null;
  const speed = row.speed == null ? NaN : Number(row.speed);
  return {
    latitude: valid ? latitude : null,
    longitude: valid ? longitude : null,
    speed: valid && Number.isFinite(speed) && speed >= 0 ? speed : null,
    currentStop: row.currentStop || '',
    lastGpsAt: Number.isFinite(timestamp) ? new Date(timestamp).toISOString() : null,
    ageSeconds,
    hasGpsLocation: valid,
    status: !valid ? 'NO_GPS' : !active ? 'OFFLINE' : ageSeconds > STALE_AFTER_SECONDS ? 'STALE' : 'LIVE'
  };
}

// Operators see their own fleet only. Never use vehicles.currentLat/currentLng:
// those legacy columns contain seeded/default coordinates, not genuine GPS fixes.
router.get('/tracking', authenticate, requireRole('ADMIN', 'COMPANY'), async (req, res) => {
  try {
    const company = req.user.role === 'COMPANY';
    const companyId = company ? req.user.companyId : '';
    const vehicles = await DB.prepare(`
      SELECT v.id, v.plateNumber, v.model, v.vehicleType, v.status, v.driverId,
        u.id AS fleet_driver_id, u.fullName AS fleet_driver_name, u.phone AS fleet_driver_phone, u.status AS fleet_driver_status,
        r.id AS fleet_route_id, r.name AS fleet_route_name,
        l.latitude, l.longitude, l.speed, l.current_stop AS currentStop, l.updated_at AS lastGpsAt
      FROM vehicles v
      LEFT JOIN users u ON u.id = v.driverId AND u.role = 'DRIVER' AND COALESCE(u.companyId, '') = COALESCE(v.companyId, '')
      LEFT JOIN routes r ON r.id = v.assignedRouteId AND COALESCE(r.companyId, '') = COALESCE(v.companyId, '')
        AND r.routeType = ?
      LEFT JOIN vehicle_live_locations l ON l.vehicle_id = v.id
      WHERE COALESCE(v.companyId, '') = ?
      ORDER BY v.plateNumber ASC
    `).all(company ? 'INTERCITY' : 'DAILY_COMMUTE', companyId);

    const trips = company ? await DB.prepare(`
      SELECT d.id, d.vehicle_id AS vehicleId, d.status, d.departure_at AS fleet_departure_at,
        d.route_id AS routeId, d.origin, d.destination
      FROM intercity_departures d
      JOIN vehicles v ON v.id = d.vehicle_id AND v.driverId = d.driver_id AND v.assignedRouteId = d.route_id AND v.companyId = d.company_id
      JOIN users u ON u.id = d.driver_id AND u.companyId = d.company_id AND u.role = 'DRIVER' AND u.status = 'ACTIVE'
      JOIN routes r ON r.id = d.route_id AND r.companyId = d.company_id AND r.routeType = 'INTERCITY'
      WHERE d.company_id = ? AND d.status = 'BOARDING'
      ORDER BY d.departure_at DESC
    `).all(companyId) : await DB.prepare(`
      SELECT t.id, t.vehicleId, t.status, t.startTime AS fleet_departure_at, t.routeId
      FROM trips t
      JOIN vehicles v ON v.id = t.vehicleId AND v.driverId = t.driverId AND v.assignedRouteId = t.routeId AND COALESCE(v.companyId, '') = ''
      JOIN users u ON u.id = t.driverId AND COALESCE(u.companyId, '') = '' AND u.role = 'DRIVER' AND u.status = 'ACTIVE'
      JOIN routes r ON r.id = t.routeId AND COALESCE(r.companyId, '') = '' AND r.routeType = 'DAILY_COMMUTE'
      WHERE t.status = 'IN_PROGRESS' ORDER BY t.startTime DESC
    `).all();
    const activeByVehicle = new Map();
    for (const trip of trips) if (!activeByVehicle.has(trip.vehicleId)) activeByVehicle.set(trip.vehicleId, trip);
    const now = Date.now();
    res.set('Cache-Control', 'no-store');
    res.json({ success: true, generatedAt: new Date(now).toISOString(), staleAfterSeconds: STALE_AFTER_SECONDS, vehicles: vehicles.map(row => {
      const trip = activeByVehicle.get(row.id);
      return {
        id: row.id, plateNumber: row.plateNumber, model: row.model, vehicleType: row.vehicleType, status: row.status,
        driver: row.fleet_driver_id ? { id: row.fleet_driver_id, name: row.fleet_driver_name, phone: row.fleet_driver_phone, status: row.fleet_driver_status } : null,
        route: row.fleet_route_id ? { id: row.fleet_route_id, name: row.fleet_route_name } : null,
        trip: trip ? { id: trip.id, status: trip.status, type: company ? 'INTERCITY' : 'DAILY_COMMUTE', departureAt: trip.fleet_departure_at } : null,
        location: locationView(row, Boolean(trip), now)
      };
    }) });
  } catch (error) {
    console.error('[Fleet] tracking unavailable:', error.message);
    res.status(500).json({ success: false, error: 'Could not load fleet GPS. Try again.' });
  }
});

module.exports = router;
