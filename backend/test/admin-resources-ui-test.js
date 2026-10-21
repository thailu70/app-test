const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const resources = require('../public/admin/resources');

const flush = () => new Promise(resolve => setImmediate(resolve));
const readings = (overrides = {}) => ({ success: true, resources: {
  busy: false, cpuPercent: 0, memoryPercent: 42.5, sampledAt: Date.now(),
  limitPercent: 90, recoveryPercent: 85, ...overrides,
} });

function fixture(fetchResources) {
  const elements = new Map();
  for (const match of resources.markup().matchAll(/<[^>]+\bid="([^"]+)"[^>]*>/g)) {
    const listeners = new Map();
    elements.set(match[1], {
      value: /\bvalue="([^"]*)"/.exec(match[0])?.[1] || '',
      textContent: '', className: '', hidden: /\bhidden\b/.test(match[0]), disabled: false,
      addEventListener: (name, listener) => listeners.set(name, listener),
      removeEventListener: (name, listener) => { if (listeners.get(name) === listener) listeners.delete(name); },
      emit: name => listeners.get(name)?.(),
    });
  }
  const timers = new Map();
  let nextTimer = 0, current = true;
  const calls = [];
  const cleanup = resources.mount({
    container: { querySelector: selector => elements.get(selector.slice(1)) },
    fetchResources: signal => { calls.push(signal); return fetchResources(signal); },
    isActive: () => current,
    timers: {
      setTimeout: (fn, ms) => { const id = ++nextTimer; timers.set(id, { fn, ms }); return id; },
      clearTimeout: id => timers.delete(id),
    },
  });
  return {
    get: id => elements.get('resource-' + id), calls, cleanup,
    leave: () => { current = false; cleanup(); },
    set: (id, value) => { const node = elements.get('resource-sim-' + id); node.value = String(value); node.emit('input'); },
    timers,
    runTimer: ms => {
      const timer = [...timers].find(([, entry]) => entry.ms === ms);
      assert.ok(timer, `A ${ms}ms timer is scheduled`);
      timers.delete(timer[0]); timer[1].fn();
    },
  };
}

test('live readings distinguish real zero from unavailable and simulator hysteresis never makes a server request', async () => {
  const app = fixture(async () => readings());
  await flush();
  assert.equal(app.get('cpu-value').textContent, '0%');
  assert.equal(app.get('memory-value').textContent, '42.5%');
  assert.equal(app.get('cpu-meter').hidden, false);
  assert.equal(app.get('live-status').textContent, 'Accepting requests');
  assert.match(resources.markup(), /Preview only — does not change server load/);
  app.set('cpu', 90);
  assert.equal(app.get('sim-status').textContent, 'New requests would pause');
  assert.match(app.get('sim-message').textContent, /retry in 10 seconds/);
  app.set('cpu', 89);
  assert.equal(app.get('sim-status').textContent, 'New requests would pause');
  app.set('cpu', 85);
  assert.equal(app.get('sim-status').textContent, 'Accepting requests');
  app.set('memory', 100);
  app.set('memory', 86);
  assert.equal(app.get('sim-status').textContent, 'New requests would pause');
  app.set('cpu', 86);
  app.set('memory', 85);
  assert.equal(app.get('sim-status').textContent, 'New requests would pause');
  app.set('cpu', 85);
  assert.equal(app.get('sim-status').textContent, 'Accepting requests');
  app.set('memory', 99);
  app.get('sim-reset').emit('click');
  assert.equal(app.get('sim-cpu').value, '0');
  assert.equal(app.get('sim-memory').value, '0');
  assert.equal(app.get('sim-status').textContent, 'Accepting requests');
  assert.equal(app.calls.length, 1, 'Slider and reset events remain entirely local');
  assert.equal(app.get('memory-value').textContent, '42.5%', 'Preview cannot overwrite live readings');
  app.cleanup();
  assert.equal(app.timers.size, 0);
});

test('live server thresholds drive preview and missing metrics are never displayed as zero', async () => {
  const app = fixture(async () => readings({ cpuPercent: null, memoryPercent: null, limitPercent: 95, recoveryPercent: 80 }));
  await flush();
  assert.equal(app.get('cpu-value').textContent, 'Unavailable');
  assert.equal(app.get('cpu-meter').hidden, true);
  assert.equal(app.get('live-status').textContent, 'Waiting for readings');
  assert.match(app.get('thresholds').textContent, /95%.*80%/);
  app.set('cpu', 94);
  assert.equal(app.get('sim-status').textContent, 'Accepting requests');
  app.set('cpu', 95);
  app.set('cpu', 81);
  assert.equal(app.get('sim-status').textContent, 'New requests would pause');
  app.set('cpu', 80);
  assert.equal(app.get('sim-status').textContent, 'Accepting requests');
  app.cleanup();
});

test('polling failures clear stale readings, permit retry and stop when settings closes', async () => {
  let fail = false;
  const app = fixture(async () => { if (fail) throw new Error('Private server details'); return readings({ busy: true, cpuPercent: 92 }); });
  await flush();
  assert.equal(app.get('live-status').textContent, 'New requests paused');
  fail = true;
  app.runTimer(5000);
  await flush();
  assert.equal(app.get('live-status').textContent, 'Live readings unavailable');
  assert.equal(app.get('cpu-value').textContent, 'Unavailable');
  assert.equal(app.get('cpu-meter').hidden, true);
  assert.doesNotMatch(app.get('live-message').textContent, /Private/);
  assert.equal(app.get('refresh').disabled, false);
  fail = false;
  await app.get('refresh').emit('click');
  assert.equal(app.get('live-status').textContent, 'New requests paused');
  assert.equal([...app.timers.values()].filter(timer => timer.ms === 5000).length, 1);
  app.leave();
  assert.equal(app.timers.size, 0);
  const count = app.calls.length;
  app.get('refresh').emit('click');
  assert.equal(app.calls.length, count);
});

