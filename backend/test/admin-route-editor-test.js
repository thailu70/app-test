'use strict';

const { test } = require('node:test');
const assert = require('node:assert/strict');
const editor = require('../public/admin/route-editor');

function fixture() {
  const mounted = [];
  const doc = { createElement: tag => new Element(tag, doc) };
  doc.defaultView = { PassoLocationPicker: { mount(host, options) {
    const input = doc.createElement('input');
    host.append(input);
    const field = {
      selected: editor.validLocation(options.initial) ? { ...options.initial } : null,
      options, input, destroyed: false,
      getValue() { return this.selected && { ...this.selected }; },
      setValue(value) { this.selected = value; input.value = value?.name || ''; options.onChange?.(value); },
      destroy() { this.destroyed = true; }
    };
    host.field = field;
    input.value = options.initial?.name || '';
    mounted.push(field);
    return field;
  } } };
  doc.body = doc.createElement('body');
  const container = doc.createElement('section');
  doc.body.append(container);
  const api = async () => { throw Error('Opening the editor must not write or fetch route records.'); };
  return { doc, container, mounted, api,
    rows: () => container.querySelector('.route-stop-list').children,
    field: row => row.querySelector('[data-stop-location]').field };
}

class Element {
  constructor(tag, doc) {
    this.tagName = tag;
    this.ownerDocument = doc;
    this.children = [];
    this.selectors = new Map();
    this.listeners = new Map();
    this.attributes = new Map();
    this.value = '';
    this.hidden = false;
    this.disabled = false;
    this.open = false;
  }
  append(child) {
    child.remove();
    this.children.push(child);
    child.parent = this;
  }
  remove() {
    if (this.parent) this.parent.children = this.parent.children.filter(child => child !== this);
    this.parent = null;
  }
  replaceChildren() { this.children.forEach(child => { child.parent = null; }); this.children = []; this.selectors.clear(); }
  setAttribute(name, value) { this.attributes.set(name, value); }
  focus() { this.focused = true; }
  addEventListener(name, listener) { this.listeners.set(name, listener); }
  removeEventListener(name, listener) { if (this.listeners.get(name) === listener) this.listeners.delete(name); }
  emit(name) { return this.listeners.get(name)?.({ target: this, preventDefault() {} }); }
  click() { if (!this.disabled) this.emit('click'); }
  showModal() { this.open = true; }
  close() { this.open = false; this.emit('close'); }
  querySelector(selector) {
    if (this.selectors.has(selector)) return this.selectors.get(selector);
    if (selector === 'input') {
      for (const child of this.children) {
        if (child.tagName === 'input') return child;
        const descendant = child.querySelector(selector);
        if (descendant) return descendant;
      }
    }
    return null;
  }
  set innerHTML(markup) {
    this.replaceChildren();
    const add = (selector, tag = 'div') => {
      const child = this.ownerDocument.createElement(tag);
      this.selectors.set(selector, child);
      this.append(child);
      return child;
    };
    if (markup.includes('class="route-stop-list"')) {
      add('.route-stop-list'); add('[data-add-route-stop]', 'button');
    } else if (markup.includes('data-stop-location')) {
      add('legend', 'legend'); add('[data-stop-location]');
      add('.route-stop-name input', 'input'); add('.route-stop-am input', 'input');
      for (const action of ['up', 'down', 'remove']) add('[data-stop-' + action + ']', 'button');
    } else if (markup.includes('data-departure-picker')) {
      add('form', 'form'); add('[data-route-area-name]'); add('[data-departure-picker]');
      add('[name="radius"]', 'input'); add('[data-area-error]'); add('[data-area-cancel]', 'button');
    }
  }
}

const stops = [
  { id: 'route_stop_a', stopName: 'Garment', stopNameAm: 'ጋርመንት', latitude: 8.95, longitude: 38.76,
    scheduledMorningTime: '06:40', scheduledEveningTime: '17:45', maxCapacity: 17 },
  { id: 'route_stop_b', stopName: 'Bole', stopNameAm: 'ቦሌ', latitude: 8.99, longitude: 38.79,
    scheduledMorningTime: '07:00', scheduledEveningTime: '17:30', maxCapacity: 19 }
];

