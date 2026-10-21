'use strict';

const rateLimit = require('express-rate-limit');

function healthRequest(req) {
  return ['GET', 'HEAD'].includes(req.method) && ['/health', '/ready'].includes(req.path);
}

function resourceStatusRequest(req) {
  return ['GET', 'HEAD'].includes(req.method) && req.path === '/admin/system/resources';
}

/** Signed-in app traffic follows server capacity; anonymous traffic retains its IP cap. */
function createApiTrafficMiddleware({ resourceMonitor, resolvePrincipal, anonymousLimit = 1000, windowMs = 15 * 60 * 1000 }) {
  async function verifiedPrincipal(req) {
    const authorization = req.headers.authorization;
    if (typeof authorization !== 'string' || !authorization.startsWith('Bearer ')) return null;
    const token = authorization.slice(7).trim();
    if (!token) return null;
    try { return await resolvePrincipal(token); }
    catch (_) { return null; }
  }
  // Health probes stay available during overload without exposing an unlimited database probe.
  const healthLimiter = rateLimit({
    windowMs, max: 1000, standardHeaders: true, legacyHeaders: false,
    message: { success: false, error: 'Too many health checks. Please try again later.' }
  });
  const anonymousLimiter = rateLimit({
    windowMs,
    max: anonymousLimit,
    standardHeaders: true,
    legacyHeaders: false,
    skip: async (req) => Boolean(await verifiedPrincipal(req)),
    message: { success: false, error: 'Too many requests from this IP. Please try again later.' }
  });
  const statusLimiter = rateLimit({
    windowMs, max: anonymousLimit, standardHeaders: true, legacyHeaders: false,
    skip: async (req) => (await verifiedPrincipal(req))?.role === 'ADMIN',
    message: { success: false, error: 'Too many monitoring requests. Please try again later.' }
  });

  return function apiTraffic(req, res, next) {
    if (healthRequest(req)) return healthLimiter(req, res, next);
    // The route still requires an active ADMIN. Cached monitoring stays reachable under load.
    if (resourceStatusRequest(req)) return statusLimiter(req, res, next);
    resourceMonitor.middleware(req, res, () => anonymousLimiter(req, res, next));
  };
}

module.exports = { createApiTrafficMiddleware };
