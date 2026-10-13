const express = require('express');
const crypto = require('crypto');
const { DB } = require('../db');
const { authenticate, requireRole } = require('../middleware/auth');

const router = express.Router();

/**
 * GET /api/companies
 * Public list used by City Travel / Intercity driver registration.
 * Only enabled companies are exposed to registration clients.
 */
router.get('/', async (_req, res) => {
  try {
    const companies = await DB.prepare(
      "SELECT id, name FROM transport_companies WHERE status = 'ACTIVE' ORDER BY name ASC"
    ).all();
    res.setHeader('Cache-Control', 'no-store');
    res.json({ success: true, companies });
  } catch (err) {
    console.error('[Companies] list failed:', err);
    res.status(503).json({ success: false, error: 'Transport company list is temporarily unavailable.' });
  }
});

/**
 * Admin-only management. Company records are separate from individual drivers,
 * so the registration dropdown does not depend on another driver registering first.
 */
router.get('/admin', authenticate, requireRole('ADMIN'), async (_req, res) => {
  try {
    const companies = await DB.prepare(
      'SELECT id, name, status, created_at AS "createdAt" FROM transport_companies ORDER BY name ASC'
    ).all();
    res.json({ success: true, companies });
  } catch (err) {
    console.error('[Companies] admin list failed:', err);
    res.status(500).json({ success: false, error: 'Could not load transport companies.' });
  }
});

router.post('/admin', authenticate, requireRole('ADMIN'), async (req, res) => {
  try {
    const name = String(req.body.name || '').trim().replace(/\s+/g, ' ').slice(0, 120);
    if (name.length < 2) {
      return res.status(400).json({ success: false, error: 'Company name must contain at least two characters.' });
    }
    const existing = await DB.prepare('SELECT id, name, status FROM transport_companies WHERE lower(name) = lower(?)').get(name);
    if (existing) {
      return res.status(409).json({ success: false, error: 'A transport company with this name already exists.' });
    }
    const company = {
      id: 'company_' + crypto.randomUUID().replace(/-/g, '').slice(0, 16),
      name,
      status: 'ACTIVE'
    };
    await DB.prepare('INSERT INTO transport_companies (id, name, status) VALUES (?, ?, ?)').run(company.id, company.name, company.status);
    await DB.prepare('INSERT INTO audit_logs (action, userId, role, details) VALUES (?, ?, ?, ?)')
      .run('TRANSPORT_COMPANY_CREATED', req.user.id, 'ADMIN', 'Created transport company: ' + name);
    res.status(201).json({ success: true, company });
  } catch (err) {
    console.error('[Companies] create failed:', err);
    res.status(500).json({ success: false, error: 'Could not create transport company.' });
  }
});

router.patch('/admin/:id/status', authenticate, requireRole('ADMIN'), async (req, res) => {
  try {
    const status = String(req.body.status || '').trim().toUpperCase();
    if (!['ACTIVE', 'INACTIVE'].includes(status)) {
      return res.status(400).json({ success: false, error: 'Status must be ACTIVE or INACTIVE.' });
    }
    const company = await DB.prepare('SELECT id, name FROM transport_companies WHERE id = ?').get(req.params.id);
    if (!company) return res.status(404).json({ success: false, error: 'Transport company not found.' });
    await DB.prepare('UPDATE transport_companies SET status = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?').run(status, company.id);
    await DB.prepare('INSERT INTO audit_logs (action, userId, role, details) VALUES (?, ?, ?, ?)')
      .run('TRANSPORT_COMPANY_STATUS_CHANGED', req.user.id, 'ADMIN', status + ' company: ' + company.name);
    res.json({ success: true, company: { ...company, status } });
  } catch (err) {
    console.error('[Companies] status update failed:', err);
    res.status(500).json({ success: false, error: 'Could not update transport company.' });
  }
});

module.exports = router;
