const express = require('express');
const router = express.Router();
const { DB } = require('../db');
const { authenticate } = require('../middleware/auth');

/**
 * POST /api/sync/pull
 * Mobile clients pull latest server state (routes, stops, vehicles, active notifications)
 */
router.post('/pull', authenticate, async (req, res) => {
  try {
    const { lastSyncTime } = req.body;

    const routes = await DB.prepare('SELECT * FROM routes WHERE active = TRUE').all();
    const stops = await DB.prepare('SELECT * FROM route_stops ORDER BY routeId, stopOrder ASC').all();
    const vehicles = await DB.prepare('SELECT * FROM vehicles').all();

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
});;

module.exports = router;
