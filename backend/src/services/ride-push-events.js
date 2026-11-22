'use strict';

/** Reference-only notifications reuse the existing encrypted FCM outbox. */
function createRidePushEvents({ db, enqueue, rides, now = Date.now, batchSize = 50 }) {
  let cursor = '';
  async function ready() {
    const query = db.isPostgres
      ? "SELECT table_name FROM information_schema.tables WHERE table_schema=current_schema() AND table_name='ride_jobs'"
      : "SELECT name FROM sqlite_master WHERE type='table' AND name='ride_jobs'";
    return Boolean(await db.prepare(query).get());
  }
  async function state(role, owner, point = null) {
    const principal = { id: owner, role };
    const data = role === 'DRIVER' ? await rides().dashboard(principal) : await rides().me(principal);
    const status = new Map();
    if (role === 'DRIVER') {
      const history = await db.prepare('SELECT id,status FROM ride_jobs WHERE driver_id=? ORDER BY created_at DESC LIMIT 50').all(owner);
      for (const ride of history) status.set('TAXI:' + ride.id, { id: ride.id, kind: 'TAXI', status: ride.status });
    }
    for (const ride of [...(data.rides || []), ...(data.ride ? [data.ride] : [])]) status.set('TAXI:' + ride.id, { id: ride.id, kind: 'TAXI', status: ride.status });
    for (const trip of data.sharedTrips || []) status.set('SHARED:' + trip.id, { id: trip.id, kind: 'SHARED', status: trip.status,
      revision: (trip.bookings || []).map(booking => booking.id + ':' + booking.status).sort().join(',') });
    // A booking event opens its parent trip; it never carries a boarding PIN.
    for (const booking of data.bookings || []) if (!status.has('SHARED:' + booking.tripId) && !['CANCELLED','NO_SHOW'].includes(booking.status)) {
      const trip = await db.prepare('SELECT status FROM ride_shared_trips WHERE id=?').get(booking.tripId);
      if (trip) status.set('SHARED:' + booking.tripId, { id: booking.tripId, kind: 'SHARED', status: trip.status });
    }
    let calls = [];
    if (role === 'DRIVER' && data.online && data.eligible && !data.busy && !data.ride && !(data.sharedTrips || []).some(trip => ['BOARDING','IN_PROGRESS'].includes(trip.status))) {
      calls = (data.calls || []).map(ride => ({ id: ride.id, kind: 'TAXI' }));
      const point = await db.prepare('SELECT latitude,longitude,recorded_at FROM ride_availability WHERE driver_id=?').get(owner);
      if (point?.recorded_at && now() - Date.parse(point.recorded_at) <= 120000) {
        const radius = (await rides().catalog()).settings.notificationRadiusKm;
        for (const trip of data.sharedRequests || []) if (require('./rides').km(Number(point.latitude),Number(point.longitude),trip.originLatitude,trip.originLongitude) <= radius) calls.push({ id: trip.id, kind: 'SHARED' });
      }
    }
    if (role === 'PASSENGER' && point?.latitude != null && point?.longitude != null && point?.gpsAt && now() - Date.parse(point.gpsAt) <= 86400000) {
      const cfg = (await rides().catalog()).settings;
      const nearby = await rides().sharedTrips();
      for (const trip of nearby.trips || []) {
        const distance = require('./rides').km(Number(point.latitude), Number(point.longitude), trip.originLatitude, trip.originLongitude);
        if (distance <= cfg.notificationRadiusKm) calls.push({ id: trip.id, kind: 'SHARED' });
      }
    }
    return { statuses: [...status.values()], calls };
  }
  async function snapshot(role, owner, point = null) {
    if (!await ready()) return JSON.stringify({ statuses: [], calls: [] });
    return JSON.stringify(await state(role, owner, point));
  }
  async function collect() {
    if (!await ready()) return;
    const tokens = await db.prepare("SELECT * FROM mobility_push_tokens WHERE scope IN ('RIDE_DRIVER','RIDE_PASSENGER') AND id>? ORDER BY id LIMIT ?").all(cursor, batchSize);
    cursor = tokens.length === batchSize ? tokens.at(-1).id : '';
    const accountStates = new Map();
    for (const token of tokens) {
      const role = token.scope.replace('RIDE_', '');
      const user = await db.prepare("SELECT id FROM users WHERE id=? AND role=? AND status='ACTIVE'").get(token.owner_id, role);
      if (!user) { await db.prepare('DELETE FROM mobility_push_tokens WHERE id=?').run(token.id); continue; }
      let current;
      const point = { latitude: token.latitude, longitude: token.longitude, gpsAt: token.gps_at };
      const cacheKey = role + ':' + token.owner_id + ':' + String(point.latitude) + ':' + String(point.longitude) + ':' + String(point.gpsAt);
      try { if (!accountStates.has(cacheKey)) accountStates.set(cacheKey, await state(role, token.owner_id, point)); current = accountStates.get(cacheKey); }
      catch (_) { continue; }
      let previous; try { previous = JSON.parse(token.snapshot); } catch (_) { previous = null; }
      for (const call of current.calls) await enqueue(token, call.id, 'NEW_REQUEST', call.kind, token.owner_id);
      if (previous?.statuses) for (const item of current.statuses) {
        const old = previous.statuses.find(old => old.id === item.id && old.kind === item.kind);
        if (old && old.status !== item.status) await enqueue(token, item.id, 'STATUS_' + item.status, item.kind, item.status);
        else if (role === 'DRIVER' && old && old.revision !== item.revision && item.kind === 'SHARED') await enqueue(token, item.id, 'BOOKING_UPDATED', item.kind, item.revision);
      }
      await db.prepare('UPDATE mobility_push_tokens SET snapshot=? WHERE id=?').run(JSON.stringify(current), token.id);
    }
  }
  async function valid(job, token) {
    const role = token.scope.replace('RIDE_', '');
    if (!['DRIVER', 'PASSENGER'].includes(role) || job.scope !== role) return false;
    if (!await db.prepare("SELECT id FROM users WHERE id=? AND role=? AND status='ACTIVE'").get(token.owner_id, role)) return false;
    try {
      const current = await state(role, token.owner_id, { latitude: token.latitude, longitude: token.longitude, gpsAt: token.gps_at });
      return job.event_type === 'NEW_REQUEST'
        ? current.calls.some(call => call.id === job.request_id && call.kind === job.kind)
        : current.statuses.some(item => item.id === job.request_id && item.kind === job.kind && ('STATUS_' + item.status === job.event_type || role === 'DRIVER' && job.kind === 'SHARED' && job.event_type === 'BOOKING_UPDATED'));
    } catch (_) { return false; }
  }
  return { collect, valid, snapshot };
}
module.exports = { createRidePushEvents };