test('settings cleanup aborts pending work and late responses cannot update the closed panel', async () => {
  let complete;
  const app = fixture(() => new Promise(resolve => { complete = resolve; }));
  app.leave();
  assert.equal(app.calls[0].aborted, true);
  complete(readings({ cpuPercent: 77 }));
  await flush();
  assert.equal(app.get('cpu-value').textContent, '');
  assert.equal(app.timers.size, 0);
});

test('a timed out reading request releases refresh and retries without overlapping requests', async () => {
  const app = fixture(signal => new Promise((resolve, reject) => {
    signal.addEventListener('abort', () => reject(Object.assign(new Error('aborted'), { name: 'AbortError' })), { once: true });
  }));
  app.get('refresh').emit('click');
  assert.equal(app.calls.length, 1, 'Refresh cannot start an overlapping poll');
  app.runTimer(12000);
  await flush();
  assert.equal(app.get('refresh').disabled, false);
  assert.match(app.get('live-message').textContent, /timed out/);
  app.cleanup();
  assert.equal(app.timers.size, 0);
});

test('admin navigation and logout release Settings polling while unavailable SMS settings leave capacity accessible', async () => {
  const folder = path.join(__dirname, '../public/admin');
  const html = fs.readFileSync(path.join(folder, 'index.html'), 'utf8');
  assert.ok(html.indexOf('/admin/resources.js?') < html.indexOf('/admin/admin.js?'), 'Capacity widget loads before its portal integration');
  const elements = new Map(), timers = new Map(), documentListeners = new Map(), calls = [];
  let nextTimer = 0, hangReadings = false, removed = false;
  function install(markup) {
    for (const match of markup.matchAll(/<[^>]+\bid="([^"]+)"[^>]*>/g)) {
      const listeners = new Map();
      const node = {
        textContent: '', value: /\bvalue="([^"]*)"/.exec(match[0])?.[1] || '',
        disabled: false, hidden: /\bhidden\b/.test(match[0]),
        classList: { add() {}, remove() {}, toggle() {} },
        addEventListener: (name, listener) => listeners.set(name, listener),
        removeEventListener: (name, listener) => { if (listeners.get(name) === listener) listeners.delete(name); },
        emit: name => listeners.get(name)?.(),
        querySelector: selector => elements.get(selector.slice(1)) || null,
      };
      let content = '';
      Object.defineProperty(node, 'innerHTML', {
        get: () => content,
        set: value => { content = value; install(value); },
      });
      elements.set(match[1], node);
    }
  }
  install(html);
  const context = {
    document: {
      querySelector: selector => elements.get(selector.slice(1)) || null,
      querySelectorAll: () => [],
      addEventListener: (name, listener) => documentListeners.set(name, listener),
    },
    sessionStorage: { getItem: () => 'test-admin-token', setItem() {}, removeItem() { removed = true; } },
    AbortController, Date, console,
    setTimeout: (fn, ms) => { const id = ++nextTimer; timers.set(id, { fn, ms }); return id; },
    clearTimeout: id => timers.delete(id),
    fetch: async (url, options) => {
      calls.push({ url, options });
      if (url === '/api/admin/system/resources' && hangReadings) return new Promise((resolve, reject) => {
        options.signal.addEventListener('abort', () => reject(Object.assign(new Error('aborted'), { name: 'AbortError' })), { once: true });
      });
      const body = url === '/api/auth/me' ? { user: { role: 'ADMIN', fullName: 'Test Admin' } }
        : url === '/api/admin/system/resources' ? readings({ cpuPercent: 12 }) : {};
      return { ok: url !== '/api/admin/integrations/afro-sms', status: url === '/api/admin/integrations/afro-sms' ? 503 : 200, json: async () => body };
    },
  };
  context.window = context;
  vm.createContext(context);
  for (const file of ['resources.js', 'admin.js']) vm.runInContext(fs.readFileSync(path.join(folder, file), 'utf8'), context);
  await flush();
  const navigate = view => documentListeners.get('click')({ target: { closest: selector => selector === '[data-view]' ? { dataset: { view } } : null } });
  navigate('settings');
  await flush();
  assert.match(elements.get('page-content').innerHTML, /SMS settings could not be loaded/);
  assert.equal(elements.get('resource-cpu-value').textContent, '12%');
  const readingCall = calls.find(call => call.url === '/api/admin/system/resources');
  assert.equal(readingCall.options.headers.Authorization, 'Bearer test-admin-token');
  assert.equal([...timers.values()].filter(timer => timer.ms === 5000).length, 1);
  navigate('reports');
  await flush();
  assert.equal(timers.size, 0, 'Leaving Settings cancels resource polling');
  hangReadings = true;
  navigate('settings');
  await flush();
  const pending = calls.filter(call => call.url === '/api/admin/system/resources').at(-1);
  assert.equal(pending.options.signal.aborted, false);
  elements.get('logout-button').emit('click');
  await flush();
  assert.equal(pending.options.signal.aborted, true);
  assert.equal(timers.size, 0);
  assert.equal(removed, true);
  assert.equal(elements.get('app-view').hidden, true);
});
