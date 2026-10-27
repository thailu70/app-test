'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const mobility = require('../public/admin/mobility');
const flush = () => new Promise(resolve => setImmediate(resolve));

class Element {
  constructor(tag, ownerDocument) { this.tagName = tag; this.ownerDocument = ownerDocument; this.children = []; this.dataset = {}; this.attributes = {}; this.listeners = new Map(); this.value = ''; this.disabled = false; this.hidden = false; this.content = ''; }
  set textContent(value) { this.content = String(value); this.children = []; }
  get textContent() { return this.content + this.children.map(child => child.textContent).join(' '); }
  set innerHTML(_) { throw new Error('Dynamic HTML must not be used for mobility records.'); }
  get firstChild() { return this.children[0]; }
  append(...nodes) { for (const node of nodes) { node.parent = this; this.children.push(node); } }
  replaceChildren(...nodes) { this.children = []; this.content = ''; this.append(...nodes); }
  setAttribute(name, value) { this.attributes[name] = String(value); }
  getAttribute(name) { return this.attributes[name]; }
  addEventListener(name, listener) { this.listeners.set(name, listener); }
  removeEventListener(name, listener) { if (this.listeners.get(name) === listener) this.listeners.delete(name); }
  emit(name, target = this, values = {}) { return this.listeners.get(name)?.({ target, preventDefault() {}, ...values }); }
  focus() { this.focused = true; }
  matches(selector) {
    if (selector[0] === '#') return this.id === selector.slice(1);
    const data = /^\[data-mobility-action(?:="([^"]+)")?\]$/.exec(selector);
    if (data) return this.dataset.mobilityAction !== undefined && (!data[1] || this.dataset.mobilityAction === data[1]);
    return this.tagName === selector;
  }
  closest(selector) { return this.matches(selector) ? this : this.parent?.closest(selector); }
  querySelectorAll(selectors) {
    const result = [], filters = selectors.split(',');
    for (const child of this.children) { if (filters.some(selector => child.matches(selector))) result.push(child); result.push(...child.querySelectorAll(selectors)); }
    return result;
  }
  querySelector(selector) { return this.querySelectorAll(selector)[0] || null; }
}

