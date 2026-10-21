'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const masterData = require('../public/admin/master-data');
const flush = () => new Promise(resolve => setImmediate(resolve));

function fixture(failures = {}) {
  const elements = new Map();
  for (const match of masterData.markup().matchAll(/<[^>]+\bid="([^"]+)"[^>]*>/g)) {
    const listeners = new Map();
    elements.set(match[1], {
      value: /\bvalue="([^"]*)"/.exec(match[0])?.[1] || '',
      textContent: '', innerHTML: '', className: '', readOnly: false,
      checked: /\bchecked\b/.test(match[0]), hidden: /\bhidden\b/.test(match[0]), disabled: /\bdisabled\b/.test(match[0]),
      addEventListener: (name, listener) => listeners.set(name, listener),
      removeEventListener: (name, listener) => { if (listeners.get(name) === listener) listeners.delete(name); },
      emit: (name, extra = {}) => listeners.get(name)?.({ preventDefault() {}, ...extra }),
      focus() { this.focused = true; }, listeners
    });
  }
  const calls = [], handlers = new Map();
  const rows = [{ code: 'MINIVAN_8', name: 'Minivan', capacity: 8, active: true }];
  let radius = { success: true, radiusMeters: 300, scope: 'ALL_VEHICLES', overrideEnabled: false };
  let sharing = { success: true, intervalSeconds: 5 };
  const api = async (endpoint, options = {}) => {
    const method = options.method || 'GET';
    const key = method + ' ' + endpoint;
    calls.push({ endpoint, method, ...options });
    if (failures[key]) throw failures[key];
    if (handlers.has(key)) return handlers.get(key)(options);
    const body = options.body ? JSON.parse(options.body) : null;
    if (endpoint === '/api/master-data/vehicle-types' && method === 'GET') return { success: true, vehicleTypes: rows };
    if (endpoint === '/api/master-data/vehicle-types' && method === 'POST') { rows.push(body); return { success: true }; }
    if (endpoint.startsWith('/api/master-data/vehicle-types/') && method === 'PATCH') {
      Object.assign(rows.find(row => row.code === decodeURIComponent(endpoint.split('/').pop())), body);
      return { success: true };
    }
    if (endpoint === '/api/master-data/gps-radius') {
      if (method === 'PUT') radius = { ...radius, ...body, overrideEnabled: true };
      return radius;
    }
    if (endpoint === '/api/master-data/gps-sharing') {
      if (method === 'PUT') sharing = { ...sharing, ...body };
      return sharing;
    }
    throw new Error('Unexpected endpoint: ' + key);
  };
  const cleanup = masterData.mount({ container: { querySelector: selector => elements.get(selector.slice(1)) }, api });
  const get = id => elements.get('master-' + id);
  return {
    get, calls, rows, failures, handlers, cleanup,
    edit: code => get('types-table').emit('click', { target: { closest: () => ({ dataset: { masterEdit: code } }) } }),
    setType: (code, name, capacity, active = true) => {
      get('type-code').value = code; get('type-name').value = name;
      get('type-capacity').value = String(capacity); get('type-active').checked = active;
    },
    writes: () => calls.filter(call => call.method !== 'GET')
  };
}

test('Master Data is wired into admin navigation and adds or edits types without changing immutable codes', async () => {
  const index = fs.readFileSync(path.join(__dirname, '../public/admin/index.html'), 'utf8');
  const main = fs.readFileSync(path.join(__dirname, '../public/admin/admin.js'), 'utf8');
  assert.match(index, /data-view="master-data"/);
  assert.ok(index.indexOf('/admin/master-data.js') < index.indexOf('/admin/admin.js'));
  assert.match(main, /RoutepassMasterData\.mount/);
  assert.match(main, /masterDataCleanup\?\.\(\)/);
  const app = fixture(); await flush();
  assert.equal(app.calls.filter(call => call.method === 'GET').length, 3);
  assert.match(app.get('types-table').innerHTML, /MINIVAN_8/);
  app.setType('COACH_40', 'Coach <Express>', 40);
  await app.get('type-form').emit('submit');
  assert.deepEqual(JSON.parse(app.writes()[0].body), { code: 'COACH_40', name: 'Coach <Express>', capacity: 40, active: true });
  assert.equal(app.writes()[0].method, 'POST');
  assert.match(app.get('types-table').innerHTML, /Coach &lt;Express&gt;/);
  assert.doesNotMatch(app.get('types-table').innerHTML, /Coach <Express>/);
  app.edit('COACH_40');
  assert.equal(app.get('type-code').readOnly, true);
  app.setType('MUTATED_CODE', 'Updated coach', 42, false);
  await app.get('type-form').emit('submit');
  assert.equal(app.writes()[1].endpoint, '/api/master-data/vehicle-types/COACH_40');
  assert.equal(app.writes()[1].method, 'PATCH');
  assert.deepEqual(JSON.parse(app.writes()[1].body), { name: 'Updated coach', capacity: 42, active: false });
  assert.equal(app.rows[1].code, 'COACH_40');
  assert.equal(app.get('type-code').readOnly, false);
  assert.equal(app.get('type-cancel').hidden, true);
  assert.match(app.get('types-message').textContent, /saved/);
  app.cleanup();
});

