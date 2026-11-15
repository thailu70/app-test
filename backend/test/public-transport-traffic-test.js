'use strict';

const { test } = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const express = require('express');
const { isPublicTransportRequest, createMobilityTrafficLimits } = require('../src/services/public-transport-traffic');

const requestA = { id: 'mrq_fixture_a', secret: crypto.randomBytes(32).toString('base64url') };
const requestB = { id: 'mrq_fixture_b', secret: crypto.randomBytes(32).toString('base64url') };
const bookingA = { id: 'gbk_fixture_a', secret: crypto.randomBytes(32).toString('base64url') };
const identities = new Map([
  ['request:' + requestA.id, requestA.secret],
  ['request:' + requestB.id, requestB.secret],
  ['booking:' + bookingA.id, bookingA.secret]
]);

function assertLimited(result) {
  assert.equal(result.status, 429);
  assert.match(result.headers.get('content-type') || '', /application\/json/);
  assert.equal(result.data.success, false);
  assert.equal(typeof result.data.error, 'string');
  assert.ok(Number(result.headers.get('retry-after')) > 0, 'Clients receive a usable retry delay');
}

async function harness(t, options = {}) {
  let businessChecks = 0;
  const service = {
    async trafficIdentity(kind, id, secret) {
      const key = kind + ':' + id;
      if (identities.get(key) === secret && typeof secret === 'string') return key;
      const error = new Error('Private transport request unavailable.');
      error.status = 404;
      throw error;
    }
  };
  const traffic = createMobilityTrafficLimits({ service, rateLimitMax: 1, ...options });
  const app = express();
  app.use(express.json());
  const router = express.Router();
  // Match the production mount order: capability identification is not authorization.
  router.use(['/requests/:id', '/bookings/:id'], traffic.identify);
  const privateHandler = kind => async (req, res, next) => {
    try {
      businessChecks++;
      const header = kind === 'request' ? 'X-Request-Secret' : 'X-Booking-Secret';
      await service.trafficIdentity(kind, req.params.id, req.get(header));
      res.json({ success: true, id: req.params.id });
    } catch (error) { next(error); }
  };
  router.get('/catalog', traffic.readLimit, (_req, res) => res.json({ success: true }));
  for (const suffix of ['', '/quotes', '/location']) {
    router.get('/requests/:id' + suffix, traffic.readLimit, privateHandler('request'));
  }
  router.get('/bookings/:id', traffic.readLimit, privateHandler('booking'));
  router.post('/requests/:id/select', traffic.writeLimit, privateHandler('request'));
  router.post('/bookings/:id/checkout', traffic.writeLimit, privateHandler('booking'));
  router.post('/requests', traffic.draftLimit, traffic.writeLimit,
    (_req, res) => res.status(201).json({ success: true }));
  // Exercise the photo policy independently of the general write bucket.
  router.put('/requests/:id/photo', traffic.photoLimit, privateHandler('request'));
  app.use('/api/mobility', router);
  app.use((error, _req, res, _next) => res.status(error.status || 500).json({ success: false, error: error.message }));
  const server = app.listen(0, '127.0.0.1');
  await new Promise(resolve => server.once('listening', resolve));
  t.after(() => new Promise(resolve => server.close(resolve)));
  const base = 'http://127.0.0.1:' + server.address().port + '/api/mobility';
  async function request(endpoint, { method = 'GET', secret, bookingSecret, headers = {}, body } = {}) {
    const supplied = { ...headers };
    if (secret !== undefined) supplied['X-Request-Secret'] = secret;
    if (bookingSecret !== undefined) supplied['X-Booking-Secret'] = bookingSecret;
    if (body !== undefined) supplied['Content-Type'] = 'application/json';
    const response = await fetch(base + endpoint, {
      method, headers: supplied, body: body === undefined ? undefined : JSON.stringify(body)
    });
    const content = await response.text();
    return { status: response.status, headers: response.headers,
      data: response.headers.get('content-type')?.includes('application/json') ? JSON.parse(content) : content };
  }
  return { request, businessChecks: () => businessChecks };
}

