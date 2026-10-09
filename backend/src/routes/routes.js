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
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1)
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
      stops.forEach((s, idx) => {
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
      });
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
