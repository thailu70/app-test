'use strict';

const WINDOW_MS = 30 * 60 * 1000;
const MAX_FIX_AGE_MS = 120 * 1000;
const MAX_ACCURACY_METERS = 100;
const ADDIS_OFFSET_MS = 3 * 60 * 60 * 1000;

function coordinatesValid(latitude, longitude) {
  return typeof latitude === 'number' && Number.isFinite(latitude) && latitude >= -90 && latitude <= 90 &&
    typeof longitude === 'number' && Number.isFinite(longitude) && longitude >= -180 && longitude <= 180;
}

function distanceMeters(lat1, lng1, lat2, lng2) {
  const radians = Math.PI / 180;
  const deltaLat = (lat2 - lat1) * radians;
  const deltaLng = (lng2 - lng1) * radians;
  const a = Math.sin(deltaLat / 2) ** 2 + Math.cos(lat1 * radians) * Math.cos(lat2 * radians) * Math.sin(deltaLng / 2) ** 2;
  return 6371000 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}

// Only the server's clock determines the departure window. Device time is used
// exclusively to reject stale GPS fixes, never to choose a departure date.
function evaluateDeparture({ latitude, longitude, radiusMeters = 300, departureAt, location, now = Date.now() }) {
  const time = Number(now instanceof Date ? now.getTime() : now);
  const departure = typeof departureAt === 'number' ? departureAt : Date.parse(departureAt);
  const metadata = { serverTime: new Date(time).toISOString(), radiusMeters: Number(radiusMeters) };
  const denied = (code, message) => ({ ...metadata, allowed: false, code, message });
  if (!coordinatesValid(Number(latitude), Number(longitude)) || latitude == null || longitude == null ||
      !Number.isFinite(Number(radiusMeters)) || Number(radiusMeters) < 25 || Number(radiusMeters) > 5000) {
    return denied('DEPARTURE_NOT_CONFIGURED', 'Ask your administrator to configure the departure coordinates and radius before confirming arrival.');
  }
  if (!Number.isFinite(departure)) {
    return denied('SCHEDULE_NOT_CONFIGURED', 'Ask your administrator to configure the departure time before confirming arrival.');
  }
  metadata.departureAt = new Date(departure).toISOString();
  metadata.windowOpensAt = new Date(departure - WINDOW_MS).toISOString();
  metadata.windowClosesAt = new Date(departure + WINDOW_MS).toISOString();
  if (Math.abs(time - departure) > WINDOW_MS) {
    return denied('OUTSIDE_DEPARTURE_WINDOW', 'Arrival can be confirmed only within 30 minutes before or after the scheduled departure. Times use Addis Ababa time.');
  }
  if (!location || !coordinatesValid(location.latitude, location.longitude) ||
      typeof location.accuracyMeters !== 'number' || !Number.isFinite(location.accuracyMeters) ||
      location.accuracyMeters < 0 || location.accuracyMeters > MAX_ACCURACY_METERS) {
    return denied('GPS_REQUIRED', 'A fresh GPS fix with accuracy of 100 metres or better is required. Enable precise location and try again.');
  }
  const fixAt = typeof location.timestamp === 'number' ? location.timestamp : Date.parse(location.timestamp);
  if (!Number.isFinite(fixAt) || time - fixAt > MAX_FIX_AGE_MS || fixAt - time > 10000) {
    return denied('GPS_STALE', 'Your GPS fix is too old. Wait for a fresh location and try again.');
  }
  const actualDistance = distanceMeters(Number(latitude), Number(longitude), location.latitude, location.longitude);
  metadata.distanceMeters = Math.round(actualDistance);
  if (actualDistance > Number(radiusMeters)) {
    return denied('OUTSIDE_DEPARTURE_RADIUS', `You are ${metadata.distanceMeters} metres from departure. Move within the ${radiusMeters} metre radius to confirm arrival.`);
  }
  return { ...metadata, allowed: true, code: 'READY', message: 'Location and departure time verified. Boarding can begin.' };
}

function commuteDepartureAt(clock, now = Date.now()) {
  if (typeof clock !== 'string' || !/^([01]\d|2[0-3]):[0-5]\d$/.test(clock)) return null;
  const serverTime = Number(now instanceof Date ? now.getTime() : now);
  const date = new Date(serverTime + ADDIS_OFFSET_MS).toISOString().slice(0, 10);
  const today = Date.parse(`${date}T${clock}:00+03:00`);
  // The nearest daily instance handles departures around local midnight without
  // using a caller-provided date or widening the 30-minute gate.
  return [today - 86400000, today, today + 86400000].sort((a, b) => Math.abs(a - serverTime) - Math.abs(b - serverTime))[0];
}

async function checkCommuteDeparture(db, route, direction, location, now = Date.now()) {
  const setting = await db.prepare('SELECT * FROM route_departure_settings WHERE route_id = ?').get(route.id);
  const stop = await db.prepare(`SELECT * FROM route_stops WHERE routeId = ? ORDER BY stopOrder ${direction === 'INBOUND' ? 'DESC' : 'ASC'} LIMIT 1`).get(route.id);
  const clock = direction === 'INBOUND'
    ? (route.eveningDeparture || stop?.scheduledEveningTime)
    : (route.morningDeparture || stop?.scheduledMorningTime);
  return evaluateDeparture({
    // Return departures use the final stop as their origin. An explicit setting
    // defines the outbound origin; it must never replace the inbound origin.
    latitude: direction === 'INBOUND' ? stop?.latitude : (setting?.latitude ?? stop?.latitude),
    longitude: direction === 'INBOUND' ? stop?.longitude : (setting?.longitude ?? stop?.longitude),
    radiusMeters: setting?.radius_m ?? 300,
    departureAt: commuteDepartureAt(clock, now),
    location,
    now
  });
}

module.exports = { evaluateDeparture, checkCommuteDeparture, commuteDepartureAt, distanceMeters };
