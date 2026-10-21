(function (root, factory) {
  const resources = factory();
  if (typeof module === 'object' && module.exports) module.exports = resources;
  if (root) root.RoutepassResources = resources;
})(typeof window === 'undefined' ? null : window, function () {
  'use strict';

  function markup() {
    return '<section id="resource-panel" class="card section-card resource-panel">' +
      '<div class="section-title"><div><h3>VPS CPU &amp; RAM</h3><p>Live capacity and a safe threshold preview.</p></div><button id="resource-refresh" class="btn btn-secondary" type="button">Refresh readings</button></div>' +
      '<div class="resource-body"><div class="resource-live-heading"><span id="resource-live-status" class="pill" role="status">Loading readings…</span><span id="resource-live-updated" class="hint"></span></div>' +
      '<div class="resource-grid"><div class="resource-reading"><span>CPU usage</span><strong id="resource-cpu-value">—</strong><progress id="resource-cpu-meter" max="100" value="0" aria-label="Live CPU usage" hidden></progress></div>' +
      '<div class="resource-reading"><span>RAM usage</span><strong id="resource-memory-value">—</strong><progress id="resource-memory-meter" max="100" value="0" aria-label="Live RAM usage" hidden></progress></div></div>' +
      '<p id="resource-live-message" class="resource-help">Reading server capacity…</p>' +
      '<p id="resource-thresholds" class="resource-help">Pause at 90% CPU or RAM. Resume when both are 85% or lower.</p>' +
      '<div class="resource-simulator"><div class="resource-simulator-heading"><div><h4>CPU &amp; RAM simulator</h4><p>Preview only — does not change server load.</p></div><button id="resource-sim-reset" class="btn btn-secondary" type="button">Reset preview</button></div>' +
      '<div class="resource-grid"><div class="field"><label for="resource-sim-cpu">Simulated CPU <output id="resource-sim-cpu-value" for="resource-sim-cpu">0%</output></label><input id="resource-sim-cpu" type="range" min="0" max="100" step="1" value="0"></div>' +
      '<div class="field"><label for="resource-sim-memory">Simulated RAM <output id="resource-sim-memory-value" for="resource-sim-memory">0%</output></label><input id="resource-sim-memory" type="range" min="0" max="100" step="1" value="0"></div></div>' +
      '<div class="resource-preview" aria-live="polite"><strong id="resource-sim-status">Accepting requests</strong><p id="resource-sim-message">Move either slider to preview the capacity threshold.</p></div></div></div></section>';
  }

  function mount({ container, fetchResources, isActive = () => true, timers = window }) {
    const find = id => container.querySelector('#' + id);
    let disposed = false, pending = false, timer = null, controller = null;
    let high = 90, low = 85, simulatedBusy = false;
    const refreshButton = find('resource-refresh');
    const cpuSlider = find('resource-sim-cpu');
    const memorySlider = find('resource-sim-memory');
    const resetButton = find('resource-sim-reset');
    const active = () => !disposed && isActive();
    const percentage = value => typeof value === 'number' && Number.isFinite(value) && value >= 0 && value <= 100 ? value : null;
    const formatPercent = value => value.toLocaleString(undefined, { maximumFractionDigits: 1 }) + '%';

    function preview() {
      const cpu = percentage(Number(cpuSlider.value)), memory = percentage(Number(memorySlider.value));
      find('resource-sim-cpu-value').textContent = cpu === null ? '—' : formatPercent(cpu);
      find('resource-sim-memory-value').textContent = memory === null ? '—' : formatPercent(memory);
      if (cpu === null || memory === null) {
        find('resource-sim-status').textContent = 'Preview unavailable';
        find('resource-sim-message').textContent = 'Select CPU and RAM values between 0% and 100%.';
        return;
      }
      if (cpu >= high || memory >= high) simulatedBusy = true;
      else if (cpu <= low && memory <= low) simulatedBusy = false;
      const result = find('resource-sim-status');
      result.textContent = simulatedBusy ? 'New requests would pause' : 'Accepting requests';
      result.className = simulatedBusy ? 'resource-warning' : 'resource-ready';
      find('resource-sim-message').textContent = simulatedBusy
        ? 'The server would ask the app to retry in 10 seconds. Bring both readings to ' + low + '% or lower to resume.'
        : 'These simulated readings allow normal requests. The pause threshold is ' + high + '% CPU or RAM.';
    }

    function reading(name, value) {
      const amount = percentage(value);
      find('resource-' + name + '-value').textContent = amount === null ? 'Unavailable' : formatPercent(amount);
      const meter = find('resource-' + name + '-meter');
      meter.hidden = amount === null;
      if (amount !== null) meter.value = amount;
    }

    function render(resources) {
      if (!resources || typeof resources.busy !== 'boolean') throw new Error('Live readings are unavailable.');
      const configuredHigh = percentage(resources.limitPercent), configuredLow = percentage(resources.recoveryPercent);
      if (configuredHigh !== null && configuredLow !== null && configuredLow < configuredHigh) {
        high = configuredHigh; low = configuredLow;
      }
      reading('cpu', resources.cpuPercent);
      reading('memory', resources.memoryPercent);
      const status = find('resource-live-status');
      const bothKnown = percentage(resources.cpuPercent) !== null && percentage(resources.memoryPercent) !== null;
      status.textContent = resources.busy ? 'New requests paused' : bothKnown ? 'Accepting requests' : 'Waiting for readings';
      status.className = 'pill ' + (resources.busy ? 'pending' : bothKnown ? 'active' : '');
      find('resource-live-message').textContent = resources.busy
        ? 'The server is busy. Apps are asked to retry in 10 seconds.'
        : bothKnown ? 'Normal app traffic is allowed. Login protections remain active.' : 'The server has not supplied every reading yet.';
      const sampledAt = typeof resources.sampledAt === 'number' && Number.isFinite(resources.sampledAt) ? new Date(resources.sampledAt) : null;
      find('resource-live-updated').textContent = sampledAt && !Number.isNaN(sampledAt.getTime())
        ? 'Updated ' + sampledAt.toLocaleTimeString() : 'No sample time available';
      find('resource-thresholds').textContent = 'Pause at ' + high + '% CPU or RAM. Resume when both are ' + low + '% or lower.';
      preview();
    }

    async function refresh() {
      if (!active() || pending) return;
      if (timer !== null) { timers.clearTimeout(timer); timer = null; }
      pending = true;
      refreshButton.disabled = true;
      controller = new AbortController();
      const requestTimeout = timers.setTimeout(() => controller?.abort(), 12000);
      try {
        const result = await fetchResources(controller.signal);
        if (!active()) return;
        if (!result?.success) throw new Error('Live readings are unavailable.');
        render(result.resources);
      } catch (error) {
        if (!active()) return;
        reading('cpu', null); reading('memory', null);
        find('resource-live-status').textContent = 'Live readings unavailable';
        find('resource-live-status').className = 'pill inactive';
        find('resource-live-updated').textContent = '';
        find('resource-live-message').textContent = error.name === 'AbortError' ? 'The request timed out. Refresh to try again.' : 'Could not load server capacity. Refresh to try again. The simulator is still available.';
      } finally {
        timers.clearTimeout(requestTimeout);
        pending = false; controller = null;
        if (active()) {
          refreshButton.disabled = false;
          timer = timers.setTimeout(() => { timer = null; refresh(); }, 5000);
        }
      }
    }

    function reset() { cpuSlider.value = '0'; memorySlider.value = '0'; simulatedBusy = false; preview(); }
    refreshButton.addEventListener('click', refresh);
    cpuSlider.addEventListener('input', preview);
    memorySlider.addEventListener('input', preview);
    resetButton.addEventListener('click', reset);
    preview(); refresh();
    return function cleanup() {
      disposed = true;
      if (timer !== null) timers.clearTimeout(timer);
      controller?.abort();
      refreshButton.removeEventListener('click', refresh);
      cpuSlider.removeEventListener('input', preview);
      memorySlider.removeEventListener('input', preview);
      resetButton.removeEventListener('click', reset);
    };
  }

  return { markup, mount };
});
