'use strict';
const express = require('express');
const rateLimit = require('express-rate-limit');
const { createAccountRecoveryService, AccountRecoveryError, REQUEST_MESSAGE } = require('../services/account-recovery');
function createAccountRecoveryRouter({ service = createAccountRecoveryService(), requestLimit = 30, resetLimit = 30 } = {}) {
  const router = express.Router();
  const asyncRoute = fn => (req, res, next) => Promise.resolve(fn(req, res, next)).catch(next);
  router.use((_req, res, next) => { res.set('Cache-Control', 'private, no-store'); next(); });
  router.post('/request', rateLimit({ windowMs: 15 * 60000, limit: requestLimit, standardHeaders: true, legacyHeaders: false,
    handler: (_req, res) => res.status(200).json({ success: true, message: REQUEST_MESSAGE, retryAfterSeconds: 60 }) }),
  asyncRoute(async (req, res) => res.json(await service.request(req.body || {}))));
  router.post('/reset', rateLimit({ windowMs: 15 * 60000, limit: resetLimit, standardHeaders: true, legacyHeaders: false,
    handler: (_req, res) => res.status(429).json({ success: false, error: 'Too many reset attempts. Please try again later.' }) }),
  asyncRoute(async (req, res) => res.json(await service.reset(req.body || {}))));
  router.use((error, _req, res, next) => {
    if (res.headersSent) return next(error);
    if (error instanceof AccountRecoveryError) return res.status(error.status).json({ success: false, error: error.message });
    console.error('[Account recovery] request failed:', error.code || error.name || 'Error');
    return res.status(503).json({ success: false, error: 'Password recovery is temporarily unavailable. Contact info@passo.live for help.' });
  });
  return router;
}
module.exports = createAccountRecoveryRouter();
module.exports.createAccountRecoveryRouter = createAccountRecoveryRouter;
