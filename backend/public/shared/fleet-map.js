(() => {
  'use strict';
  let leafletLoading = null;
  function leaflet() {
    if (window.L) return Promise.resolve(window.L);
    if (leafletLoading) return leafletLoading;
    leafletLoading = new Promise((resolve, reject) => {
      const script = document.createElement('script');
      script.src = '/shared/vendor/leaflet/leaflet.js';
      const timeout = setTimeout(() => { script.remove(); leafletLoading = null; reject(Error('Map library timed out. Driver coordinates are available below.')); }, 10000);
      script.onload = () => { clearTimeout(timeout); resolve(window.L); };
      script.onerror = () => { clearTimeout(timeout); script.remove(); leafletLoading = null; reject(Error('Map library is unavailable. Driver coordinates are available below.')); };
      document.head.append(script);
    });
    return leafletLoading;
  }
  const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({ '&':'&amp;', '<':'&lt;', '>':'&gt;', '"':'&quot;', "'":'&#39;' })[c]);
  const label = status => ({ LIVE:'Live', STALE:'Stale GPS', OFFLINE:'Offline', NO_GPS:'No GPS yet' })[status] || 'No GPS yet';
  const badge = status => '<span class="fleet-badge ' + esc(status.toLowerCase()) + '">' + label(status) + '</span>';
  function age(seconds) {
    if (seconds == null) return 'No GPS report';
    if (seconds < 60) return seconds + ' sec ago';
    if (seconds < 3600) return Math.floor(seconds / 60) + ' min ago';
    if (seconds < 86400) return Math.floor(seconds / 3600) + ' hr ago';
    return Math.floor(seconds / 86400) + ' day(s) ago';
  }
  function mount({ container, apiUrl = '/api/fleet/tracking', token }) {
    if (!container) return () => {};
    container.innerHTML = '<section class="fleet-tracking">' +
      '<div class="fleet-toolbar"><div><h3>Driver GPS</h3><p>Real phone locations from your fleet. Refreshes every 15 seconds.</p></div><div class="fleet-actions"><button type="button" data-fleet-fit>Fit fleet</button><button type="button" data-fleet-refresh>Refresh GPS</button></div></div>' +
      '<div class="fleet-stats" data-fleet-stats></div><p class="fleet-message" data-fleet-message role="status">Loading fleet GPS…</p>' +
      '<div class="fleet-layout"><div><div class="fleet-map" data-fleet-map aria-label="Map of real driver GPS locations"></div><p class="fleet-map-note" data-fleet-map-note>Road map: OpenStreetMap. No marker is shown without a real GPS report.</p></div>' +
      '<aside class="fleet-explainer"><h4>Reading the map</h4><p>' + badge('LIVE') + '<br>Active route and a GPS report within 2 minutes.</p><p>' + badge('STALE') + '<br>Active route; last report is over 2 minutes old.</p><p>' + badge('OFFLINE') + '<br>No active confirmed route. The marker is the last known position.</p><p>' + badge('NO_GPS') + '<br>No valid GPS report yet. No marker is placed.</p></aside></div>' +
      '<div class="fleet-filter"><input type="search" data-fleet-search aria-label="Find driver, vehicle or route" placeholder="Search driver, vehicle or route"><label>Show <select data-fleet-status aria-label="Filter by GPS status"><option value="ALL">All statuses</option><option value="LIVE">Live</option><option value="STALE">Stale GPS</option><option value="OFFLINE">Offline</option><option value="NO_GPS">No GPS yet</option></select></label></div>' +
      '<div class="fleet-table-wrap"><table class="fleet-table"><thead><tr><th>Driver / vehicle</th><th>Route</th><th>GPS status</th><th>Last GPS report</th><th>Coordinates</th><th>Map</th></tr></thead><tbody data-fleet-rows></tbody></table></div>' +
      '<p class="fleet-footnote">Drivers must allow precise location, confirm arrival for their assigned route, and keep the driver screen open to send GPS. Old coordinates stay labelled with their last report time; they are never shown as live.</p></section>';
    const $ = selector => container.querySelector(selector);
    let rows = [], map = null, markers = null, mounted = true, busy = false, controller = null, fitInitially = true, mapStarting = false;
    let search = '', status = 'ALL';
    function fixes() {
      return rows.map(row => {
        const loc = { ...row.location };
        if (loc.lastGpsAt && loc.hasGpsLocation) {
          loc.ageSeconds = Math.max(0, Math.floor((Date.now() - Date.parse(loc.lastGpsAt)) / 1000));
          if (loc.status === 'LIVE' && loc.ageSeconds > 120) loc.status = 'STALE';
        }
        return { ...row, location: loc };
      });
    }
    function visibleRows() {
      return fixes().filter(row => (status === 'ALL' || row.location.status === status) &&
        [row.driver?.name, row.driver?.phone, row.plateNumber, row.model, row.route?.name].join(' ').toLowerCase().includes(search));
    }
    function fit() {
      if (!map || !markers) return;
      map.invalidateSize();
      if (markers.getLayers().length) map.fitBounds(markers.getBounds(), { padding: [40, 40], maxZoom: 15 });
      else map.fitWorld();
    }
    async function drawMap() {
      if (!mounted || mapStarting) return;
      try {
        if (!map) {
          mapStarting = true;
          const L = await leaflet();
          if (!mounted) return;
          map = L.map($('[data-fleet-map]'), { scrollWheelZoom: false }).fitWorld();
          const tiles = L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', { maxZoom: 19, referrerPolicy: 'origin', attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors' }).addTo(map);
          tiles.on('tileerror', () => { if (mounted) $('[data-fleet-map-note]').textContent = 'Road map tiles could not load. Driver markers and coordinates below remain available. Check your connection.'; });
          markers = L.featureGroup().addTo(map);
        }
        markers.clearLayers();
        for (const row of visibleRows()) {
          const loc = row.location;
          if (!loc.hasGpsLocation || !Number.isFinite(loc.latitude) || !Number.isFinite(loc.longitude)) continue;
          window.L.marker([loc.latitude, loc.longitude], { icon: window.L.divIcon({ className: 'fleet-pin ' + loc.status.toLowerCase(), iconSize: [22, 22], iconAnchor: [11, 11] }), title: (row.driver?.name || 'Unassigned driver') + ' · ' + row.plateNumber + ' · ' + label(loc.status) })
            .bindPopup('<div class="fleet-popup"><strong>' + esc(row.plateNumber) + '</strong><br>' + esc(row.driver?.name || 'No assigned driver') + '<br>' + esc(row.route?.name || 'No route assigned') + '<br>' + label(loc.status) + ' · ' + age(loc.ageSeconds) + '<br>' + esc(new Date(loc.lastGpsAt).toLocaleString()) + '</div>').addTo(markers);
        }
        if (fitInitially && markers.getLayers().length) { fit(); fitInitially = false; }
      } catch (error) { if (mounted) $('[data-fleet-map-note]').textContent = error.message; }
      finally { mapStarting = false; }
    }
    function render() {
      if (!mounted) return;
      const all = fixes();
      $('[data-fleet-stats]').innerHTML = ['LIVE', 'STALE', 'OFFLINE', 'NO_GPS'].map(s => '<div class="fleet-stat"><strong>' + all.filter(r => r.location.status === s).length + '</strong><span>' + label(s) + '</span></div>').join('');
      const visible = visibleRows();
      $('[data-fleet-rows]').innerHTML = visible.length ? visible.map(row => {
        const loc = row.location, has = loc.hasGpsLocation;
        return '<tr><td><strong>' + esc(row.driver?.name || 'No assigned driver') + '</strong><small>' + esc(row.plateNumber) + ' · ' + esc(row.model) + '</small><small>' + esc(row.driver?.phone || '') + '</small></td><td>' + esc(row.route?.name || 'No route assigned') + '<small>' + esc(row.trip ? (row.trip.type === 'INTERCITY' ? 'Intercity · Boarding / route active' : 'Daily commute · Route active') : 'No active route') + '</small></td><td>' + badge(loc.status) + '</td><td>' + age(loc.ageSeconds) + '<small>' + esc(loc.lastGpsAt ? new Date(loc.lastGpsAt).toLocaleString() : 'Waiting for driver phone') + '</small></td><td>' + (has ? Number(loc.latitude).toFixed(5) + ', ' + Number(loc.longitude).toFixed(5) : '—') + '<small>' + esc(loc.currentStop || '') + '</small></td><td>' + (has ? '<button type="button" data-fleet-focus="' + esc(row.id) + '">Locate</button>' : '—') + '</td></tr>';
      }).join('') : '<tr><td colspan="6" class="fleet-empty">' + (rows.length ? 'No vehicles match your filters.' : 'No fleet vehicles are registered yet. Add a vehicle and assign its driver and route to get started.') + '</td></tr>';
      drawMap();
    }
    async function refresh() {
      if (!mounted || busy || document.hidden) return;
      busy = true;
      const refreshButton = $('[data-fleet-refresh]');
      refreshButton.disabled = true;
      controller = new AbortController();
      const timeout = setTimeout(() => controller?.abort(), 10000);
      try {
        const response = await fetch(apiUrl, { headers: { Authorization: 'Bearer ' + (typeof token === 'function' ? token() : token), Accept: 'application/json' }, credentials: 'same-origin', cache: 'no-store', signal: controller.signal });
        const body = await response.json();
        if (!response.ok || body.success !== true) {
          if (response.status === 401 || response.status === 403) rows = [];
          throw Error(body.error || 'Could not load fleet GPS (' + response.status + ').');
        }
        if (!mounted) return;
        rows = Array.isArray(body.vehicles) ? body.vehicles : [];
        $('[data-fleet-message]').classList.remove('error');
        $('[data-fleet-message]').textContent = 'Last refreshed ' + new Date().toLocaleTimeString() + ' · ' + rows.length + ' vehicle(s). ' + (rows.some(r => r.location?.hasGpsLocation) ? 'Only real GPS reports are plotted.' : 'Waiting for a driver to send GPS.');
      } catch (error) {
        if (!mounted) return;
        $('[data-fleet-message]').classList.add('error');
        $('[data-fleet-message]').textContent = (error.name === 'AbortError' ? 'GPS refresh timed out.' : error.message) + ' ' + (rows.length ? 'Showing the last loaded reports with their actual age. ' : '') + 'Use Refresh GPS to retry.';
      } finally {
        clearTimeout(timeout);
        busy = false;
        if (mounted) { refreshButton.disabled = false; render(); }
      }
    }
    const onClick = event => {
      const button = event.target.closest('button');
      if (!button) return;
      if (button.hasAttribute('data-fleet-refresh')) refresh();
      if (button.hasAttribute('data-fleet-fit')) { fitInitially = true; fit(); }
      const id = button.getAttribute('data-fleet-focus');
      if (id && map) {
        const row = rows.find(r => r.id === id);
        if (row?.location.hasGpsLocation) { map.invalidateSize(); map.setView([row.location.latitude, row.location.longitude], 16); }
      }
    };
    const onInput = () => { search = $('[data-fleet-search]').value.trim().toLowerCase(); status = $('[data-fleet-status]').value; render(); };
    const onVisible = () => { if (!document.hidden) { map?.invalidateSize(); refresh(); } };
    container.addEventListener('click', onClick);
    $('[data-fleet-search]').addEventListener('input', onInput);
    $('[data-fleet-status]').addEventListener('change', onInput);
    document.addEventListener('visibilitychange', onVisible);
    const interval = setInterval(refresh, 15000);
    render();
    refresh();
    return () => { mounted = false; clearInterval(interval); controller?.abort(); document.removeEventListener('visibilitychange', onVisible); container.removeEventListener('click', onClick); map?.remove(); container.innerHTML = ''; };
  }
  window.RoutepassFleetMap = { mount };
})();
