'use strict';
const express = require('express');
const rateLimit = require('express-rate-limit');
const { getGeoService, GeoError } = require('../services/mobility-geo');
function createGeoRouter({ service = getGeoService(), limit = 180 } = {}) {
  const router = express.Router();
  router.use(['/locations', '/route'], rateLimit({ windowMs: 60000, limit, standardHeaders: true, legacyHeaders: false,
    message: { success: false, error: 'Please pause briefly before searching again.' } }));
  router.use((_req, res, next) => { res.set('Cache-Control', 'private, no-store'); next(); });
  const handle = fn => async (req, res) => {
    try { res.json({ success: true, ...await fn(req) }); }
    catch (error) { res.status(error instanceof GeoError ? error.status : 503).json({ success: false, error: error instanceof GeoError ? error.message : 'Location service is temporarily unavailable.' }); }
  };
  router.get('/locations', handle(req => service.locations(req.query.q)));
  router.get('/route', handle(req => service.route(req.query)));
  return router;
}
module.exports = createGeoRouter();
module.exports.createGeoRouter = createGeoRouter;
