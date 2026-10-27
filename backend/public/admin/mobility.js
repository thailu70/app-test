(function (root, factory) {
  const panel = factory();
  if (typeof module === 'object' && module.exports) module.exports = panel;
  if (root) root.PassoMobilityAdmin = panel;
})(typeof window === 'undefined' ? null : window, function () {
  'use strict';

  const ROOT = '/api/mobility/admin';
  const terminal = row => ['REJECTED', 'COMPLETED'].includes(row.status);
  const assignable = row => ['REQUESTED', 'ASSIGNED'].includes(row.status);
  const titleCase = value => String(value || 'Unknown').replace(/_/g, ' ').toLowerCase().replace(/\b\w/g, letter => letter.toUpperCase());
  function money(value) {
    const number = Number(value);
    return value !== null && value !== undefined && value !== '' && Number.isFinite(number)
      ? 'ETB ' + number.toLocaleString('en-ET', { maximumFractionDigits: 2 }) : 'Not quoted';
  }
  function dateText(value) {
    if (!value) return 'Not specified';
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return String(value);
    if (/^\d{4}-\d{2}-\d{2}$/.test(String(value))) return date.toLocaleDateString('en-GB', { timeZone: 'Africa/Addis_Ababa', dateStyle: 'medium' }) + ' · Ethiopia';
    return date.toLocaleString('en-GB', {
      timeZone: 'Africa/Addis_Ababa', dateStyle: 'medium', timeStyle: 'short'
    }) + ' · Ethiopia';
  }
  function photoPath(value) {
    return typeof value === 'string' && /^\/api\/mobility\/[A-Za-z0-9/_-]+$/.test(value) ? value : null;
  }
  function vehicleChoices(options, driverId) {
    return (options.vehicles || []).filter(vehicle => String(vehicle.driverId) === String(driverId));
  }
  function markup() {
    return '<div id="mobility-admin-panel">' +
      '<div class="mobility-toolbar"><div class="mobility-tabs" role="tablist" aria-label="Group travel and cargo">' +
      '<button id="mobility-tab-trips" class="btn btn-secondary" type="button" role="tab" aria-controls="mobility-workspace" aria-selected="true">Proposed trips</button>' +
      '<button id="mobility-tab-group" class="btn btn-secondary" type="button" role="tab" aria-controls="mobility-workspace" aria-selected="false">Group requests</button>' +
      '<button id="mobility-tab-cargo" class="btn btn-secondary" type="button" role="tab" aria-controls="mobility-workspace" aria-selected="false">Cargo requests</button></div>' +
      '<button id="mobility-refresh" class="btn btn-secondary" type="button">Refresh</button></div>' +
      '<p id="mobility-message" class="form-message" role="status" hidden></p>' +
      '<div class="mobility-filters"><div class="field"><label for="mobility-search">Find a route, driver or customer</label><input id="mobility-search" type="search" placeholder="Search these records" maxlength="120"></div>' +
      '<div class="field"><label for="mobility-status">Status</label><select id="mobility-status"><option value="">All statuses</option></select></div></div>' +
      '<div id="mobility-workspace" class="mobility-workspace" role="tabpanel" aria-labelledby="mobility-tab-trips">' +
      '<section class="card section-card mobility-list-section"><div class="section-title"><div><h3 id="mobility-list-title">Proposed group trips</h3><p id="mobility-list-count">Loading…</p></div></div>' +
      '<div id="mobility-list" class="mobility-list" aria-busy="true"></div></section>' +
      '<section id="mobility-detail" class="card section-card mobility-detail" aria-label="Selected record"></section></div></div>';
  }

  function mount({ container, api, token, isActive = () => true, fetchPhoto = (...args) => fetch(...args), objectUrls = URL }) {
    const doc = container.ownerDocument || document;
    const find = id => container.querySelector('#mobility-' + id);
    const listeners = [], requests = new Set();
    let mode = 'trips', rows = [], options = { drivers: [], vehicles: [] }, selectedId = null;
    let disposed = false, loading = false, saving = false, revision = 0, photoRevision = 0, photoObjectUrl = null, photoRequest = null;
    const active = () => !disposed && isActive();
    const selected = () => rows.find(row => String(row.id) === String(selectedId));
    function node(tag, text, className) {
      const element = doc.createElement(tag);
      if (text !== undefined) element.textContent = String(text);
      if (className) element.className = className;
      return element;
    }
    function button(text, action, className = 'btn btn-secondary') {
      const element = node('button', text, className);
      element.type = 'button'; element.dataset.mobilityAction = action;
      return element;
    }
    function listen(element, event, handler) {
      element.addEventListener(event, handler);
      listeners.push(() => element.removeEventListener(event, handler));
    }
    function message(text = '', error = false) {
      if (!active()) return;
      find('message').textContent = text;
      find('message').className = 'form-message' + (error ? ' error' : '');
      find('message').hidden = !text;
    }
    function clearPhoto() {
      photoRevision++;
      photoRequest?.abort(); photoRequest = null;
      if (photoObjectUrl) objectUrls.revokeObjectURL(photoObjectUrl);
      photoObjectUrl = null;
    }
    async function call(path, settings = {}) {
      const controller = new AbortController(); requests.add(controller);
      try { return await api(path, { ...settings, signal: controller.signal }); }
      finally { requests.delete(controller); }
    }
    function setBusy() {
      find('refresh').disabled = loading || saving;
      for (const name of ['trips', 'group', 'cargo']) find('tab-' + name).disabled = saving;
      find('list').setAttribute('aria-busy', String(loading));
      for (const element of find('detail').querySelectorAll('input,select,textarea,button')) element.disabled = saving || loading || element.dataset.unavailable === 'true';
    }
    function visibleRows() {
      const query = find('search').value.trim().toLowerCase(), status = find('status').value;
      return rows.filter(row => (!status || row.status === status) && (!query || [row.origin, row.destination, row.driverName,
        row.vehiclePlate, row.customerName, row.customerPhone].some(value => String(value || '').toLowerCase().includes(query))));
    }
    function renderList() {
      if (!active()) return;
      const list = find('list'); list.replaceChildren();
      const visible = visibleRows();
      find('list-count').textContent = visible.length + ' of ' + rows.length + ' records · Ethiopia time';
      if (!visible.length) list.append(node('p', rows.length ? 'No records match these filters.' : mode === 'trips'
        ? 'No group trips have been proposed yet.' : mode === 'cargo' ? 'No cargo requests have been submitted yet.' : 'No group route requests yet.', 'mobility-empty'));
      for (const row of visible) {
        const item = button('', 'select', 'mobility-record');
        item.dataset.id = String(row.id);
        item.setAttribute('aria-pressed', String(String(row.id) === String(selectedId)));
        item.append(node('strong', row.origin + ' → ' + row.destination));
        const status = node('span', titleCase(row.status), 'pill ' + String(row.status).toLowerCase());
        item.append(status);
        item.append(node('span', mode === 'trips' ? (row.driverName || 'Driver') + ' · ' + (row.vehiclePlate || 'Vehicle unavailable')
          : (row.customerName || 'Customer') + ' · ' + (row.people ? row.people + ' people · ' : '') + (row.vehicleType || 'Vehicle type unavailable'), 'mobility-record-sub'));
        item.append(node('span', dateText(mode === 'trips' ? row.departureAt : row.travelDate), 'mobility-record-sub'));
        item.append(node('span', mode === 'trips' ? money(row.fareEtb) + ' / seat · ' + row.seats + ' seats' : money(row.quoteEtb), 'mobility-record-price'));
        list.append(item);
      }
    }
    function detailLine(list, label, value) {
      const group = node('div'); group.append(node('dt', label), node('dd', value === null || value === undefined || value === '' ? 'Not specified' : value)); list.append(group);
    }
    function field(form, label, tag, id, settings = {}) {
      const wrap = node('div', undefined, 'field');
      const input = node(tag); input.id = id;
      const caption = node('label', label); caption.htmlFor = id;
      Object.assign(input, settings); wrap.append(caption, input); form.append(wrap);
      return input;
    }
    function noteField(form, value = '') {
      return field(form, 'Review note', 'textarea', 'mobility-note', { value, maxLength: 2000, rows: 3, placeholder: 'Add a message for the driver or customer' });
    }
    function addOptions(select, values, label, placeholder) {
      select.replaceChildren();
      const empty = node('option', placeholder); empty.value = ''; select.append(empty);
      for (const value of values) { const option = node('option', label(value)); option.value = String(value.id); select.append(option); }
    }
    function renderDetail(focus = false) {
      if (!active()) return;
      clearPhoto();
      const detail = find('detail'); detail.replaceChildren();
      const row = selected();
      if (!row) { detail.append(node('p', 'Select a record to review its details.', 'mobility-empty')); return; }
      const heading = node('h3', row.origin + ' → ' + row.destination); heading.tabIndex = -1; detail.append(heading);
      detail.append(node('span', titleCase(row.status), 'pill ' + String(row.status).toLowerCase()));
      const data = node('dl', undefined, 'mobility-facts');
      detailLine(data, mode === 'trips' ? 'Departure' : 'Requested travel', dateText(mode === 'trips' ? row.departureAt : row.travelDate));
      if (mode === 'trips') {
        detailLine(data, 'Driver', row.driverName); detailLine(data, 'Vehicle', [row.vehicleType, row.vehicleModel, row.vehiclePlate].filter(Boolean).join(' · '));
        detailLine(data, 'Seats', row.seats); detailLine(data, 'Available seats', row.availableSeats);
        detailLine(data, 'Proposed fare per seat', money(row.fareEtb));
      } else {
        detailLine(data, 'Customer', row.customerName); detailLine(data, 'Phone', row.customerPhone);
        if (mode === 'group') detailLine(data, 'Travelers', row.people);
        detailLine(data, 'Requested vehicle', row.vehicleType); detailLine(data, 'Details', row.details);
        detailLine(data, 'Administrative quote', money(row.quoteEtb));
        detailLine(data, 'Assigned driver', options.drivers.find(driver => String(driver.id) === String(row.driverId))?.fullName || row.driverName || (row.driverId ? 'Previously assigned driver' : null));
        const vehicle = options.vehicles.find(item => String(item.id) === String(row.vehicleId));
        detailLine(data, 'Assigned vehicle', vehicle?.plateNumber || row.vehiclePlate || (row.vehicleId ? 'Previously assigned vehicle' : null));
        if (row.originLatitude !== null && row.originLatitude !== undefined && row.originLongitude !== null && row.originLongitude !== undefined)
          detailLine(data, 'Departure coordinates', row.originLatitude + ', ' + row.originLongitude);
        if (row.destinationLatitude !== null && row.destinationLatitude !== undefined && row.destinationLongitude !== null && row.destinationLongitude !== undefined)
          detailLine(data, 'Destination coordinates', row.destinationLatitude + ', ' + row.destinationLongitude);
      }
      if (row.reviewNote) detailLine(data, 'Latest review note', row.reviewNote);
      detail.append(data);
      if (mode === 'cargo' && row.photoUrl) {
        const photo = button('View cargo photo', 'photo'); photo.dataset.photoPath = photoPath(row.photoUrl) || '';
        if (!photo.dataset.photoPath) { photo.disabled = true; photo.dataset.unavailable = 'true'; photo.textContent = 'Cargo photo unavailable'; }
        detail.append(photo); const media = node('div'); media.id = 'mobility-photo'; media.setAttribute('aria-live', 'polite'); detail.append(media);
      }
      if (mode === 'trips' && row.status === 'PENDING_REVIEW') {
        const form = node('form', undefined, 'mobility-form'); form.dataset.form = 'review'; noteField(form);
        const actions = node('div', undefined, 'form-actions');
        actions.append(button('Approve trip', 'APPROVE', 'btn btn-primary'), button('Reject trip', 'REJECT', 'btn btn-danger'));
        form.append(node('p', 'Approval publishes this trip for passengers to book. The proposed fare is per passenger seat.', 'mobility-help'), actions); detail.append(form);
      } else if (mode !== 'trips' && assignable(row)) {
        const form = node('form', undefined, 'mobility-form'); form.dataset.form = 'assign';
        const driver = field(form, 'Driver', 'select', 'mobility-driver', { required: true });
        addOptions(driver, options.drivers, value => value.fullName + ' · ' + value.phone, 'Choose an approved driver');
        driver.value = String(row.driverId || '');
        const vehicleSelect = field(form, 'Vehicle', 'select', 'mobility-vehicle', { required: true });
        function updateVehicles() {
          addOptions(vehicleSelect, vehicleChoices(options, driver.value), value => [value.plateNumber, value.model, value.vehicleType,
            mode === 'group' ? value.capacityLimit + ' seats' : ''].filter(Boolean).join(' · '), 'Choose this driver’s vehicle');
        }
        updateVehicles(); vehicleSelect.value = String(row.vehicleId || '');
        driver.addEventListener('change', updateVehicles);
        field(form, 'Quote in ETB (optional)', 'input', 'mobility-quote', { type: 'number', min: '0.01', max: '10000000', step: '0.01', value: row.quoteEtb ?? '', inputMode: 'decimal' });
        noteField(form);
        form.append(node('p', 'Only approved drivers and their eligible vehicles are listed. A quote records the proposed price; it does not charge the customer or mark it accepted.', 'mobility-help'));
        if (!options.drivers.length) form.append(node('p', 'No eligible approved drivers are available for this service yet.', 'mobility-warning'));
        const actions = node('div', undefined, 'form-actions');
        actions.append(button(row.status === 'ASSIGNED' ? 'Update assignment' : 'Assign driver & vehicle', 'assign', 'btn btn-primary'));
        if (row.status === 'ASSIGNED') actions.append(button('Mark completed', 'COMPLETED'));
        actions.append(button('Reject request', 'REJECTED', 'btn btn-danger')); form.append(actions); detail.append(form);
      } else {
        detail.append(node('p', terminal(row) ? 'This record is closed.' : 'This trip has already been reviewed. Existing bookings and the approved fare are retained.', 'mobility-help'));
      }
      setBusy();
      if (focus) heading.focus({ preventScroll: true });
    }
    async function load() {
      if (!active() || loading || saving) return;
      const loadRevision = ++revision, loadMode = mode;
      loading = true; message(); setBusy();
      try {
        const results = await Promise.all(loadMode === 'trips' ? [call(ROOT + '/trips')] : [
          call(ROOT + '/requests?kind=' + (loadMode === 'group' ? 'GROUP' : 'CARGO')),
          call(ROOT + '/options?kind=' + (loadMode === 'group' ? 'GROUP' : 'CARGO'))
        ]);
        if (!active() || revision !== loadRevision) return;
        const key = loadMode === 'trips' ? 'trips' : 'requests';
        if (!results[0].success || !Array.isArray(results[0][key])) throw new Error('The server did not return this list.');
        if (loadMode !== 'trips' && (!results[1].success || !Array.isArray(results[1].drivers) || !Array.isArray(results[1].vehicles))) throw new Error('Driver and vehicle options could not be loaded.');
        rows = results[0][key]; options = results[1] || { drivers: [], vehicles: [] };
        if (!rows.some(row => String(row.id) === String(selectedId))) selectedId = null;
        renderList(); renderDetail();
      } catch (error) {
        if (active() && revision === loadRevision) {
          message('Could not refresh. ' + (error.message || 'Try again.'), true);
          if (!rows.length) { find('list').replaceChildren(node('p', 'Records are unavailable. Use Refresh to try again.', 'mobility-empty')); find('list-count').textContent = 'Unavailable'; }
        }
      } finally { if (active() && revision === loadRevision) { loading = false; setBusy(); } }
    }
    function changeMode(next) {
      if (saving || next === mode) return;
      revision++; for (const controller of requests) controller.abort();
      mode = next; rows = []; selectedId = null; options = { drivers: [], vehicles: [] }; loading = false;
      find('search').value = ''; find('status').replaceChildren(node('option', 'All statuses')); find('status').firstChild.value = '';
      const statuses = next === 'trips' ? ['PENDING_REVIEW', 'APPROVED', 'REJECTED', 'BOARDING', 'COMPLETED'] : ['REQUESTED', 'ASSIGNED', 'REJECTED', 'COMPLETED'];
      for (const status of statuses) { const item = node('option', titleCase(status)); item.value = status; find('status').append(item); }
      for (const name of ['trips', 'group', 'cargo']) {
        find('tab-' + name).setAttribute('aria-selected', String(name === mode));
        find('tab-' + name).tabIndex = name === mode ? 0 : -1;
      }
      find('workspace').setAttribute('aria-labelledby', 'mobility-tab-' + next);
      find('list-title').textContent = next === 'trips' ? 'Proposed group trips' : next === 'group' ? 'Private group route requests' : 'Cargo transport requests';
      renderList(); renderDetail(); void load();
    }
    async function save(action) {
      if (!active() || saving || loading) return;
      const row = selected(); if (!row) return;
      const note = container.querySelector('#mobility-note')?.value.trim() || '';
      let path, body;
      if (mode === 'trips') {
        if (row.status !== 'PENDING_REVIEW' || !['APPROVE', 'REJECT'].includes(action)) return;
        path = '/trips/' + encodeURIComponent(row.id) + '/review'; body = { decision: action, note };
      } else {
        if (!assignable(row)) return;
        if (action === 'assign') {
          const driverId = container.querySelector('#mobility-driver').value, vehicleId = container.querySelector('#mobility-vehicle').value;
          const quote = container.querySelector('#mobility-quote').value.trim();
          if (!options.drivers.some(driver => String(driver.id) === driverId) || !vehicleChoices(options, driverId).some(vehicle => String(vehicle.id) === vehicleId))
            return message('Choose an approved driver and a vehicle assigned to that driver.', true);
          if (quote && (!Number.isFinite(Number(quote)) || Number(quote) <= 0 || Number(quote) > 10000000)) return message('Enter a quote greater than zero and no more than ETB 10,000,000.', true);
          path = '/requests/' + encodeURIComponent(row.id) + '/assign'; body = { driverId, vehicleId, note }; if (quote) body.quoteEtb = Number(quote);
        } else if (action === 'REJECTED' || (action === 'COMPLETED' && row.status === 'ASSIGNED')) {
          path = '/requests/' + encodeURIComponent(row.id) + '/status'; body = { status: action, note };
        } else return;
      }
      saving = true; setBusy(); message('Saving…');
      try {
        const result = await call(ROOT + path, { method: 'POST', body: JSON.stringify(body) });
        if (!active()) return;
        const updated = mode === 'trips' ? result.trip : result.request;
        if (!result.success || !updated || String(updated.id) !== String(row.id)) throw new Error('The server did not confirm this update. Refresh to check the current status.');
        rows = rows.map(item => String(item.id) === String(row.id) ? updated : item);
        renderList(); renderDetail(); message(action === 'APPROVE' ? 'Trip approved and published for passengers.' : 'Record updated.');
      } catch (error) { if (active()) message(error.message || 'Could not save. Try again.', true); }
      finally { if (active()) { saving = false; setBusy(); } }
    }
    async function showPhoto(buttonElement) {
      if (!active() || buttonElement.disabled) return;
      const path = photoPath(buttonElement.dataset.photoPath); if (!path) return;
      const media = container.querySelector('#mobility-photo'); if (!media) return;
      const currentPhoto = ++photoRevision, controller = new AbortController(); requests.add(controller); photoRequest = controller;
      buttonElement.disabled = true; media.textContent = 'Loading cargo photo…';
      try {
        const response = await fetchPhoto(path, { headers: { Authorization: 'Bearer ' + token }, credentials: 'same-origin', cache: 'no-store', signal: controller.signal });
        if (!response.ok || !/^image\/(jpeg|png|webp)(;|$)/i.test(response.headers.get('content-type') || '')) throw new Error('Cargo photo is unavailable.');
        const blob = await response.blob(); if (blob.size > 5 * 1024 * 1024) throw new Error('Cargo photo exceeds the supported size.');
        if (!active() || photoRevision !== currentPhoto) return;
        if (photoObjectUrl) objectUrls.revokeObjectURL(photoObjectUrl);
        photoObjectUrl = objectUrls.createObjectURL(blob);
        const image = node('img'); image.src = photoObjectUrl; image.alt = 'Cargo photo supplied with this request'; image.className = 'mobility-cargo-photo';
        media.replaceChildren(image); buttonElement.textContent = 'Reload cargo photo';
      } catch (error) { if (active() && photoRevision === currentPhoto) media.textContent = error.message || 'Could not load cargo photo.'; }
      finally { requests.delete(controller); if (photoRequest === controller) photoRequest = null; if (active() && photoRevision === currentPhoto) buttonElement.disabled = false; }
    }
    const tabs = ['trips', 'group', 'cargo'];
    for (const next of tabs) {
      listen(find('tab-' + next), 'click', () => changeMode(next));
      listen(find('tab-' + next), 'keydown', event => {
        if (saving || !['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return;
        event.preventDefault();
        const index = tabs.indexOf(next), target = event.key === 'Home' ? tabs[0] : event.key === 'End' ? tabs[tabs.length - 1]
          : tabs[(index + (event.key === 'ArrowRight' ? 1 : tabs.length - 1)) % tabs.length];
        changeMode(target); find('tab-' + target).focus();
      });
    }
    listen(find('refresh'), 'click', load);
    listen(find('search'), 'input', renderList); listen(find('status'), 'change', renderList);
    listen(find('list'), 'click', event => {
      const item = event.target.closest('[data-mobility-action="select"]');
      if (!item || saving) return;
      selectedId = item.dataset.id; message(); renderList(); renderDetail(true);
    });
    listen(find('detail'), 'submit', event => event.preventDefault());
    listen(find('detail'), 'click', event => {
      const item = event.target.closest('[data-mobility-action]'); if (!item) return;
      if (item.dataset.mobilityAction === 'photo') return showPhoto(item);
      return save(item.dataset.mobilityAction);
    });
    // Install the initial tab through the same path used when switching service.
    mode = ''; changeMode('trips');
    return () => { disposed = true; revision++; clearPhoto(); for (const request of requests) request.abort(); for (const release of listeners) release(); };
  }
  return { markup, mount, money, photoPath, vehicleChoices };
});
