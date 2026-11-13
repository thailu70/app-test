(function (root, factory) {
  const picker = factory(root);
  if (typeof module === 'object' && module.exports) module.exports = picker;
  if (root) root.PassoLocationPicker = picker;
})(typeof window === 'undefined' ? null : window, function (root) {
  'use strict';
  let nextId = 0;

  function validLocation(value) {
    if (!value || value.latitude == null || value.longitude == null ||
        String(value.latitude).trim() === '' || String(value.longitude).trim() === '') return null;
    const name = String(value.name || '').trim();
    const latitude = Number(value.latitude), longitude = Number(value.longitude);
    if (!name || !Number.isFinite(latitude) || !Number.isFinite(longitude) ||
        Math.abs(latitude) > 90 || Math.abs(longitude) > 180 || (latitude === 0 && longitude === 0)) return null;
    return { name, latitude, longitude };
  }

  function createSearch({ api, onResults, onStatus, delay = 250 }) {
    let timer = null, controller = null, revision = 0, destroyed = false;
    function cancel() {
      revision++;
      clearTimeout(timer);
      timer = null;
      controller?.abort();
      controller = null;
    }
    function update(text) {
      cancel();
      if (destroyed) return;
      const query = String(text || '').trim().replace(/\s+/g, ' ').slice(0, 120);
      onResults([], {});
      if (query.length < 2) {
        onStatus('Enter at least two letters, or choose a point on the map.');
        return;
      }
      const current = revision;
      onStatus('Searching locations…');
      timer = setTimeout(async () => {
        timer = null;
        controller = typeof AbortController === 'function' ? new AbortController() : null;
        try {
          const result = await api('/api/mobility/locations?q=' + encodeURIComponent(query), { signal: controller?.signal });
          if (destroyed || current !== revision) return;
          const places = (Array.isArray(result?.places) ? result.places : []).map(validLocation).filter(Boolean);
          onResults(places, result || {});
          if (result?.searchAvailable === false) onStatus(result.message || 'Showing saved locations. You can also choose a point on the map.');
          else onStatus(places.length ? 'Choose a location from the results.' : 'No locations found. Choose a point on the map.');
        } catch (error) {
          if (destroyed || current !== revision || error?.name === 'AbortError') return;
          onResults([], {});
          onStatus('Locations could not load. Try again or choose a point on the map.');
        }
      }, delay);
    }
    return { update, cancel, destroy() { destroyed = true; cancel(); } };
  }

  function mount(host, { api, label = 'Location', initial = null, onChange = () => {} } = {}) {
    if (!host?.ownerDocument || typeof api !== 'function') throw Error('A location field and API are required.');
    const doc = host.ownerDocument, view = doc.defaultView || root;
    const prefix = 'passo-location-' + (++nextId);
    let selected = validLocation(initial), places = [], active = -1, focused = false, destroyed = false;
    let dialog = null, map = null, marker = null, mapPoint = null, mapName = null, usePoint = null;
    const listeners = [];
    function element(tag, className, text) {
      const node = doc.createElement(tag);
      if (className) node.className = className;
      if (text !== undefined) node.textContent = text;
      return node;
    }
    function listen(node, event, handler) {
      node.addEventListener(event, handler);
      listeners.push(() => node.removeEventListener(event, handler));
    }
    const field = element('div', 'passo-location-field');
    const title = element('label', 'passo-location-label', label);
    const controls = element('div', 'passo-location-controls');
    const inputWrap = element('div', 'passo-location-search');
    const input = element('input', 'passo-location-input');
    input.id = prefix + '-input';
    title.htmlFor = input.id;
    input.type = 'search'; input.maxLength = 120; input.autocomplete = 'off';
    input.placeholder = 'Search saved locations or places';
    input.setAttribute('role', 'combobox');
    input.setAttribute('aria-autocomplete', 'list');
    input.setAttribute('aria-expanded', 'false');
    input.setAttribute('aria-controls', prefix + '-results');
    input.setAttribute('aria-describedby', prefix + '-status');
    const results = element('div', 'passo-location-results');
    results.id = prefix + '-results'; results.hidden = true;
    results.setAttribute('role', 'listbox');
    results.setAttribute('aria-label', label + ' suggestions');
    const mapButton = element('button', 'passo-location-map-button', 'Choose on map');
    mapButton.type = 'button';
    mapButton.setAttribute('aria-label', 'Choose ' + label.toLocaleLowerCase() + ' on map');
    const status = element('p', 'passo-location-status');
    status.id = prefix + '-status'; status.setAttribute('role', 'status');
    inputWrap.append(input, results); controls.append(inputWrap, mapButton);
    field.append(title, controls, status); host.replaceChildren(field);

    function closeResults() {
      results.hidden = true;
      active = -1;
      input.setAttribute('aria-expanded', 'false');
      input.removeAttribute('aria-activedescendant');
    }
    function showActive(index) {
      if (!places.length) return;
      active = (index + places.length) % places.length;
      for (const [position, option] of Array.from(results.children).entries()) {
        option.setAttribute('aria-selected', String(position === active));
        option.classList.toggle('active', position === active);
      }
      input.setAttribute('aria-activedescendant', prefix + '-option-' + active);
    }
    function commit(value, notify = true) {
      search.cancel(); closeResults();
      selected = validLocation(value);
      input.value = selected?.name || '';
      status.textContent = selected ? 'Location selected.' : 'Choose a search result or a point on the map.';
      if (notify) onChange(selected ? { ...selected } : null);
    }
    function renderResults(items) {
      places = items;
      results.replaceChildren();
      for (const [index, place] of places.entries()) {
        const option = element('button', 'passo-location-option', place.name);
        option.id = prefix + '-option-' + index; option.type = 'button'; option.tabIndex = -1;
        option.setAttribute('role', 'option'); option.setAttribute('aria-selected', 'false');
        option.addEventListener('click', () => { commit(place); input.focus(); });
        results.append(option);
      }
      if (focused && places.length) {
        results.hidden = false;
        input.setAttribute('aria-expanded', 'true');
        showActive(0);
      } else closeResults();
    }
    const search = createSearch({ api,
      onResults(items) { if (!destroyed) renderResults(items); },
      onStatus(text) { if (!destroyed) status.textContent = text; }
    });
    commit(initial, false);

    function closeMap() {
      if (!dialog) return;
      const previous = dialog;
      dialog = null;
      map?.remove(); map = null; marker = null;
      if (previous.open && typeof previous.close === 'function') previous.close();
      previous.remove(); mapPoint = null; mapName = null; usePoint = null;
      if (!destroyed) mapButton.focus();
    }
    function mapSelection(point) {
      const latitude = Number(point?.lat), longitude = Number(point?.lng);
      mapPoint = validLocation({ name: mapName.value.trim() || 'Selected location', latitude, longitude });
      usePoint.disabled = true;
      if (!mapPoint) return;
      if (marker) marker.setLatLng([latitude, longitude]);
      else {
        marker = view.L.marker([latitude, longitude], { draggable: true,
          icon: view.L.divIcon({ className: 'passo-location-pin', html: '<span></span>', iconSize: [28, 28], iconAnchor: [14, 14] }) }).addTo(map);
        marker.on('dragend', () => { if (marker) mapSelection(marker.getLatLng()); });
      }
      usePoint.disabled = !mapName.value.trim();
    }
    function openMap() {
      if (destroyed || dialog) return;
      search.cancel(); closeResults();
      dialog = element('dialog', 'passo-location-dialog');
      dialog.setAttribute('aria-modal', 'true'); dialog.setAttribute('aria-labelledby', prefix + '-map-title');
      const header = element('div', 'passo-location-dialog-header');
      const heading = element('h3', '', 'Choose ' + label.toLocaleLowerCase()); heading.id = prefix + '-map-title';
      const close = element('button', 'passo-location-close', 'Close'); close.type = 'button';
      close.setAttribute('aria-label', 'Close location map'); header.append(heading, close);
      const nameLabel = element('label', 'passo-location-label', 'Location name');
      mapName = element('input', 'passo-location-input'); mapName.id = prefix + '-map-name';
      mapName.type = 'text'; mapName.maxLength = 200; mapName.value = selected?.name || input.value.trim();
      nameLabel.htmlFor = mapName.id;
      const mapHost = element('div', 'passo-location-map'); mapHost.setAttribute('aria-label', 'Select ' + label.toLocaleLowerCase() + ' on map');
      const mapStatus = element('p', 'passo-location-map-status', 'Click the map to place a pin, or drag the selected pin.');
      mapStatus.setAttribute('role', 'status');
      const actions = element('div', 'passo-location-dialog-actions');
      const cancel = element('button', 'passo-location-cancel', 'Cancel'); cancel.type = 'button';
      usePoint = element('button', 'passo-location-confirm', 'Use this location'); usePoint.type = 'button'; usePoint.disabled = true;
      actions.append(cancel, usePoint); dialog.append(header, nameLabel, mapName, mapHost, mapStatus, actions);
      doc.body.append(dialog);
      close.addEventListener('click', closeMap); cancel.addEventListener('click', closeMap);
      dialog.addEventListener('cancel', event => { event.preventDefault(); closeMap(); });
      dialog.addEventListener('close', closeMap);
      mapName.addEventListener('input', () => { usePoint.disabled = !mapPoint || !mapName.value.trim(); });
      usePoint.addEventListener('click', () => {
        const value = validLocation({ ...mapPoint, name: mapName.value });
        if (!value) return;
        commit(value); closeMap();
      });
      if (typeof dialog.showModal === 'function') dialog.showModal();
      else dialog.setAttribute('open', '');
      if (!view?.L) {
        mapStatus.textContent = 'The map could not load. Close this window and choose a search result.';
        mapHost.hidden = true;
        return;
      }
      try {
        map = view.L.map(mapHost, { scrollWheelZoom: true });
        map.setView(selected ? [selected.latitude, selected.longitude] : [9.0305, 38.7400], selected ? 15 : 12);
        const tiles = view.L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
          maxZoom: 19, referrerPolicy: 'origin', attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
        }).addTo(map);
        tiles.on('tileerror', () => { if (dialog) mapStatus.textContent = 'Map tiles could not load. Try a location search if you cannot identify the point.'; });
        map.on('click', event => mapSelection(event.latlng));
        if (selected) mapSelection({ lat: selected.latitude, lng: selected.longitude });
        map.invalidateSize();
      } catch (_) {
        map?.remove(); map = null; marker = null; mapPoint = null;
        usePoint.disabled = true;
        mapStatus.textContent = 'The map could not load. Close this window and choose a search result.';
      }
    }
    listen(input, 'input', () => {
      selected = null;
      onChange(null);
      search.update(input.value);
    });
    listen(input, 'focus', () => {
      focused = true;
      if (!selected && input.value.trim().length >= 2) search.update(input.value);
    });
    listen(input, 'blur', () => {
      focused = false; search.cancel(); closeResults();
      if (!selected) status.textContent = 'Choose a search result or a point on the map.';
    });
    listen(input, 'keydown', event => {
      if (event.key === 'Escape') {
        event.preventDefault(); search.cancel(); closeResults();
        if (!selected) status.textContent = 'Choose a search result or a point on the map.';
      }
      if (event.key === 'ArrowDown' && places.length) { event.preventDefault(); results.hidden = false; input.setAttribute('aria-expanded', 'true'); showActive(active + 1); }
      if (event.key === 'ArrowUp' && places.length) { event.preventDefault(); results.hidden = false; input.setAttribute('aria-expanded', 'true'); showActive(active - 1); }
      if (event.key === 'Enter' && !results.hidden && active >= 0) { event.preventDefault(); commit(places[active]); }
    });
    listen(results, 'mousedown', event => event.preventDefault());
    listen(mapButton, 'click', openMap);
    listen(doc, 'pointerdown', event => { if (!host.contains(event.target)) closeResults(); });
    return {
      getValue() { return selected ? { ...selected } : null; },
      setValue(value) { if (!destroyed) commit(value); },
      destroy() {
        if (destroyed) return;
        destroyed = true; search.destroy(); closeMap();
        for (const remove of listeners) remove();
        host.replaceChildren();
      }
    };
  }
  return { mount, validLocation, createSearch };
});
