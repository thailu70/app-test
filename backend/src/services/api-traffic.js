'use strict';

const rateLimit = require('express-rate-limit');
const { isPublicTransportRequest } = require('./public-transport-traffic');

function healthRequest(req) {
  return ['GET', 'HEAD'].includes(req.method) && ['/health', '/ready'].includes(req.path);
}

function resourceStatusRequest(req) {
  return ['GET', 'HEAD'].includes(req.method) && req.path === '/admin/system/resources';
}

function rideContinuityRequest(req) {
  const path = req.path.replace(/\/$/, '');
  if (['GET', 'HEAD'].includes(req.method)) return /^\/rides\/(?:me|driver\/dashboard|requests\/[^/]+|shared\/trips\/[^/]+)$/.test(path);
  return req.method === 'POST' && (
    /^\/rides\/driver\/trips\/[^/]+\/(?:arrive|start|locations|wait|complete|cancel)$/.test(path) ||
    /^\/rides\/requests\/[^/]+\/(?:cancel|stop-wait)$/.test(path) ||
    /^\/rides\/driver\/shared\/trips\/[^/]+\/(?:actions|location)$/.test(path) ||
    /^\/rides\/shared\/bookings\/[^/]+\/cancel$/.test(path));
}

/** Signed-in app traffic follows server capacity; anonymous traffic retains its IP cap. */
function createApiTrafficMiddleware({ resourceMonitor, resolvePrincipal, anonymousLimit = 1000, windowMs = 15 * 60 * 1000,
  publicTransportLimit = 6000, publicTransportWindowMs = 60000, rideContinuityLimit = 300 }) {
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
  // Carrier networks can put many guest cargo/group customers behind one IP.
  // Private tracking has a verified request/booking quota inside its router;
  // retain a generous short-window IP backstop before any database work.
  const publicTransportLimiter = rateLimit({
    windowMs: publicTransportWindowMs, max: publicTransportLimit,
    skip: async req => Boolean(await verifiedPrincipal(req)),
    standardHeaders: true, legacyHeaders: false,
    message: { success: false, error: 'This transport service is receiving many requests. Please wait briefly and try again.' }
  });

  // A bounded lane keeps an existing journey's location, boarding, cancellation
  // and settlement reachable during load shedding. It never admits a new ride,
  // a driver acceptance, a street start, a seat booking or a settings mutation.
  // Authentication here only chooses the budget; the router still checks role
  // and trip ownership. Forged bearer strings cannot enter this lane.
  const continuityIdentities = new WeakMap();
  const continuityLimiter = rateLimit({
    windowMs: 60000, max: rideContinuityLimit, standardHeaders: true, legacyHeaders: false,
    keyGenerator: req => 'ride-account:' + continuityIdentities.get(req),
    message: { success: false, error: 'Please pause briefly before refreshing this journey again.' }
  });

  return async function apiTraffic(req, res, next) {
    if (healthRequest(req)) return healthLimiter(req, res, next);
    // The route still requires an active ADMIN. Cached monitoring stays reachable under load.
    if (resourceStatusRequest(req)) return statusLimiter(req, res, next);
    if (rideContinuityRequest(req)) {
      const principal = await verifiedPrincipal(req);
      if (principal && ['DRIVER', 'PASSENGER'].includes(principal.role)) {
        continuityIdentities.set(req, principal.id);
        return continuityLimiter(req, res, next);
      }
    }
    resourceMonitor.middleware(req, res, () => isPublicTransportRequest(req)
      ? publicTransportLimiter(req, res, next)
      : anonymousLimiter(req, res, next));
  };
}

module.exports = { createApiTrafficMiddleware, rideContinuityRequest };
