'use strict';

const rateLimit = require('express-rate-limit');

// These routes keep their own public-transport budgets. This is only a quota
// classification: it never authorizes access to a private request or booking.
function isPublicTransportRequest(req) {
  const path = req.path.replace(/\/$/, '');
  if (['GET', 'HEAD'].includes(req.method)) {
    return /^\/mobility\/(?:catalog|locations|route|track|trips(?:\/[^/]+\/seats)?|requests\/[^/]+(?:\/(?:photo|quotes|location))?|bookings\/[^/]+|drivers\/[^/]+\/reviews|tickets\/[^/]+)$/.test(path);
  }
  if (req.method === 'POST') {
    return /^\/mobility\/(?:requests|trips\/[^/]+\/bookings|bookings\/[^/]+\/(?:checkout|reconcile)|requests\/[^/]+\/(?:push-token|submit|select|confirm|cancel|review))$/.test(path);
  }
  return (req.method === 'PUT' && /^\/mobility\/requests\/[^/]+\/photo$/.test(path)) ||
    (req.method === 'DELETE' && /^\/mobility\/requests\/[^/]+\/push-token$/.test(path));
}

function createMobilityTrafficLimits({ service, rateLimitMax = 60, draftLimitMax = 120, photoLimitMax = 120 }) {
  // Only server-validated capabilities enter this map. Rotating a header, phone
  // number or request ID must never mint a new trusted quota.
  const identities = new WeakMap();
  const identity = req => identities.get(req) || (req.user?.id ? 'account:' + req.user.id : null);
  const key = req => identity(req) || 'ip:' + req.ip;
  function limiter(windowMs, limit, message, keyGenerator) {
    return rateLimit({ windowMs, limit, standardHeaders: true, legacyHeaders: false,
      ...(keyGenerator ? { keyGenerator } : {}),
      message: { success: false, error: message } });
  }
  const publicRead = limiter(60000, rateLimitMax * 10,
    'Please pause briefly before refreshing transport information again.');
  const privateRead = limiter(15 * 60000, rateLimitMax * 10,
    'This request has been refreshed very frequently. Please wait before trying again.', key);
  const publicWrite = limiter(15 * 60000, rateLimitMax * 10,
    'Too many transport actions. Please wait before trying again.');
  const privateWrite = limiter(15 * 60000, rateLimitMax,
    'Too many actions on this transport request. Please wait before trying again.', key);
  const draftLimit = limiter(60 * 60000, draftLimitMax,
    'Several transport requests have been sent from this network. Please wait before creating another.');
  const publicPhoto = limiter(60 * 60000, photoLimitMax,
    'Several cargo photos have been uploaded from this network. Please wait before trying again.');
  const privatePhoto = limiter(60 * 60000, 20,
    'Please wait before uploading another cargo photo for this request.', key);
  const probeLimit = rateLimit({ windowMs: 60000, limit: rateLimitMax * 5,
    standardHeaders: true, legacyHeaders: false,
    skip: req => identities.has(req),
    message: { success: false, error: 'Too many unsuccessful private transport requests. Please wait before trying again.' } });

  async function identify(req, res, next) {
    const kind = req.baseUrl.split('/').at(-2) === 'bookings' ? 'booking' : 'request';
    const capability = req.get(kind === 'booking' ? 'X-Booking-Secret' : 'X-Request-Secret');
    if (typeof capability === 'string' && capability.length >= 32 && capability.length <= 200 &&
        typeof service.trafficIdentity === 'function') {
      try {
        const validated = await service.trafficIdentity(kind, req.params.id, capability);
        if (typeof validated === 'string' && validated.startsWith(kind + ':')) identities.set(req, 'guest:' + validated);
      } catch (_) { /* Invalid capabilities retain the shared abuse budget and normal route authorization. */ }
    }
    // The outer short-window IP backstop and CPU/RAM gate run before this
    // lookup. Invalid guesses must not poison a valid customer's quota.
    probeLimit(req, res, next);
  }
  return {
    identify,
    readLimit: (req, res, next) => (identity(req) ? privateRead : publicRead)(req, res, next),
    writeLimit: (req, res, next) => (identity(req) ? privateWrite : publicWrite)(req, res, next),
    draftLimit,
    photoLimit: (req, res, next) => publicPhoto(req, res, () => privatePhoto(req, res, next))
  };
}

module.exports = { isPublicTransportRequest, createMobilityTrafficLimits };