test('radius and sharing controls enforce integer ranges and save explicit values for both transport services', async () => {
  const app = fixture(); await flush();
  assert.equal(app.get('radius-input').value, '300');
  assert.match(app.get('radius-scope').textContent, /Existing route and departure radii/);
  for (const value of ['24', '5001', '25.5', '']) {
    app.get('radius-input').value = value;
    await app.get('radius-form').emit('submit');
  }
  for (const value of ['0', '61', '2.5', '']) {
    app.get('sharing-input').value = value;
    await app.get('sharing-form').emit('submit');
  }
  assert.equal(app.writes().length, 0);
  app.get('radius-input').value = '500';
  await app.get('radius-form').emit('submit');
  assert.deepEqual(JSON.parse(app.writes()[0].body), { radiusMeters: 500 });
  assert.equal(app.writes()[0].endpoint, '/api/master-data/gps-radius');
  assert.match(app.get('radius-scope').textContent, /active for all daily commute and intercity/);
  for (const value of ['1', '60']) {
    app.get('sharing-input').value = value;
    await app.get('sharing-form').emit('submit');
  }
  assert.deepEqual(app.writes().slice(1).map(call => JSON.parse(call.body)), [{ intervalSeconds: 1 }, { intervalSeconds: 60 }]);
  assert.ok(app.writes().every(call => call.method === 'PUT'));
  app.cleanup();
});

test('independent load and save failures preserve forms, allow retry and never show an unavailable setting as zero', async () => {
  const app = fixture({ 'GET /api/master-data/vehicle-types': new Error('Connection lost') }); await flush();
  assert.match(app.get('types-table').innerHTML, /unavailable/);
  assert.equal(app.get('radius-submit').disabled, false);
  assert.equal(app.get('sharing-input').value, '5');
  app.setType('COACH_40', 'Coach', 40);
  delete app.failures['GET /api/master-data/vehicle-types'];
  await app.get('types-refresh').emit('click');
  assert.equal(app.get('type-name').value, 'Coach');
  app.failures['POST /api/master-data/vehicle-types'] = new Error('This type code already exists.');
  await app.get('type-form').emit('submit');
  assert.match(app.get('types-message').textContent, /already exists/);
  assert.equal(app.get('type-name').value, 'Coach');
  assert.equal(app.get('type-submit').disabled, false);
  app.failures['PUT /api/master-data/gps-sharing'] = new Error('Server busy. Try again shortly.');
  app.get('sharing-input').value = '12';
  await app.get('sharing-form').emit('submit');
  assert.equal(app.get('sharing-input').value, '12');
  assert.equal(app.get('sharing-submit').disabled, false);
  assert.match(app.get('sharing-message').textContent, /Server busy/);
  app.cleanup();
});

test('type validation prevents invalid requests and navigation aborts a pending save without late rendering', async () => {
  const app = fixture(); await flush();
  for (const [code, name, seats] of [['1BAD', 'Coach', 40], ['AB', 'x', 40], ['AB', 'Coach', 0], ['AB', 'Coach', 101], ['AB', 'Coach', 1.5]]) {
    app.setType(code, name, seats);
    await app.get('type-form').emit('submit');
  }
  assert.equal(app.writes().length, 0);
  let finish;
  app.handlers.set('POST /api/master-data/vehicle-types', () => new Promise(resolve => { finish = resolve; }));
  app.setType('COACH_40', 'Coach', 40);
  const pending = app.get('type-form').emit('submit');
  await app.get('type-form').emit('submit');
  assert.equal(app.writes().length, 1, 'Repeated clicks cannot duplicate an in-flight save');
  const before = app.get('types-message').textContent;
  const request = app.writes()[0];
  app.cleanup();
  assert.equal(request.signal.aborted, true);
  finish({ success: true }); await pending;
  assert.equal(app.get('types-message').textContent, before);
  assert.equal(app.get('type-form').listeners.size, 0);
});
