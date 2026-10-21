'use strict';

const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
process.env.NODE_ENV = 'test';
process.env.PAYMENT_MODE = 'TEST';
process.env.JWT_SECRET = 'routepass-capacity-security-regression-secret';
process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), 'routepass-api-traffic-'));
delete process.env.DATABASE_URL;

const express = require('express');
const jwt = require('jsonwebtoken');
const { DB } = require('../src/db');
const { authenticate, signToken, resolveActivePrincipal } = require('../src/middleware/auth');
const { createApiTrafficMiddleware } = require('../src/services/api-traffic');
const { createResourcePressureMonitor } = require('../src/services/resource-pressure');

const available = { middleware: (_req, _res, next) => next() };

async function harness(t, options = {}) {
  let principalLookups = 0;
  const app = express();
  app.use('/api/', createApiTrafficMiddleware({
    anonymousLimit: options.anonymousLimit ?? 2,
    windowMs: 900000,
    resourceMonitor: options.resourceMonitor || available,
    resolvePrincipal: async token => {
      principalLookups++;
      return (options.resolvePrincipal || resolveActivePrincipal)(token);
    }
  }));
  app.use(express.json());
  app.get('/api/health', (_req, res) => res.json({ status: 'HEALTHY' }));
  app.get('/api/ready', (_req, res) => res.json({ status: 'READY' }));
  app.get('/api/public', (_req, res) => res.json({ success: true }));
  app.get('/api/protected', authenticate, (req, res) => res.json({ id: req.user.id, role: req.user.role }));
  if (options.authRoutes) {
    app.use('/api/auth', require('../src/routes/auth'));
    app.use('/api/companies', require('../src/routes/company-portal'));
  }
  const server = app.listen(0, '127.0.0.1');
  await new Promise(resolve => server.once('listening', resolve));
  t.after(() => new Promise(resolve => server.close(resolve)));
  const base = 'http://127.0.0.1:' + server.address().port;
  async function request(endpoint, { token, method = 'GET', body } = {}) {
    const headers = {};
    if (token !== undefined) headers.Authorization = 'Bearer ' + token;
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    const response = await fetch(base + endpoint, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
    const text = await response.text();
    return { status: response.status, headers: response.headers,
      data: text && response.headers.get('content-type')?.includes('application/json') ? JSON.parse(text) : text || null };
  }
  return { request, lookups: () => principalLookups };
}

test('capacity admission preserves active-account validation and route-specific security limits', async t => {
  // The production startup migration creates this table; this isolated HTTP fixture
  // needs the same account columns for the existing company-login/authentication paths.
  await DB.exec(`CREATE TABLE IF NOT EXISTS transport_companies(
    id TEXT PRIMARY KEY,name TEXT NOT NULL,status TEXT DEFAULT 'ACTIVE',phone TEXT,
    representative_name TEXT,password_hash TEXT,registration_secret_hash TEXT,
    created_at TEXT DEFAULT CURRENT_TIMESTAMP,updated_at TEXT DEFAULT CURRENT_TIMESTAMP)`);
  for (const [id, status] of [['capacity_driver_a', 'ACTIVE'], ['capacity_driver_b', 'ACTIVE'],
    ['capacity_inactive', 'INACTIVE'], ['capacity_pending', 'PENDING']]) {
    await DB.prepare('INSERT INTO users(id,role,fullName,phone,passwordHash,status) VALUES(?,?,?,?,?,?)')
      .run(id, 'DRIVER', id, id, 'unused', status);
  }
  const driverA = signToken({ id: 'capacity_driver_a', role: 'DRIVER' });
  const driverB = signToken({ id: 'capacity_driver_b', role: 'DRIVER' });

  await t.test('active drivers sharing an exhausted anonymous IP retain access until their DB account is deactivated', async t => {
    const api = await harness(t);
    for (let i = 0; i < 2; i++) assert.equal((await api.request('/api/public')).status, 200);
    for (let i = 0; i < 8; i++) {
      const token = i % 2 ? driverA : driverB;
      const response = await api.request('/api/protected', { token });
      assert.equal(response.status, 200);
      assert.equal(response.data.id, i % 2 ? 'capacity_driver_a' : 'capacity_driver_b');
      assert.equal(response.headers.get('ratelimit-limit'), null);
    }
    assert.equal((await api.request('/api/public')).status, 429);
    await DB.prepare("UPDATE users SET status='INACTIVE' WHERE id=?").run('capacity_driver_b');
    assert.equal((await api.request('/api/protected', { token: driverB })).status, 429,
      'A still-signed JWT must lose its quota bypass immediately after deactivation');
    assert.equal((await api.request('/api/protected', { token: driverA })).status, 200);
  });

  await t.test('malformed, forged, expired, pending and role-mismatched tokens cannot bypass the anonymous cap', async t => {
    const invalidTokens = [
      'not-a-jwt',
      jwt.sign({ id: 'capacity_driver_a', role: 'DRIVER' }, 'different-secret'),
      jwt.sign({ id: 'capacity_driver_a', role: 'DRIVER' }, process.env.JWT_SECRET, { expiresIn: -1 }),
      signToken({ id: 'capacity_inactive', role: 'DRIVER' }),
      signToken({ id: 'capacity_pending', role: 'DRIVER' }),
      signToken({ id: 'capacity_driver_a', role: 'ADMIN' }),
      signToken({ id: 'missing-account', role: 'DRIVER' })
    ];
    for (const token of invalidTokens) {
      const api = await harness(t, { anonymousLimit: 1 });
      assert.equal((await api.request('/api/public')).status, 200);
      assert.equal((await api.request('/api/public', { token })).status, 429);
    }
  });

  await t.test('a failed active-account lookup never grants an anonymous-quota bypass', async t => {
    const api = await harness(t, { anonymousLimit: 1, resolvePrincipal: async () => { throw new Error('DB unavailable'); } });
    assert.equal((await api.request('/api/public')).status, 200);
    assert.equal((await api.request('/api/public', { token: driverA })).status, 429);
  });

  await t.test('real sampled CPU and RAM gates reject work before auth lookups and recover with hysteresis', async t => {
    let total = 1000, idle = 900, time = 0, availableMemory = 800;
    const monitor = createResourcePressureMonitor({
      readCpu: () => ({ total, idle, cores: 2 }),
      readMemory: () => ({ totalBytes: 1000, availableBytes: availableMemory }),
      now: () => time
    });
    function sample(cpuPercent, memoryPercent) {
      total += 2000;
      idle += 2000 * (100 - cpuPercent) / 100;
      availableMemory = 1000 * (100 - memoryPercent) / 100;
      time += 1000;
      monitor.sample();
    }
    monitor.sample();
    const api = await harness(t, { resourceMonitor: monitor });
    assert.equal((await api.request('/api/protected', { token: driverA })).status, 200);
    sample(90, 20);
    const beforeBusyLookups = api.lookups();
    const busy = await api.request('/api/protected', { token: driverA });
    assert.equal(busy.status, 503);
    assert.equal(busy.data.code, 'SERVER_BUSY');
    assert.equal(busy.headers.get('retry-after'), '10');
    assert.equal(busy.headers.get('cache-control'), 'no-store');
    assert.equal(api.lookups(), beforeBusyLookups, 'Over-capacity requests must not query account state');
    for (const endpoint of ['/api/health', '/api/ready', '/api/health?probe=1']) {
      assert.equal((await api.request(endpoint, { token: 'invalid' })).status, 200);
      assert.equal((await api.request(endpoint, { method: 'HEAD' })).status, 200);
    }
    assert.equal(api.lookups(), beforeBusyLookups);
    assert.equal((await api.request('/api/health', { method: 'POST', body: {} })).status, 503,
      'Only liveness/readiness GET and HEAD are exempt');
    sample(86, 20);
    assert.equal((await api.request('/api/protected', { token: driverA })).status, 503);
    sample(85, 85);
    assert.equal((await api.request('/api/protected', { token: driverA })).status, 200);
    sample(10, 90);
    assert.equal((await api.request('/api/public')).status, 503);
    sample(10, 85);
    assert.equal((await api.request('/api/protected', { token: driverA })).status, 200);
  });

  await t.test('authenticated quota bypass does not weaken existing login, OTP or company-login limits', async t => {
    const api = await harness(t, { anonymousLimit: 1, authRoutes: true });
    for (let i = 0; i < 10; i++) {
      const endpoint = i % 2 ? '/api/auth/request-otp' : '/api/auth/login';
      assert.equal((await api.request(endpoint, { token: driverA, method: 'POST', body: {} })).status, 400);
    }
    const limitedOtp = await api.request('/api/auth/request-otp', { token: driverA, method: 'POST', body: {} });
    assert.equal(limitedOtp.status, 429);
    assert.equal(limitedOtp.headers.get('ratelimit-limit'), '10');
    for (let i = 0; i < 8; i++) {
      assert.equal((await api.request('/api/companies/login', { token: driverA, method: 'POST', body: {} })).status, 401);
    }
    const limitedCompany = await api.request('/api/companies/login', { token: driverA, method: 'POST', body: {} });
    assert.equal(limitedCompany.status, 429);
    assert.equal(limitedCompany.headers.get('ratelimit-limit'), '8');
  });
});
