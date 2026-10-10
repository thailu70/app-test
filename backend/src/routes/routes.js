const express = require('express');
const router = express.Router();
const crypto = require('crypto');
const { DB } = require('../db');
const { authenticate, requireRole } = require('../middleware/auth');

/**
 * GET /api/routes
 * List all active transit lines
 */
router.get('/', async (req, res) => {
  try {
    const routes = await DB.prepare('SELECT * FROM routes WHERE active = TRUE ORDER BY name ASC').all();
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
    const route = await DB.prepare('SELECT * FROM routes WHERE id = ?').get(req.params.id);
    if (!route) {
      return res.status(404).json({ success: false, error: 'Route not found' });
    }

    const stops = await DB.prepare('SELECT * FROM route_stops WHERE routeId = ? ORDER BY stopOrder ASC').all(req.params.id);

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
      serviceType = 'TWO_WAY',
      stops = []
    } = req.body;

    if (!['ONE_WAY', 'TWO_WAY'].includes(String(serviceType).toUpperCase())) {
      return res.status(400).json({ success: false, error: 'serviceType must be ONE_WAY or TWO_WAY.' });
    }

    if (!name || !nameAm) {
      return res.status(400).json({
        success: false,
        error: 'Route name in English and Amharic are required.'
      });
    }

    if (!Array.isArray(stops) || stops.length !== 2 ||
        !String(stops[0]?.stopName || '').trim() || !String(stops[1]?.stopName || '').trim() ||
        String(stops[0].stopName).trim().toLowerCase() === String(stops[1].stopName).trim().toLowerCase()) {
      return res.status(400).json({
        success: false,
        error: 'A route must have exactly two different endpoints: one departure point and one destination point.'
      });
    }

    const routeId = `route_${crypto.randomUUID().slice(0, 8)}`;

    await DB.prepare(`
      INSERT INTO routes (id, name, nameAm, description, morningDeparture, eveningDeparture, distanceKm, basePriceEtb, serviceType, active)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, TRUE)
    `).run(
      routeId,
      name.trim(),
      nameAm.trim(),
      description.trim(),
      morningDeparture,
      eveningDeparture,
      parseFloat(distanceKm) || 10.0,
      parseFloat(basePriceEtb) || 2500.0,
      String(serviceType).toUpperCase()
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

    const createdRoute = await DB.prepare('SELECT * FROM routes WHERE id = ?').get(routeId);
    const createdStops = await DB.prepare('SELECT * FROM route_stops WHERE routeId = ? ORDER BY stopOrder ASC').all(routeId);

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
    const current = await DB.prepare('SELECT * FROM routes WHERE id = ?').get(req.params.id);
    if (!current) return res.status(404).json({ success: false, error: 'Route not found.' });

    const name = String(req.body.name ?? current.name).trim();
    const nameAm = String(req.body.nameAm ?? current.nameAm).trim();
    if (!name || !nameAm) return res.status(400).json({ success: false, error: 'Route names in English and Amharic are required.' });
    if (req.body.stops !== undefined && (
      !Array.isArray(req.body.stops) || req.body.stops.length !== 2 ||
      !String(req.body.stops[0]?.stopName || '').trim() ||
      !String(req.body.stops[1]?.stopName || '').trim() ||
      String(req.body.stops[0].stopName).trim().toLowerCase() === String(req.body.stops[1].stopName).trim().toLowerCase()
    )) {
      return res.status(400).json({ success: false, error: 'A route must have exactly two different endpoints: one departure point and one destination point.' });
    }
    const serviceType = String(req.body.serviceType ?? current.serviceType ?? 'TWO_WAY').toUpperCase();
    if (!['ONE_WAY', 'TWO_WAY'].includes(serviceType)) return res.status(400).json({ success: false, error: 'serviceType must be ONE_WAY or TWO_WAY.' });

    await DB.transaction(async (tx) => {
      await tx.prepare(`
        UPDATE routes SET name = ?, nameAm = ?, description = ?,
          morningDeparture = ?, eveningDeparture = ?, distanceKm = ?, basePriceEtb = ?, serviceType = ?, active = ?
        WHERE id = ?
      `).run(
        name,
        nameAm,
        String(req.body.description ?? current.description ?? '').trim(),
        String(req.body.morningDeparture ?? current.morningDeparture ?? '06:30'),
        String(req.body.eveningDeparture ?? current.eveningDeparture ?? '17:30'),
        Number(req.body.distanceKm ?? current.distanceKm ?? 10),
        Number(req.body.basePriceEtb ?? current.basePriceEtb ?? 2500),
        serviceType,
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
            String(stop.id || `stop_${crypto.randomUUID().slice(0, 8)}`),
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

    const route = await DB.prepare('SELECT * FROM routes WHERE id = ?').get(req.params.id);
    const stops = await DB.prepare('SELECT * FROM route_stops WHERE routeId = ? ORDER BY stopOrder ASC').all(req.params.id);
    res.json({ success: true, route, stops });
  } catch (err) {
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
    const route = await DB.prepare('SELECT * FROM routes WHERE id = ?').get(req.params.id);
    if (!route) {
      return res.status(404).json({ success: false, error: 'Route not found' });
    }

    await DB.prepare('DELETE FROM route_stops WHERE routeId = ?').run(req.params.id);
    await DB.prepare('DELETE FROM routes WHERE id = ?').run(req.params.id);

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
