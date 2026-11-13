'use strict';

const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const folder = path.join(__dirname, '../public/admin');
const flush = () => new Promise(resolve => setImmediate(resolve));
const savedStops = [
  { id: 'current_stop_a', stopName: 'Garment', stopNameAm: 'ጋርመንት', latitude: 8.95, longitude: 38.76, scheduledMorningTime: '06:35', maxCapacity: 16 },
  { id: 'current_stop_b', stopName: 'Bole', stopNameAm: 'ቦሌ', latitude: 8.99, longitude: 38.79, scheduledEveningTime: '17:40', maxCapacity: 18 }
];

function fixture() {
  const html = fs.readFileSync(path.join(folder, 'index.html'), 'utf8');
  const nodes = new Map(), dynamicIds = new Set(), documentListeners = new Map(), calls = [], instances = [];
  const routes = [{ id: 'current_route', name: 'Garment to Bole', nameAm: 'ጋርመንት ቦሌ', morningDeparture: '06:30', eveningDeparture: '17:30', distanceKm: 12, basePriceEtb: 2500, active: true }];
  let detailHandler = null, areaHandler = null;

  function node(id, markup) {
    const listeners = new Map();
    const result = {
      id, value: /\bvalue="([^"]*)"/.exec(markup)?.[1] || '', hidden: /\bhidden\b/.test(markup), disabled: false,
      textContent: '', className: '', classList: { add() {}, remove() {}, toggle() {} },
      listeners, addEventListener: (type, fn) => listeners.set(type, fn),
      removeEventListener: (type, fn) => { if (listeners.get(type) === fn) listeners.delete(type); },
      emit: (type, event = {}) => listeners.get(type)?.({ currentTarget: result, preventDefault() {}, ...event }),
      querySelector(selector) { return selector === 'button[type="submit"]' ? result.submitButton : nodes.get(selector.slice(1)) || null; },
      scrollIntoView() { result.scrolled = true; }
    };
    let content = '';
    Object.defineProperty(result, 'innerHTML', { get: () => content, set(value) {
      content = value;
      if (id === 'page-content') {
        for (const oldId of dynamicIds) nodes.delete(oldId);
        dynamicIds.clear();
        install(value, true);
      }
    } });
    return result;
  }

  function install(markup, dynamic = false) {
    for (const match of markup.matchAll(/<[^>]+\bid="([^"]+)"[^>]*>/g)) {
      nodes.set(match[1], node(match[1], match[0]));
      if (dynamic) dynamicIds.add(match[1]);
    }
    for (const match of markup.matchAll(/<form\b[^>]*\bid="([^"]+)"[^>]*>([\s\S]*?)<\/form>/g)) {
      const form = nodes.get(match[1]);
      const fields = new Map();
      for (const input of match[2].matchAll(/<(?:input|textarea)\b[^>]*\bname="([^"]+)"[^>]*>/g)) {
        const id = /\bid="([^"]+)"/.exec(input[0])?.[1];
        const field = nodes.get(id) || node(input[1], input[0]);
        field.defaultValue = field.value;
        fields.set(input[1], field);
      }
      form.fields = fields;
      form.elements = { namedItem: key => fields.get(key) };
      form.submitButton = { textContent: 'Create route', disabled: false };
      form.reset = () => { for (const field of fields.values()) field.value = field.defaultValue; };
    }
  }

  install(html);
  const context = {
    AbortController, Date, console,
    document: {
      querySelector: selector => nodes.get(selector.slice(1)) || null,
      querySelectorAll: () => [],
      addEventListener: (type, fn) => documentListeners.set(type, fn)
    },
    FormData: class { constructor(form) { this.form = form; } entries() { return [...this.form.fields].map(([key, field]) => [key, field.value]); } },
    sessionStorage: { getItem: () => 'admin-ui-fixture-token', setItem() {}, removeItem() {} },
    setTimeout: () => 1, clearTimeout() {},
    PassoRouteEditor: {
      mount(options) {
        const instance = { options, stops: [], setCalls: [], resetCount: 0, destroyed: false, error: null,
          getStops() { if (this.error) throw this.error; return JSON.parse(JSON.stringify(this.stops)); },
          setStops(stops) { this.setCalls.push(stops); this.stops = JSON.parse(JSON.stringify(stops)); },
          reset() { this.stops = []; this.resetCount++; },
          destroy() { this.destroyed = true; }
        };
        instances.push(instance);
        return instance;
      },
      selectDepartureArea: options => areaHandler ? areaHandler(options) : Promise.resolve(null)
    },
    fetch: async (url, options) => {
      calls.push({ url, options });
      let data = {};
      if (url === '/api/auth/me') data = { user: { role: 'ADMIN', fullName: 'UI Test Administrator' } };
      if (url === '/api/admin/routes') data = { routes };
      if (url === '/api/routes/current_route' && !options.method) data = detailHandler ? await detailHandler() : { route: routes[0], stops: savedStops };
      if (url.endsWith('/departure-settings') && !options.method) data = { settings: { latitude: 8.95, longitude: 38.76, radius_m: 300 } };
      return { ok: true, status: 200, json: async () => data };
    }
  };
  context.window = context;
  vm.runInNewContext(fs.readFileSync(path.join(folder, 'admin.js'), 'utf8'), context);
  function click(selector, dataset) {
    const button = { dataset, disabled: false };
    documentListeners.get('click')({ target: { closest: requested => requested === selector ? button : null } });
    return button;
  }
  return {
    get: id => nodes.get(id), calls, instances,
    current: () => instances.at(-1),
    navigate: view => click('[data-view]', { view }),
    edit: () => click('[data-edit-route]', { editRoute: 'current_route' }),
    departure: () => click('[data-geofence-route]', { geofenceRoute: 'current_route' }),
    writes: () => calls.filter(call => call.options.method && call.options.method !== 'GET'),
    setDetailsHandler: handler => { detailHandler = handler; },
    setAreaHandler: handler => { areaHandler = handler; }
  };
}

