const express = require('express');
const { DB } = require('../db');
const router = express.Router();

router.get('/', async (_req, res) => {
  try {
    const companies = await DB.prepare("SELECT id, name FROM transport_companies WHERE status = 'ACTIVE' ORDER BY name ASC").all();
    res.setHeader('Cache-Control', 'no-store');
    res.json({ success: true, companies });
  } catch (err) {
    console.error('[Companies] list failed:', err);
    res.status(503).json({ success: false, error: 'Transport company list is temporarily unavailable.' });
  }
});

module.exports = router;
