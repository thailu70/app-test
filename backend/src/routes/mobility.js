'use strict';
const express = require('express');
const rateLimit = require('express-rate-limit');
const QRCode = require('qrcode');
const { authenticate, requireRole } = require('../middleware/auth');
const { operationalAccount } = require('../services/operational-onboarding');
const { getMobilityService, MobilityError } = require('../services/mobility');
const escape = value => String(value ?? '').replace(/[&<>"']/g, c => ({ '&':'&amp;', '<':'&lt;', '>':'&gt;', '"':'&quot;', "'":'&#39;' }[c]));
function createMobilityRouter({ service = getMobilityService(), rateLimitMax = 60 } = {}) {
  const router = express.Router();
  const asyncRoute = fn => (req, res, next) => Promise.resolve(fn(req, res, next)).catch(next);
  const readLimit = rateLimit({ windowMs: 15 * 60000, limit: rateLimitMax * 5, standardHeaders: true, legacyHeaders: false });
  const writeLimit = rateLimit({ windowMs: 15 * 60000, limit: rateLimitMax, standardHeaders: true, legacyHeaders: false });
  const draftLimit = rateLimit({ windowMs: 60 * 60000, limit: 10, standardHeaders: true, legacyHeaders: false,
    message: { success:false,error:'You have sent several transport requests. Please wait before creating another.' } });
  const photoLimit = rateLimit({ windowMs: 60 * 60000, limit: 20, standardHeaders: true, legacyHeaders: false,
    message: { success:false,error:'Please wait before uploading another cargo photo.' } });
  router.use((_req, res, next) => { res.set('Cache-Control', 'private, no-store'); res.set('Referrer-Policy', 'no-referrer'); next(); });
  router.use(asyncRoute(async (_req, _res, next) => { await service.init(); next(); }));
  const respond = (res, result, status = 200) => res.status(status).json({ success: true, ...result });
  const bookingSecret = req => req.get('X-Booking-Secret');
  const requestSecret = req => req.get('X-Request-Secret');
  router.get('/catalog', readLimit, asyncRoute(async (_req,res) => respond(res, await service.catalog())));
  router.get('/trips', readLimit, asyncRoute(async (req,res) => respond(res, await service.trips(req.query))));
  router.get('/trips/:id/seats', readLimit, asyncRoute(async (req,res) => respond(res, await service.seats(req.params.id))));
  router.post('/trips/:id/bookings', writeLimit, asyncRoute(async (req,res) => respond(res, await service.createBooking(req.params.id, req.body || {}), 201)));
  router.get('/bookings/:id', readLimit, asyncRoute(async (req,res) => respond(res, await service.booking(req.params.id, bookingSecret(req)))));
  router.post('/bookings/:id/checkout', writeLimit, asyncRoute(async (req,res) => respond(res, await service.checkout(req.params.id, bookingSecret(req)))));
  router.post('/bookings/:id/reconcile', writeLimit, asyncRoute(async (req,res) => respond(res, await service.booking(req.params.id, bookingSecret(req)))));
  router.post('/payments/webhook', asyncRoute(async (req,res) => respond(res, await service.webhook(req.body))));
  router.post('/requests', draftLimit, writeLimit, asyncRoute(async (req,res) => respond(res, await service.createRequest(req.body || {}), 201)));
  router.get('/requests/:id', readLimit, asyncRoute(async (req,res) => respond(res, await service.request(req.params.id, requestSecret(req)))));
  router.put('/requests/:id/photo', photoLimit, writeLimit, express.raw({ type: () => true, limit: '5mb' }), asyncRoute(async (req,res) => respond(res, await service.uploadPhoto(req.params.id, requestSecret(req), req.body, String(req.get('Content-Type') || '').split(';')[0].toLowerCase()))));
  router.get('/requests/:id/photo', readLimit, (req,res,next) => req.get('Authorization') ? authenticate(req,res,next) : next(), asyncRoute(async (req,res) => {
    const photo = await service.photo(req.params.id, requestSecret(req), req.user);
    res.set('X-Content-Type-Options', 'nosniff'); res.set('Content-Disposition', 'inline; filename="cargo-photo"'); res.type(photo.contentType).send(photo.bytes);
  }));
  router.post('/requests/:id/submit', writeLimit, asyncRoute(async (req,res) => respond(res, await service.submitRequest(req.params.id, requestSecret(req)))));
  router.get('/tickets/:id', readLimit, asyncRoute(async (req,res) => {
    const ticket = await service.ticket(req.params.id, req.query.token), qr = await QRCode.toDataURL(ticket.qrToken, { margin: 2, width: 300 });
    res.set('Content-Security-Policy', "default-src 'none'; img-src data:; style-src 'unsafe-inline'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'");
    const trip = ticket.trip, p = ticket.traveler;
    const presentation = require('../services/ticket-presentation').ticketPresentation();
    const demoNotice = presentation.synthetic ? `<p role="note" style="padding:14px;border:2px solid #9b5b12;background:#fff1d6;color:#713b00;border-radius:12px"><strong>SYNTHETIC DEMO</strong><br>${escape(presentation.warning)}</p>` : '';
    res.type('html').send(`<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>${presentation.synthetic ? "Passo synthetic demo pass" : "Passo boarding pass"}</title><style>body{margin:0;background:#fffaf2;color:#05273c;font:18px system-ui,sans-serif}main{max-width:440px;margin:24px auto;padding:28px;border-radius:28px;background:white;box-shadow:0 8px 32px #002c3410}h1{color:#007e78;margin:0}h2{font-size:24px}small{color:#516775}img{display:block;max-width:100%;margin:auto}dl{display:grid;grid-template-columns:1fr 1fr;gap:14px}dd{margin:0;font-weight:700}dt{color:#516775;font-size:14px}.number{font:700 20px monospace;color:#007e78;overflow-wrap:anywhere}</style><main><h1>PASSO</h1><small>Go Further, Together.</small>${demoNotice}<h2>${escape(trip.origin)} → ${escape(trip.destination)}</h2><p>${escape(new Date(trip.departureAt).toLocaleString('en-GB',{timeZone:'Africa/Addis_Ababa',dateStyle:'long',timeStyle:'short'}))} · Ethiopia time</p><dl><dt>Traveler<dd>${escape(p.name)}<dt>Seat<dd>${escape(p.seatNumber)}<dt>Vehicle<dd>${escape(trip.vehiclePlate)}<dt>Status<dd>${escape(p.status)}</dl><img src="${qr}" alt="Boarding QR code"><p class="number">${escape(p.ticketNumber)}</p><p>${presentation.synthetic ? escape(presentation.boardingInstruction) : "Show this QR code or ticket number to your assigned driver. Keep this private boarding pass safe."}</p></main></html>`);
  }));
  router.use('/admin', authenticate, requireRole('ADMIN'));
  router.get('/admin/trips', asyncRoute(async (_req,res) => respond(res, await service.trips({},true))));
  router.post('/admin/trips/:id/review', asyncRoute(async (req,res) => respond(res, await service.reviewTrip(req.user.id, req.params.id, req.body || {}))));
  router.get('/admin/requests', asyncRoute(async (req,res) => respond(res, await service.requests(req.query.kind))));
  router.get('/admin/options', asyncRoute(async (req,res) => respond(res, await service.options(req.query.kind))));
  router.post('/admin/requests/:id/assign', asyncRoute(async (req,res) => respond(res, await service.assignRequest(req.user.id,req.params.id,req.body || {}))));
  router.post('/admin/requests/:id/status', asyncRoute(async (req,res) => respond(res, await service.requestStatus(req.user.id,req.params.id,req.body || {}))));
  router.use('/driver', authenticate, requireRole('DRIVER'), operationalAccount);
  router.get('/driver/dashboard', asyncRoute(async (req,res) => respond(res, await service.dashboard(req.user.id))));
  router.post('/driver/trips', asyncRoute(async (req,res) => respond(res, await service.createTrip(req.user.id,req.body || {}),201)));
  router.get('/driver/trips/:id/roster', asyncRoute(async (req,res) => respond(res, await service.roster(req.user.id,req.params.id))));
  router.post('/driver/trips/:id/boarding', asyncRoute(async (req,res) => respond(res, await service.startBoarding(req.user.id,req.params.id,req.body || {}))));
  router.post('/driver/trips/:id/board', asyncRoute(async (req,res) => respond(res, await service.board(req.user.id,req.params.id,req.body || {}))));
  router.post('/driver/trips/:id/complete', asyncRoute(async (req,res) => respond(res, await service.completeTrip(req.user.id,req.params.id))));
  router.use((error,_req,res,next) => {
    if (res.headersSent) return next(error);
    if (error.type === 'entity.too.large') return res.status(413).json({success:false,error:'Cargo photos must be 5 MB or smaller.'});
    if (error instanceof MobilityError) return res.status(error.status).json({success:false,error:error.message,...(error.code?{code:error.code}:{})});
    if (['23505','SQLITE_CONSTRAINT_PRIMARYKEY','SQLITE_CONSTRAINT_UNIQUE'].includes(error.code)) return res.status(409).json({success:false,error:'This seat or record was just reserved. Refresh and try again.'});
    console.error('[Mobility] request failed:',error.code || error.name);
    res.status(500).json({success:false,error:'Group travel and cargo are temporarily unavailable. Try again shortly.'});
  });
  return router;
}
module.exports = createMobilityRouter();
module.exports.createMobilityRouter = createMobilityRouter;