test('route creation requires selected locations and never adopts another route stop identity', () => {
  const app = fixture();
  const control = editor.mount(app);
  assert.equal(app.rows().length, 2);
  assert.throws(() => control.getStops(), /Choose every route location/);
  const [departure, destination] = app.rows();
  app.field(departure).setValue({ id: 'foreign_saved_stop', name: ' Selected departure ', latitude: '9.01', longitude: '38.72' });
  app.field(destination).setValue({ name: 'Selected destination', latitude: 9.04, longitude: 38.77 });
  const payload = control.getStops();
  assert.deepEqual(payload.map(stop => [stop.stopName, stop.latitude, stop.longitude]),
    [['Selected departure', 9.01, 38.72], ['Selected destination', 9.04, 38.77]]);
  assert.ok(payload.every(stop => !Object.hasOwn(stop, 'id')));
  app.field(destination).setValue(null);
  app.field(destination).input.value = 'An unselected search query';
  assert.throws(() => control.getStops(), /Choose every route location/, 'Typed text cannot reuse previous coordinates.');
  control.destroy();
});

test('editing and reordering selected points retains route-owned IDs, schedules, capacities and unsaved locations', () => {
  const app = fixture();
  const control = editor.mount(app);
  control.setStops(stops);
  const [first, second] = app.rows();
  app.field(first).setValue({ id: 'other_route_stop', name: 'Changed departure', latitude: 9.02, longitude: 38.8 });
  first.querySelector('.route-stop-am input').value = 'የተለወጠ መነሻ';
  second.querySelector('[data-stop-up]').click();
  const payload = control.getStops();
  assert.deepEqual(payload.map(stop => stop.id), ['route_stop_b', 'route_stop_a']);
  assert.deepEqual(payload[0], stops[1]);
  assert.deepEqual(payload[1], { ...stops[0], stopName: 'Changed departure', stopNameAm: 'የተለወጠ መነሻ', latitude: 9.02, longitude: 38.8 });
  assert.equal(first.querySelector('legend').textContent, 'Destination');
  assert.equal(second.querySelector('legend').textContent, 'Departure');
  assert.equal(app.field(first).destroyed, false, 'Moving a point must keep its current search/selection state.');
  assert.equal(stops[0].stopName, 'Garment', 'The loaded route record is not mutated before saving.');
  control.destroy();
});

test('intermediate stops can be inserted and removed while reset cancels editing and disposes pickers', () => {
  const app = fixture();
  const control = editor.mount(app);
  control.setStops(stops);
  const [departure, destination] = app.rows();
  assert.equal(departure.querySelector('[data-stop-remove]').disabled, true);
  app.container.querySelector('[data-add-route-stop]').click();
  const middle = app.rows()[1];
  assert.equal(middle.querySelector('legend').textContent, 'Stop 1');
  assert.equal(app.rows()[2], destination);
  app.field(middle).setValue({ name: 'Intermediate location', latitude: 9.03, longitude: 38.75 });
  assert.deepEqual(control.getStops().map(stop => stop.id), ['route_stop_a', undefined, 'route_stop_b']);
  middle.querySelector('[data-stop-remove]').click();
  assert.equal(app.field(middle).destroyed, true);
  assert.deepEqual(control.getStops(), stops);
  control.reset();
  assert.equal(app.field(departure).destroyed, true);
  assert.equal(app.field(destination).destroyed, true);
  assert.equal(app.rows().length, 2);
  assert.throws(() => control.getStops(), /Choose every route location/);
  control.destroy();
  assert.ok(app.mounted.every(field => field.destroyed));
  assert.equal(app.container.children.length, 0);
  assert.throws(() => control.getStops(), /Refresh the route form/);
});

