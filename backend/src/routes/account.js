'use strict';
const express = require('express');
const rateLimit = require('express-rate-limit');
const { verifyToken } = require('../middleware/auth');
const { DB } = require('../db');
const { sessionIsCurrent } = require('../services/account-sessions');
const { createAccountDeletionService, AccountDeletionError, isAccountClosed } = require('../services/account-deletion');

function createAccountRouter({ service = createAccountDeletionService(), db = DB, attemptLimit = 5 } = {}) {
  const router = express.Router();
  const asyncRoute = fn => (req, res, next) => Promise.resolve(fn(req, res, next)).catch(next);
  const limiter = rateLimit({ windowMs: 15 * 60000, limit: attemptLimit, standardHeaders: true, legacyHeaders: false });
  router.use((_req, res, next) => { res.set('Cache-Control', 'private, no-store'); next(); });
  router.use(asyncRoute(async (req, _res, next) => {
    const token = /^Bearer ([^\s]+)$/.exec(String(req.headers.authorization || ''))?.[1];
    const decoded = verifyToken(token), role = String(decoded?.role || '').toUpperCase();
    const onboarding = decoded?.scope === 'ONBOARDING' && decoded?.aud === 'routepass-onboarding' && ['DRIVER','COMPANY'].includes(role);
    if (!decoded || typeof decoded.id !== 'string' || !decoded.id || !['PASSENGER','DRIVER','COMPANY','ADMIN'].includes(role) || (decoded.scope !== undefined && !onboarding) || (!onboarding && decoded.aud === 'routepass-onboarding')) throw new AccountDeletionError(401, 'Sign in to manage your account.');
    if (!await sessionIsCurrent(decoded, db)) throw new AccountDeletionError(401, 'Your password changed. Sign in again to manage your account.');
    const account = role === 'COMPANY' ? await db.prepare('SELECT id,status FROM transport_companies WHERE id=?').get(decoded.id)
      : await db.prepare('SELECT id,role,status FROM users WHERE id=? AND role=?').get(decoded.id, role);
    if (!account || !['ACTIVE','PENDING'].includes(account.status) || await isAccountClosed(decoded.id, role, db)) throw new AccountDeletionError(401, 'This account is no longer available.');
    req.account = { id: account.id, role }; next();
  }));
  router.get('/deletion', asyncRoute(async (req, res) => res.json({ success: true, deletion: await service.inspect(req.account.id, req.account.role) })));
  router.post('/deletion', limiter, asyncRoute(async (req, res) => {
    if (Object.keys(req.body || {}).some(key => !['password','confirmation'].includes(key))) throw new AccountDeletionError(400, 'Only your own account can be deleted.');
    res.status(200).json({ success: true, deletion: await service.erase(req.account.id, req.account.role, req.body) });
  }));
  router.use((error, _req, res, next) => {
    if (res.headersSent) return next(error);
    if (error instanceof AccountDeletionError) return res.status(error.status).json({ success: false, error: error.message, blockers: error.blockers });
    console.error('[Account deletion] request failed:', error.code || error.name || 'Error');
    return res.status(503).json({ success: false, error: 'Account deletion is temporarily unavailable. Contact thailu70@gmail.com for help.' });
  });
  return router;
}
module.exports = createAccountRouter();
module.exports.createAccountRouter = createAccountRouter;
