(function (root, factory) {
  const editor = factory();
  if (typeof module === 'object' && module.exports) module.exports = editor;
  if (root) root.PassoRouteEditor = editor;
})(typeof window === 'undefined' ? null : window, function () {
  'use strict';

  function validLocation(value) {
    return Boolean(value && String(value.name || '').trim() &&
      value.latitude !== null && value.latitude !== undefined && String(value.latitude).trim() !== '' &&
      value.longitude !== null && value.longitude !== undefined && String(value.longitude).trim() !== '' &&
      Number.isFinite(Number(value.latitude)) && Math.abs(Number(value.latitude)) <= 90 &&
      Number.isFinite(Number(value.longitude)) && Math.abs(Number(value.longitude)) <= 180 &&
      (Number(value.latitude) !== 0 || Number(value.longitude) !== 0));
  }

  function stopPayload(original, location, nameAm, stopName) {
    if (!validLocation(location)) throw Error('Choose every route location from the search results or map before saving.');
    const name = String(stopName ?? location.name).trim();
    const translated = String(nameAm || '').trim() || name;
    if (!name || name.length > 100 || translated.length > 100) throw Error('Enter stop names of 1 to 100 characters. The full selected address stays in the location field.');
    const result = { stopName: name, stopNameAm: translated,
      latitude: Number(location.latitude), longitude: Number(location.longitude) };
    // A search result is a place, not the identity of a stop on this route.
    for (const key of ['id', 'scheduledMorningTime', 'scheduledEveningTime', 'maxCapacity']) {
      if (original[key] !== undefined && original[key] !== null) result[key] = original[key];
    }
    return result;
  }

  function mount({ container, api, document: suppliedDocument }) {
    const doc = suppliedDocument || container.ownerDocument;
    const picker = doc.defaultView.PassoLocationPicker;
    if (!picker) throw Error('The location selector is unavailable. Refresh this page and try again.');
    let entries = [], disposed = false;
    container.innerHTML = '<div class="route-stop-list"></div><button class="btn btn-secondary" type="button" data-add-route-stop>Add intermediate stop</button>';
    const list = container.querySelector('.route-stop-list');
    const addButton = container.querySelector('[data-add-route-stop]');

    function updateOrder() {
      entries.forEach((entry, index) => {
        const label = index === 0 ? 'Departure' : index === entries.length - 1 ? 'Destination' : 'Stop ' + index;
        entry.legend.textContent = label;
        entry.up.disabled = index === 0;
        entry.down.disabled = index === entries.length - 1;
        entry.remove.disabled = entries.length <= 2;
        entry.up.setAttribute('aria-label', 'Move ' + label.toLowerCase() + ' earlier');
        entry.down.setAttribute('aria-label', 'Move ' + label.toLowerCase() + ' later');
        entry.remove.setAttribute('aria-label', 'Remove ' + label.toLowerCase());
        list.append(entry.element);
      });
    }

    function createEntry(original = {}) {
      const element = doc.createElement('fieldset');
      element.className = 'route-stop-card';
      element.innerHTML = '<legend></legend><div data-stop-location></div>' +
        '<label class="route-stop-name">Stop name (English)<input type="text" maxlength="100" required aria-label="Stop name in English"></label>' +
        '<label class="route-stop-am">Amharic name (optional)<input type="text" maxlength="100" aria-label="Stop name in Amharic"></label>' +
        '<div class="route-stop-actions"><button class="btn btn-secondary" type="button" data-stop-up>Move up</button>' +
        '<button class="btn btn-secondary" type="button" data-stop-down>Move down</button>' +
        '<button class="btn btn-secondary" type="button" data-stop-remove>Remove stop</button></div>';
      const entry = { original: { ...original }, element, legend: element.querySelector('legend'),
        up: element.querySelector('[data-stop-up]'), down: element.querySelector('[data-stop-down]'),
        remove: element.querySelector('[data-stop-remove]'), name: element.querySelector('.route-stop-name input'),
        amharic: element.querySelector('.route-stop-am input'), nameEdited: false };
      entry.name.value = original.stopName || original.stop_name || '';
      entry.name.addEventListener('input', () => { entry.nameEdited = true; });
      entry.amharic.value = original.stopNameAm || original.stop_name_am || '';
      entry.picker = picker.mount(element.querySelector('[data-stop-location]'), {
        api, label: 'Location', initial: { name: original.stopName || original.stop_name || '',
          latitude: original.latitude, longitude: original.longitude },
        onChange(location) {
          if (location && !entry.nameEdited) entry.name.value = location.name.length > 100 ? location.name.split(',')[0].trim() : location.name;
        }
      });
      function move(offset) {
        const index = entries.indexOf(entry), next = index + offset;
        if (next < 0 || next >= entries.length) return;
        [entries[index], entries[next]] = [entries[next], entries[index]];
        updateOrder();
      }
      entry.up.addEventListener('click', () => move(-1));
      entry.down.addEventListener('click', () => move(1));
      entry.remove.addEventListener('click', () => {
        if (entries.length <= 2) return;
        entries.splice(entries.indexOf(entry), 1);
        entry.picker.destroy();
        element.remove();
        updateOrder();
      });
      return entry;
    }

    function setStops(stops = []) {
      if (disposed) return;
      entries.forEach(entry => { entry.picker.destroy(); entry.element.remove(); });
      entries = (Array.isArray(stops) ? stops : []).map(createEntry);
      while (entries.length < 2) entries.push(createEntry());
      updateOrder();
    }
    function addStop() {
      const entry = createEntry();
      entries.splice(entries.length - 1, 0, entry);
      updateOrder();
      entry.element.querySelector('input')?.focus();
    }
    addButton.addEventListener('click', addStop);
    setStops();
    return {
      getStops() {
        if (disposed) throw Error('Refresh the route form before saving.');
        return entries.map(entry => stopPayload(entry.original, entry.picker.getValue(), entry.amharic.value, entry.name.value));
      },
      setStops,
      reset() { setStops(); },
      destroy() {
        disposed = true;
        entries.forEach(entry => entry.picker.destroy());
        entries = [];
        addButton.removeEventListener('click', addStop);
        container.replaceChildren();
      }
    };
  }

  function selectDepartureArea({ api, settings = {}, routeName, signal, document: suppliedDocument }) {
    const doc = suppliedDocument || document;
    if (signal?.aborted) return Promise.resolve(null);
    const picker = doc.defaultView.PassoLocationPicker;
    if (!picker) return Promise.reject(Error('The location selector is unavailable. Refresh and try again.'));
    const dialog = doc.createElement('dialog');
    dialog.className = 'route-area-dialog';
    dialog.innerHTML = '<form class="form-stack"><h2>Departure area</h2><p data-route-area-name></p>' +
      '<div data-departure-picker></div><label>Allowed radius (metres)<input name="radius" type="number" min="25" max="5000" step="1" required></label>' +
      '<p class="muted">The radius controls how close drivers must be to this departure point.</p>' +
      '<p class="form-error" data-area-error role="alert" hidden></p><div class="form-actions">' +
      '<button class="btn btn-primary" type="submit">Save departure area</button><button class="btn btn-secondary" type="button" data-area-cancel>Cancel</button></div></form>';
    dialog.querySelector('[data-route-area-name]').textContent = routeName || '';
    const form = dialog.querySelector('form');
    const radius = dialog.querySelector('[name="radius"]');
    radius.value = settings.radius_m || settings.radiusMeters || 300;
    doc.body.append(dialog);
    const field = picker.mount(dialog.querySelector('[data-departure-picker]'), { api, label: 'Departure location',
      initial: { name: settings.stopName || 'Current departure area', latitude: settings.latitude, longitude: settings.longitude } });
    return new Promise(resolve => {
      let finished = false;
      function finish(result) {
        if (finished) return;
        finished = true;
        signal?.removeEventListener('abort', cancel);
        field.destroy();
        if (dialog.open) dialog.close();
        dialog.remove();
        resolve(result);
      }
      function cancel() { finish(null); }
      dialog.addEventListener('cancel', event => { event.preventDefault(); cancel(); });
      dialog.addEventListener('close', cancel);
      dialog.querySelector('[data-area-cancel]').addEventListener('click', cancel);
      signal?.addEventListener('abort', cancel, { once: true });
      form.addEventListener('submit', event => {
        event.preventDefault();
        const selected = field.getValue(), metres = Number(radius.value);
        const error = dialog.querySelector('[data-area-error]');
        if (!validLocation(selected) || !Number.isInteger(metres) || metres < 25 || metres > 5000) {
          error.textContent = 'Choose a departure location and a radius from 25 to 5000 metres.';
          error.hidden = false;
          return;
        }
        finish({ latitude: Number(selected.latitude), longitude: Number(selected.longitude), radiusMeters: metres });
      });
      dialog.showModal();
    });
  }

  return { mount, selectDepartureArea, stopPayload, validLocation };
});
