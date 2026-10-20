const express = require('express');
const router = express.Router();
const crypto = require('crypto');
const { DB } = require('../db');
const { authenticate, requireRole } = require('../middleware/auth');
const { optionalPrincipal, commuteRoute } = require('../services/commute-scope');
const { routeSearch } = require('../services/route-search');

const stopNameKey = value => String(value ?? '').normalize('NFKC').trim().replace(/\s+/gu, ' ').toLowerCase();
function stopPoint(stop) {
  if (stop.latitude == null || stop.longitude == null || String(stop.latitude).trim() === '' || String(stop.longitude).trim() === '') return null;
  const latitude = Number(stop.latitude), longitude = Number(stop.longitude);
  return Number.isFinite(latitude) && Number.isFinite(longitude) && Math.abs(latitude) <= 90 && Math.abs(longitude) <= 180 && (latitude !== 0 || longitude !== 0)
    ? { latitude, longitude } : null;
}
function sameStopPoint(a, b) {
  return a && b && Math.abs(a.latitude - b.latitude) <= 0.000001 && Math.abs(a.longitude - b.longitude) <= 0.000001;
}
function stopIdentityCandidates(stop, existing) {
  const names = [stopNameKey(stop.stopName), stopNameKey(stop.stopNameAm)].filter(Boolean);
  const point = stopPoint(stop);
  const byName = existing.filter(old => names.some(name => name === stopNameKey(old.stopName) || name === stopNameKey(old.stopNameAm))
    && (!point || !stopPoint(old) || sameStopPoint(point, stopPoint(old))));
  const byPoint = point ? existing.filter(old => sameStopPoint(point, stopPoint(old))) : [];
  const byBoth = byPoint.filter(old => byName.includes(old));
  if (byBoth.length === 1) return byBoth;
  // A unique physical point remains the same stop even if its label is corrected.
  // Shared coordinates/names are ambiguous; never guess its identity by row order.
  return byPoint.length ? byPoint : byName;
}
async function routeStopIdentities(tx, routeId, incoming) {
  const existing = await tx.prepare('SELECT * FROM route_stops WHERE routeId = ?').all(routeId);
  const ownedIds = new Set(existing.map(stop => stop.id)), declaredIds = new Set();
  const invalid = message => { throw Object.assign(new Error(message), { status: 400 }); };
  const explicit = [];
  for (const stop of incoming) {
    if (!stop || typeof stop !== 'object' || Array.isArray(stop)) invalid('Each stop must be an object.');
    const id = String(stop.id || '').trim();
    if (id) {
      if (declaredIds.has(id)) invalid('A stop ID can appear only once in a route.');
      declaredIds.add(id);
      if (!ownedIds.has(id)) {
        const other = await tx.prepare('SELECT routeId FROM route_stops WHERE id = ?').get(id);
        if (other) invalid('A stop ID from another route cannot be used.');
      }
    }
    explicit.push(id || null);
  }
  const unclaimed = existing.filter(stop => !declaredIds.has(stop.id));
  const candidates = incoming.map((stop, index) => explicit[index] ? [] : stopIdentityCandidates(stop, unclaimed));
  const claims = new Map();
  for (const matches of candidates) if (matches.length === 1) claims.set(matches[0].id, (claims.get(matches[0].id) || 0) + 1);
  return incoming.map((stop, index) => {
    const matches = candidates[index];
    return explicit[index] || (matches.length === 1 && claims.get(matches[0].id) === 1 ? matches[0].id : `stop_${crypto.randomUUID().slice(0, 8)}`);
  });
}

/**
 * GET /api/routes
 * List all active transit lines
 */
router.get('/', async (req, res) => {
  let search;
  try { search = routeSearch(req.query); }
  catch (err) { return res.status(400).json({ success: false, error: err.message }); }
  try {
    const principal = await optionalPrincipal(req);
    if(principal?.role==='COMPANY') return res.status(403).json({success:false,error:'Use your company intercity route portal.'});
    const routes = await DB.prepare(`SELECT r.* FROM routes r WHERE ${commuteRoute('r')} AND ${search.where} ORDER BY ${search.order}`).all(...search.params);
    res.json({
      success: true,
      count: routes.length,
      routes
    });
  } catch (err) {
    console.error('[RoutePass] request failed:', err);
    res.status(500).json({ success: false, error: 'Internal server error.' });
  }
});

/**
 * GET /api/routes/:id
 * Get single route with its ordered stops
 */
