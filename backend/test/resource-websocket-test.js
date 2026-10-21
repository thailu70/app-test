'use strict';

const { test, before, after } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const WebSocket = require('ws');

process.env.NODE_ENV = 'test';
delete process.env.DATABASE_URL;
process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), 'routepass-resource-websocket-'));
process.env.JWT_SECRET = 'resource-websocket-tests-authentication-secret';
process.env.QR_SIGNING_KEY = 'resource-websocket-tests-qr-signing-secret';
const { server, resourcePressure } = require('../src/server');
const sockets = new Set();
let url;

before(async () => {
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', () => { server.removeListener('error', reject); resolve(); });
  });
  url = 'ws://127.0.0.1:' + server.address().port + '/ws';
});

after(async () => {
  resourcePressure.stop();
  for (const socket of sockets) socket.terminate();
  await new Promise(resolve => server.close(resolve));
});

function pressureFixture(t) {
  let busy = false;
  const original = resourcePressure.getSnapshot;
  resourcePressure.getSnapshot = () => ({ busy });
  t.after(() => { resourcePressure.getSnapshot = original; });
  return value => { busy = value; };
}

function connect(t) {
  const socket = new WebSocket(url);
  sockets.add(socket);
  const messages = [], pending = [];
  socket.on('error', () => { /* Open/close assertions below report connection failures. */ });
  socket.on('message', raw => {
    const message = JSON.parse(String(raw));
    const next = pending.shift();
    if (next) next(message); else messages.push(message);
  });
  const opened = new Promise((resolve, reject) => {
    socket.once('open', resolve); socket.once('error', reject);
  });
  const closed = new Promise(resolve => socket.once('close', (code, reason) => {
    sockets.delete(socket); resolve({ code, reason: String(reason) });
  }));
  t.after(async () => {
    if (socket.readyState !== WebSocket.CLOSED) socket.terminate();
    await closed;
  });
  function message() {
    if (messages.length) return Promise.resolve(messages.shift());
    return new Promise((resolve, reject) => {
      const receive = value => { clearTimeout(timer); resolve(value); };
      const timer = setTimeout(() => {
        const index = pending.indexOf(receive);
        if (index >= 0) pending.splice(index, 1);
        reject(new Error('WebSocket response was not received.'));
      }, 3000);
      pending.push(receive);
    });
  }
  async function request(value) { socket.send(JSON.stringify(value)); return message(); }
  return { opened, closed, message, request, messages };
}

test('busy server closes new WebSockets with 1013 and normal guest authentication resumes after recovery', { timeout: 10000 }, async t => {
  const setBusy = pressureFixture(t);
  setBusy(true);
  const refused = connect(t);
  await refused.opened;
  const close = await refused.closed;
  assert.equal(close.code, 1013);
  assert.match(close.reason, /busy/i);
  assert.deepEqual(refused.messages, [], 'Busy connections must not receive an authenticated or guest session.');

  setBusy(false);
  const recovered = connect(t);
  await recovered.opened;
  const welcome = await recovered.message();
  assert.equal(welcome.type, 'CONNECTION_ESTABLISHED');
  assert.equal(welcome.authenticated, false);
  assert.equal((await recovered.request({ type: 'AUTHENTICATE', token: 'invalid-test-token' })).type, 'AUTHENTICATION_FAILED');
  assert.equal((await recovered.request({ type: 'PING' })).type, 'PONG');
});

test('existing WebSocket heartbeats continue while work pauses and normal authorization resumes after recovery', { timeout: 10000 }, async t => {
  const setBusy = pressureFixture(t);
  const existing = connect(t);
  await existing.opened;
  assert.equal((await existing.message()).type, 'CONNECTION_ESTABLISHED');

  setBusy(true);
  assert.equal((await existing.request({ type: 'PING' })).type, 'PONG');
  for (const work of [
    { type: 'AUTHENTICATE', token: 'invalid-test-token' },
    { type: 'DRIVER_LOCATION_UPDATE', latitude: 9.03, longitude: 38.74, speed: 0 }
  ]) {
    const response = await existing.request(work);
    assert.equal(response.type, 'SERVER_BUSY');
    assert.equal(response.retryAfterSeconds, 10);
  }
  assert.equal((await existing.request({ type: 'PING' })).type, 'PONG');

  setBusy(false);
  assert.equal((await existing.request({ type: 'AUTHENTICATE', token: 'invalid-test-token' })).type, 'AUTHENTICATION_FAILED');
  const unauthorized = await existing.request({ type: 'DRIVER_LOCATION_UPDATE', latitude: 9.03, longitude: 38.74 });
  assert.equal(unauthorized.type, 'GPS_REJECTED');
  assert.equal(unauthorized.reason, 'UNAUTHORIZED_DRIVER');
  assert.equal((await existing.request({ type: 'PING' })).type, 'PONG');
});