test('departure area saves a chosen point and valid integer radius only after confirmation', async () => {
  const app = fixture();
  const pending = editor.selectDepartureArea({ api: app.api, document: app.doc,
    routeName: 'Garment to Bole', settings: { latitude: 8.95, longitude: 38.76, radius_m: 300 } });
  const dialog = app.doc.body.children.at(-1);
  assert.equal(dialog.open, true);
  assert.equal(dialog.querySelector('[data-route-area-name]').textContent, 'Garment to Bole');
  const field = dialog.querySelector('[data-departure-picker]').field;
  for (const radius of ['', '24', '5001', '25.5']) {
    dialog.querySelector('[name="radius"]').value = radius;
    dialog.querySelector('form').emit('submit');
    assert.equal(dialog.open, true);
    assert.equal(dialog.querySelector('[data-area-error]').hidden, false);
  }
  field.setValue(null);
  dialog.querySelector('[name="radius"]').value = '500';
  dialog.querySelector('form').emit('submit');
  assert.equal(dialog.open, true);
  field.setValue({ name: 'Selected departure', latitude: '9.1', longitude: '38.7' });
  dialog.querySelector('form').emit('submit');
  assert.deepEqual(await pending, { latitude: 9.1, longitude: 38.7, radiusMeters: 500 });
  assert.equal(dialog.parent, null);
  assert.equal(field.destroyed, true);
});

test('cancelling or leaving departure settings returns no change and removes the picker', async () => {
  for (const action of ['cancel', 'escape', 'abort']) {
    const app = fixture();
    const controller = new AbortController();
    const pending = editor.selectDepartureArea({ api: app.api, document: app.doc,
      signal: controller.signal, settings: { latitude: 9, longitude: 38, radius_m: 300 } });
    const dialog = app.doc.body.children.at(-1);
    const field = dialog.querySelector('[data-departure-picker]').field;
    if (action === 'cancel') dialog.querySelector('[data-area-cancel]').click();
    if (action === 'escape') dialog.emit('cancel');
    if (action === 'abort') controller.abort();
    assert.equal(await pending, null, action);
    assert.equal(dialog.parent, null, action);
    assert.equal(field.destroyed, true, action);
  }
});

test('missing, zero and out-of-range locations cannot become saved route coordinates', () => {
  for (const location of [null, { name: 'Unlocated' }, { name: 'Blank', latitude: '', longitude: '' },
    { name: 'Placeholder', latitude: 0, longitude: 0 }, { name: 'Null', latitude: null, longitude: 38 },
    { name: 'Out of range', latitude: 95, longitude: 38 }, { name: 'Not numeric', latitude: 'abc', longitude: 38 }]) {
    assert.throws(() => editor.stopPayload({}, location, ''), /Choose every route location/);
  }
  assert.throws(() => editor.stopPayload({}, { name: 'x'.repeat(101), latitude: 9, longitude: 38 }, ''), /100/);
  assert.throws(() => editor.stopPayload({}, { name: 'Short', latitude: 9, longitude: 38 }, 'x'.repeat(101)), /100/);
  assert.deepEqual(editor.stopPayload({}, { name: 'Bole, ' + 'address '.repeat(20), latitude: 9, longitude: 38 }, '', 'Bole'),
    { stopName: 'Bole', stopNameAm: 'Bole', latitude: 9, longitude: 38 });
});

test('long location addresses use a visible short stop name and later location choices preserve manual labels', () => {
  const app = fixture();
  const control = editor.mount(app);
  const [departure, destination] = app.rows();
  const longAddress = 'Bole, ' + 'Street name '.repeat(14);
  app.field(departure).setValue({ name: longAddress, latitude: 9, longitude: 38.7 });
  app.field(destination).setValue({ name: 'Destination', latitude: 9.04, longitude: 38.77 });
  assert.equal(departure.querySelector('.route-stop-name input').value, 'Bole');
  assert.equal(app.field(departure).getValue().name, longAddress);
  assert.equal(control.getStops()[0].stopName, 'Bole');
  const shortName = departure.querySelector('.route-stop-name input');
  shortName.value = 'Passenger boarding gate'; shortName.emit('input');
  app.field(departure).setValue({ name: 'Updated point', latitude: 9.05, longitude: 38.72 });
  assert.equal(control.getStops()[0].stopName, 'Passenger boarding gate');
  assert.equal(control.getStops()[0].latitude, 9.05);
  control.destroy();
});