router.get('/:id', async (req, res) => {
  try {
    const principal = await optionalPrincipal(req);
    if(principal?.role==='COMPANY') return res.status(403).json({success:false,error:'Use your company route portal.'});
    const route = await DB.prepare(`SELECT * FROM routes WHERE id = ? AND ${commuteRoute('')}`).get(req.params.id);
    if (!route) {
      return res.status(404).json({ success: false, error: 'Route not found' });
    }

    const stops = await DB.prepare(`SELECT * FROM route_stops WHERE routeId = ? ORDER BY stopOrder ASC`).all(req.params.id);

    res.json({
      success: true,
      route,
      stops
    });
  } catch (err) {
    console.error('[RoutePass] request failed:', err);
    res.status(500).json({ success: false, error: 'Internal server error.' });
  }
});

/**
 * POST /api/routes
 * Admin/Operator only: Create a new transit route with stops
 */
router.post('/', authenticate, requireRole('ADMIN'), async (req, res) => {
  try {
    const {
      name,
      nameAm,
      description = '',
      morningDeparture = '06:30',
      eveningDeparture = '17:30',
      distanceKm = 10.0,
      basePriceEtb = 2500.0,
      stops = []
    } = req.body;

    if (!name || !nameAm) {
      return res.status(400).json({
        success: false,
        error: 'Route name in English and Amharic are required.'
      });
    }

    const routeId = `route_${crypto.randomUUID().slice(0, 8)}`;

    await DB.prepare(`
      INSERT INTO routes (id, name, nameAm, description, morningDeparture, eveningDeparture, distanceKm, basePriceEtb, active)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, TRUE)
    `).run(
      routeId,
      name.trim(),
      nameAm.trim(),
      description.trim(),
      morningDeparture,
      eveningDeparture,
      parseFloat(distanceKm) || 10.0,
      parseFloat(basePriceEtb) || 2500.0
    );

    // Insert stops if provided
    const insertStop = DB.prepare(`
      INSERT INTO route_stops (id, routeId, stopName, stopNameAm, stopOrder, latitude, longitude, scheduledMorningTime, scheduledEveningTime, maxCapacity)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    `);

    if (Array.isArray(stops)) {
      for (const [idx, s] of stops.entries()) {
        const stopId = `stop_${crypto.randomUUID().slice(0, 8)}`;
        await insertStop.run(
          stopId,
          routeId,
          s.stopName || `Stop ${idx + 1}`,
          s.stopNameAm || `ማቆሚያ ${idx + 1}`,
          idx + 1,
          s.latitude || (9.01 + idx * 0.005),
          s.longitude || (38.75 + idx * 0.005),
          s.scheduledMorningTime || morningDeparture,
          s.scheduledEveningTime || eveningDeparture,
          s.maxCapacity || 20
        );
      }
    }

    // Audit log
    await DB.prepare(`
      INSERT INTO audit_logs (action, userId, role, details)
      VALUES ('ROUTE_CREATED', ?, 'ADMIN', ?)
    `).run(req.user.id, `Created route: ${name} (ETB ${basePriceEtb})`);

    const createdRoute = await DB.prepare(`SELECT * FROM routes WHERE id = ?`).get(routeId);
    const createdStops = await DB.prepare(`SELECT * FROM route_stops WHERE routeId = ? ORDER BY stopOrder ASC`).all(routeId);

    res.status(201).json({
      success: true,
      message: 'Transit route created successfully',
      route: createdRoute,
      stops: createdStops
    });
  } catch (err) {
    console.error('[RoutePass] request failed:', err);
    res.status(500).json({ success: false, error: 'Internal server error.' });
  }
});

/**
 * PUT /api/routes/:id
 * Update route metadata and optionally replace the ordered stop list.
 */