test('public transport admission uses an explicit method and path allowlist', () => {
  const allowed = [
    ['GET', '/mobility/catalog'], ['HEAD', '/mobility/catalog'],
    ['GET', '/mobility/locations'], ['GET', '/mobility/route'],
    ['GET', '/mobility/trips'], ['GET', '/mobility/trips/trip_a/seats'],
    ['POST', '/mobility/trips/trip_a/bookings'],
    ['POST', '/mobility/requests'], ['GET', '/mobility/requests/mrq_a'],
    ['GET', '/mobility/requests/mrq_a/quotes'], ['GET', '/mobility/requests/mrq_a/location'],
    ['GET', '/mobility/requests/mrq_a/photo'], ['PUT', '/mobility/requests/mrq_a/photo'],
    ['POST', '/mobility/requests/mrq_a/submit'], ['POST', '/mobility/requests/mrq_a/select'],
    ['POST', '/mobility/requests/mrq_a/confirm'], ['POST', '/mobility/requests/mrq_a/cancel'],
    ['POST', '/mobility/requests/mrq_a/review'], ['POST', '/mobility/requests/mrq_a/push-token'],
    ['DELETE', '/mobility/requests/mrq_a/push-token'],
    ['GET', '/mobility/bookings/gbk_a'], ['POST', '/mobility/bookings/gbk_a/checkout'],
    ['POST', '/mobility/bookings/gbk_a/reconcile'], ['GET', '/mobility/tickets/ticket_a'],
    ['GET', '/mobility/drivers/driver_a/reviews'], ['GET', '/mobility/track']
  ];
  for (const [method, path] of allowed) {
    assert.equal(isPublicTransportRequest({ method, path }), true, method + ' ' + path);
  }
  const excluded = [
    ['POST', '/auth/login'], ['POST', '/auth/request-otp'], ['GET', '/admin/system/resources'],
    ['GET', '/mobility/admin/requests'], ['POST', '/mobility/admin/requests/id/assign'],
    ['GET', '/mobility/driver/opportunities'], ['POST', '/mobility/driver/requests/id/quotes'],
    ['POST', '/mobility/payments/webhook'], ['POST', '/mobility/reviews/id/report'],
    ['GET', '/mobility/requests/id/unknown'], ['POST', '/mobility/unknown'],
    ['POST', '/mobility/catalog'], ['DELETE', '/mobility/requests/id'],
    ['GET', '/mobility/requests/id/submit'], ['PATCH', '/mobility/requests/id/select'],
    ['GET', '/not-mobility/catalog'], ['OPTIONS', '/mobility/requests']
  ];
  for (const [method, path] of excluded) {
    assert.equal(isPublicTransportRequest({ method, path }), false, method + ' ' + path);
  }
});

test('two cargo customers behind one IP retain independent status, quotes and location polling quotas', async t => {
  const api = await harness(t);
  for (let round = 0; round < 3; round++) {
    for (const customer of [requestA, requestB]) {
      for (const suffix of ['', '/quotes', '/location']) {
        assert.equal((await api.request('/requests/' + customer.id + suffix, { secret: customer.secret })).status, 200);
      }
    }
  }
  assert.equal((await api.request('/requests/' + requestA.id, { secret: requestA.secret })).status, 200);
  assertLimited(await api.request('/requests/' + requestA.id, { secret: requestA.secret }));
  assert.equal((await api.request('/requests/' + requestB.id, { secret: requestB.secret })).status, 200,
    'Customer A cannot consume customer B\'s private allowance');
});

test('private booking and cargo quotas remain separate and do not consume public browsing allowance', async t => {
  const api = await harness(t);
  for (let i = 0; i < 10; i++) {
    assert.equal((await api.request('/catalog')).status, 200);
    assert.equal((await api.request('/bookings/' + bookingA.id, { bookingSecret: bookingA.secret })).status, 200);
  }
  assertLimited(await api.request('/catalog'));
  assertLimited(await api.request('/bookings/' + bookingA.id, { bookingSecret: bookingA.secret }));
  assert.equal((await api.request('/requests/' + requestA.id, { secret: requestA.secret })).status, 200);
});

