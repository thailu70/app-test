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
console.log('Telebirr H5 signing and order-reference tests passed.');
