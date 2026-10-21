const { test } = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const code = fs.readFileSync(require('node:path').join(__dirname, '../public/company/company.js'), 'utf8');
test('company portal keeps rendering and permits retry after summary failure', async () => {
  const elements = new Map();
  const get = selector => {
    if (!elements.has(selector)) elements.set(selector, { value: 'DAILY_COMMUTE', classList: { add() {}, remove() {}, toggle() {} } });
    return elements.get(selector);
  };
  let fail = true;
  vm.runInNewContext(code, {
    document: { querySelector: get, querySelectorAll: () => [] },
    sessionStorage: { getItem: () => 'local-test-token', removeItem() {} },
    fetch: async url => ({
      ok: !(fail && url.endsWith('/summary')), status: fail && url.endsWith('/summary') ? 503 : 200,
      json: async () => url.endsWith('/vehicle-types') ? { success: true, vehicleTypes: [] } : url.endsWith('/summary') ? { summary: { activeAssignments: 3 } } : url.endsWith('/fleet') ? { vehicles: [{ plateNumber: 'TEST-001', model: 'Bus' }] } : {}
    }),
    Date, console, AbortController, setTimeout, clearTimeout
  });
  // Flush the async startup refresh without introducing a wall-clock delay.
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(get('#assignmentsCount').textContent, '—');
  assert.match(get('#fleetRows').innerHTML, /TEST-001/);
  assert.match(get('#portalStatus').textContent, /Some data could not load/);
  assert.equal(get('#refresh').disabled, false);
  fail = false;
  await get('#refresh').onclick();
  assert.equal(get('#assignmentsCount').textContent, 3);
  assert.match(get('#portalStatus').textContent, /Updated/);
});