router.put('/:id', authenticate, requireRole('ADMIN'), async (req, res) => {
  try {
    const current = await DB.prepare(`SELECT * FROM routes WHERE id = ? AND COALESCE(companyId,'')='' AND COALESCE(routeType,'DAILY_COMMUTE')='DAILY_COMMUTE'`).get(req.params.id);
    if (!current) return res.status(404).json({ success: false, error: 'Route not found.' });

    const name = String(req.body.name ?? current.name).trim();
    const nameAm = String(req.body.nameAm ?? current.nameAm).trim();
    if (!name || !nameAm) return res.status(400).json({ success: false, error: 'Route names in English and Amharic are required.' });

    await DB.transaction(async (tx) => {
      // The admin text editor omits IDs. Resolve stable identities before deleting
      // rows so existing passenger pickup/destination references remain valid.
      const stopIds = Array.isArray(req.body.stops) ? await routeStopIdentities(tx, req.params.id, req.body.stops) : null;
      await tx.prepare(`
        UPDATE routes SET name = ?, nameAm = ?, description = ?,
          morningDeparture = ?, eveningDeparture = ?, distanceKm = ?, basePriceEtb = ?, active = ?
        WHERE id = ?
      `).run(
        name,
        nameAm,
        String(req.body.description ?? current.description ?? '').trim(),
        String(req.body.morningDeparture ?? current.morningDeparture ?? '06:30'),
        String(req.body.eveningDeparture ?? current.eveningDeparture ?? '17:30'),
        Number(req.body.distanceKm ?? current.distanceKm ?? 10),
        Number(req.body.basePriceEtb ?? current.basePriceEtb ?? 2500),
        req.body.active === undefined ? current.active : (req.body.active ? true : false),
        req.params.id
      );

      if (Array.isArray(req.body.stops)) {
        await tx.prepare('DELETE FROM route_stops WHERE routeId = ?').run(req.params.id);
        for (const [idx, stop] of req.body.stops.entries()) {
          await tx.prepare(`
            INSERT INTO route_stops
              (id, routeId, stopName, stopNameAm, stopOrder, latitude, longitude, scheduledMorningTime, scheduledEveningTime, maxCapacity)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
          `).run(
            stopIds[idx],
            req.params.id,
            String(stop.stopName || `Stop ${idx + 1}`).trim(),
            String(stop.stopNameAm || `ማቆሚያ ${idx + 1}`).trim(),
            idx + 1,
            Number(stop.latitude ?? (9.01 + idx * 0.005)),
            Number(stop.longitude ?? (38.75 + idx * 0.005)),
            String(stop.scheduledMorningTime || req.body.morningDeparture || current.morningDeparture || '06:30'),
            String(stop.scheduledEveningTime || req.body.eveningDeparture || current.eveningDeparture || '17:30'),
            Number(stop.maxCapacity || 20)
          );
        }
      }

      await tx.prepare('INSERT INTO audit_logs (action, userId, role, details) VALUES (?, ?, ?, ?)')
        .run('ROUTE_UPDATED', req.user.id, 'ADMIN', `Updated route ${req.params.id}: ${name}`);
    });

    const principal = await optionalPrincipal(req);
    if(principal?.role==='COMPANY') return res.status(403).json({success:false,error:'Use your company route portal.'});
    const route = await DB.prepare(`SELECT * FROM routes WHERE id = ? AND ${commuteRoute('')}`).get(req.params.id);
    const stops = await DB.prepare(`SELECT * FROM route_stops WHERE routeId = ? ORDER BY stopOrder ASC`).all(req.params.id);
    res.json({ success: true, route, stops });
  } catch (err) {
    if (err.status === 400) return res.status(400).json({ success: false, error: err.message });
    console.error('[RoutePass] route update failed:', err);
    res.status(500).json({ success: false, error: 'Could not update route.' });
  }
});

/**
 * DELETE /api/routes/:id
 * Admin only: Delete route
 */
router.delete('/:id', authenticate, requireRole('ADMIN'), async (req, res) => {
  try {
    const principal = await optionalPrincipal(req);
    if(principal?.role==='COMPANY') return res.status(403).json({success:false,error:'Use your company route portal.'});
    const route = await DB.prepare(`SELECT * FROM routes WHERE id = ? AND ${commuteRoute('')}`).get(req.params.id);
    if (!route) {
      return res.status(404).json({ success: false, error: 'Route not found' });
    }

    await DB.prepare(`DELETE FROM route_stops WHERE routeId = ?`).run(req.params.id);
    await DB.prepare(`DELETE FROM routes WHERE id = ?`).run(req.params.id);

    await DB.prepare(`
      INSERT INTO audit_logs (action, userId, role, details)
      VALUES ('ROUTE_DELETED', ?, 'ADMIN', ?)
    `).run(req.user.id, `Deleted route: ${route.name} (${req.params.id})`);

    res.json({
      success: true,
      message: `Route '${route.name}' deleted successfully.`
    });
  } catch (err) {
    console.error('[RoutePass] request failed:', err);
    res.status(500).json({ success: false, error: 'Internal server error.' });
  }
});

module.exports = router;