test('rotating forged secrets and customer-controlled headers does not create fresh private buckets', async t => {
  const api = await harness(t);
  let blocked;
  for (let i = 0; i < 12; i++) {
    const result = await api.request('/requests/' + requestA.id, {
      secret: crypto.randomBytes(32).toString('base64url'),
      headers: { 'X-Client-Id': 'forged-' + i, 'X-User-Id': 'forged-' + i, Authorization: 'Bearer forged-' + i }
    });
    if (result.status === 429) { blocked = result; break; }
    assert.equal(result.status, 404, 'Traffic identification must never authorize an invalid request secret');
  }
  assert.ok(blocked, 'Invalid-secret probes must be bounded by the shared IP backstop');
  assertLimited(blocked);
  assert.equal((await api.request('/requests/' + requestA.id, { secret: requestA.secret })).status, 200,
    'Valid private access is not poisoned by invalid probes on the same IP');
});

test('one request capability cannot authorize another request or a booking', async t => {
  const api = await harness(t);
  assert.equal((await api.request('/requests/' + requestB.id, { secret: requestA.secret })).status, 404);
  assert.equal((await api.request('/requests/mrq_missing', { secret: requestA.secret })).status, 404);
  assert.equal((await api.request('/bookings/' + bookingA.id, { bookingSecret: requestA.secret })).status, 404);
  assert.equal((await api.request('/requests/' + requestA.id, { secret: requestA.secret })).status, 200);
  assert.ok(api.businessChecks() >= 4, 'The route still performs its business authorization check');
});

test('private mutations have separate bounded customer quotas and JSON retry responses', async t => {
  const api = await harness(t);
  assert.equal((await api.request('/requests/' + requestA.id + '/select', { method: 'POST', secret: requestA.secret, body: {} })).status, 200);
  assertLimited(await api.request('/requests/' + requestA.id + '/select', { method: 'POST', secret: requestA.secret, body: {} }));
  assert.equal((await api.request('/requests/' + requestB.id + '/select', { method: 'POST', secret: requestB.secret, body: {} })).status, 200);
  assert.equal((await api.request('/bookings/' + bookingA.id + '/checkout', { method: 'POST', bookingSecret: bookingA.secret, body: {} })).status, 200);
});

test('new public requests retain a shared-IP creation backstop despite changing body details', async t => {
  const api = await harness(t, { draftLimitMax: 2 });
  for (let i = 0; i < 2; i++) {
    assert.equal((await api.request('/requests', { method: 'POST', body: { customerPhone: '091111110' + i } })).status, 201);
  }
  assertLimited(await api.request('/requests', { method: 'POST', body: { customerPhone: '0999999999' } }));
  assert.equal((await api.request('/catalog')).status, 200, 'Creation throttling does not disable browsing');
});

test('photo uploads retain both a private-request limit and a shared-IP upload backstop', async t => {
  await t.test('same request cannot upload more than twenty photos per hour', async t => {
    const api = await harness(t, { rateLimitMax: 60, photoLimitMax: 120 });
    for (let i = 0; i < 20; i++) {
      assert.equal((await api.request('/requests/' + requestA.id + '/photo', { method: 'PUT', secret: requestA.secret })).status, 200);
    }
    assertLimited(await api.request('/requests/' + requestA.id + '/photo', { method: 'PUT', secret: requestA.secret }));
    assert.equal((await api.request('/requests/' + requestB.id + '/photo', { method: 'PUT', secret: requestB.secret })).status, 200);
  });
  await t.test('many valid requests cannot bypass the shared upload ceiling', async t => {
    const api = await harness(t, { photoLimitMax: 2 });
    for (const customer of [requestA, requestB]) {
      assert.equal((await api.request('/requests/' + customer.id + '/photo', { method: 'PUT', secret: customer.secret })).status, 200);
    }
    assertLimited(await api.request('/requests/' + requestA.id + '/photo', { method: 'PUT', secret: requestA.secret }));
  });
});
