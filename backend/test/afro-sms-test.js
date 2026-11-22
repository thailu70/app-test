'use strict';

const assert = require('node:assert/strict');
const test = require('node:test');
const { createAfroSmsService, normalizePhone, AFROMESSAGE_SEND_URL } = require('../src/services/afro-sms');

function settingsDb(values = {}) {
  return { prepare: () => ({ get: async key => ({ value: values[key] || '' }) }) };
}

function service({ values, env, fetchImpl } = {}) {
  return createAfroSmsService({ db: settingsDb(values), env: env || {}, fetchImpl, isReviewRuntime: () => false });
}

test('normalizes Ethiopian mobile numbers to E.164', () => {
  assert.equal(normalizePhone('0912 345 678'), '+251912345678');
  assert.equal(normalizePhone('0712-345-678'), '+251712345678');
  assert.equal(normalizePhone('251912345678'), '+251912345678');
});

test('sends an authenticated JSON request to the fixed AfroMessage endpoint and uses the provider sender default', async () => {
  let request;
  const sms = service({ values: { AFRO_SMS_API_TOKEN: 'fixture-secret' }, fetchImpl: async (url, options) => {
    request = { url, options };
    return { ok: true, status: 200, json: async () => ({ acknowledge: 'success', response: { message_id: 'fixture-message' } }) };
  } });
  const result = await sms.sendSms('0912345678', 'PASSO verification code: 111222');
  assert.deepEqual(result, { sent: true, status: 200, messageId: 'fixture-message' });
  assert.equal(request.url, AFROMESSAGE_SEND_URL);
  assert.equal(request.options.method, 'POST');
  assert.equal(request.options.headers.Authorization, 'Bearer fixture-secret');
  assert.deepEqual(JSON.parse(request.options.body), { to: '+251912345678', message: 'PASSO verification code: 111222' });
});

test('includes only the configured identifier and verified sender', async () => {
  let payload;
  const sms = service({ values: { AFRO_SMS_API_TOKEN: 'fixture-secret', AFRO_SMS_IDENTIFIER: 'fixture-id', AFRO_SMS_SENDER: 'PASSO' }, fetchImpl: async (_url, options) => {
    payload = JSON.parse(options.body);
    return { ok: true, status: 200, json: async () => ({ acknowledge: 'success', response: {} }) };
  } });
  assert.equal((await sms.sendSms('+251912345678', 'Test')).sent, true);
  assert.deepEqual(payload, { to: '+251912345678', message: 'Test', from: 'fixture-id', sender: 'PASSO' });
});

test('returns a safe, actionable AfroMessage rejection reason without echoing phone or code', async () => {
  const sms = service({ values: { AFRO_SMS_API_TOKEN: 'fixture-secret' }, fetchImpl: async () => ({ ok: true, status: 200,
    json: async () => ({ acknowledge: 'error', response: { errors: ['Sender 0912345678 cannot send code 111222'] } }) }) });
  const result = await sms.sendSms('0912345678', 'PASSO code 111222');
  assert.equal(result.sent, false);
  assert.equal(result.error, 'Sender [phone] cannot send code [code]');
  assert.ok(!JSON.stringify(result).includes('0912345678'));
  assert.ok(!JSON.stringify(result).includes('111222'));
});

test('treats non-success HTTP responses as failed delivery', async () => {
  const sms = service({ values: { AFRO_SMS_API_TOKEN: 'fixture-secret' }, fetchImpl: async () => ({ ok: false, status: 401,
    json: async () => ({ acknowledge: 'success', response: {} }) }) });
  assert.match((await sms.sendSms('0912345678', 'Test')).error, /HTTP 401/);
});

test('does not send when configuration, number or message is missing', async () => {
  let calls = 0;
  const fetchImpl = async () => { calls++; throw new Error('should not send'); };
  const noToken = service({ fetchImpl });
  assert.equal((await noToken.sendSms('0912345678', 'Test')).skipped, true);
  const configured = service({ values: { AFRO_SMS_API_TOKEN: 'fixture-secret' }, fetchImpl });
  assert.equal((await configured.sendSms('bad', 'Test')).skipped, true);
  assert.equal((await configured.sendSms('0912345678', '')).skipped, true);
  assert.equal(calls, 0);
});

test('does not expose network exception details or secrets', async () => {
  const sms = service({ values: { AFRO_SMS_API_TOKEN: 'fixture-secret' }, fetchImpl: async () => { throw new Error('socket failure'); } });
  assert.deepEqual(await sms.sendSms('0912345678', 'Test'), { sent: false, error: 'Could not connect to AfroMessage.' });
});
