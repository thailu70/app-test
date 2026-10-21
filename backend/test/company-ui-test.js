const { test } = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');

function portal(file, responses, failures = {}) {
  const html = fs.readFileSync(path.join(__dirname, '../public/company/', file === 'company' ? 'index.html' : 'intercity.html'), 'utf8');
  const ids = new Set([...html.matchAll(/\bid="([^"]+)"/g)].map(m => m[1]));
  const elements = new Map();
  const get = selector => {
    const id = /^#([\w-]+)/.exec(selector)?.[1];
    if (id) assert.ok(ids.has(id), `The ${file} page supplies required element ${id}`);
    if (!elements.has(selector)) {
      const classes = new Set();
      const element = { value: selector === '#routeType' ? 'INTERCITY' : '', options: [], selectedOptions: [{ textContent: 'Daily · last 30 days' }],
        classList: { add: x => classes.add(x), remove: x => classes.delete(x), contains: x => classes.has(x), toggle: (x, force) => force ? classes.add(x) : classes.delete(x) },
        setAttribute() {}, querySelector: () => ({ disabled: false }), scrollIntoView() {}, data: [],
      };
      let content = '';
      Object.defineProperty(element, 'innerHTML', { get: () => content, set: value => {
        content = value;
        element.options = [...value.matchAll(/<option value="([^"]*)">([^<]*)<\/option>/g)].map(m => ({ value: m[1], textContent: m[2] }));
        if (element.options.length) element.value = element.options[0].value;
      } });
      elements.set(selector, element);
    }
    return elements.get(selector);
  };
  const calls = [];
  const timers = new Map();
  let nextTimer = 0;
  let removed = false;
  const context = { document: { querySelector: get, querySelectorAll: () => [] }, Date, console, URLSearchParams, AbortController,
    setTimeout: (fn, ms) => { const id = ++nextTimer; timers.set(id, { fn, ms }); return id; },
    clearTimeout: id => timers.delete(id),
    sessionStorage: { getItem: () => 'test-company-token', setItem() {}, removeItem() { removed = true; } },
    FormData: class { constructor(element) { this.data = element.data; } [Symbol.iterator]() { return this.data[Symbol.iterator](); } },
    fetch: async (url, options) => {
      const endpoint = url.split('?')[0]; calls.push({ endpoint, options });
      const fail = failures[endpoint];
      if (fail?.hang) return new Promise((resolve, reject) => options.signal.addEventListener('abort', () => reject(Object.assign(new Error('aborted'), { name: 'AbortError' })), { once: true }));
      if (fail instanceof Error) throw fail;
      return { ok: !fail, status: fail || 200, json: async () => fail ? { error: `Service unavailable: ${endpoint}` } : responses[endpoint] || {} };
    },
  };
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../public/company/', file + '.js'), 'utf8'), context);
  return { get, calls, failures, removed: () => removed, flush: () => new Promise(resolve => setImmediate(resolve)),
    pendingTimeouts: () => timers.size,
    expireTimeouts: () => { for (const { fn, ms } of [...timers.values()]) { assert.equal(ms, 12000); fn(); } },
  };
}

const data = () => ({
  '/api/master-data/vehicle-types': { success: true, vehicleTypes: [{ code: 'MINIBUS_14', name: 'Minibus', capacity: 14, active: true }, { code: 'MINIVAN_8', name: 'Minivan', capacity: 8, active: true }] },
  '/api/companies/me': { company: { name: 'Sample Transport' } },
  '/api/companies/operations/summary': { summary: { activeAssignments: 1 } },
  '/api/companies/operations/fleet': { vehicles: [{ id: 'v1', plateNumber: 'AA-001', model: 'Coach', capacityLimit: 30 }] },
  '/api/companies/operations/drivers': { drivers: [{ id: 'd1', fullName: 'Approved Driver', status: 'ACTIVE', phone: '+251900000001' }] },
  '/api/companies/operations/routes': { routes: [{ id: 'r1', name: 'Addis – Hawassa', routeType: 'INTERCITY', active: true }] },
  '/api/intercity/company/departures': { departures: [{ id: 'trip1', origin: 'Addis', destination: 'Hawassa', departure_at: '2099-01-01T08:00:00Z', status: 'SCHEDULED', seats: 30, fare_etb: 500, radius_m: 300 }] },
  '/api/intercity/company/requests': { requests: [] },
  '/api/intercity/company/roster': { roster: [{ id: 'b1', passengerName: 'Paid Passenger', passengerPhone: '+251911111111', seatNumber: 1, paymentStatus: 'PAID', amountEtb: 500 }] },
  '/api/intercity/company/performance': { total: { bookings: 1, boarded: 0, revenueEtb: 500 }, points: [] },
});

test('company routes can fail independently and a section retry retains fleet, drivers and assignment choices', async () => {
  const app = portal('company', data(), { '/api/companies/operations/routes': new Error('Network connection lost') });
  await app.flush();
  assert.match(app.get('#routesStatus').innerHTML, /Network connection lost/);
  assert.match(app.get('#routesStatus').innerHTML, /data-retry="routes"/);
  assert.match(app.get('#fleetRows').innerHTML, /AA-001/);
  assert.match(app.get('#driverRows').innerHTML, /Approved Driver/);
  assert.equal(app.get('#refresh').disabled, false);
  app.get('#assignDriver').value = 'd1';
  delete app.failures['/api/companies/operations/routes'];
  const before = app.calls.length;
  await app.get('#routesStatus').onclick({ target: { closest: () => ({ dataset: { retry: 'routes' } }) } });
  await app.flush();
  assert.deepEqual(app.calls.slice(before).map(c => c.endpoint), ['/api/companies/operations/routes']);
  assert.match(app.get('#routeRows').innerHTML, /Addis – Hawassa/);
  assert.equal(app.get('#routesStatus').innerHTML, '');
  assert.equal(app.get('#assignDriver').value, 'd1');
});

test('company refresh keeps previously loaded fleet visible when a later request fails', async () => {
  const app = portal('company', data()); await app.flush();
  app.failures['/api/companies/operations/fleet'] = 503;
  await app.get('#refresh').onclick();
  assert.match(app.get('#fleetRows').innerHTML, /AA-001/);
  assert.match(app.get('#fleetStatus').innerHTML, /Last loaded data is still shown/);
  assert.match(app.get('#routeRows').innerHTML, /Addis – Hawassa/);
});

test('expired company session displays sign-in reason and stops late rendering', async () => {
  const app = portal('company', data(), { '/api/companies/operations/routes': 401 }); await app.flush();
  assert.equal(app.removed(), true);
  assert.equal(app.get('#dash').classList.contains('hidden'), true);
  assert.equal(app.get('#login').classList.contains('hidden'), false);
  assert.match(app.get('#loginMsg').textContent, /session expired/);
});

test('intercity page renders schedules, roster and drivers despite fleet failure, and retries only fleet', async () => {
  const app = portal('intercity', data(), { '/api/companies/operations/fleet': 503 }); await app.flush();
  assert.match(app.get('#scheduleStatus').innerHTML, /Fleet: Service unavailable/);
  assert.match(app.get('#departureRows').innerHTML, /Hawassa/);
  assert.match(app.get('#driverSelect').innerHTML, /Approved Driver/);
  assert.match(app.get('#routeSelect').innerHTML, /Addis – Hawassa/);
  assert.match(app.get('#rosterRows').innerHTML, /Paid Passenger/);
  assert.equal(app.get('#metricBookings').textContent, '1');
  app.get('#routeSelect').value = 'r1'; app.get('#driverSelect').value = 'd1';
  delete app.failures['/api/companies/operations/fleet']; const before = app.calls.length;
  await app.get('#scheduleStatus').onclick({ target: { closest: () => ({ dataset: { retry: 'fleet' } }) } }); await app.flush();
  assert.deepEqual(app.calls.slice(before).map(c => c.endpoint), ['/api/companies/operations/fleet']);
  assert.match(app.get('#vehicleSelect').innerHTML, /AA-001/);
  assert.equal(app.get('#scheduleStatus').innerHTML, '');
  assert.equal(app.get('#routeSelect').value, 'r1'); assert.equal(app.get('#driverSelect').value, 'd1');
});

test('intercity report errors are visible in their own sections without blocking departures', async () => {
  const app = portal('intercity', data(), { '/api/intercity/company/roster': 503, '/api/intercity/company/performance': 503 }); await app.flush();
  assert.match(app.get('#rosterStatus').innerHTML, /Roster: Service unavailable/);
  assert.match(app.get('#reportsStatus').innerHTML, /Performance: Service unavailable/);
  assert.match(app.get('#departureRows').innerHTML, /Hawassa/);
  assert.match(app.get('#message').textContent, /Some data could not load/);
});

for (const file of ['company', 'intercity']) test(`${file} request timeout releases successful sections and retries once`, async () => {
  const app = portal(file, data(), { '/api/companies/operations/routes': { hang: true } });
  await app.flush();
  assert.equal(app.pendingTimeouts(), 1, 'Only the stalled request keeps its timeout');
  app.expireTimeouts(); await app.flush();
  const status = file === 'company' ? '#routesStatus' : '#scheduleStatus';
  assert.match(app.get(status).innerHTML, /request timed out/);
  assert.match(app.get(file === 'company' ? '#fleetRows' : '#departureRows').innerHTML, file === 'company' ? /AA-001/ : /Hawassa/);
  assert.equal(app.pendingTimeouts(), 0, 'Timed-out and successful requests clear their timers');
  delete app.failures['/api/companies/operations/routes'];
  const before = app.calls.length;
  await app.get(status).onclick({ target: { closest: () => ({ dataset: { retry: 'routes' } }) } }); await app.flush();
  assert.deepEqual(app.calls.slice(before).map(c => c.endpoint), ['/api/companies/operations/routes']);
  assert.equal(app.get(status).innerHTML, '');
  assert.equal(app.pendingTimeouts(), 0);
});

test('company vehicle registration offers active master-data types and preserves selection when catalog changes', async () => {
  const responses = data();
  responses['/api/master-data/vehicle-types'].vehicleTypes.push(
    { code: 'COACH_40', name: 'Express <Coach>', capacity: 40, active: true },
    { code: 'RETIRED_20', name: 'Retired coach', capacity: 20, active: false }
  );
  const app = portal('company', responses); await app.flush();
  assert.match(app.get('#vehicleType').innerHTML, /value="COACH_40">Express &lt;Coach&gt; · 40 seats/);
  assert.doesNotMatch(app.get('#vehicleType').innerHTML, /RETIRED_20/);
  app.get('#vehicleType').value = 'COACH_40';
  responses['/api/master-data/vehicle-types'].vehicleTypes[2].capacity = 42;
  await app.get('#refresh').onclick();
  assert.equal(app.get('#vehicleType').value, 'COACH_40');
  assert.match(app.get('#vehicleType').innerHTML, /42 seats/);
  app.failures['/api/master-data/vehicle-types'] = 503;
  await app.get('#refresh').onclick();
  assert.equal(app.get('#vehicleType').value, 'COACH_40');
  assert.match(app.get('#vehicleType').innerHTML, /42 seats/);
  assert.match(app.get('#fleetStatus').innerHTML, /Vehicle types:.*Last loaded data/);
});

test('company catalog failure retains built-in vehicle choices and section retry loads newly added types', async () => {
  const app = portal('company', data(), { '/api/master-data/vehicle-types': 503 });
  app.get('#vehicleType').innerHTML = '<option value="MINIVAN_8">Minivan · 8 seats</option><option value="MINIBUS_14">Minibus · 14 seats</option>';
  app.get('#vehicleType').value = 'MINIBUS_14';
  await app.flush();
  assert.equal(app.get('#vehicleType').value, 'MINIBUS_14');
  assert.match(app.get('#fleetRows').innerHTML, /AA-001/);
  assert.match(app.get('#fleetStatus').innerHTML, /data-retry="vehicleTypes"/);
  delete app.failures['/api/master-data/vehicle-types'];
  const before = app.calls.length;
  await app.get('#fleetStatus').onclick({ target: { closest: () => ({ dataset: { retry: 'vehicleTypes' } }) } });
  await app.flush();
  assert.deepEqual(app.calls.slice(before).map(call => call.endpoint), ['/api/master-data/vehicle-types']);
  assert.equal(app.get('#vehicleType').value, 'MINIBUS_14');
  assert.equal(app.get('#fleetStatus').innerHTML, '');
});
