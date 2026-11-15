(function (root, factory) {
  const panel = factory();
  if (typeof module === 'object' && module.exports) module.exports = panel;
  if (root) root.PassoRidesAdmin = panel;
})(typeof window === 'undefined' ? null : window, function () {
  'use strict';
  const ROOT = '/api/rides/admin';
  const fields = [
    ['baseFareEtb', 'Base fare · ETB', 0, 100000, '.01'],
    ['perKmEtb', 'Distance rate · ETB per km', 0, 100000, '.01'],
    ['minimumFareEtb', 'Minimum fare · ETB', 0, 100000, '.01'],
    ['waitingPerMinuteEtb', 'Billable waiting · ETB per minute', 0, 100000, '.01'],
    ['pickupGraceMinutes', 'Free pickup waiting · minutes', 0, 120, '1'],
    ['stopGraceMinutes', 'Free requested-stop waiting · minutes', 0, 120, '1'],
    ['maxWaitingMinutes', 'Maximum billable waiting per stop · minutes', 1, 1440, '1'],
    ['noShowGraceMinutes', 'Shared boarding no-show grace · minutes', 1, 120, '1'],
    ['fixedCommissionEtb', 'Passo fee per completed taxi trip · ETB', 0, 100000, '.01'],
    ['sharedFixedCommissionEtb', 'Passo fee per shared vehicle trip · ETB', 0, 100000, '.01'],
    ['notificationRadiusKm', 'Nearby request radius · km', .1, 200, '.1'],
    ['arrivalRadiusMeters', 'Arrival verification radius · metres', 30, 2000, '1'],
    ['scheduleBufferMinutes', 'Buffer before scheduled commute · minutes', 0, 240, '1'],
    ['maxTripMinutes', 'Maximum estimated trip duration · minutes', 5, 720, '1'],
    ['minimumSharedFareEtb', 'Minimum proposed shared fare per seat · ETB', .01, 1000000, '.01'],
    ['maximumSharedFareEtb', 'Maximum proposed shared fare per seat · ETB', .01, 1000000, '.01'],
    ['pollingSeconds', 'App refresh interval · seconds', 3, 60, '1'],
    ['gpsUploadSeconds', 'Driver GPS upload interval · seconds', 1, 60, '1']
  ];
  const tiers = ['ECONOMY', 'COMFORT', 'MINI_VAN'];
  const names = { ECONOMY: 'Economy', COMFORT: 'Comfort', MINI_VAN: 'Mini-Van' };
  const terminal = status => ['COMPLETED', 'CANCELLED'].includes(status);
  function money(v) { return Number.isFinite(Number(v)) && v !== null ? 'ETB ' + Number(v).toLocaleString('en-ET', { maximumFractionDigits: 2 }) : 'Not set'; }
  function markup() {
    return '<div id="rides-admin-panel"><div class="rides-toolbar"><div class="rides-tabs" role="tablist" aria-label="Taxi and shared rides">' +
      '<button id="rides-tab-settings" class="btn btn-secondary" type="button" role="tab" aria-selected="true" aria-controls="rides-settings">Fares &amp; settings</button>' +
      '<button id="rides-tab-eligibility" class="btn btn-secondary" type="button" role="tab" aria-selected="false" aria-controls="rides-eligibility">Driver approvals</button>' +
      '<button id="rides-tab-trips" class="btn btn-secondary" type="button" role="tab" aria-selected="false" aria-controls="rides-trips">Trips &amp; support</button></div>' +
      '<button id="rides-refresh" class="btn btn-secondary" type="button">Refresh</button></div>' +
      '<p id="rides-message" class="form-message" role="status" hidden></p>' +
      '<section id="rides-settings" class="card form-card" role="tabpanel" aria-labelledby="rides-tab-settings">' +
      '<h3>Taxi fares and shared rides</h3><p class="rides-help">New taxi bookings stay unavailable until you configure fares and enable the service. Passengers pay the driver directly. Normal traffic time is never billable waiting. New rates apply to new journeys; existing fare and fee snapshots are retained.</p>' +
      '<form id="rides-settings-form"><div class="rides-switches"><label class="rides-check"><input id="rides-enabled" type="checkbox"> Enable new taxi bookings</label>' +
      '<label class="rides-check"><input id="rides-sharedEnabled" type="checkbox"> Enable shared Group Ride bookings</label></div>' +
      '<div class="driver-form-grid">' + fields.map(([key, label, min, max, step]) => '<div class="field"><label for="rides-' + key + '">' + label + '</label><input id="rides-' + key + '" type="number" min="' + min + '" max="' + max + '" step="' + step + '" required inputmode="decimal"></div>').join('') + '</div>' +
      '<h4>Vehicle tier multipliers</h4><div id="rides-tiers" class="rides-tier-grid">' + tiers.map(id => '<div class="field"><label for="rides-tier-' + id + '">' + names[id] + '</label><input id="rides-tier-' + id + '" type="number" min=".1" max="10" step=".01" required></div>').join('') + '</div>' +
      '<p class="rides-help">Shared fares are agreed per seat and locked when booked. The shared Passo fee is charged once per completed vehicle trip, regardless of the number of bookings. Only verified driver credit top-ups are available in Group travel &amp; cargo.</p>' +
      '<div class="form-actions"><button class="btn btn-primary" type="submit">Save settings</button></div></form></section>' +
      '<section id="rides-eligibility" role="tabpanel" aria-labelledby="rides-tab-eligibility" hidden><p class="rides-help">Taxi approval is separate from daily commute, cargo and intercity assignments. Required registration documents and operational approval still apply. Approve each eligible driver and vehicle, and choose its taxi tier.</p><div id="rides-driver-list"></div></section>' +
      '<section id="rides-trips" role="tabpanel" aria-labelledby="rides-tab-trips" hidden><p class="rides-help">Recent taxi and shared trips. A support resolution requires a reason and is audited. Completing a taxi settles its existing meter fare and fixed Passo fee; cancelling before travel releases the fee hold.</p><div id="rides-trip-list"></div></section></div>';
  }
  function settingsPayload(read) {
    const payload = { enabled: Boolean(read('enabled', true)), sharedEnabled: Boolean(read('sharedEnabled', true)) };
    for (const [key, label, min, max] of fields) {
      const raw = read(key), value = Number(raw);
      if (raw === '' || !Number.isFinite(value) || value < min || value > max) throw new Error(label + ' is outside the allowed range.');
      payload[key] = value;
    }
    if (payload.enabled && [payload.baseFareEtb, payload.perKmEtb, payload.minimumFareEtb].some(value => value <= 0)) throw new Error('Enter a positive base fare, per-km rate and minimum fare before enabling taxi bookings.');
    if (payload.maximumSharedFareEtb < payload.minimumSharedFareEtb) throw new Error('The maximum shared fare must be at least the minimum shared fare.');
    payload.tiers = tiers.map(id => {
      const raw = read('tier-' + id), multiplier = Number(raw);
      if (raw === '' || !Number.isFinite(multiplier) || multiplier < .1 || multiplier > 10) throw new Error(names[id] + ' multiplier must be between 0.1 and 10.');
      return { id, name: names[id], multiplier };
    });
    return payload;
  }
  function mount({ container, api, isActive = () => true }) {
    const doc = container.ownerDocument || document, find = id => container.querySelector('#rides-' + id);
    let disposed = false, loading = false, saving = false, revision = 0, mode = 'settings';
    const requests = new Set(), listeners = [], active = () => !disposed && isActive();
    const node = (tag, text, cls) => { const el = doc.createElement(tag); if (text !== undefined) el.textContent = String(text); if (cls) el.className = cls; return el; };
    function listen(el, event, handler) { el.addEventListener(event, handler); listeners.push(() => el.removeEventListener(event, handler)); }
    function message(text = '', error = false) { if (!active()) return; find('message').textContent = text; find('message').className = 'form-message' + (error ? ' error' : ''); find('message').hidden = !text; }
    function busy() { for (const el of container.querySelectorAll('button,input,select,textarea')) el.disabled = loading || saving; container.setAttribute('aria-busy', String(loading || saving)); }
    async function call(path, options = {}) { const controller = new AbortController(); requests.add(controller); try { const result = await api(ROOT + path, { ...options, signal: controller.signal }); if (!result.success) throw new Error(result.error || 'The operation could not be completed.'); return result; } finally { requests.delete(controller); } }
    function populateSettings(result) {
      for (const [key] of fields) find(key).value = result.settings[key] ?? '';
      for (const key of ['enabled', 'sharedEnabled']) find(key).checked = result.settings[key] === true;
      for (const id of tiers) find('tier-' + id).value = (result.tiers || []).find(tier => tier.id === id)?.multiplier ?? 1;
    }
    function checkbox(text, checked) { const label = node('label', undefined, 'rides-check'), input = node('input'); input.type = 'checkbox'; input.checked = Boolean(checked); label.append(input, node('span', text)); return { label, input }; }
    function renderDrivers(drivers) {
      const list = find('driver-list'); list.replaceChildren();
      if (!drivers.length) list.append(node('p', 'No operationally approved drivers are available.', 'empty-note'));
      for (const driver of drivers) {
        const form = node('form', undefined, 'card form-card rides-driver'); form.dataset.driver = driver.id;
        form.append(node('h3', driver.fullName), node('p', driver.phone || '', 'rides-help'));
        const approved = checkbox('Approve this driver for taxi and shared rides', driver.approved); form.append(approved.label);
        const vehicleInputs = [];
        for (const vehicle of driver.vehicles || []) {
          const row = node('div', undefined, 'rides-vehicle');
          const enabled = checkbox([vehicle.model, vehicle.plateNumber, vehicle.capacityLimit + ' passenger seats'].filter(Boolean).join(' · '), vehicle.approved);
          const select = node('select'); select.setAttribute('aria-label', 'Taxi tier for ' + vehicle.plateNumber);
          for (const id of tiers) { const option = node('option', names[id]); option.value = id; select.append(option); } select.value = vehicle.tier || 'ECONOMY';
          row.append(enabled.label, select); form.append(row); vehicleInputs.push({ vehicleId: vehicle.id, input: enabled.input, select });
        }
        if (!vehicleInputs.length) form.append(node('p', 'No eligible driver-owned vehicles.', 'rides-help'));
        const actions = node('div', undefined, 'form-actions'), button = node('button', 'Save approval', 'btn btn-primary'); button.type = 'submit'; actions.append(button); form.append(actions);
        listen(form, 'submit', async event => {
          event.preventDefault(); if (saving || loading || !active()) return;
          const payload = { approved: approved.input.checked, vehicles: vehicleInputs.map(value => ({ vehicleId: value.vehicleId, approved: value.input.checked, tier: value.select.value })) };
          await save('/eligibility/' + encodeURIComponent(driver.id), 'PUT', payload, 'Driver and vehicle approvals saved.');
        }); list.append(form);
      }
    }
    function renderTrips(result) {
      const list = find('trip-list'); list.replaceChildren();
      const rides = result.rides || [], shared = result.sharedTrips || [];
      if (!rides.length && !shared.length) list.append(node('p', 'No taxi or shared rides yet.', 'empty-note'));
      for (const ride of [...rides.map(value => ({ ...value, shared: false })), ...shared.map(value => ({ ...value, shared: true }))]) {
        const card = node('section', undefined, 'card form-card rides-driver');
        card.append(node('h3', ride.origin + ' → ' + ride.destination));
        card.append(node('p', (ride.shared ? 'Shared Group Ride' : 'Taxi · ' + (names[ride.tier] || ride.tier)) + ' · ' + ride.status, 'rides-help'));
        const facts = node('dl', undefined, 'mobility-facts');
        for (const [label, value] of [['Trip reference', ride.id], ['Driver', ride.driverName || 'Not assigned'], ['Passenger', ride.shared ? 'Separate seat bookings' : ride.passengerName || (ride.source === 'STREET' ? 'Street pickup' : 'Not assigned')], ['Fare', ride.shared ? money(ride.farePerSeatEtb) + ' / seat' : money(ride.fareEtb ?? ride.estimatedFareEtb)], ['Passo fee', money(ride.commissionEtb)], ['Settlement', ride.settlement || 'Not settled']]) {
          const row = node('div'); row.append(node('dt', label), node('dd', value)); facts.append(row);
        } card.append(facts);
        if (!terminal(ride.status)) {
          const form = node('form', undefined, 'rides-resolution'), select = node('select'), label = node('label', 'Support resolution');
          select.setAttribute('aria-label', 'Resolution for ' + ride.id);
          const choices = [['', 'Choose a resolution'], ['CANCEL', 'Cancel and release fee hold']];
          if (ride.status === 'IN_PROGRESS') choices.push(['COMPLETE', 'Complete and settle recorded fare']);
          for (const [value, text] of choices) { const option = node('option', text); option.value = value; select.append(option); }
          const note = node('textarea'); note.required = true; note.minLength = 5; note.maxLength = 2000; note.rows = 2; note.placeholder = 'Reason and supporting details'; note.setAttribute('aria-label', 'Support resolution reason');
          const button = node('button', 'Record support resolution', 'btn btn-secondary'); button.type = 'submit';
          form.append(label, select, note, button);
          listen(form, 'submit', async event => { event.preventDefault(); if (saving || loading || !active()) return; if (!select.value || note.value.trim().length < 5) return message('Choose a resolution and enter a reason of at least five characters.', true); await save((ride.shared ? '/shared/trips/' : '/trips/') + encodeURIComponent(ride.id) + '/resolve', 'POST', { action: select.value, note: note.value.trim() }, 'Support resolution recorded.'); });
          card.append(form);
        } list.append(card);
      }
    }
    async function load(clearMessage = true) {
      if (!active() || loading || saving) return;
      const current = ++revision; loading = true; if (clearMessage) message(); busy();
      try {
        const result = await call('/' + mode);
        if (!active() || current !== revision) return;
        if (mode === 'settings') populateSettings(result);
        else if (mode === 'eligibility') renderDrivers(result.drivers || []);
        else renderTrips(result);
      } catch (error) { if (active() && current === revision) message('Could not refresh. ' + error.message, true); }
      finally { if (active() && current === revision) { loading = false; busy(); } }
    }
    async function save(path, method, payload, success) {
      if (!active() || saving || loading) return;
      saving = true; busy(); message();
      try { const result = await call(path, { method, body: JSON.stringify(payload) }); if (!active()) return; if (path === '/settings') populateSettings(result); message(success); }
      catch (error) { if (active()) message(error.message || 'Could not save. Your changes are still here.', true); return; }
      finally { if (active()) { saving = false; busy(); } }
      if (path !== '/settings') await load(false);
    }
    for (const name of ['settings', 'eligibility', 'trips']) listen(find('tab-' + name), 'click', () => {
      if (loading || saving || mode === name) return;
      mode = name;
      for (const tab of ['settings', 'eligibility', 'trips']) { find(tab).hidden = name !== tab; find('tab-' + tab).setAttribute('aria-selected', String(name === tab)); }
      void load();
    });
    listen(find('refresh'), 'click', () => void load());
    listen(find('settings-form'), 'submit', async event => {
      event.preventDefault(); if (loading || saving || !active()) return;
      try { const payload = settingsPayload((key, checked) => checked ? find(key).checked : find(key).value); await save('/settings', 'PUT', payload, 'Settings saved. Existing journeys retain their agreed rates.'); }
      catch (error) { message(error.message, true); }
    });
    void load();
    return () => { disposed = true; revision++; for (const request of requests) request.abort(); for (const remove of listeners) remove(); };
  }
  return { markup, mount, settingsPayload, fields };
});
