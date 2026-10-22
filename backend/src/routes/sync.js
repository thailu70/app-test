const express = require('express');
const router = express.Router();
const { DB } = require('../db');
const { authenticate, requireRole } = require('../middleware/auth');
const { passengerAssignment } = require('../services/passenger-assignment');
let intercitySchemaReady;
function ensureIntercitySchema() {
  if (!intercitySchemaReady) intercitySchemaReady = require('../services/intercity-schema').initIntercitySchema().catch(error => { intercitySchemaReady = null; throw error; });
  return intercitySchemaReady;
}

// Match the discovery API's public projection. Explicit nulls also stop legacy
// native DTO defaults from inventing a location when private fields are omitted.
function publicVehicle(row) {
  return { id: row.id, plateNumber: row.plateNumber, model: row.model, vehicleType: row.vehicleType,
    capacityLimit: row.capacityLimit, currentOccupancy: row.currentOccupancy, assignedRouteId: row.assignedRouteId,
    status: row.status, driverId: null, driverName: null, currentLat: null, currentLng: null };
}
function authorizedVehicle(row) {
  const latitude = row.sync_latitude == null ? NaN : Number(row.sync_latitude), longitude = row.sync_longitude == null ? NaN : Number(row.sync_longitude);
  const timestamp = row.sync_gps_at instanceof Date ? row.sync_gps_at.getTime() : Date.parse(String(row.sync_gps_at || '').replace(/^(\d{4}-\d{2}-\d{2})[ T](\d{2}:\d{2}:\d{2}(?:\.\d+)?)$/, '$1T$2Z'));
  // This legacy projection has no age/status fields. Never label an old fix or
  // a completed/reassigned journey's last position as the current location.
  const age = Date.now() - timestamp;
  const valid = Number(row.sync_active_trip) === 1 && Number.isFinite(latitude) && Math.abs(latitude) <= 90 && Number.isFinite(longitude) && Math.abs(longitude) <= 180 && Number.isFinite(age) && age >= 0 && age <= 120000;
  return { ...publicVehicle(row), driverId: row.driverId || null, driverName: row.driverName || null,
    currentLat: valid ? latitude : null, currentLng: valid ? longitude : null };
}

/**
 * POST /api/sync/pull
 * Mobile clients pull latest server state (routes, stops, vehicles, active notifications)
 */
router.post('/pull', authenticate, async (req, res) => {
  try {
    res.set('Cache-Control', 'private, no-store');
    const { lastSyncTime } = req.body;

    const companyId=req.user.role === 'DRIVER' ? req.user.companyId||'' : '';
    if(req.user.role==='COMPANY')return res.status(403).json({success:false,error:'Use company operations endpoints.'});
    if (companyId) await ensureIntercitySchema();
    const routes = await DB.prepare(companyId ? 'SELECT * FROM routes WHERE companyId=? AND active=TRUE' : "SELECT * FROM routes WHERE COALESCE(companyId,'')='' AND COALESCE(routeType,'DAILY_COMMUTE')='DAILY_COMMUTE' AND active=TRUE").all(...(companyId?[companyId]:[]));
    const stops = await DB.prepare(companyId?'SELECT s.* FROM route_stops s JOIN routes r ON r.id=s.routeId WHERE r.companyId=? ORDER BY s.routeId,s.stopOrder':"SELECT s.* FROM route_stops s JOIN routes r ON r.id=s.routeId WHERE COALESCE(r.companyId,'')='' AND COALESCE(r.routeType,'DAILY_COMMUTE')='DAILY_COMMUTE' ORDER BY s.routeId,s.stopOrder").all(...(companyId?[companyId]:[]));
    const activeJourney = companyId ? `EXISTS (SELECT 1 FROM intercity_departures d
      JOIN routes r ON r.id=d.route_id AND r.companyId=d.company_id AND r.routeType='INTERCITY'
      JOIN users u ON u.id=d.driver_id AND u.role='DRIVER' AND u.status='ACTIVE' AND u.companyId=d.company_id
      WHERE d.vehicle_id=v.id AND d.driver_id=v.driverId AND d.route_id=v.assignedRouteId AND d.company_id=v.companyId AND d.status='BOARDING')`
      : `EXISTS (SELECT 1 FROM trips t
      JOIN routes r ON r.id=t.routeId AND COALESCE(r.companyId,'')='' AND COALESCE(r.routeType,'DAILY_COMMUTE')='DAILY_COMMUTE'
      JOIN users u ON u.id=t.driverId AND u.role='DRIVER' AND u.status='ACTIVE' AND COALESCE(u.companyId,'')=''
      WHERE t.vehicleId=v.id AND t.driverId=v.driverId AND t.routeId=v.assignedRouteId AND t.status='IN_PROGRESS')`;
    const vehicleRows = await DB.prepare(`SELECT v.*,l.latitude AS sync_latitude,l.longitude AS sync_longitude,l.updated_at AS sync_gps_at,
      CASE WHEN ${activeJourney} THEN 1 ELSE 0 END AS sync_active_trip
      FROM vehicles v LEFT JOIN vehicle_live_locations l ON l.vehicle_id=v.id
      ${companyId ? 'WHERE v.driverId=? AND v.companyId=?' : "WHERE COALESCE(v.companyId,'')=''"}`)
      .all(...(companyId ? [req.user.id, companyId] : []));
    let assignedVehicleId = null;
    if (req.user.role === 'PASSENGER') {
      const assignment = await passengerAssignment(req.user.id);
      if (assignment.active && assignment.vehicle) assignedVehicleId = assignment.vehicle.id;
    }
    const vehicles = vehicleRows.map(row => req.user.role === 'ADMIN' || (req.user.role === 'DRIVER' && row.driverId === req.user.id) || row.id === assignedVehicleId
      ? authorizedVehicle(row) : publicVehicle(row));

    const role = req.user.role;
    let notifsQuery = "SELECT * FROM notifications WHERE targetAudience = 'ALL'";
    if (role === 'PASSENGER') {
      notifsQuery = "SELECT * FROM notifications WHERE targetAudience IN ('ALL', 'PASSENGERS')";
    } else if (role === 'DRIVER') {
      notifsQuery = "SELECT * FROM notifications WHERE targetAudience IN ('ALL', 'TRANSPORTERS')";
    }
    const notifications = await DB.prepare(`${notifsQuery} ORDER BY timestamp DESC LIMIT 20`).all();

    res.json({
      success: true,
      serverTime: new Date().toISOString(),
      data: {
        routes,
        stops,
        vehicles,
        notifications
      }
    });
  } catch (err) {
    console.error('[RoutePass] request failed:', err);
    res.status(500).json({ success: false, error: 'Internal server error.' });
  }
});

/**
 * POST /api/sync/push
 * Mobile clients push offline check-ins / logs collected during offline mode
 */
router.post('/push', authenticate, requireRole('DRIVER'), (req, res) => {
  // Client-originated offline events have no verifiable server signature. Accepting them
  // as boarding records would bypass online QR, trip ownership, payment and capacity checks.
  return res.status(409).json({
    success: false,
    code: 'OFFLINE_CHECKIN_SYNC_DISABLED',
    error: 'Offline boarding records are not accepted until a signed offline validation protocol is deployed.'
  });
});

module.exports = router;
