'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('crypto');
const { remainingSubscriptionDays, subscriptionExpiry } = require('../src/services/subscription-days');

const now = Date.parse('2026-10-20T08:00:00.000Z');
const day = 86400000;

test('remaining days decrease from expiry instead of the activation counter', () => {
  assert.equal(remainingSubscriptionDays({ endDate: '2026-10-25T08:00:00Z', daysRemaining: 30 }, now), 5);
  assert.equal(remainingSubscriptionDays({ endDate: '2026-10-25T08:00:00Z', daysRemaining: 30 }, now + day), 4);
  assert.equal(remainingSubscriptionDays({ endDate: '2026-10-20T09:00:00Z', daysRemaining: 30 }, now), 1);
  assert.equal(remainingSubscriptionDays({ endDate: '2026-10-20T08:00:00Z', daysRemaining: 30 }, now), 0);
  assert.equal(remainingSubscriptionDays({ endDate: '2026-10-19', daysRemaining: 30 }, now), 0);
  assert.equal(remainingSubscriptionDays({ endDate: '2026-12-04T08:00:00Z', daysRemaining: 30 }, now), 45);
  assert.equal(remainingSubscriptionDays({ endDate: '2026-12-04', daysRemaining: 0, subscriptionStatus: 'PENDING', paymentStatus: 'UNPAID' }, now), 0);
});

test('ISO date and legacy activation dates use UTC midnight consistently', () => {
  assert.equal(subscriptionExpiry({ endDate: '2026-10-25' }), Date.parse('2026-10-25T00:00:00Z'));
  assert.equal(subscriptionExpiry({ endDate: '25 Oct 2026' }), Date.parse('2026-10-25T00:00:00Z'));
  assert.equal(subscriptionExpiry({ endDate: '2026-10-25T03:00:00+03:00' }), Date.parse('2026-10-25T00:00:00Z'));
  assert.equal(subscriptionExpiry({ endDate: '2026-02-30' }), null);
  assert.equal(subscriptionExpiry({ endDate: '31 Feb 2026' }), null);
});

test('verified server QR retains the exact expiry when endDate lost the time', () => {
  const sub = { id: 'sub-1', passengerId: 'passenger-1', routeId: 'route-1', endDate: '2026-10-25', daysRemaining: 30 };
  const expiry = now + 5 * day + 3600000;
  const payload = `${sub.id}:${sub.passengerId}:${sub.routeId}:${expiry}`;
  const key = process.env.QR_SIGNING_KEY?.trim() || process.env.JWT_SECRET?.trim() || 'routepass_production_qr_hmac_secret_2026';
  const signature = crypto.createHmac('sha256', key).update(payload).digest('hex').slice(0, 16);
  sub.qrToken = `RP1:${payload}:${signature}`;
  assert.equal(subscriptionExpiry(sub), expiry);
  assert.equal(remainingSubscriptionDays(sub, now), 6);
  assert.equal(remainingSubscriptionDays(sub, expiry), 0);
  assert.equal(subscriptionExpiry({ ...sub, id: 'another-sub' }), Date.parse('2026-10-25T00:00:00Z'));
  assert.equal(subscriptionExpiry({ ...sub, qrToken: `RP1:${payload}:0000000000000000` }), Date.parse('2026-10-25T00:00:00Z'));
});

test('date-less legacy records retain their stored value without a fabricated thirty days', () => {
  assert.equal(remainingSubscriptionDays({ daysRemaining: 7 }, now), 7);
  assert.equal(remainingSubscriptionDays({ endDate: 'not-a-date', daysRemaining: 0 }, now), 0);
  assert.equal(remainingSubscriptionDays({}, now), 0);
});
