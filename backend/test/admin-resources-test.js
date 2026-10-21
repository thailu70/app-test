'use strict';

const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
process.env.NODE_ENV = 'test';
process.env.PAYMENT_MODE = 'TEST';
process.env.JWT_SECRET = 'routepass-admin-resource-security-regression-secret';
process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), 'routepass-admin-resources-'));
delete process.env.DATABASE_URL;

const { DB } = require('../src/db');
const { signToken } = require('../src/middleware/auth');
const { server, resourcePressure, runDatabaseMigrations } = require('../src/server');

test('actual admin resource endpoint is private, read-only and reachable safely during capacity pressure', async t => {
  await runDatabaseMigrations();
  const tokens = {};
  for (const [id, role, status] of [
    ['resources_admin', 'ADMIN', 'ACTIVE'],
    ['resources_driver', 'DRIVER', 'ACTIVE'],
    ['resources_inactive', 'ADMIN', 'INACTIVE']
  ]) {
    await DB.prepare('INSERT INTO users(id,role,fullName,phone,passwordHash,status) VALUES(?,?,?,?,?,?)')
      .run(id, role, id, id, 'unused', status);
    tokens[id] = signToken({ id, role });
  }
  const forgedRole = signToken({ id: 'resources_driver', role: 'ADMIN' });
  const originalGetSnapshot = resourcePressure.getSnapshot;
  const originalMiddleware = resourcePressure.middleware;
  t.after(() => {
    resourcePressure.getSnapshot = originalGetSnapshot;
    resourcePressure.middleware = originalMiddleware;
    resourcePressure.stop();
  });
  server.listen(0, '127.0.0.1');
  await new Promise(resolve => server.once('listening', resolve));
  t.after(() => new Promise(resolve => server.close(resolve)));
  const base = 'http://127.0.0.1:' + server.address().port;
  const endpoint = '/api/admin/system/resources';
  async function request(url = endpoint, { token = tokens.resources_admin, method = 'GET', body } = {}) {
    const headers = {};
    if (token) headers.Authorization = 'Bearer ' + token;
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    const response = await fetch(base + url, { method, headers,
      body: body === undefined ? undefined : JSON.stringify(body) });
    const text = await response.text();
    return { status: response.status, headers: response.headers,
      data: text && response.headers.get('content-type')?.includes('application/json') ? JSON.parse(text) : text || null };
  }

  await t.test('only a currently active database ADMIN can read the cached resource snapshot', async () => {
    const response = await request();
    assert.equal(response.status, 200);
    assert.equal(response.data.success, true);
    assert.equal(response.data.resources.limitPercent, 90);
    assert.equal(response.data.resources.recoveryPercent, 85);
    for (const field of ['cpuPercent', 'memoryPercent']) {
      const value = response.data.resources[field];
      assert.ok(value === null || Number.isFinite(value) && value >= 0 && value <= 100);
    }
    assert.equal(response.headers.get('cache-control'), 'private, no-store');
    const head = await request(endpoint, { method: 'HEAD' });
    assert.equal(head.status, 200);
    assert.equal(head.data, null);
    assert.equal(head.headers.get('cache-control'), 'private, no-store');
    for (const [token, status] of [[null, 401], [tokens.resources_driver, 403],
      [tokens.resources_inactive, 401], [forgedRole, 401], ['not-a-valid-jwt', 401]]) {
      const denied = await request(endpoint, { token });
      assert.equal(denied.status, status);
      assert.equal(denied.data.resources, undefined);
    }
  });

  await t.test('query parameters and POST bodies cannot change real measurements or safety thresholds', async () => {
    const before = resourcePressure.getSnapshot();
    const query = await request(endpoint + '?cpuPercent=100&memoryPercent=100&busy=true&limitPercent=1&recoveryPercent=0');
    assert.equal(query.status, 200);
    assert.deepEqual(query.data.resources, before);
    const post = await request(endpoint, { method: 'POST', body: {
      cpuPercent: 100, memoryPercent: 100, busy: true, limitPercent: 1, recoveryPercent: 0
    } });
    assert.equal(post.status, 404);
    assert.deepEqual(resourcePressure.getSnapshot(), before);
    assert.deepEqual((await request()).data.resources, before);
  });

  await t.test('cached ADMIN reads remain authorized under simulated pressure while ordinary API work receives 503', async t => {
    // Overrides exist only in this isolated test process; no production simulator endpoint exists.
    const busy = Object.freeze({ ...originalGetSnapshot(), busy: true, cpuPercent: 95,
      memoryPercent: 92, sampledAt: 1792540800000, limitPercent: 90, recoveryPercent: 85 });
    resourcePressure.getSnapshot = () => busy;
    resourcePressure.middleware = (_req, res) => {
      res.set('Retry-After', '10');
      res.set('Cache-Control', 'no-store');
      return res.status(503).json({ success: false, code: 'SERVER_BUSY', error: 'The server is busy. Please try again shortly.' });
    };
    t.after(() => {
      resourcePressure.getSnapshot = originalGetSnapshot;
      resourcePressure.middleware = originalMiddleware;
    });
    const normal = await request('/api/routes');
    assert.equal(normal.status, 503);
    assert.equal(normal.data.code, 'SERVER_BUSY');
    assert.equal(normal.headers.get('retry-after'), '10');
    const resource = await request(endpoint + '?busy=false&cpuPercent=0');
    assert.equal(resource.status, 200);
    assert.deepEqual(resource.data.resources, busy);
    assert.equal(resource.headers.get('cache-control'), 'private, no-store');
    for (const [token, status] of [[null, 401], [tokens.resources_driver, 403],
      [tokens.resources_inactive, 401], [forgedRole, 401]]) {
      assert.equal((await request(endpoint, { token })).status, status);
    }
    assert.equal((await request(endpoint, { method: 'POST', body: { busy: false } })).status, 503);
    assert.equal((await request('/api/health', { token: null })).status, 200);
    assert.equal((await request('/api/ready', { token: null })).status, 200);
    assert.deepEqual(resourcePressure.getSnapshot(), busy);
  });
});
