'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const picker = require('../public/shared/location-picker');
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
const point = { name: 'Bole Medhanialem', latitude: 8.997, longitude: 38.787 };

class Element {
  constructor(tagName, doc) {
    this.tagName = tagName.toUpperCase(); this.ownerDocument = doc;
    this.children = []; this.attributes = new Map(); this.events = new Map(); this.hidden = false;
    this.value = ''; this.textContent = ''; this.className = '';
    const classes = new Set();
    this.classList = { toggle(name, enabled) { if (enabled) classes.add(name); else classes.delete(name); } };
  }
  append(...children) { for (const child of children) { child.parentNode = this; this.children.push(child); } }
  replaceChildren(...children) { this.children = []; this.append(...children); }
  remove() { if (this.parentNode) this.parentNode.children = this.parentNode.children.filter(child => child !== this); }
  setAttribute(name, value) { this.attributes.set(name, String(value)); }
  getAttribute(name) { return this.attributes.get(name) ?? null; }
  removeAttribute(name) { this.attributes.delete(name); }
  addEventListener(name, handler) { if (!this.events.has(name)) this.events.set(name, new Set()); this.events.get(name).add(handler); }
  removeEventListener(name, handler) { this.events.get(name)?.delete(handler); }
  emit(name, event = {}) { for (const handler of this.events.get(name) || []) handler({ target: this, preventDefault() {}, ...event }); }
  focus() { if (this.ownerDocument.activeElement === this) return; this.ownerDocument.activeElement = this; this.emit('focus'); }
  contains(node) { return node === this || this.children.some(child => child.contains(node)); }
  showModal() { this.open = true; }
  close() { this.open = false; this.emit('close'); }
}
function fixture(options = {}) {
  const doc = new Element('document'); doc.ownerDocument = doc; doc.defaultView = {};
  doc.createElement = tag => new Element(tag, doc);
  doc.body = doc.createElement('body'); doc.append(doc.body);
  const host = doc.createElement('div'); doc.body.append(host);
  const find = (node, predicate) => predicate(node) ? node : node.children.map(child => find(child, predicate)).find(Boolean);
  const changes = [], calls = [];
  const controller = picker.mount(host, { label: 'Departure', onChange: value => changes.push(value),
    api: async (path, request) => { calls.push({ path, signal: request.signal }); return { places: [point] }; }, ...options });
  return { doc, host, controller, changes, calls,
    input: find(host, node => node.getAttribute('role') === 'combobox'),
    results: find(host, node => node.getAttribute('role') === 'listbox'),
    find: predicate => find(doc, predicate) };
}

test('saved points require real, named coordinates while valid zero axes remain supported', () => {
  assert.equal(picker.validLocation({ name: 'Missing', latitude: null, longitude: 38 }), null);
  assert.equal(picker.validLocation({ name: 'Missing', latitude: ' ', longitude: 38 }), null);
  assert.equal(picker.validLocation({ name: 'Origin', latitude: 0, longitude: 0 }), null);
  assert.equal(picker.validLocation({ name: 'Invalid', latitude: 91, longitude: 38 }), null);
  assert.deepEqual(picker.validLocation({ name: ' Equator ', latitude: '0', longitude: '38' }), { name: 'Equator', latitude: 0, longitude: 38 });
});

test('only the latest search result can reach the visible dropdown, even if API ignores cancellation', async () => {
  const requests = [], results = [], statuses = [];
  const search = picker.createSearch({ delay: 0, api: (url, options) => new Promise(resolve => requests.push({ url, options, resolve })),
    onResults: places => results.push(places), onStatus: message => statuses.push(message) });
  search.update('Bole'); await pause(5);
  search.update('CMC'); await pause(5);
  assert.equal(requests[0].options.signal.aborted, true);
  requests[1].resolve({ places: [{ ...point, name: 'CMC' }] }); await pause(0);
  requests[0].resolve({ places: [{ ...point, name: 'Bole stale' }] }); await pause(0);
  assert.deepEqual(results.at(-1).map(value => value.name), ['CMC']);
  assert.equal(statuses.at(-1), 'Choose a location from the results.');
  search.destroy();
});

