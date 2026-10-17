const express = require('express');
const { DB } = require('../db');

const router = express.Router();

// Public directory is used during driver registration. Portal authentication and
// operations live only in company-portal.js so route order cannot shadow them.
router.get('/', async (_req, res) => {
  try {
    const companies = await DB.prepare("SELECT id, name FROM transport_companies WHERE status = 'ACTIVE' ORDER BY name ASC").all();
    res.setHeader('Cache-Control', 'no-store');
    res.json({ success: true, companies });
  } catch (err) {
    console.error('[Companies] list failed:', err);
    res.status(503).json({ success: false, error: 'Company list unavailable.' });
  }
});

module.exports = router;
