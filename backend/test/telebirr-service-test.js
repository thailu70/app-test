'use strict';
const assert = require('node:assert/strict');
const telebirr = require('../src/services/telebirr');

const request = {
  timestamp: '1755866911',
  nonce_str: 'H5QN4M6EAB2TXXVFK8SVV0RW6UFASICS',
  method: 'payment.preorder',
  version: '1.0',
  sign: 'not-part-of-signature',
  sign_type: 'SHA256WithRSA',
  biz_content: {
    notify_url: 'https://merchant.example/notify',
    appid: '1227484825753601',
    merch_code: '101011',
    merch_order_id: 'RP123456789ABCDEF',
    trade_type: 'Checkout',
    title: 'RoutePass',
    total_amount: '2500.00',
    trans_currency: 'ETB',
    timeout_express: '120m'
  }
};
const expected = [
  'appid=1227484825753601',
  'merch_code=101011',
  'merch_order_id=RP123456789ABCDEF',
  'method=payment.preorder',
  'nonce_str=H5QN4M6EAB2TXXVFK8SVV0RW6UFASICS',
  'notify_url=https://merchant.example/notify',
  'timeout_express=120m',
  'timestamp=1755866911',
  'title=RoutePass',
  'total_amount=2500.00',
  'trade_type=Checkout',
  'trans_currency=ETB',
  'version=1.0'
].join('&');
assert.equal(telebirr.canonicalString(request), expected, 'Telebirr signing string must flatten and sort official H5 C2B request parameters.');
assert.match(telebirr.merchantOrderId(), /^RP[0-9]+[A-F0-9]+$/, 'Merchant order references must contain letters and digits only.');
assert.equal(
  telebirr.canonicalString({ transId: 'provider-txn', sign: 'x', sign_type: 'SHA256WithRSA' }),
  'trans_id=provider-txn',
  'Provider notification aliases are canonicalized for signature verification.'
);
const { publicBaseUrl, httpsCallbackUrl } = require('../src/services/public-url');
const domainKeys = ['PUBLIC_BASE_URL', 'TELEBIRR_NOTIFY_URL', 'TELEBIRR_REDIRECT_URL'];
const originalDomains = Object.fromEntries(domainKeys.map(key => [key, process.env[key]]));
try {
  for (const key of domainKeys) delete process.env[key];
  assert.equal(publicBaseUrl(), 'https://passo.live');
  assert.equal(telebirr.config().notifyUrl, 'https://passo.live/api/subscriptions/telebirr/webhook');
  assert.equal(telebirr.config().redirectUrl, 'https://passo.live/api/subscriptions/telebirr/return');
  process.env.PUBLIC_BASE_URL = 'https://routepass.duckdns.org/';
  assert.equal(telebirr.config().notifyUrl, 'https://routepass.duckdns.org/api/subscriptions/telebirr/webhook');
  assert.equal(telebirr.config().redirectUrl, 'https://routepass.duckdns.org/api/subscriptions/telebirr/return');
  process.env.TELEBIRR_NOTIFY_URL = 'https://passo.live/registered/provider-webhook';
  process.env.TELEBIRR_REDIRECT_URL = 'https://passo.live/registered/provider-return';
  assert.equal(telebirr.config().notifyUrl, process.env.TELEBIRR_NOTIFY_URL, 'Explicit registered callbacks retain precedence.');
  assert.equal(telebirr.config().redirectUrl, process.env.TELEBIRR_REDIRECT_URL);
  for (const value of ['http://passo.live', 'https://user:password@passo.live', 'https://passo.live/other-path', 'https://passo.live?host=other']) {
    process.env.PUBLIC_BASE_URL = value;
    assert.throws(publicBaseUrl, /PUBLIC_BASE_URL/, 'Invalid callback bases must never be silently reused.');
  }
  assert.equal(httpsCallbackUrl('https://routepass.duckdns.org/api/intercity/payments/webhook'), 'https://routepass.duckdns.org/api/intercity/payments/webhook');
  for (const value of ['http://passo.live/api/intercity/payments/webhook', 'https://user:password@passo.live/callback', 'https://passo.live/callback#fragment', 'not-a-url'])
    assert.throws(() => httpsCallbackUrl(value), /HTTPS/);
} finally {
  for (const key of domainKeys) if (originalDomains[key] === undefined) delete process.env[key]; else process.env[key] = originalDomains[key];
}
console.log('Telebirr H5 signing and order-reference tests passed.');
