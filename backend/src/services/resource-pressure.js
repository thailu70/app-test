'use strict';

const fs = require('node:fs');
const os = require('node:os');

const DEFAULT_HIGH_WATERMARK = 90;
const DEFAULT_LOW_WATERMARK = 85;
const RETRY_AFTER_SECONDS = 10;

const finite = value => typeof value === 'number' && Number.isFinite(value);
const nonnegative = value => finite(value) && value >= 0;
const positive = value => finite(value) && value > 0;
const percent = (used, total) => Math.min(100, Math.max(0, used / total * 100));

function createSystemResourceReaders(options = {}) {
  const readTextFile = options.readTextFile || (path => fs.readFileSync(path, 'utf8'));
  const cpuTimes = options.cpuTimes || (() => os.cpus());
  const totalMemory = options.totalMemory || (() => os.totalmem());
  const freeMemory = options.freeMemory || (() => os.freemem());
  function text(path) {
    try { const value = readTextFile(path); return typeof value === 'string' ? value.trim() : null; }
    catch (_) { return null; }
  }
  function counter(path) {
    const value = text(path);
    if (value === null || !/^\d+$/.test(value)) return null;
    const number = Number(value);
    return nonnegative(number) ? number : null;
  }
  function fields(value) {
    const result = new Map();
    for (const line of (value || '').split('\n')) {
      const match = /^(\w+)\s+(\d+)\s*$/.exec(line.trim());
      if (match) result.set(match[1], Number(match[2]));
    }
    return result;
  }
  function cpuSetSize(value) {
    if (!value) return null;
    const cpus = new Set();
    for (const part of value.split(',')) {
      const match = /^(\d+)(?:-(\d+))?$/.exec(part.trim());
      if (!match) return null;
      const first = Number(match[1]), last = Number(match[2] || match[1]);
      if (last < first || last - first > 100000) return null;
      for (let cpu = first; cpu <= last; cpu++) cpus.add(cpu);
    }
    return cpus.size || null;
  }
  function readCpu() {
    const cpus = cpuTimes();
    if (!Array.isArray(cpus) || !cpus.length) return null;
    let total = 0, idle = 0;
    for (const cpu of cpus) {
      const times = cpu?.times;
      if (!times || !nonnegative(times.idle) || !Object.values(times).every(nonnegative)) return null;
      idle += times.idle;
      total += Object.values(times).reduce((sum, value) => sum + value, 0);
    }
    let limitCores = null;
    let usageMicros = null;
    const v2Max = text('/sys/fs/cgroup/cpu.max');
    if (v2Max) {
      const match = /^(\d+)\s+(\d+)$/.exec(v2Max);
      if (match && positive(Number(match[1])) && positive(Number(match[2]))) limitCores = Number(match[1]) / Number(match[2]);
    }
    const cpuset = cpuSetSize(text('/sys/fs/cgroup/cpuset.cpus.effective'));
    if (positive(cpuset) && cpuset < cpus.length) limitCores = positive(limitCores) ? Math.min(limitCores, cpuset) : cpuset;
    if (positive(limitCores)) usageMicros = fields(text('/sys/fs/cgroup/cpu.stat')).get('usage_usec') ?? null;
    if (!positive(limitCores) || !nonnegative(usageMicros)) {
      for (const directory of ['/sys/fs/cgroup/cpu', '/sys/fs/cgroup/cpu,cpuacct']) {
        const quota = counter(directory + '/cpu.cfs_quota_us'), period = counter(directory + '/cpu.cfs_period_us');
        if (positive(quota) && positive(period)) {
          const usage = counter(directory + '/cpuacct.usage') ?? counter('/sys/fs/cgroup/cpuacct/cpuacct.usage');
          if (nonnegative(usage)) { limitCores = quota / period; usageMicros = usage / 1000; break; }
        }
      }
    }
    return { total, idle, cores: cpus.length,
      limitCores: positive(limitCores) ? Math.min(limitCores, cpus.length) : null,
      usageMicros: nonnegative(usageMicros) ? usageMicros : null };
  }
  function readMemory() {
    const meminfo = new Map();
    for (const line of (text('/proc/meminfo') || '').split('\n')) {
      const match = /^(\w+):\s+(\d+)\s+kB\s*$/.exec(line.trim());
      if (match) meminfo.set(match[1], Number(match[2]) * 1024);
    }
    const totalBytes = meminfo.get('MemTotal') ?? totalMemory();
    let availableBytes = meminfo.get('MemAvailable');
    if (!nonnegative(availableBytes) && meminfo.has('MemFree')) {
      availableBytes = Math.max(0, meminfo.get('MemFree') + (meminfo.get('Buffers') || 0) +
        (meminfo.get('Cached') || 0) + (meminfo.get('SReclaimable') || 0) - (meminfo.get('Shmem') || 0));
    }
    if (!nonnegative(availableBytes)) availableBytes = freeMemory();
    let limitBytes = counter('/sys/fs/cgroup/memory.max');
    let usageBytes = counter('/sys/fs/cgroup/memory.current');
    let inactiveFileBytes = fields(text('/sys/fs/cgroup/memory.stat')).get('inactive_file') || 0;
    if (!positive(limitBytes) || !nonnegative(usageBytes)) {
      limitBytes = counter('/sys/fs/cgroup/memory/memory.limit_in_bytes');
      usageBytes = counter('/sys/fs/cgroup/memory/memory.usage_in_bytes');
      const stat = fields(text('/sys/fs/cgroup/memory/memory.stat'));
      inactiveFileBytes = stat.get('total_inactive_file') ?? stat.get('inactive_file') ?? 0;
    }
    const container = positive(limitBytes) && limitBytes < totalBytes && nonnegative(usageBytes) && nonnegative(inactiveFileBytes)
      ? { limitBytes, usageBytes, inactiveFileBytes } : null;
    return { totalBytes, availableBytes, container };
  }
  return { readCpu, readMemory };
}

