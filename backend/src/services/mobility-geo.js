'use strict';

class GeoError extends Error {
  constructor(message, status = 503) { super(message); this.name = 'GeoError'; this.status = status; }
}
function coordinate(value, latitude) {
  if (value === undefined || value === null || String(value).trim() === '') throw new GeoError('Select both pickup and destination on the map.', 400);
  const number = Number(value);
  if (!Number.isFinite(number) || Math.abs(number) > (latitude ? 90 : 180)) throw new GeoError('Choose a valid map location.', 400);
  return number;
}
// Keep normal routes exactly as returned by OSRM. Only exceptionally long
// journeys need bounded, distance-based reduction for existing mobile clients.
function detailedGeometry(value) {
  const points = value?.coordinates;
  if (value?.type !== 'LineString' || !Array.isArray(points) || points.length < 2 || points.length > 100000 ||
      points.some(p => !Array.isArray(p) || p.length < 2 || !Number.isFinite(p[0]) || !Number.isFinite(p[1]) || Math.abs(p[0]) > 180 || Math.abs(p[1]) > 90))
    throw new GeoError('The road geometry is unavailable. Please choose nearby road access points.');
  if (points.length <= 20000) return { geometry: value, geometryQuality: 'FULL', geometryToleranceMeters: 0 };
  const radius = 6371000, latitudeScale = Math.cos(points[0][1] * Math.PI / 180);
  const xy = points.map(p => [radius * p[0] * Math.PI / 180 * latitudeScale,
    radius * Math.log(Math.tan(Math.PI / 4 + Math.max(-85, Math.min(85, p[1])) * Math.PI / 360)) * latitudeScale]);
  for (const tolerance of [0.25, 0.5, 1, 2, 5]) {
    const keep = new Uint8Array(points.length); keep[0] = keep[points.length - 1] = 1;
    const stack = [[0, points.length - 1]];
    let comparisons = 0, exhausted = false;
    while (stack.length && !exhausted) {
      const [first, last] = stack.pop(), a = xy[first], b = xy[last], dx = b[0] - a[0], dy = b[1] - a[1], squared = dx * dx + dy * dy;
      let furthest = -1, maximum = tolerance * tolerance;
      for (let i = first + 1; i < last; i++) {
        if (++comparisons > 3000000) { exhausted = true; break; }
        const p = xy[i], ratio = squared ? Math.max(0, Math.min(1, ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / squared)) : 0;
        const distance = (p[0] - a[0] - ratio * dx) ** 2 + (p[1] - a[1] - ratio * dy) ** 2;
        if (distance > maximum) { maximum = distance; furthest = i; }
      }
      if (furthest >= 0) { keep[furthest] = 1; stack.push([first, furthest], [furthest, last]); }
    }
    if (exhausted) continue;
    const reduced = points.filter((_p, i) => keep[i]);
    if (reduced.length <= 20000) return { geometry: { type: 'LineString', coordinates: reduced }, geometryQuality: 'HIGH_DETAIL', geometryToleranceMeters: tolerance };
  }
  throw new GeoError('This road route is too complex. Choose a shorter route.');
}
function createGeoService({ fetchImpl = globalThis.fetch, db, env = process.env, now = Date.now } = {}) {
  const searchCache = new Map(), reverseCache = new Map(), routeCache = new Map();
  const photon = env.PHOTON_URL || 'http://photon:2322', osrm = env.OSRM_URL || 'http://osrm:5000';
  const timeoutMs = Math.min(10000, Math.max(500, Number(env.GEO_TIMEOUT_MS) || 3500));
  function cacheGet(cache, key) { const entry = cache.get(key); return entry && entry.until > now() ? entry.value : null; }
  function cachePut(cache, key, value, seconds) {
    if (cache.size >= 500) cache.delete(cache.keys().next().value);
    cache.set(key, { value, until: now() + seconds * 1000 }); return value;
  }
  async function json(base, pathname, params, maxBytes = 2000000) {
    const url = new URL(pathname, base);
    if (!['http:', 'https:'].includes(url.protocol)) throw new GeoError('Location service is not configured.');
    for (const [key, value] of Object.entries(params)) url.searchParams.set(key, String(value));
    const response = await fetchImpl(url, { signal: AbortSignal.timeout(timeoutMs), headers: { Accept: 'application/json' } });
    if (!response.ok) throw new GeoError('Location service is temporarily unavailable.');
    const body = await response.text();
    if (body.length > maxBytes) throw new GeoError('Location response is too large.');
    return JSON.parse(body);
  }
  async function savedLocations(query) {
    const database = db || require('../db').DB;
    // Only named public route stops are searched. Never read driver GPS or customer pins here.
    const rows = await database.prepare('SELECT stopName,stopNameAm,latitude,longitude FROM route_stops ORDER BY stopName LIMIT 2000').all();
    const normalized = query.toLocaleLowerCase();
    return rows.filter(row => [row.stopName, row.stopNameAm].some(name => String(name || '').toLocaleLowerCase().includes(normalized))
      && row.latitude != null && row.longitude != null && String(row.latitude).trim() !== '' && String(row.longitude).trim() !== '')
      .map(row => ({ name: String(row.stopName || '').trim() || String(row.stopNameAm || '').trim(), latitude: Number(row.latitude), longitude: Number(row.longitude) }))
      .filter(row => row.name && Number.isFinite(row.latitude) && Number.isFinite(row.longitude) && Math.abs(row.latitude) <= 90 && Math.abs(row.longitude) <= 180
        && (row.latitude !== 0 || row.longitude !== 0)).slice(0, 8);
  }
  async function locations(input) {
    const query = String(input || '').trim().replace(/\s+/g, ' ');
    if (query.length < 2) return { places: [], source: 'saved', searchAvailable: true, attribution: '© OpenStreetMap contributors' };
    if (query.length > 120) throw new GeoError('Keep location searches under 120 characters.', 400);
    const key = query.toLocaleLowerCase();
    let saved = []; try { saved = await savedLocations(query); } catch (_) { /* A new empty database can still use Photon. */ }
    let result = cacheGet(searchCache, key);
    try {
      if (!result) {
        const data = await json(photon, '/api/', { q: query, limit: 8, countrycode: 'ET' });
        const places = (data.features || []).map(feature => {
          const p = feature.properties || {}, c = feature.geometry?.coordinates || [];
          return { name: [...new Set([p.name, p.street, p.district, p.city, p.state, p.country].filter(Boolean))].join(', '), latitude: Number(c[1]), longitude: Number(c[0]) };
        }).filter(place => place.name && Number.isFinite(place.latitude) && Number.isFinite(place.longitude) && Math.abs(place.latitude) <= 90 && Math.abs(place.longitude) <= 180);
        // Cache the geocoder response only. Saved route stops can change while an
        // administrator creates or edits routes and must be current on each search.
        result = cachePut(searchCache, key, { places, source: 'photon', searchAvailable: true, attribution: '© OpenStreetMap contributors' }, 300);
      }
      const places = [...saved, ...result.places].filter((p, i, list) => list.findIndex(other => other.name === p.name && Math.abs(other.latitude - p.latitude) < 0.00001 && Math.abs(other.longitude - p.longitude) < 0.00001) === i).slice(0, 8);
      return { ...result, places };
    } catch (_) {
      return { places: saved, source: 'saved', searchAvailable: false, message: 'Showing saved stops. You can also choose a map pin.', attribution: '© OpenStreetMap contributors' };
    }
  }
  async function reverse(input) {
    const latitude = coordinate(input.lat ?? input.latitude, true);
    const longitude = coordinate(input.lon ?? input.longitude, false);
    const key = `${latitude.toFixed(4)},${longitude.toFixed(4)}`;
    const cached = cacheGet(reverseCache, key);
    if (cached) return cached;
    try {
      const data = await json(photon, '/reverse/', { lat: latitude, lon: longitude, lang: 'en', countrycode: 'ET' });
      const properties = data.features?.[0]?.properties || {};
      const meaningful = value => {
        const label = String(value || '').trim();
        return label && !/^\d+$/.test(label) ? label : null;
      };
      const district = meaningful(properties.district || properties.neighbourhood || properties.suburb);
      const county = meaningful(properties.county);
      const city = meaningful(properties.city || properties.locality || properties.state);
      const area = district || county;
      const name = area && city && area.toLocaleLowerCase() !== city.toLocaleLowerCase()
        ? `${area}, ${city}` : area || city || meaningful(properties.street);
      return cachePut(reverseCache, key, { name: name || null, source: 'photon', attribution: '© OpenStreetMap contributors' }, 600);
    } catch (_) {
      throw new GeoError('Nearby area name is temporarily unavailable.');
    }
  }
  async function route(input) {
    const coordinates = [coordinate(input.originLongitude, false), coordinate(input.originLatitude, true), coordinate(input.destinationLongitude, false), coordinate(input.destinationLatitude, true)];
    const key = coordinates.map(n => n.toFixed(6)).join(','), cached = cacheGet(routeCache, key);
    if (cached) return cached;
    let data;
    try { data = await json(osrm, '/route/v1/driving/' + coordinates.slice(0, 2).join(',') + ';' + coordinates.slice(2).join(','), { overview: 'full', geometries: 'geojson', steps: false, radiuses: '500;500' }, 4000000); }
    catch (_) { throw new GeoError('Road distance is unavailable. You can still request driver quotes.'); }
    const result = data.routes?.[0];
    if (data.code !== 'Ok' || !result || !Number.isFinite(result.distance) || result.distance < 0 || !Number.isFinite(result.duration)) throw new GeoError('No drivable road route was found. Adjust the map pins.', 422);
    const detail = detailedGeometry(result.geometry);
    const snapped = (index) => {
      const waypoint = data.waypoints?.[index];
      return Number.isFinite(waypoint?.distance) && waypoint.distance >= 0 ? Math.round(waypoint.distance) : null;
    };
    return cachePut(routeCache, key, { distanceKm: Math.round(result.distance / 10) / 100, durationMinutes: Math.ceil(result.duration / 60), ...detail,
      originSnapMeters: snapped(0), destinationSnapMeters: snapped(1), source: 'osrm', attribution: '© OpenStreetMap contributors' }, 1800);
  }
  return { locations, reverse, route };
}
let instance;
function getGeoService() { if (!instance) instance = createGeoService(); return instance; }
module.exports = { GeoError, createGeoService, getGeoService, detailedGeometry };
