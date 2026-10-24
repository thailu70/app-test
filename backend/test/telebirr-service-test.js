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

require('node:test').test('provider sandbox is disabled by default for checkout, status and signed notifications while live payments remain available', async () => {
  const crypto = require('node:crypto');
  const keys = ['NODE_ENV','PAYMENT_TESTS_ENABLED','TELEBIRR_ENVIRONMENT','TELEBIRR_FABRIC_APP_ID','TELEBIRR_APP_SECRET','TELEBIRR_MERCHANT_APP_ID','TELEBIRR_MERCHANT_CODE','TELEBIRR_PRIVATE_KEY','TELEBIRR_PUBLIC_KEY','TELEBIRR_NOTIFY_URL','TELEBIRR_REDIRECT_URL'];
  const saved = Object.fromEntries(keys.map(key => [key, process.env[key]])), originalFetch = global.fetch;
  const pair = crypto.generateKeyPairSync('rsa', { modulusLength: 2048 });
  Object.assign(process.env, {
    NODE_ENV:'production', TELEBIRR_ENVIRONMENT:'sandbox', TELEBIRR_FABRIC_APP_ID:'isolated-app',
    TELEBIRR_APP_SECRET:'isolated-secret', TELEBIRR_MERCHANT_APP_ID:'isolated-merchant', TELEBIRR_MERCHANT_CODE:'123456',
    TELEBIRR_PRIVATE_KEY:pair.privateKey.export({format:'pem',type:'pkcs8'}), TELEBIRR_PUBLIC_KEY:pair.publicKey.export({format:'pem',type:'spki'}),
    TELEBIRR_NOTIFY_URL:'https://merchant.example/notify', TELEBIRR_REDIRECT_URL:'https://merchant.example/return'
  });
  const notice = { merch_order_id:'RPPOLICY12345678', total_amount:'450.00', trans_currency:'ETB', trade_status:'PAY_SUCCESS', sign_type:'SHA256WithRSA' };
  notice.sign = crypto.sign('sha256', Buffer.from(telebirr.canonicalString(notice)), { key:pair.privateKey, padding:crypto.constants.RSA_PKCS1_PSS_PADDING, saltLength:32 }).toString('base64');
  let calls = [];
  global.fetch = async (url, options) => {
    calls.push(String(url));
    const data = String(url).endsWith('/token') ? {token:'isolated-token', expirationDate:Date.now()+3600000}
      : String(url).endsWith('/preOrder') ? {biz_content:{prepay_id:'isolated-prepay'}}
      : {biz_content:{merch_order_id:'RPPOLICY12345678', total_amount:'450.00', trans_currency:'ETB', trade_status:'PAY_SUCCESS'}};
    assert.equal(options.method, 'POST');
    return {ok:true,status:200,json:async()=>data};
  };
  try {
    delete process.env.PAYMENT_TESTS_ENABLED;
    for (const flag of [undefined, 'false', '0', 'yes']) {
      if (flag === undefined) delete process.env.PAYMENT_TESTS_ENABLED; else process.env.PAYMENT_TESTS_ENABLED = flag;
      assert.ok(telebirr.missingConfiguration().some(value=>value.includes('PAYMENT_TESTS_ENABLED')));
      await assert.rejects(() => telebirr.createCheckout('RPPOLICY12345678',450,'Fixture'), error=>error.code==='PAYMENT_TESTS_DISABLED'&&error.checkoutRetrySafe===true);
      await assert.rejects(() => telebirr.queryOrderStatus('RPPOLICY12345678'), error=>error.code==='PAYMENT_TESTS_DISABLED');
      assert.throws(() => telebirr.checkoutUrlForPrepay('existing-sandbox-prepay'), error=>error.code==='PAYMENT_TESTS_DISABLED');
      assert.equal(telebirr.verifyNotification(notice), false, 'A genuine sandbox signature cannot settle an unpaid order while disabled');
    }
    assert.equal(calls.length,0, 'Disabled sandbox paths must not submit or query provider orders');

    process.env.PAYMENT_TESTS_ENABLED='true';
    assert.deepEqual(telebirr.missingConfiguration(),[]);
    const sandbox = await telebirr.createCheckout('RPPOLICY12345678',450,'Fixture');
    assert.match(sandbox.checkoutUrl,/^https:\/\/developerportal\.ethiotelebirr\.et:/);
    assert.equal((await telebirr.queryOrderStatus('RPPOLICY12345678')).paid,true);
    assert.equal(telebirr.verifyNotification(notice),true);
    const enabledCalls = calls.length;
    process.env.PAYMENT_TESTS_ENABLED='false';
    await assert.rejects(() => telebirr.queryOrderStatus('RPPOLICY12345678'), error=>error.code==='PAYMENT_TESTS_DISABLED');
    assert.equal(calls.length,enabledCalls, 'Turning tests off also blocks a cached sandbox session');
    assert.equal(telebirr.verifyNotification(notice),false);

    process.env.TELEBIRR_ENVIRONMENT='production';
    telebirr.resetTokenCacheForTests();calls=[];
    assert.deepEqual(telebirr.missingConfiguration(),[]);
    const live = await telebirr.createCheckout('RPPOLICY12345678',450,'Fixture');
    assert.match(live.checkoutUrl,/^https:\/\/superapp\.ethiomobilemoney\.et:/);
    assert.equal((await telebirr.queryOrderStatus('RPPOLICY12345678')).paid,true);
    assert.equal(telebirr.verifyNotification(notice),true, 'Disabling free tests does not reject genuine production settlement');
    assert.ok(calls.length>=3&&calls.every(url=>url.startsWith('https://superapp.ethiomobilemoney.et:')));
    assert.equal(telebirr.verifyNotification({...notice,total_amount:'1.00'}),false);
  } finally {
    global.fetch=originalFetch;telebirr.resetTokenCacheForTests();
    for (const key of keys) if (saved[key]===undefined) delete process.env[key]; else process.env[key]=saved[key];
  }
});
