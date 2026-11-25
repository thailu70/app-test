'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const time = require('../public/shared/ethiopian-datetime');

test('Ethiopian clock displays EAT correctly with all requested day parts', () => {
  assert.equal(time.formatClock('07:00'), '01:00 · Day · ቀን');
  assert.equal(time.formatClock('10:00'), '04:00 · Day · ቀን');
  assert.equal(time.formatClock('14:00'), '08:00 · Day · ቀን');
  assert.equal(time.formatClock('18:00'), '12:00 · Evening · ማታ');
  assert.equal(time.formatClock('00:00'), '06:00 · Night · ሌሊት');
});

test('Ethiopian calendar rendering uses the Ethiopian year and Ethiopian day boundary', () => {
  const date = time.formatDate('2026-11-25');
  assert.match(date, /2019/);
  assert.doesNotMatch(date, /2026/);
  assert.equal(time.formatTimestamp('2026-11-25T04:00:00.000Z'), date + ' · 01:00 · Day · ቀን');
  assert.equal(time.formatDate('2026-02-30'), '2026-02-30');
});
