'use strict';

const { test } = require('node:test');
const assert = require('node:assert/strict');
const { createResourcePressureMonitor, createSystemResourceReaders } = require('../src/services/resource-pressure');

function fixture() {
  let total = 1000, idle = 800, time = 1000, ram = 40;
  let cpuReads = 0, memoryReads = 0;
  const monitor = createResourcePressureMonitor({
    readCpu: () => { cpuReads++; return { total, idle, cores: 2 }; },
    readMemory: () => { memoryReads++; return { totalBytes: 1000, availableBytes: 1000 - ram * 10 }; },
    now: () => time
  });
  monitor.sample();
  return { monitor, counts: () => [cpuReads, memoryReads],
    tick(cpu, memory = ram) { total += 1000; idle += 1000 - cpu * 10; time += 1000; ram = memory; return monitor.sample(); } };
}

test('CPU at ninety percent rejects, while lower normal usage remains admitted', () => {
  const f = fixture();
  assert.equal(f.monitor.getSnapshot().cpuPercent, null);
  assert.equal(f.tick(89).busy, false);
  const hot = f.tick(90);
  assert.equal(hot.cpuPercent, 90);
  assert.equal(hot.busy, true);
  assert.equal(hot.limitPercent, 90);
  assert.equal(hot.recoveryPercent, 85);
});

test('RAM at ninety percent rejects without requiring high CPU', () => {
  const f = fixture();
  assert.equal(f.tick(10, 89).busy, false);
  const hot = f.tick(10, 90);
  assert.equal(hot.memoryPercent, 90);
  assert.equal(hot.busy, true);
});

test('hysteresis waits until both resources recover to eighty-five percent', () => {
  const f = fixture();
  assert.equal(f.tick(95, 40).busy, true);
  assert.equal(f.tick(89, 40).busy, true);
  assert.equal(f.tick(85, 86).busy, true);
  assert.equal(f.tick(86, 85).busy, true);
  assert.equal(f.tick(85, 85).busy, false);
  assert.equal(f.tick(89, 89).busy, false);
});

test('Linux available memory excludes reclaimable filesystem cache', () => {
  const readers = createSystemResourceReaders({
    readTextFile: path => path === '/proc/meminfo'
      ? 'MemTotal: 1000 kB\nMemFree: 5 kB\nMemAvailable: 600 kB\nCached: 595 kB\n' : null,
    totalMemory: () => 1024000, freeMemory: () => 5120
  });
  const monitor = createResourcePressureMonitor({ readCpu: () => null, readMemory: readers.readMemory });
  const snapshot = monitor.sample();
  assert.equal(snapshot.memoryPercent, 40);
  assert.equal(snapshot.busy, false);
});

test('older Linux meminfo also accounts for caches when MemAvailable is absent', () => {
  const readers = createSystemResourceReaders({
    readTextFile: path => path === '/proc/meminfo'
      ? 'MemTotal: 1000 kB\nMemFree: 100 kB\nBuffers: 100 kB\nCached: 400 kB\nSReclaimable: 100 kB\nShmem: 100 kB\n' : null,
    totalMemory: () => 1024000, freeMemory: () => 102400
  });
  const monitor = createResourcePressureMonitor({ readCpu: () => null, readMemory: readers.readMemory });
  assert.equal(monitor.sample().memoryPercent, 40);
});

test('finite cgroup v2 working-set memory limit is respected without counting inactive file cache', () => {
  const files = {
    '/proc/meminfo': 'MemTotal: 1000 kB\nMemAvailable: 900 kB\n',
    '/sys/fs/cgroup/memory.max': String(100 * 1024),
    '/sys/fs/cgroup/memory.current': String(95 * 1024),
    '/sys/fs/cgroup/memory.stat': 'inactive_file 20480\n'
  };
  const readers = createSystemResourceReaders({ readTextFile: path => files[path] || null });
  const monitor = createResourcePressureMonitor({ readCpu: () => null, readMemory: readers.readMemory });
  assert.equal(monitor.sample().memoryPercent, 75);
  assert.equal(monitor.getSnapshot().busy, false);
  files['/sys/fs/cgroup/memory.stat'] = 'inactive_file 5120\n';
  assert.equal(monitor.sample().memoryPercent, 90);
  assert.equal(monitor.getSnapshot().busy, true);
});

test('finite cgroup v1 working-set memory uses total_inactive_file', () => {
  const files = {
    '/proc/meminfo': 'MemTotal: 1000 kB\nMemAvailable: 900 kB\n',
    '/sys/fs/cgroup/memory/memory.limit_in_bytes': String(100 * 1024),
    '/sys/fs/cgroup/memory/memory.usage_in_bytes': String(99 * 1024),
    '/sys/fs/cgroup/memory/memory.stat': 'total_inactive_file 15360\n'
  };
  const readers = createSystemResourceReaders({ readTextFile: path => files[path] || null });
  const monitor = createResourcePressureMonitor({ readCpu: () => null, readMemory: readers.readMemory });
  assert.equal(monitor.sample().memoryPercent, 84);
  assert.equal(monitor.getSnapshot().busy, false);
});