test('failed online search can still present saved locations, and teardown suppresses delayed requests', async () => {
  const rows = [], messages = []; let calls = 0;
  const search = picker.createSearch({ delay: 0, api: async () => { calls++; return { places: [point], searchAvailable: false, message: 'Showing saved stops.' }; },
    onResults: places => rows.push(places), onStatus: message => messages.push(message) });
  search.update('Bole'); await pause(5);
  assert.deepEqual(rows.at(-1), [point]); assert.equal(messages.at(-1), 'Showing saved stops.');
  search.update('Addis'); search.destroy(); await pause(5);
  assert.equal(calls, 1);
});

test('selecting a real suggestion saves coordinates and editing its text immediately invalidates them', async () => {
  const app = fixture();
  app.input.focus(); app.input.value = 'Bole'; app.input.emit('input'); await pause(280);
  assert.equal(app.calls.length, 1); assert.match(app.calls[0].path, /\/api\/mobility\/locations\?q=Bole$/);
  assert.equal(app.results.hidden, false);
  app.input.emit('keydown', { key: 'Enter' });
  assert.deepEqual(app.controller.getValue(), point); assert.deepEqual(app.changes.at(-1), point);
  const copy = app.controller.getValue(); copy.name = 'External mutation'; assert.equal(app.controller.getValue().name, point.name);
  app.input.value = 'A different place'; app.input.emit('input');
  assert.equal(app.controller.getValue(), null); assert.equal(app.changes.at(-1), null);
  app.controller.destroy(); await pause(280);
  assert.equal(app.calls.length, 1); assert.equal(app.host.children.length, 0);
  assert.equal([...app.doc.events.values()].reduce((count, handlers) => count + handlers.size, 0), 0);
});

test('existing route points load unchanged and a cancelled map leaves the saved point intact', () => {
  const app = fixture({ initial: point });
  assert.deepEqual(app.controller.getValue(), point); assert.equal(app.input.value, point.name);
  assert.equal(app.changes.length, 0);
  app.find(node => node.className === 'passo-location-map-button').emit('click');
  assert.ok(app.find(node => node.tagName === 'DIALOG'));
  app.find(node => node.className === 'passo-location-cancel').emit('click');
  assert.equal(app.find(node => node.tagName === 'DIALOG'), undefined);
  assert.deepEqual(app.controller.getValue(), point);
  app.controller.setValue({ name: 'Bad coordinate', latitude: '', longitude: 38 });
  assert.equal(app.controller.getValue(), null);
  app.controller.destroy();
});

test('map selection requires an explicit valid pin and name, then commits and disposes the map', () => {
  const app = fixture(); let mapClick, removed = 0, markerPoint;
  const map = { setView() {}, invalidateSize() {}, remove() { removed++; }, on(name, handler) { if (name === 'click') mapClick = handler; } };
  app.doc.defaultView.L = {
    map: () => map,
    tileLayer: () => ({ addTo: () => ({ on() {} }) }),
    divIcon: value => value,
    marker: position => {
      markerPoint = position;
      return { addTo() { return this; }, on() {}, setLatLng(value) { markerPoint = value; }, getLatLng() { return { lat: markerPoint[0], lng: markerPoint[1] }; } };
    }
  };
  app.find(node => node.className === 'passo-location-map-button').emit('click');
  const name = app.find(node => node.id?.endsWith('-map-name'));
  const confirm = app.find(node => node.className === 'passo-location-confirm');
  assert.equal(confirm.disabled, true);
  name.value = 'Loading point'; name.emit('input'); assert.equal(confirm.disabled, true);
  mapClick({ latlng: { lat: 9.01, lng: 38.77 } });
  assert.equal(confirm.disabled, false);
  mapClick({ latlng: { lat: 0, lng: 0 } }); assert.equal(confirm.disabled, true);
  mapClick({ latlng: { lat: 9.02, lng: 38.78 } });
  confirm.emit('click');
  assert.deepEqual(app.controller.getValue(), { name: 'Loading point', latitude: 9.02, longitude: 38.78 });
  assert.equal(removed, 1); assert.equal(app.find(node => node.tagName === 'DIALOG'), undefined);
  app.controller.destroy();
});