function fixture(overrides = {}) {
  const doc = { createElement: tag => new Element(tag, doc) }, container = doc.createElement('div');
  for (const match of mobility.markup().matchAll(/<([a-z]+)[^>]*\bid="([^"]+)"[^>]*>/g)) {
    const element = doc.createElement(match[1]); element.id = match[2]; container.append(element);
  }
  const trip = { id: 'trip-1', origin: '<img onerror=alert(1)> Addis', destination: 'Hawassa', departureAt: '2027-01-02T03:00:00Z', fareEtb: 650, seats: 8, availableSeats: 8, driverId: 'driver-1', driverName: 'Driver One', vehiclePlate: 'AA-123', status: 'PENDING_REVIEW' };
  const group = { id: 'group-1', kind: 'GROUP', origin: 'Addis', destination: 'Bishoftu', people: 6, travelDate: '2027-01-03T04:00:00Z', vehicleType: 'MINIVAN_8', customerName: 'Buyer', customerPhone: '+251911111111', details: 'A family journey', status: 'REQUESTED' };
  const cargo = { ...group, id: 'cargo-1', kind: 'CARGO', people: null, details: 'Furniture', photoUrl: '/api/mobility/requests/cargo-1/photo' };
  const options = { success: true, drivers: [{ id: 'driver-1', fullName: 'Driver One', phone: '0911111111' }, { id: 'driver-2', fullName: 'Driver Two', phone: '0922222222' }], vehicles: [
    { id: 'vehicle-1', driverId: 'driver-1', plateNumber: 'AA-123', vehicleType: 'MINIVAN_8', capacityLimit: 8 },
    { id: 'vehicle-2', driverId: 'driver-2', plateNumber: 'AA-456', vehicleType: 'MINIVAN_8', capacityLimit: 8 }
  ] };
  const calls = [], handlers = new Map(), failures = new Map(), revoked = [], photos = [];
  const api = async (endpoint, config = {}) => {
    const call = { endpoint, ...config }; calls.push(call);
    if (failures.has(endpoint)) throw failures.get(endpoint);
    if (handlers.has(endpoint)) return handlers.get(endpoint)(call);
    if (endpoint.endsWith('/trips')) return { success: true, trips: [trip] };
    if (endpoint.includes('/requests?kind=GROUP')) return { success: true, requests: [group] };
    if (endpoint.includes('/requests?kind=CARGO')) return { success: true, requests: [cargo] };
    if (endpoint.includes('/options?')) return options;
    if (endpoint.endsWith('/review')) { Object.assign(trip, { status: JSON.parse(config.body).decision === 'APPROVE' ? 'APPROVED' : 'REJECTED' }); return { success: true, trip: { ...trip } }; }
    if (endpoint.endsWith('/assign')) { const row = endpoint.includes('group-1') ? group : cargo; Object.assign(row, JSON.parse(config.body), { status: 'ASSIGNED' }); return { success: true, request: { ...row } }; }
    if (endpoint.endsWith('/status')) { const row = endpoint.includes('group-1') ? group : cargo; Object.assign(row, JSON.parse(config.body)); return { success: true, request: { ...row } }; }
    throw new Error('Unexpected API request');
  };
  const cleanup = mobility.mount({ container, api, token: 'admin-private-token', fetchPhoto: async (...args) => { photos.push(args); return { ok: true, headers: { get: () => 'image/jpeg' }, blob: async () => ({ size: 1024 }) }; },
    objectUrls: { createObjectURL: () => 'blob:private-cargo-photo', revokeObjectURL: url => revoked.push(url) }, ...overrides });
  const get = id => container.querySelector('#mobility-' + id);
  const action = name => get('detail').querySelectorAll('[data-mobility-action]').find(button => button.dataset.mobilityAction === name);
  return { container, get, trip, group, cargo, options, calls, handlers, failures, photos, revoked, cleanup,
    select: () => get('list').emit('click', get('list').children[0]),
    click: name => get('detail').emit('click', action(name)), action,
    writes: () => calls.filter(call => call.method === 'POST') };
}

test('admin mobility navigation loads before the portal, safely renders trip details and only approves pending proposals', async () => {
  const index = fs.readFileSync(path.join(__dirname, '../public/admin/index.html'), 'utf8');
  const main = fs.readFileSync(path.join(__dirname, '../public/admin/admin.js'), 'utf8');
  assert.match(index, /data-view="mobility"/); assert.ok(index.indexOf('/admin/mobility.js') < index.indexOf('/admin/admin.js'));
  assert.match(main, /PassoMobilityAdmin\.mount/); assert.equal((main.match(/mobilityCleanup\?\.\(\)/g) || []).length, 2);
  const app = fixture(); await flush(); app.select();
  assert.match(app.get('detail').textContent, /<img onerror=alert\(1\)> Addis/);
  assert.equal(app.get('detail').querySelectorAll('img').length, 0);
  assert.match(app.get('list').textContent, /Ethiopia/);
  app.get('note').value = 'Approved at the driver’s proposed fare';
  await app.click('APPROVE');
  assert.deepEqual(JSON.parse(app.writes()[0].body), { decision: 'APPROVE', note: 'Approved at the driver’s proposed fare' });
  assert.match(app.get('message').textContent, /published/); assert.equal(app.action('APPROVE'), undefined);
  assert.match(app.get('detail').textContent, /Existing bookings and the approved fare are retained/);
  app.cleanup();
});

