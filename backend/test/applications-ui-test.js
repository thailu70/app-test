'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const panel = require('../public/shared/applications');
const flush = () => new Promise(resolve => setImmediate(resolve));
function fixture(api) {
  const elements = new Map(), listeners = new Map();
  const node = selector => {
    if (!elements.has(selector)) elements.set(selector, { innerHTML: '', textContent: '', hidden: false, disabled: false, value: '' });
    return elements.get(selector);
  };
  const container = {
    querySelector: node, querySelectorAll: () => [],
    addEventListener: (name, fn) => listeners.set(name, fn), removeEventListener: name => listeners.delete(name)
  };
  const cleanup = panel.mount({ container, api });
  return { node, cleanup, click(selector, dataset = {}) { listeners.get('click')?.({ target: { closest: value => value === selector ? { dataset } : null } }); } };
}
test('review applications retain the loaded list on error and recover without exposing raw applicant HTML', async () => {
  let failing = false;
  const f = fixture(async () => {
    if (failing) throw Error('Service temporarily unavailable');
    return { applications: [{ id: 'new_driver', role: 'DRIVER', fullName: '<img src=x onerror=alert(1)>', phone: '+251900000000', status: 'PENDING', onboarding: { phoneVerified: false } }] };
  });
  await flush();
  assert.match(f.node('[data-application-list]').innerHTML, /&lt;img/);
  assert.doesNotMatch(f.node('[data-application-list]').innerHTML, /<img/);
  failing = true; f.click('[data-application-refresh]'); await flush();
  assert.match(f.node('[data-application-message]').textContent, /temporarily unavailable/);
  assert.match(f.node('[data-application-list]').innerHTML, /new_driver/);
  assert.equal(f.node('[data-application-refresh]').disabled, false);
  failing = false; f.click('[data-application-refresh]'); await flush();
  assert.match(f.node('[data-application-message]').textContent, /updated/);
  f.cleanup();
});
test('switching applicants discards an older response and cleanup prevents a late document detail render', async () => {
  const pending = new Map();
  const f = fixture(path => path.endsWith('/applications') ? Promise.resolve({ applications: [] }) : new Promise(resolve => pending.set(path, resolve)));
  await flush();
  f.click('[data-application-open]', { applicationRole: 'DRIVER', applicationOpen: 'first' });
  f.click('[data-application-open]', { applicationRole: 'DRIVER', applicationOpen: 'second' });
  pending.get('/api/onboarding/applications/DRIVER/second')({ account: { id: 'second', role: 'DRIVER', fullName: 'Second applicant' }, onboarding: { missing: ['LICENSE'], documents: [], ready: false } });
  await flush();
  pending.get('/api/onboarding/applications/DRIVER/first')({ account: { id: 'first', role: 'DRIVER', fullName: 'Stale applicant' }, onboarding: { documents: [], ready: true } });
  await flush();
  assert.match(f.node('[data-application-detail]').innerHTML, /Second applicant/);
  assert.doesNotMatch(f.node('[data-application-detail]').innerHTML, /Stale applicant/);
  assert.match(f.node('[data-application-detail]').innerHTML, /data-application-decision="APPROVE" disabled/);
  f.click('[data-application-open]', { applicationRole: 'DRIVER', applicationOpen: 'late' });
  f.cleanup();
  pending.get('/api/onboarding/applications/DRIVER/late')({ account: { id: 'late', role: 'DRIVER', fullName: 'After sign out' }, onboarding: { documents: [] } });
  await flush();
  assert.doesNotMatch(f.node('[data-application-detail]').innerHTML, /After sign out/);
});
