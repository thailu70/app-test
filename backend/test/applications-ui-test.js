'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const panel = require('../public/shared/applications');
const flush = () => new Promise(resolve => setImmediate(resolve));
function fixture(api) {
  const elements = new Map(), listeners = new Map();
  const node = selector => {
    if (!elements.has(selector)) elements.set(selector, { innerHTML: '', textContent: '', hidden: false, disabled: false, value: '', focus() {} });
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
test('initial acceptance reviews the route and whole-vehicle fee while final allocation approval remains disabled', async () => {
  let decision;
  const account = { id: 'daily', role: 'DRIVER', fullName: 'Daily applicant', status: 'PENDING' };
  const onboarding = { acceptanceRequired: true, acceptanceStatus: 'PENDING', canVerifyAndUpload: false, reviewStatus: 'AWAITING_ACCEPTANCE', ready: false, missing: ['ADMIN_ACCEPTANCE','PHONE_VERIFICATION'],
    routePreference: { mode: 'PROPOSED', departure: '<script>departure</script>', destination: 'Destination', proposedMonthlyFeeEtb: 45000, proposedMonthlyFeeBasis: 'PER_VEHICLE' }, documents: [] };
  const f = fixture(async (path, options) => {
    if (path.endsWith('/review')) { decision = JSON.parse(options.body); return {}; }
    if (path.endsWith('/applications')) return { applications: [{ ...account, onboarding }] };
    return { account, onboarding };
  });
  await flush();
  assert.match(f.node('[data-application-list]').innerHTML, /Awaiting admin acceptance/);
  assert.match(f.node('[data-application-list]').innerHTML, /After admin acceptance/);
  f.click('[data-application-open]', { applicationRole: 'DRIVER', applicationOpen: 'daily' }); await flush();
  const html = f.node('[data-application-detail]').innerHTML;
  assert.match(html, /45,000\.00 ETB \/ vehicle \/ month/);
  assert.match(html, /&lt;script&gt;departure/);
  assert.doesNotMatch(html, /<script>/);
  assert.match(html, /data-application-decision="ACCEPT">Accept initial application/);
  assert.match(html, /data-application-decision="APPROVE" disabled/);
  onboarding.routePreference = { mode: 'EXISTING', routeName: 'Bole to CMC', proposedMonthlyFeeEtb: 20000, proposedMonthlyFeeBasis: 'PER_VEHICLE', feeLocked: true, passengerCapacity: 8, passengerMonthlyFareEtb: 2500 };
  f.click('[data-application-open]', { applicationRole: 'DRIVER', applicationOpen: 'daily' }); await flush();
  assert.match(f.node('[data-application-detail]').innerHTML, /Calculated monthly fee/);
  assert.match(f.node('[data-application-detail]').innerHTML, /8 seats × 2,500\.00 ETB per passenger \/ month/);
  f.click('[data-application-decision]', { applicationDecision: 'ACCEPT' }); await flush();
  assert.equal(decision.decision, 'ACCEPT');
  assert.match(f.node('[data-application-message]').textContent, /upload documents for final approval/);
  f.cleanup();
});

test('driver approval uses review readiness while truthful phone status remains unverified', async () => {
  let decision;
  const account = { id: 'otp-deferred-driver', role: 'DRIVER', fullName: 'Test driver', status: 'PENDING' };
  const onboarding = { acceptanceRequired: true, acceptedAt: '2026-10-23T00:00:00Z', submittedAt: '2026-10-23T01:00:00Z',
    ready: false, missing: ['PHONE_VERIFICATION'], reviewReady: true, reviewMissing: [], phoneVerified: false,
    phoneVerificationRequiredForApproval: false, phoneVerificationRequiredForAllocation: false,
    documents: [{ kind: 'LICENSE' }], routePreference: {} };
  const f = fixture(async (path, options) => {
    if (path.endsWith('/review')) { decision = JSON.parse(options.body); return { account, onboarding }; }
    return path.endsWith('/applications') ? { applications: [{ ...account, onboarding }] } : { account, onboarding };
  });
  await flush();
  assert.match(f.node('[data-application-list]').innerHTML, /Verification deferred/);
  f.click('[data-application-open]', { applicationRole: 'DRIVER', applicationOpen: account.id }); await flush();
  let html = f.node('[data-application-detail]').innerHTML;
  assert.match(html, /Not yet verified/);
  assert.match(html, /OTP is not required for driver approval or passenger allocation at present/);
  assert.doesNotMatch(html, /The phone and required documents are complete/);
  assert.match(html, /data-application-decision="APPROVE">/);
  f.click('[data-application-decision]', { applicationDecision: 'APPROVE' }); await flush();
  assert.equal(decision.decision, 'APPROVE');
  assert.match(f.node('[data-application-message]').textContent, /Passenger allocation is now available/);
  onboarding.reviewReady = false; onboarding.reviewMissing = ['INSURANCE'];
  f.click('[data-application-open]', { applicationRole: 'DRIVER', applicationOpen: account.id }); await flush();
  html = f.node('[data-application-detail]').innerHTML;
  assert.match(html, /Missing: Insurance/);
  assert.match(html, /data-application-decision="APPROVE" disabled/);
  f.cleanup();
});

test('group and cargo initial reviews identify the service and do not require a daily commute monthly fee',async()=>{
  for(const serviceType of ['GROUP_TRAVEL','CARGO']){
    const account={id:serviceType,role:'DRIVER',fullName:'Fixture Driver',status:'PENDING',serviceType};
    const onboarding={serviceType,acceptanceRequired:true,acceptanceStatus:'PENDING',reviewStatus:'AWAITING_ACCEPTANCE',documents:[],routePreference:{mode:'PROPOSED',departure:'Addis Ababa',destination:'Hawassa',vehicleType:'MINIBUS_14',passengerCapacity:14,proposedMonthlyFeeEtb:null}};
    const f=fixture(async path=>path.endsWith('/applications')?{applications:[{...account,onboarding}]}:{account,onboarding});
    await flush();f.click('[data-application-open]',{applicationRole:'DRIVER',applicationOpen:serviceType});await flush();
    const html=f.node('[data-application-detail]').innerHTML;
    assert.match(html,new RegExp(serviceType==='CARGO'?'Cargo transport proposal':'Group travel proposal'));
    assert.match(html,/data-application-decision="ACCEPT">/);assert.doesNotMatch(html,/Monthly fee for the whole vehicle|Not provided yet/);
    if(serviceType==='GROUP_TRAVEL')assert.match(html,/separate admin approval/);else assert.doesNotMatch(html,/Registered seats/);
    f.cleanup();
  }
});


test('document corrections need instructions and are separate from initial rejection or suspension', async () => {
  const calls=[];
  const account={id:'corrections',role:'DRIVER',fullName:'Correction Fixture',status:'PENDING'};
  const onboarding={acceptanceRequired:true,acceptedAt:'accepted',reviewStatus:'SUBMITTED',submittedAt:'submitted',reviewReady:true,documents:[]};
  const f=fixture(async(path,options)=>{
    if(path.endsWith('/review')){calls.push(JSON.parse(options.body));return {account,onboarding};}
    return path.endsWith('/applications')?{applications:[{...account,onboarding}]}:{account,onboarding};
  });
  await flush();f.click('[data-application-open]',{applicationRole:'DRIVER',applicationOpen:'corrections'});await flush();
  assert.match(f.node('[data-application-detail]').innerHTML,/data-application-decision="CORRECTIONS"/);
  f.click('[data-application-decision]',{applicationDecision:'CORRECTIONS'});await flush();
  assert.equal(calls.length,0);assert.match(f.node('[data-application-message]').textContent,/Explain which/);
  f.node('[data-application-note]').value='Please upload a clearer licence image.';
  f.click('[data-application-decision]',{applicationDecision:'CORRECTIONS'});await flush();
  assert.deepEqual(calls,[{decision:'CORRECTIONS',note:'Please upload a clearer licence image.'}]);
  assert.match(f.node('[data-application-message]').textContent,/accepted route is retained/);f.cleanup();
});

test('a suspended driver offers restoration and reports pending recovery without claiming activation', async () => {
  const account={id:'restore',role:'DRIVER',fullName:'Restore Fixture',status:'SUSPENDED'};
  const onboarding={acceptanceRequired:true,acceptedAt:'accepted',reviewStatus:'APPROVED',submittedAt:'submitted',reviewReady:true,documents:[]};
  const f=fixture(async(path,options)=>path.endsWith('/review')?{account:{...account,status:'PENDING'},onboarding}:path.endsWith('/applications')?{applications:[{...account,onboarding}]}:{account,onboarding});
  await flush();f.click('[data-application-open]',{applicationRole:'DRIVER',applicationOpen:'restore'});await flush();
  const html=f.node('[data-application-detail]').innerHTML;
  assert.match(html,/data-application-decision="REACTIVATE"/);assert.doesNotMatch(html,/data-application-decision="APPROVE"/);
  f.click('[data-application-decision]',{applicationDecision:'REACTIVATE'});await flush();
  assert.match(f.node('[data-application-message]').textContent,/pending application/);f.cleanup();
});