function memoryPercent(reading) {
  if (!reading || !positive(reading.totalBytes) || !nonnegative(reading.availableBytes) || reading.availableBytes > reading.totalBytes) return null;
  let usage = percent(reading.totalBytes - reading.availableBytes, reading.totalBytes);
  const container = reading.container;
  if (container && positive(container.limitBytes) && container.limitBytes < reading.totalBytes &&
      nonnegative(container.usageBytes) && nonnegative(container.inactiveFileBytes)) {
    usage = Math.max(usage, percent(Math.max(0, container.usageBytes - container.inactiveFileBytes), container.limitBytes));
  }
  return usage;
}

function cpuPercent(previous, current, elapsedMs) {
  if (!previous || !current || !nonnegative(current.total) || !nonnegative(current.idle) ||
      !nonnegative(previous.total) || !nonnegative(previous.idle) ||
      current.idle > current.total || previous.idle > previous.total ||
      (positive(previous.cores) && positive(current.cores) && previous.cores !== current.cores)) return null;
  const total = current.total - previous.total, idle = current.idle - previous.idle;
  if (!positive(total) || !nonnegative(idle) || idle > total) return null;
  let usage = percent(total - idle, total);
  if (positive(elapsedMs) && positive(current.limitCores) && current.limitCores === previous.limitCores &&
      nonnegative(current.usageMicros) && nonnegative(previous.usageMicros)) {
    const used = current.usageMicros - previous.usageMicros;
    if (nonnegative(used)) usage = Math.max(usage, percent(used, elapsedMs * 1000 * current.limitCores));
  }
  return usage;
}

/** Cached, process-local admission gate. Sampling never happens in an HTTP request. */
function createResourcePressureMonitor(options = {}) {
  const high = options.highWatermark ?? DEFAULT_HIGH_WATERMARK;
  const low = options.lowWatermark ?? DEFAULT_LOW_WATERMARK;
  const sampleIntervalMs = options.sampleIntervalMs ?? 2000;
  if (!positive(high) || high > 100 || !nonnegative(low) || low >= high) throw new RangeError('Invalid resource pressure thresholds.');
  if (!finite(sampleIntervalMs) || sampleIntervalMs < 250 || sampleIntervalMs > 60000) throw new RangeError('Invalid resource sample interval.');
  const system = createSystemResourceReaders(options.systemReaders);
  const readCpu = options.readCpu || system.readCpu;
  const readMemory = options.readMemory || system.readMemory;
  const now = options.now || Date.now;
  const schedule = options.setIntervalFn || setInterval;
  const cancel = options.clearIntervalFn || clearInterval;
  let timer = null, previousCpu = null, previousTime = null;
  let snapshot = Object.freeze({ busy: false, cpuPercent: null, memoryPercent: null, sampledAt: null,
    limitPercent: high, recoveryPercent: low });
  function sample() {
    let cpu = null, memory = null, time = null;
    try { cpu = readCpu(); } catch (_) { /* A failed metric must never become NaN or a synthetic 100%. */ }
    try { memory = readMemory(); } catch (_) { /* Keep resource probing independent. */ }
    try { const value = now(); time = finite(value) ? value : null; } catch (_) { /* No valid CPU wall-clock interval. */ }
    const cpuUsage = cpuPercent(previousCpu, cpu, time !== null && previousTime !== null ? time - previousTime : null);
    const memoryUsage = memoryPercent(memory);
    let busy = snapshot.busy;
    if ((cpuUsage !== null && cpuUsage >= high) || (memoryUsage !== null && memoryUsage >= high)) busy = true;
    else if (busy && cpuUsage !== null && memoryUsage !== null && cpuUsage <= low && memoryUsage <= low) busy = false;
    snapshot = Object.freeze({ busy, cpuPercent: cpuUsage, memoryPercent: memoryUsage, sampledAt: time,
      limitPercent: high, recoveryPercent: low });
    previousCpu = cpu; previousTime = time;
    return snapshot;
  }
  function start() {
    if (timer !== null) return;
    previousCpu = null; previousTime = null;
    sample();
    timer = schedule(sample, sampleIntervalMs);
    if (timer && typeof timer.unref === 'function') timer.unref();
  }
  function stop() { if (timer !== null) { cancel(timer); timer = null; } }
  function getSnapshot() { return snapshot; }
  function middleware(_req, res, next) {
    if (!snapshot.busy) return next();
    res.set('Retry-After', String(RETRY_AFTER_SECONDS));
    res.set('Cache-Control', 'no-store');
    return res.status(503).json({ success: false, code: 'SERVER_BUSY', error: 'The server is busy. Please try again shortly.' });
  }
  return { start, stop, sample, getSnapshot, middleware };
}

module.exports = { createResourcePressureMonitor, createSystemResourceReaders };
