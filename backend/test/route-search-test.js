const { test } = require('node:test');
const assert = require('node:assert/strict');
const os = require('node:os');
const fs = require('node:fs');
const path = require('node:path');
process.env.NODE_ENV = 'test';
process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), 'routepass-search-'));
const { DB } = require('../src/db');
const { routeSearch } = require('../src/services/route-search');
async function search(query) {
  const s = routeSearch(query);
  return DB.prepare(`SELECT r.* FROM routes r WHERE ${s.where} ORDER BY ${s.order}`).all(...s.params);
}
test('route discovery uses parameterized filters, literal text and stable sorting', async () => {
  for (const [id, name, type, origin, destination, fare, active] of [
    ['search_a', 'Test corridor 100%', 'INTERCITY', 'Addis Ababa', 'Hawassa', 600, true],
    ['search_b', 'Test corridor 1000', 'DAILY_COMMUTE', 'Bole', 'Merkato', 250, true],
    ['search_c', 'Hidden test corridor', 'INTERCITY', 'Addis Ababa', 'Hawassa', 100, false]
  ]) {
    await DB.prepare(`INSERT INTO routes (id,name,nameAm,description,routeType,origin,destination,basePriceEtb,active) VALUES (?,?,?,?,?,?,?,?,?)`)
      .run(id, name, 'ሙከራ', 'Airport connection', type, origin, destination, fare, Number(active));
  }
  assert.deepEqual((await search({ q: 'Test corridor', sort: 'fare' })).map(r => r.id), ['search_b', 'search_a']);
  assert.deepEqual((await search({ origin: 'addis', destination: 'hawassa', routeType: 'INTERCITY' })).map(r => r.id), ['search_a']);
  assert.deepEqual((await search({ q: '100%' })).map(r => r.id), ['search_a']);
  assert.equal((await search({ q: "' OR 1=1 --" })).length, 0);
  assert.equal((await search({ q: 'Airport connection' })).length, 2);
  assert.equal((await search({ q: 'ሙከራ' })).length, 2);
  for (const query of [{ sort: 'constructor' }, { sort: 'fare; DROP TABLE routes' }, { q: ['a'] }, { period: 'night' }, { routeType: 'invalid' }, { q: 'x'.repeat(121) }]) {
    assert.throws(() => routeSearch(query));
  }
});