test('cgroup CPU quota detects a saturated partial core even when whole-host CPU is low', () => {
  let tick = 0;
  const files = { '/sys/fs/cgroup/cpu.max': '50000 100000', '/sys/fs/cgroup/cpu.stat': 'usage_usec 0\n' };
  const readers = createSystemResourceReaders({
    readTextFile: path => files[path] || null,
    cpuTimes: () => [{ times: { user: tick * 100, idle: tick * 900 } }, { times: { user: 0, idle: tick * 1000 } }]
  });
  const monitor = createResourcePressureMonitor({ readCpu: readers.readCpu,
    readMemory: () => ({ totalBytes: 1000, availableBytes: 900 }), now: () => tick * 1000 });
  monitor.sample();
  tick = 1; files['/sys/fs/cgroup/cpu.stat'] = 'usage_usec 450000\n';
  const snapshot = monitor.sample();
  assert.equal(snapshot.cpuPercent, 90);
  assert.equal(snapshot.busy, true);
});

test('counter resets and invalid metrics cannot yield NaN, infinity or a fabricated pressure spike', () => {
  let cpu = { total: 1000, idle: 800 }, memory = { totalBytes: 1000, availableBytes: 900 }, time = 1000;
  const monitor = createResourcePressureMonitor({ readCpu: () => cpu, readMemory: () => memory, now: () => time++ });
  monitor.sample();
  cpu = { total: 100, idle: 80 };
  assert.equal(monitor.sample().cpuPercent, null);
  for (const invalid of [null, { total: NaN, idle: 0 }, { total: Infinity, idle: 0 }, { total: -1, idle: 0 }, { total: 20, idle: 50 }]) {
    cpu = invalid; memory = { totalBytes: 0, availableBytes: Infinity };
    const snapshot = monitor.sample();
    assert.equal(snapshot.cpuPercent, null);
    assert.equal(snapshot.memoryPercent, null);
    assert.equal(snapshot.busy, false);
  }
  cpu = { total: 1000, idle: 200 }; memory = { totalBytes: 1000, availableBytes: 900 };
  monitor.sample();
  cpu = { total: 2000, idle: 400 };
  assert.equal(monitor.sample().cpuPercent, 80);
});

test('failed probes preserve an existing pressure gate until valid recovery samples', () => {
  const f = fixture(); f.tick(95, 40);
  assert.equal(f.monitor.getSnapshot().busy, true);
  const monitor = createResourcePressureMonitor({
    readCpu: () => { throw new Error('probe unavailable'); },
    readMemory: () => { throw new Error('probe unavailable'); }, now: () => NaN
  });
  assert.deepEqual(monitor.sample(), { busy: false, cpuPercent: null, memoryPercent: null, sampledAt: null,
    limitPercent: 90, recoveryPercent: 85 });
  // No elapsed CPU delta is available if a caller samples the same counters twice.
  assert.equal(f.monitor.sample().cpuPercent, null);
  assert.equal(f.monitor.getSnapshot().busy, true);
});

test('timer is cached, unreferenced, idempotent and cleanly stopped', () => {
  let cpuReads = 0, memoryReads = 0, unrefs = 0, clears = 0, callback = null;
  const handle = { unref() { unrefs++; } };
  const monitor = createResourcePressureMonitor({
    readCpu: () => { cpuReads++; return { total: cpuReads * 1000, idle: cpuReads * 900 }; },
    readMemory: () => { memoryReads++; return { totalBytes: 1000, availableBytes: 900 }; },
    setIntervalFn: (fn, delay) => { callback = fn; assert.equal(delay, 2000); return handle; },
    clearIntervalFn: timer => { assert.equal(timer, handle); clears++; }
  });
  monitor.start(); monitor.start();
  assert.equal(unrefs, 1); assert.equal(cpuReads, 1); assert.equal(memoryReads, 1);
  const initial = monitor.getSnapshot();
  for (let request = 0; request < 10000; request++) assert.equal(monitor.getSnapshot(), initial);
  assert.equal(cpuReads, 1); assert.equal(memoryReads, 1);
  callback(); assert.equal(cpuReads, 2); assert.equal(memoryReads, 2);
  monitor.stop(); monitor.stop(); assert.equal(clears, 1);
});

test('middleware uses only cached readings and returns temporary HTTP503 with retry guidance', () => {
  const f = fixture(); let nextCalls = 0;
  const res = { headers: {}, statusCode: null, body: null,
    set(name, value) { this.headers[name] = value; return this; },
    status(value) { this.statusCode = value; return this; }, json(value) { this.body = value; return this; } };
  f.monitor.middleware({}, res, () => nextCalls++);
  assert.equal(nextCalls, 1);
  f.tick(95, 40);
  const before = f.counts();
  for (let request = 0; request < 1000; request++) f.monitor.middleware({}, res, () => nextCalls++);
  assert.deepEqual(f.counts(), before);
  assert.equal(nextCalls, 1); assert.equal(res.statusCode, 503);
  assert.equal(res.headers['Retry-After'], '10'); assert.equal(res.headers['Cache-Control'], 'no-store');
  assert.deepEqual(res.body, { success: false, code: 'SERVER_BUSY', error: 'The server is busy. Please try again shortly.' });
  f.tick(80, 40); f.monitor.middleware({}, res, () => nextCalls++);
  assert.equal(nextCalls, 2);
});

test('invalid threshold or interval settings are rejected explicitly', () => {
  for (const options of [{ highWatermark: NaN }, { highWatermark: 101 }, { highWatermark: 90, lowWatermark: 90 },
    { lowWatermark: -1 }, { sampleIntervalMs: 0 }, { sampleIntervalMs: Infinity }])
    assert.throws(() => createResourcePressureMonitor(options), RangeError);
});