test('admin route creation and editing send the selected stop payload through their existing API entry points', async () => {
  const app = fixture(); await flush(); app.navigate('routes'); await flush();
  const form = app.get('create-route-form');
  const instance = app.current();
  assert.ok(instance);
  assert.doesNotMatch(app.get('page-content').innerHTML, /stopsText|latitude \| longitude/);
  form.elements.namedItem('name').value = 'Selected route';
  form.elements.namedItem('nameAm').value = 'መስመር';
  instance.stops = savedStops.map(({ id, ...stop }) => stop);
  await form.emit('submit');
  const created = app.writes()[0];
  assert.equal(created.url, '/api/routes');
  assert.equal(created.options.method, 'POST');
  assert.equal(created.options.headers.Authorization, 'Bearer admin-ui-fixture-token');
  assert.deepEqual(JSON.parse(created.options.body).stops, instance.stops);
  assert.equal(instance.destroyed, true, 'A saved route refresh must dispose the old location controls.');
  app.edit(); await flush();
  assert.deepEqual(app.current().setCalls[0], savedStops, 'Editing must pass existing IDs and stop metadata into the selector.');
  assert.equal(app.get('create-route-form').elements.namedItem('routeId').value, 'current_route');
  app.current().stops[0].latitude = 9.02;
  await app.get('create-route-form').emit('submit');
  const updated = app.writes()[1];
  assert.equal(updated.url, '/api/routes/current_route');
  assert.equal(updated.options.method, 'PUT');
  assert.deepEqual(JSON.parse(updated.options.body).stops.map(stop => stop.id), ['current_stop_a', 'current_stop_b']);
});

test('invalid unselected locations prevent route writes and cancel returns to a clean create form', async () => {
  const app = fixture(); await flush(); app.navigate('routes'); await flush();
  const form = app.get('create-route-form');
  app.current().error = Error('Choose every route location from the search results or map before saving.');
  form.elements.namedItem('name').value = 'Unsaved name';
  await form.emit('submit');
  assert.equal(app.writes().length, 0);
  assert.match(app.get('route-form-message').textContent, /Choose every route location/);
  assert.equal(form.elements.namedItem('name').value, 'Unsaved name');
  app.current().error = null;
  app.edit(); await flush();
  const editing = app.current();
  app.get('route-cancel').emit('click');
  assert.equal(editing.resetCount, 1);
  assert.equal(form.elements.namedItem('routeId').value, '');
  assert.equal(form.submitButton.textContent, 'Create route');
  assert.equal(app.get('route-cancel').hidden, true);
});

test('leaving route management rejects late edit responses and aborts an open departure location selection', async () => {
  const app = fixture(); await flush(); app.navigate('routes'); await flush();
  let completeDetails;
  app.setDetailsHandler(() => new Promise(resolve => { completeDetails = resolve; }));
  app.edit(); await flush();
  const editing = app.current();
  app.navigate('overview'); await flush();
  completeDetails({ route: { id: 'current_route', name: 'Stale response' }, stops: savedStops }); await flush();
  assert.equal(editing.destroyed, true);
  assert.equal(editing.setCalls.length, 0);
  assert.equal(app.get('create-route-form'), undefined);
  app.navigate('routes'); await flush();
  let signal;
  app.setAreaHandler(options => new Promise(resolve => {
    signal = options.signal;
    signal.addEventListener('abort', () => resolve(null), { once: true });
  }));
  app.departure(); await flush();
  assert.equal(signal.aborted, false);
  app.navigate('overview'); await flush();
  assert.equal(signal.aborted, true);
  assert.equal(app.writes().length, 0);
});
