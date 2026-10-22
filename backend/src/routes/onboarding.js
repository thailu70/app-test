'use strict';
const express = require('express');
const jwt = require('jsonwebtoken');
const rateLimit = require('express-rate-limit');
const { authenticate, requireRole, verifyToken, JWT_SECRET } = require('../middleware/auth');
const { createOnboardingService, initOnboardingSchema, OnboardingError, MAX_DOCUMENT_BYTES, REQUIRED } = require('../services/onboarding');
const { commuteSuggestions, commuteQuote, CommuteQuoteError } = require('../services/commute-quote');

function createOnboardingRouter({ service = createOnboardingService(), rateLimitMax = 10 } = {}) {
  const router = express.Router();
  const asyncRoute = fn => (req, res, next) => Promise.resolve(fn(req, res, next)).catch(next);
  const limiter = () => rateLimit({ windowMs: 15 * 60000, limit: rateLimitMax, standardHeaders: true, legacyHeaders: false,
    handler: (_req, res) => res.status(429).json({ success: false, error: 'Too many attempts. Please try again later.' }) });
  const registerLimit = limiter(), loginLimit = limiter(), smsLimit = limiter(), verifyLimit = limiter();
  router.use((_req, res, next) => { res.set('Cache-Control', 'private, no-store'); next(); });
  router.use(asyncRoute(async (_req, _res, next) => { await initOnboardingSchema(); next(); }));
  router.get('/commute-suggestions', asyncRoute(async (req, res) => res.json({ success: true, ...await commuteSuggestions(req.query) })));
  router.get('/commute-quote', asyncRoute(async (req, res) => res.json({ success: true, ...await commuteQuote(req.query) })));
  const issue = result => ({ success: true, ...result, token: jwt.sign({ id: result.account.id, role: result.account.role, scope: 'ONBOARDING' }, JWT_SECRET, { expiresIn: '2h', audience: 'routepass-onboarding' }) });
  router.post('/register', registerLimit, asyncRoute(async (req, res) => res.status(201).json(issue(await service.register(req.body || {})))));
  router.post('/login', loginLimit, asyncRoute(async (req, res) => res.json(issue(await service.login(req.body || {})))));
  const reviewGuard = [authenticate, requireRole('ADMIN', 'COMPANY')];
  const target = req => {
    const role = String(req.params.role || '').toUpperCase();
    if (!REQUIRED[role]) throw new OnboardingError(404, 'Application unavailable.');
    return { id: String(req.params.id || ''), role };
  };
  const reviewUrls = result => {
    result.onboarding.documents = result.onboarding.documents.map(doc => ({ ...doc,
      url: `/api/onboarding/applications/${result.account.role}/${encodeURIComponent(result.account.id)}/documents/${doc.kind}` }));
    return result;
  };
  async function deliver(res, id, role, kind) {
    const document = await service.readDocument(id, role, String(kind || '').toUpperCase());
    res.set('Content-Type', document.contentType);
    res.set('Content-Length', String(document.bytes.length));
    res.set('Content-Disposition', 'attachment; filename="' + document.filename + '"');
    res.set('X-Content-Type-Options', 'nosniff');
    res.send(document.bytes);
  }
  router.get('/applications', ...reviewGuard, asyncRoute(async (req, res) => {
    const limit = req.query.limit === undefined ? 100 : Number(req.query.limit), cursor = String(req.query.cursor || '');
    if (!Number.isInteger(limit) || limit < 1 || limit > 100 || cursor.length > 80 || /[\u0000-\u001f]/.test(cursor)) throw new OnboardingError(400, 'Invalid application page.');
    const result = await service.applications(req.user, limit, cursor);
    for (const application of result.applications) reviewUrls({ account: application, onboarding: application.onboarding });
    res.json({ success: true, ...result });
  }));
  router.get('/applications/:role/:id', ...reviewGuard, asyncRoute(async (req, res) => {
    const { id, role } = target(req);
    if (!(await service.mayReview(req.user, id, role))) throw new OnboardingError(404, 'Application unavailable.');
    res.json({ success: true, ...reviewUrls(await service.snapshot(id, role, { review: true })) });
  }));
  router.get('/applications/:role/:id/documents/:kind', ...reviewGuard, asyncRoute(async (req, res) => {
    const { id, role } = target(req);
    if (!(await service.mayReview(req.user, id, role))) throw new OnboardingError(404, 'Document unavailable.');
    await deliver(res, id, role, req.params.kind);
  }));
  router.post('/applications/:role/:id/review', ...reviewGuard, asyncRoute(async (req, res) => {
    const { id, role } = target(req);
    res.json({ success: true, ...reviewUrls(await service.review(req.user, id, role, String(req.body?.decision || '').toUpperCase(), req.body?.note || '')) });
  }));
  router.use(asyncRoute(async (req, res, next) => {
    const token = /^Bearer ([^\s]+)$/.exec(String(req.headers.authorization || ''))?.[1];
    const decoded = verifyToken(token);
    if (!decoded || decoded.scope !== 'ONBOARDING' || decoded.aud !== 'routepass-onboarding' || !REQUIRED[decoded.role] || typeof decoded.id !== 'string') throw new OnboardingError(401, 'Sign in to your onboarding account.');
    await service.snapshot(decoded.id, decoded.role);
    req.onboarding = { id: decoded.id, role: decoded.role };
    next();
  }));
  router.get('/me', asyncRoute(async (req, res) => res.json({ success: true, ...await service.snapshot(req.onboarding.id, req.onboarding.role) })));
  router.post('/request-otp', smsLimit, asyncRoute(async (req, res) => res.json({ success: true, ...await service.requestOtp(req.onboarding.id, req.onboarding.role) })));
  router.post('/verify-otp', verifyLimit, asyncRoute(async (req, res) => res.json({ success: true, ...await service.verifyOtp(req.onboarding.id, req.onboarding.role, req.body?.code) })));
  router.patch('/profile', asyncRoute(async (req, res) => res.json({ success: true, ...await service.updateProfile(req.onboarding.id, req.onboarding.role, req.body || {}) })));
  router.put('/documents/:kind', express.raw({ type: () => true, limit: MAX_DOCUMENT_BYTES }), asyncRoute(async (req, res) => {
    const mime = String(req.headers['content-type'] || '').split(';')[0].trim().toLowerCase();
    res.json({ success: true, ...await service.upload(req.onboarding.id, req.onboarding.role, String(req.params.kind).toUpperCase(), req.body, mime) });
  }));
  router.get('/documents/:kind', asyncRoute(async (req, res) => deliver(res, req.onboarding.id, req.onboarding.role, req.params.kind)));
  router.post('/submit', asyncRoute(async (req, res) => res.json({ success: true, ...await service.submit(req.onboarding.id, req.onboarding.role) })));
  router.use((error, _req, res, next) => {
    if (res.headersSent) return next(error);
    if (error.type === 'entity.too.large') return res.status(413).json({ success: false, error: 'Each document must be 5 MB or smaller.' });
    if (error instanceof OnboardingError || error instanceof CommuteQuoteError) return res.status(error.status).json({ success: false, error: error.message });
    if (error.code === '23505' || error.code === 'SQLITE_CONSTRAINT_UNIQUE') return res.status(409).json({ success: false, error: 'That phone, company or vehicle is already registered.' });
    console.error('[Onboarding] request failed:', error.code || error.name || 'Error');
    res.status(500).json({ success: false, error: 'Onboarding is temporarily unavailable. Please try again.' });
  });
  return router;
}
module.exports = createOnboardingRouter();
module.exports.createOnboardingRouter = createOnboardingRouter;
module.exports.initOnboardingSchema = initOnboardingSchema;
