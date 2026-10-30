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
function createGeoService({ fetchImpl = globalThis.fetch, db, env = process.env, now = Date.now } = {}) {
  const searchCache = new Map(), routeCache = new Map();
  const photon = env.PHOTON_URL || 'http://photon:2322', osrm = env.OSRM_URL || 'http://osrm:5000';
  const timeoutMs = Math.min(10000, Math.max(500, Number(env.GEO_TIMEOUT_MS) || 3500));
  function cacheGet(cache, key) { const entry = cache.get(key); return entry && entry.until > now() ? entry.value : null; }
  function cachePut(cache, key, value, seconds) {
    if (cache.size >= 500) cache.delete(cache.keys().next().value);
    cache.set(key, { value, until: now() + seconds * 1000 }); return value;
  }
  async function json(base, pathname, params) {
    const url = new URL(pathname, base);
    if (!['http:', 'https:'].includes(url.protocol)) throw new GeoError('Location service is not configured.');
    for (const [key, value] of Object.entries(params)) url.searchParams.set(key, String(value));
    const response = await fetchImpl(url, { signal: AbortSignal.timeout(timeoutMs), headers: { Accept: 'application/json' } });
    if (!response.ok) throw new GeoError('Location service is temporarily unavailable.');
    const body = await response.text();
    if (body.length > 2000000) throw new GeoError('Location response is too large.');
    return JSON.parse(body);
  }
  async function savedLocations(query) {
    const database = db || require('../db').DB;
    // Only named public route stops are searched. Never read driver GPS or customer pins here.
    const rows = await database.prepare('SELECT stopName,stopNameAm,latitude,longitude FROM route_stops ORDER BY stopName LIMIT 2000').all();
    const normalized = query.toLocaleLowerCase();
    return rows.filter(row => [row.stopName, row.stopNameAm].some(name => String(name || '').toLocaleLowerCase().includes(normalized)))
      .map(row => ({ name: String(row.stopName || row.stopNameAm), latitude: Number(row.latitude), longitude: Number(row.longitude) }))
      .filter(row => row.name && Number.isFinite(row.latitude) && Number.isFinite(row.longitude) && Math.abs(row.latitude) <= 90 && Math.abs(row.longitude) <= 180).slice(0, 8);
  }
  async function locations(input) {
    const query = String(input || '').trim().replace(/\s+/g, ' ');
    if (query.length < 2) return { places: [], source: 'saved', searchAvailable: true, attribution: '© OpenStreetMap contributors' };
    if (query.length > 120) throw new GeoError('Keep location searches under 120 characters.', 400);
    const key = query.toLocaleLowerCase(), cached = cacheGet(searchCache, key);
    if (cached) return cached;
    let saved = []; try { saved = await savedLocations(query); } catch (_) { /* A new empty database can still use Photon. */ }
    try {
      const data = await json(photon, '/api/', { q: query, limit: 8, countrycode: 'ET' });
      const places = (data.features || []).map(feature => {
        const p = feature.properties || {}, c = feature.geometry?.coordinates || [];
        return { name: [...new Set([p.name, p.street, p.district, p.city, p.state, p.country].filter(Boolean))].join(', '), latitude: Number(c[1]), longitude: Number(c[0]) };
      }).filter(place => place.name && Number.isFinite(place.latitude) && Number.isFinite(place.longitude) && Math.abs(place.latitude) <= 90 && Math.abs(place.longitude) <= 180);
      const merged = [...saved, ...places].filter((p, i, list) => list.findIndex(other => other.name === p.name && Math.abs(other.latitude - p.latitude) < 0.00001 && Math.abs(other.longitude - p.longitude) < 0.00001) === i).slice(0, 8);
      return cachePut(searchCache, key, { places: merged, source: 'photon', searchAvailable: true, attribution: '© OpenStreetMap contributors' }, 300);
    } catch (_) {
      return { places: saved, source: 'saved', searchAvailable: false, message: 'Showing saved stops. You can also choose a map pin.', attribution: '© OpenStreetMap contributors' };
    }
  }
  async function route(input) {
    const coordinates = [coordinate(input.originLongitude, false), coordinate(input.originLatitude, true), coordinate(input.destinationLongitude, false), coordinate(input.destinationLatitude, true)];
    const key = coordinates.map(n => n.toFixed(6)).join(','), cached = cacheGet(routeCache, key);
    if (cached) return cached;
    let data;
    try { data = await json(osrm, '/route/v1/driving/' + coordinates.slice(0, 2).join(',') + ';' + coordinates.slice(2).join(','), { overview: 'simplified', geometries: 'geojson', steps: false, radiuses: '2000;2000' }); }
    catch (_) { throw new GeoError('Road distance is unavailable. You can still request driver quotes.'); }
    const result = data.routes?.[0];
    if (data.code !== 'Ok' || !result || !Number.isFinite(result.distance) || result.distance < 0 || !Number.isFinite(result.duration)) throw new GeoError('No drivable road route was found. Adjust the map pins.', 422);
    const geometry = result.geometry?.type === 'LineString' && Array.isArray(result.geometry.coordinates) && result.geometry.coordinates.length <= 20000 ? result.geometry : null;
    return cachePut(routeCache, key, { distanceKm: Math.round(result.distance / 10) / 100, durationMinutes: Math.ceil(result.duration / 60), geometry, source: 'osrm', attribution: '© OpenStreetMap contributors' }, 1800);
  }
  return { locations, route };
}
let instance;
function getGeoService() { if (!instance) instance = createGeoService(); return instance; }
module.exports = { GeoError, createGeoService, getGeoService };
