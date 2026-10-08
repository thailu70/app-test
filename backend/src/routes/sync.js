const express = require('express');
const router = express.Router();
const { DB } = require('../db');
const { authenticate } = require('../middleware/auth');

/**
 * POST /api/sync/pull
 * Mobile clients pull latest server state (routes, stops, vehicles, active notifications)
 */
router.post('/pull', authenticate, (req, res) => {
  try {
    const { lastSyncTime } = req.body;

    const routes = DB.prepare('SELECT * FROM routes WHERE active = 1').all();
    const stops = DB.prepare('SELECT * FROM route_stops ORDER BY routeId, stopOrder ASC').all();
    const vehicles = DB.prepare('SELECT * FROM vehicles').all();

    const role = req.user.role;
    let notifsQuery = "SELECT * FROM notifications WHERE targetAudience = 'ALL'";
    if (role === 'PASSENGER') {
      notifsQuery = "SELECT * FROM notifications WHERE targetAudience IN ('ALL', 'PASSENGERS')";
    } else if (role === 'DRIVER') {
      notifsQuery = "SELECT * FROM notifications WHERE targetAudience IN ('ALL', 'TRANSPORTERS')";
    }
    const notifications = DB.prepare(`${notifsQuery} ORDER BY timestamp DESC LIMIT 20`).all();

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
    res.status(500).json({ success: false, error: err.message });
  }
});

/**
 * POST /api/sync/push
 * Mobile clients push offline check-ins / logs collected during offline mode
 */
router.post('/push', authenticate, (req, res) => {
  try {
    const { offlineCheckins = [] } = req.body;

    const insertCheckin = DB.prepare(`
      INSERT OR IGNORE INTO checkin_records (id, tripId, passengerId, passengerName, routeId, stopName, status, vehicleId, driverId)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
    `);

    let processedCount = 0;
    for (const chk of offlineCheckins) {
      if (chk.id && chk.passengerId) {
        insertCheckin.run(
          chk.id,
          chk.tripId || 'offline_trip',
          chk.passengerId,
          chk.passengerName || 'Offline Passenger',
          chk.routeId || '',
          chk.stopName || '',
          chk.status || 'BOARDED',
          chk.vehicleId || '',
          req.user.id
        );
        processedCount++;
      }
    }

    res.json({
      success: true,
      message: `Successfully synchronized ${processedCount} offline check-in records.`,
      serverTime: new Date().toISOString()
    });
  } catch (err) {
    res.status(500).json({ success: false, error: err.message });
  }
});

module.exports = router;