test('group assignment accepts only a current driver-vehicle pair, validates the optional quote and never marks a payment', async () => {
  const app = fixture(); await flush(); app.get('tab-group').emit('click'); await flush(); app.select();
  assert.match(app.get('detail').textContent, /6/);
  app.get('driver').value = 'driver-1'; app.get('driver').emit('change');
  assert.equal(app.get('vehicle').children.length, 2);
  assert.equal(app.get('vehicle').children[1].value, 'vehicle-1');
  app.get('vehicle').value = 'vehicle-2'; await app.click('assign'); assert.equal(app.writes().length, 0);
  app.get('vehicle').value = 'vehicle-1'; app.get('quote').value = '-1'; await app.click('assign'); assert.equal(app.writes().length, 0);
  app.get('quote').value = '4500'; app.get('note').value = 'Assigned for the requested date'; await app.click('assign');
  assert.deepEqual(JSON.parse(app.writes()[0].body), { driverId: 'driver-1', vehicleId: 'vehicle-1', quoteEtb: 4500, note: 'Assigned for the requested date' });
  assert.match(app.get('detail').textContent, /does not charge the customer or mark it accepted/);
  await app.click('COMPLETED');
  assert.equal(JSON.parse(app.writes()[1].body).status, 'COMPLETED'); assert.equal(app.action('assign'), undefined);
  assert.match(app.get('detail').textContent, /record is closed/); app.cleanup();
});

test('failed assignment retains form input, duplicate saves are blocked and navigation cleanup aborts pending writes', async () => {
  const app = fixture(); await flush(); app.get('tab-group').emit('click'); await flush(); app.select();
  app.get('driver').value = 'driver-1'; app.get('driver').emit('change'); app.get('vehicle').value = 'vehicle-1'; app.get('quote').value = '1200';
  app.failures.set('/api/mobility/admin/requests/group-1/assign', new Error('Driver is no longer available'));
  await app.click('assign'); assert.match(app.get('message').textContent, /no longer available/); assert.equal(app.get('quote').value, '1200');
  app.failures.clear(); let complete;
  app.handlers.set('/api/mobility/admin/requests/group-1/assign', () => new Promise(resolve => { complete = resolve; }));
  const pending = app.click('assign'); await app.click('assign');
  assert.equal(app.writes().length, 2);
  const before = app.get('message').textContent; app.cleanup();
  assert.equal(app.writes()[1].signal.aborted, true);
  complete({ success: true, request: { ...app.group, status: 'ASSIGNED' } }); await pending;
  assert.equal(app.get('message').textContent, before); assert.equal(app.get('detail').listeners.size, 0);
});

test('cargo photos stay behind admin authentication, reject external locations and revoke local previews on navigation', async () => {
  for (const path of ['https://other.test/photo', '//other.test/photo', '/api/mobility/../../secret', '/api/mobility/photos/a?token=secret', 'javascript:alert(1)']) assert.equal(mobility.photoPath(path), null);
  const app = fixture(); await flush(); app.get('tab-cargo').emit('click'); await flush(); app.select();
  await app.click('photo');
  assert.equal(app.photos[0][0], '/api/mobility/requests/cargo-1/photo');
  assert.equal(app.photos[0][1].headers.Authorization, 'Bearer admin-private-token');
  assert.equal(app.get('photo').querySelector('img').src, 'blob:private-cargo-photo');
  app.get('tab-group').emit('click'); await flush();
  assert.deepEqual(app.revoked, ['blob:private-cargo-photo']); app.cleanup();
});

test('tab keyboard navigation aborts stale responses and refresh errors retain the last loaded list', async () => {
  const app = fixture(); await flush();
  app.get('tab-trips').emit('keydown', app.get('tab-trips'), { key: 'ArrowRight' }); await flush();
  assert.equal(app.get('tab-group').getAttribute('aria-selected'), 'true'); assert.equal(app.get('tab-group').focused, true);
  app.failures.set('/api/mobility/admin/requests?kind=GROUP', new Error('Connection lost'));
  await app.get('refresh').emit('click');
  assert.match(app.get('list').textContent, /Bishoftu/); assert.match(app.get('message').textContent, /Connection lost/);
  app.cleanup();
});
