"use strict";
const express = require("express");
const rateLimit = require("express-rate-limit");
const { authenticate, requireRole } = require("../middleware/auth");
const { RideError, getRidesService } = require("../services/rides");
function createRidesRouter({ service = getRidesService() } = {}) {
  const router = express.Router(),
    asyncRoute = (fn) => (req, res, next) =>
      Promise.resolve(fn(req, res)).catch(next),
    respond = (res, result, status = 200) =>
      res.status(status).json({ success: true, ...result });
  const publicLimit = rateLimit({
    windowMs: 60000,
    max: 600,
    standardHeaders: true,
    legacyHeaders: false,
    message: {
      success: false,
      error: "Please wait briefly before trying again.",
    },
  });
  const estimateLimit = rateLimit({
    windowMs: 60000,
    max: 120,
    standardHeaders: true,
    legacyHeaders: false,
    message: {
      success: false,
      error: "Please wait briefly before requesting another fare estimate.",
    },
  });
  const accountWriteLimit = rateLimit({
    windowMs: 60000,
    max: 60,
    keyGenerator: (req) => req.user.id,
    standardHeaders: true,
    legacyHeaders: false,
    message: {
      success: false,
      error: "Please wait briefly before trying another ride action.",
    },
  });
  const gpsLimit = rateLimit({
    windowMs: 60000,
    max: 300,
    keyGenerator: (req) => req.user.id,
    standardHeaders: true,
    legacyHeaders: false,
    message: {
      success: false,
      error: "Please reduce the GPS upload frequency briefly.",
    },
  });
  const accountReadLimit = rateLimit({
    windowMs: 60000,
    max: 600,
    keyGenerator: (req) => req.user.id,
    standardHeaders: true,
    legacyHeaders: false,
    message: {
      success: false,
      error: "Please wait briefly before refreshing this ride.",
    },
  });
  const accountEstimateLimit = rateLimit({
    windowMs: 60000,
    max: 120,
    keyGenerator: (req) => req.user.id,
    standardHeaders: true,
    legacyHeaders: false,
    message: {
      success: false,
      error: "Please wait briefly before requesting another fare estimate.",
    },
  });
  router.use((_req, res, next) => {
    res.set("Cache-Control", "private, no-store");
    res.set("Referrer-Policy", "no-referrer");
    next();
  });
  router.use((req, res, next) =>
    Promise.resolve(service.init())
      .then(() => next())
      .catch(next),
  );
  const optionalAuth = (req, res, next) =>
    req.get("Authorization") ? authenticate(req, res, next) : next();
  const passenger = [
      authenticate,
      requireRole("PASSENGER"),
      (req, res, next) =>
        req.method === "GET" ? next() : accountWriteLimit(req, res, next),
    ],
    driver = [
      authenticate,
      requireRole("DRIVER"),
      (req, res, next) =>
        req.method === "GET"
          ? next()
          : req.path.endsWith("/locations") || req.path.endsWith("/location")
            ? gpsLimit(req, res, next)
            : accountWriteLimit(req, res, next),
    ],
    admin = [authenticate, requireRole("ADMIN")];
  router.post('/push-token', authenticate, requireRole('DRIVER', 'PASSENGER'), accountWriteLimit,
    asyncRoute(async (req, res) => respond(res, await require('../services/mobility-push').getMobilityPushService().registerRideAccount(req.user, req.body || {}))));
  router.delete('/push-token', authenticate, requireRole('DRIVER', 'PASSENGER'), accountWriteLimit,
    asyncRoute(async (req, res) => respond(res, await require('../services/mobility-push').getMobilityPushService().unregisterRideAccount(req.user, req.body || {}))));
  router.get(
    "/catalog",
    optionalAuth,
    (req, res, next) => req.user ? accountReadLimit(req, res, next) : publicLimit(req, res, next),
    asyncRoute(async (_req, res) => respond(res, await service.catalog())),
  );
  router.get('/nearby',optionalAuth,(req,res,next)=>req.user?accountReadLimit(req,res,next):publicLimit(req,res,next),asyncRoute(async(req,res)=>respond(res,await service.nearby(req.query))));
  router.post(
    "/estimate",
    optionalAuth,
    (req, res, next) =>
      req.user
        ? accountEstimateLimit(req, res, next)
        : estimateLimit(req, res, next),
    asyncRoute(async (req, res) =>
      respond(res, await service.estimate(req.body || {})),
    ),
  );
  router.get(
    "/shared/trips",
    optionalAuth,
    (req, res, next) => req.user ? accountReadLimit(req, res, next) : publicLimit(req, res, next),
    asyncRoute(async (req, res) =>
      respond(res, await service.sharedTrips(req.query)),
    ),
  );
  router.get(
    "/shared/trips/:id",
    optionalAuth,
    (req, res, next) =>
      req.user ? accountReadLimit(req, res, next) : publicLimit(req, res, next),
    asyncRoute(async (req, res) =>
      respond(res, await service.sharedTrip(req.params.id, req.user)),
    ),
  );
  router.get(
    "/me",
    ...passenger,
    asyncRoute(async (req, res) => respond(res, await service.me(req.user))),
  );
  router.post(
    "/requests",
    ...passenger,
    asyncRoute(async (req, res) =>
      respond(res, await service.createRequest(req.user, req.body || {}), 201),
    ),
  );
  router.get(
    "/requests/:id",
    authenticate,
    asyncRoute(async (req, res) =>
      respond(res, await service.get(req.user, req.params.id)),
    ),
  );
  router.post(
    "/requests/:id/cancel",
    authenticate,
    accountWriteLimit,
    asyncRoute(async (req, res) =>
      respond(
        res,
        await service.cancel(req.user, req.params.id, req.body || {}),
      ),
    ),
  );
  router.post(
    "/requests/:id/stop-wait",
    ...passenger,
    asyncRoute(async (req, res) =>
      respond(
        res,
        await service.stopWait(req.user, req.params.id, req.body || {}),
      ),
    ),
  );
  router.get(
    "/driver/dashboard",
    ...driver,
    asyncRoute(async (req, res) =>
      respond(res, await service.dashboard(req.user)),
    ),
  );
  router.put(
    "/driver/availability",
    ...driver,
    asyncRoute(async (req, res) =>
      respond(res, await service.availability(req.user, req.body || {})),
    ),
  );
  router.post(
    "/driver/requests/:id/accept",
    ...driver,
    asyncRoute(async (req, res) =>
      respond(
        res,
        await service.accept(req.user, req.params.id, req.body || {}),
      ),
    ),
  );
  router.post(
    "/driver/street",
    ...driver,
    asyncRoute(async (req, res) =>
      respond(res, await service.street(req.user, req.body || {}), 201),
    ),
  );
  for (const [path, method] of [
    ["arrive", "arrive"],
    ["start", "start"],
    ["locations", "samples"],
    ["wait", "wait"],
    ["complete", "complete"],
    ["cancel", "cancel"],
  ])
    router.post(
      "/driver/trips/:id/" + path,
      ...driver,
      asyncRoute(async (req, res) =>
        respond(
          res,
          await service[method](req.user, req.params.id, req.body || {}),
        ),
      ),
    );
  router.post(
    "/shared/requests",
    ...passenger,
    asyncRoute(async (req, res) =>
      respond(
        res,
        await service.createShared(req.user, req.body || {}, false),
        201,
      ),
    ),
  );
  router.post(
    "/driver/shared/trips",
    ...driver,
    asyncRoute(async (req, res) =>
      respond(
        res,
        await service.createShared(req.user, req.body || {}, true),
        201,
      ),
    ),
  );
  router.post(
    "/driver/shared/trips/:id/accept",
    ...driver,
    asyncRoute(async (req, res) =>
      respond(
        res,
        await service.acceptShared(req.user, req.params.id, req.body || {}),
      ),
    ),
  );
  router.post(
    "/shared/trips/:id/bookings",
    ...passenger,
    asyncRoute(async (req, res) =>
      respond(
        res,
        await service.bookShared(req.user, req.params.id, req.body || {}),
        201,
      ),
    ),
  );
  router.post(
    "/shared/bookings/:id/cancel",
    ...passenger,
    asyncRoute(async (req, res) =>
      respond(res, await service.cancelBooking(req.user, req.params.id)),
    ),
  );
  router.post('/shared/trips/:id/cancel',...passenger,asyncRoute(async(req,res)=>respond(res,await service.cancelSharedRequest(req.user,req.params.id))));
  router.post(
    "/driver/shared/trips/:id/actions",
    ...driver,
    asyncRoute(async (req, res) =>
      respond(
        res,
        await service.sharedActions(req.user, req.params.id, req.body || {}),
      ),
    ),
  );
  router.post(
    "/driver/shared/trips/:id/location",
    ...driver,
    asyncRoute(async (req, res) =>
      respond(
        res,
        await service.sharedLocation(req.user, req.params.id, req.body || {}),
      ),
    ),
  );
  router.get(
    "/admin/settings",
    ...admin,
    asyncRoute(async (_req, res) => respond(res, await service.catalog())),
  );
  router.put(
    "/admin/settings",
    ...admin,
    asyncRoute(async (req, res) =>
      respond(res, await service.updateSettings(req.user.id, req.body || {})),
    ),
  );
  router.get(
    "/admin/eligibility",
    ...admin,
    asyncRoute(async (_req, res) => respond(res, await service.eligibility())),
  );
  router.put(
    "/admin/eligibility/:id",
    ...admin,
    asyncRoute(async (req, res) =>
      respond(
        res,
        await service.updateEligibility(
          req.user.id,
          req.params.id,
          req.body || {},
        ),
      ),
    ),
  );
  router.get(
    "/admin/trips",
    ...admin,
    asyncRoute(async (req, res) =>
      respond(res, await service.adminTrips(req.user)),
    ),
  );
  router.post(
    "/admin/trips/:id/resolve",
    ...admin,
    asyncRoute(async (req, res) =>
      respond(
        res,
        await service.resolve(req.user, req.params.id, req.body || {}),
      ),
    ),
  );
  router.post('/admin/shared/trips/:id/resolve',...admin,asyncRoute(async(req,res)=>respond(res,await service.resolveShared(req.user,req.params.id,req.body||{}))));
  router.use((error, _req, res, next) => {
    if (
      error instanceof RideError ||
      (error?.status && error.status >= 400 && error.status < 500)
    )
      return res
        .status(error.status)
        .json({
          success: false,
          error: error.message,
          code: error.code || "RIDE_ERROR",
        });
    if (error?.name === "GeoError")
      return res
        .status(error.status || 503)
        .json({
          success: false,
          error: error.message,
          code: "ROAD_ROUTE_UNAVAILABLE",
        });
    next(error);
  });
  return router;
}
module.exports = createRidesRouter();
module.exports.createRidesRouter = createRidesRouter;
