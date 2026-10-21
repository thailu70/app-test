(function (root, factory) {
  const masterData = factory();
  if (typeof module === 'object' && module.exports) module.exports = masterData;
  if (root) root.RoutepassMasterData = masterData;
})(typeof window === 'undefined' ? null : window, function () {
  'use strict';

  const escape = value => String(value ?? '').replace(/[&<>"']/g,
    character => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[character]);

  function markup() {
    return '<div id="master-data-panel">' +
      '<section class="card section-card"><div class="section-title"><div><h3>Vehicle types</h3><p>Manage the names and seating capacities available for vehicle registration.</p></div><button id="master-types-refresh" class="btn btn-secondary" type="button">Refresh types</button></div>' +
      '<div id="master-types-table" class="table-wrap"><p class="loading">Loading vehicle types…</p></div>' +
      '<form id="master-type-form" class="master-form"><h4 id="master-type-form-title">Add a vehicle type</h4><p class="master-help">Type codes stay fixed after creation. Capacity changes apply to new vehicles and type assignments; existing vehicle capacities are retained.</p>' +
      '<div class="master-type-fields"><div class="field"><label for="master-type-code">Type code</label><input id="master-type-code" name="code" maxlength="30" pattern="[A-Z][A-Z0-9_]{1,29}" placeholder="MINIBUS_14" autocomplete="off" required><span class="hint">2–30 uppercase letters, numbers or underscores; start with a letter.</span></div>' +
      '<div class="field"><label for="master-type-name">Display name</label><input id="master-type-name" name="name" minlength="2" maxlength="80" placeholder="Minibus" required></div>' +
      '<div class="field"><label for="master-type-capacity">Passenger seats</label><input id="master-type-capacity" name="capacity" type="number" min="1" max="100" step="1" inputmode="numeric" required><span class="hint">1–100 seats.</span></div></div>' +
      '<label class="master-checkbox" for="master-type-active"><input id="master-type-active" type="checkbox" checked> Available for new vehicles</label>' +
      '<div class="form-actions"><button id="master-type-submit" class="btn btn-primary" type="submit">Add vehicle type</button><button id="master-type-cancel" class="btn btn-secondary" type="button" hidden>Cancel edit</button></div>' +
      '<p id="master-types-message" class="form-message" role="status" hidden></p></form></section>' +
      '<div class="master-settings-grid"><section class="card section-card"><div class="section-title"><div><h3>Global GPS boarding radius</h3><p>One arrival radius for daily commute and intercity vehicles.</p></div><button id="master-radius-refresh" class="btn btn-secondary" type="button">Refresh</button></div>' +
      '<form id="master-radius-form" class="master-form"><p id="master-radius-scope" class="master-help">Loading the current radius…</p>' +
      '<div class="field"><label for="master-radius-input">GPS radius (metres)</label><input id="master-radius-input" type="number" min="25" max="5000" step="1" inputmode="numeric" aria-describedby="master-radius-help" required disabled><span id="master-radius-help" class="hint">25–5,000 metres from the configured departure point. Applies to all vehicles, including company intercity trips.</span></div>' +
      '<div class="form-actions"><button id="master-radius-submit" class="btn btn-primary" type="submit" disabled>Save global radius</button></div><p id="master-radius-message" class="form-message" role="status" hidden></p></form></section>' +
      '<section class="card section-card"><div class="section-title"><div><h3>Driver GPS sharing frequency</h3><p>Choose how often drivers share their location.</p></div><button id="master-sharing-refresh" class="btn btn-secondary" type="button">Refresh</button></div>' +
      '<form id="master-sharing-form" class="master-form"><p class="master-help">A longer interval reduces location uploads and VPS load. A shorter interval gives more frequent location updates.</p>' +
      '<div class="field"><label for="master-sharing-input">Location sharing interval (seconds)</label><input id="master-sharing-input" type="number" min="1" max="60" step="1" inputmode="numeric" aria-describedby="master-sharing-help" required disabled><span id="master-sharing-help" class="hint">1–60 seconds between driver location updates. Used by daily commute and intercity drivers in supported app versions.</span></div>' +
      '<div class="form-actions"><button id="master-sharing-submit" class="btn btn-primary" type="submit" disabled>Save sharing interval</button></div><p id="master-sharing-message" class="form-message" role="status" hidden></p></form></section></div></div>';
  }

  function mount({ container, api, isActive = () => true }) {
    const find = id => container.querySelector('#' + id);
    const listeners = [];
    const requests = new Set();
    let disposed = false, types = [], editingCode = null, typeSaving = false;
    const active = () => !disposed && isActive();
    function listen(id, event, handler) {
      const node = find(id);
      node.addEventListener(event, handler);
      listeners.push(() => node.removeEventListener(event, handler));
    }
    function message(id, text, error = false) {
      if (!active()) return;
      const node = find(id);
      node.textContent = text;
      node.className = 'form-message' + (error ? ' error' : '');
      node.hidden = !text;
    }
    async function call(path, options = {}) {
      const controller = new AbortController();
      requests.add(controller);
      try { return await api(path, { ...options, signal: controller.signal }); }
      finally { requests.delete(controller); }
    }
    function renderTypes() {
      if (!active()) return;
      const rows = types.map(type => '<tr><td><code>' + escape(type.code) + '</code></td><td>' + escape(type.name) +
        '</td><td>' + escape(type.capacity) + '</td><td><span class="pill ' + (type.active ? 'active' : 'inactive') + '">' +
        (type.active ? 'Available' : 'Unavailable') + '</span></td><td><button class="btn btn-secondary" type="button" data-master-edit="' + escape(type.code) +
        '" aria-label="Edit ' + escape(type.name) + '"' + (typeSaving ? ' disabled' : '') + '>Edit</button></td></tr>').join('');
      find('master-types-table').innerHTML = '<table><thead><tr><th>Code</th><th>Vehicle type</th><th>Seats</th><th>Availability</th><th>Action</th></tr></thead><tbody>' +
        (rows || '<tr><td class="empty-cell" colspan="5">No vehicle types yet. Add the first type below.</td></tr>') + '</tbody></table>';
    }
    function resetType() {
      editingCode = null;
      find('master-type-code').value = '';
      find('master-type-code').readOnly = false;
      find('master-type-name').value = '';
      find('master-type-capacity').value = '';
      find('master-type-active').checked = true;
      find('master-type-form-title').textContent = 'Add a vehicle type';
      find('master-type-submit').textContent = 'Add vehicle type';
      find('master-type-cancel').hidden = true;
    }
    function typeBusy(busy) {
      typeSaving = busy;
      for (const id of ['master-type-code', 'master-type-name', 'master-type-capacity', 'master-type-active',
        'master-type-submit', 'master-type-cancel', 'master-types-refresh']) find(id).disabled = busy;
      renderTypes();
    }
    async function loadTypes() {
      find('master-types-refresh').disabled = true;
      try {
        const result = await call('/api/master-data/vehicle-types');
        if (!active()) return false;
        if (!result.success || !Array.isArray(result.vehicleTypes)) throw new Error('Could not load vehicle types.');
        types = result.vehicleTypes;
        renderTypes();
        message('master-types-message', '');
        return true;
      } catch (error) {
        if (active()) {
          if (!types.length) find('master-types-table').innerHTML = '<p class="empty-note">Vehicle types are unavailable. Use Refresh types to try again.</p>';
          message('master-types-message', 'Could not load vehicle types. ' + (types.length ? 'Last loaded types are still shown. ' : '') + (error.message || 'Try again.'), true);
        }
        return false;
      } finally { if (active()) find('master-types-refresh').disabled = typeSaving; }
    }
    listen('master-types-refresh', 'click', loadTypes);
    listen('master-type-cancel', 'click', () => { resetType(); message('master-types-message', ''); });
    listen('master-types-table', 'click', event => {
      const button = event.target.closest('[data-master-edit]');
      if (!button || typeSaving) return;
      const type = types.find(row => row.code === button.dataset.masterEdit);
      if (!type) return;
      editingCode = type.code;
      find('master-type-code').value = type.code;
      find('master-type-code').readOnly = true;
      find('master-type-name').value = type.name;
      find('master-type-capacity').value = String(type.capacity);
      find('master-type-active').checked = Boolean(type.active);
      find('master-type-form-title').textContent = 'Edit vehicle type';
      find('master-type-submit').textContent = 'Save vehicle type';
      find('master-type-cancel').hidden = false;
      message('master-types-message', '');
      find('master-type-name').focus();
    });
    listen('master-type-form', 'submit', async event => {
      event.preventDefault();
      if (typeSaving) return;
      const code = editingCode || find('master-type-code').value.trim().toUpperCase();
      const name = find('master-type-name').value.trim();
      const capacity = Number(find('master-type-capacity').value);
      if (!/^[A-Z][A-Z0-9_]{1,29}$/.test(code)) return message('master-types-message', 'Use a 2–30 character code starting with a letter, with uppercase letters, numbers or underscores.', true);
      if (name.length < 2 || name.length > 80) return message('master-types-message', 'Use a display name with 2–80 characters.', true);
      if (!Number.isInteger(capacity) || capacity < 1 || capacity > 100) return message('master-types-message', 'Use a whole number from 1 to 100 seats.', true);
      const editing = Boolean(editingCode);
      const body = { name, capacity, active: find('master-type-active').checked };
      if (!editing) body.code = code;
      typeBusy(true);
      message('master-types-message', 'Saving vehicle type…');
      try {
        const result = await call('/api/master-data/vehicle-types' + (editing ? '/' + encodeURIComponent(code) : ''),
          { method: editing ? 'PATCH' : 'POST', body: JSON.stringify(body) });
        if (!active()) return;
        if (!result.success) throw new Error(result.error || 'Could not save the vehicle type.');
        resetType();
        const reloaded = await loadTypes();
        if (active()) message('master-types-message', reloaded ? 'Vehicle type saved.' : 'Vehicle type saved, but the list could not be refreshed. Use Refresh types to try again.', !reloaded);
      } catch (error) { if (active()) message('master-types-message', error.message || 'Could not save the vehicle type. Try again.', true); }
      finally { if (active()) typeBusy(false); }
    });

    function numericSetting({ key, path, field, min, max, saved, onValue }) {
      let loading = false, saving = false, loaded = false;
      const input = find('master-' + key + '-input');
      const submit = find('master-' + key + '-submit');
      const refresh = find('master-' + key + '-refresh');
      const messageId = 'master-' + key + '-message';
      function busy() { input.disabled = !loaded || loading || saving; submit.disabled = !loaded || loading || saving; refresh.disabled = loading || saving; }
      function apply(result) {
        const number = result[field];
        if (!result.success || !Number.isInteger(number) || number < min || number > max) throw new Error('The server returned an invalid setting.');
        input.value = String(number);
        loaded = true;
        if (onValue) onValue(result);
      }
      async function load() {
        if (loading || saving) return;
        loading = true; busy();
        try { const result = await call(path); if (active()) { apply(result); message(messageId, ''); } }
        catch (error) { if (active()) message(messageId, 'Could not load this setting. ' + (error.message || 'Try again.'), true); }
        finally { loading = false; if (active()) busy(); }
      }
      listen('master-' + key + '-refresh', 'click', load);
      listen('master-' + key + '-form', 'submit', async event => {
        event.preventDefault();
        if (loading || saving || !loaded) return;
        const number = Number(input.value);
        if (!Number.isInteger(number) || number < min || number > max) return message(messageId, 'Use a whole number from ' + min + ' to ' + max + '.', true);
        saving = true; busy(); message(messageId, 'Saving…');
        try {
          const result = await call(path, { method: 'PUT', body: JSON.stringify({ [field]: number }) });
          if (active()) { apply(result); message(messageId, saved); }
        } catch (error) { if (active()) message(messageId, error.message || 'Could not save this setting. Try again.', true); }
        finally { saving = false; if (active()) busy(); }
      });
      load();
    }
    numericSetting({ key: 'radius', path: '/api/master-data/gps-radius', field: 'radiusMeters', min: 25, max: 5000,
      saved: 'Global GPS radius saved for all daily commute and intercity vehicles.',
      onValue: result => { find('master-radius-scope').textContent = result.overrideEnabled
        ? 'Global radius is active for all daily commute and intercity vehicles.'
        : 'No global override is active. Existing route and departure radii remain in use until you save a global radius.'; } });
    numericSetting({ key: 'sharing', path: '/api/master-data/gps-sharing', field: 'intervalSeconds', min: 1, max: 60,
      saved: 'Driver GPS sharing interval saved.' });
    loadTypes();
    return () => { disposed = true; listeners.forEach(remove => remove()); requests.forEach(controller => controller.abort()); requests.clear(); };
  }
  return { markup, mount };
});
