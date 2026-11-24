'use strict';
const express = require('express');
const rateLimit = require('express-rate-limit');
const { authenticate } = require('../middleware/auth');
const { getGeoService, GeoError } = require('../services/mobility-geo');
function createGeoRouter({ service = getGeoService(), limit = 180, authenticateRequest = authenticate } = {}) {
  const router = express.Router();
  const options = { windowMs: 60000, limit, standardHeaders: true, legacyHeaders: false,
    message: { success: false, error: 'Please pause briefly before searching again.' } };
  const guestLimit = rateLimit(options);
  const accountLimit = rateLimit({ ...options, keyGenerator: req => 'account:' + req.user.id });
  // Carrier networks put many drivers behind one IP. Only an active account
  // verified by the normal authentication middleware receives its own budget.
  router.use(['/locations', '/route'], (req, res, next) => req.get('Authorization')
    ? authenticateRequest(req, res, () => accountLimit(req, res, next))
    : guestLimit(req, res, next));
  router.use((_req, res, next) => { res.set('Cache-Control', 'private, no-store'); next(); });
  const handle = fn => async (req, res) => {
    try { res.json({ success: true, ...await fn(req) }); }
    catch (error) { res.status(error instanceof GeoError ? error.status : 503).json({ success: false, error: error instanceof GeoError ? error.message : 'Location service is temporarily unavailable.' }); }
  };
  router.get('/locations', handle(req => service.locations(req.query.q)));
  router.get('/locations/reverse', handle(req => service.reverse(req.query)));
  router.get('/route', handle(req => service.route(req.query)));
  return router;
}
module.exports = createGeoRouter();
module.exports.createGeoRouter = createGeoRouter;
