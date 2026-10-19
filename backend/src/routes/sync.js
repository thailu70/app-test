const express = require('express');
const router = express.Router();
const { DB } = require('../db');
const { authenticate, requireRole } = require('../middleware/auth');

/**
 * POST /api/sync/pull
 * Mobile clients pull latest server state (routes, stops, vehicles, active notifications)
 */
router.post('/pull', authenticate, async (req, res) => {
  try {
    const { lastSyncTime } = req.body;

    const companyId=req.user.companyId||'';
    if(req.user.role==='COMPANY')return res.status(403).json({success:false,error:'Use company operations endpoints.'});
    const routes = await DB.prepare(companyId ? 'SELECT * FROM routes WHERE companyId=? AND active=TRUE' : "SELECT * FROM routes WHERE COALESCE(companyId,'')='' AND COALESCE(routeType,'DAILY_COMMUTE')='DAILY_COMMUTE' AND active=TRUE").all(...(companyId?[companyId]:[]));
    const stops = await DB.prepare(companyId?'SELECT s.* FROM route_stops s JOIN routes r ON r.id=s.routeId WHERE r.companyId=? ORDER BY s.routeId,s.stopOrder':"SELECT s.* FROM route_stops s JOIN routes r ON r.id=s.routeId WHERE COALESCE(r.companyId,'')='' AND COALESCE(r.routeType,'DAILY_COMMUTE')='DAILY_COMMUTE' ORDER BY s.routeId,s.stopOrder").all(...(companyId?[companyId]:[]));
    const vehicles = await DB.prepare(companyId?'SELECT * FROM vehicles WHERE driverId=?':"SELECT * FROM vehicles WHERE COALESCE(companyId,'')=''").all(...(companyId?[req.user.id]:[]));

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
