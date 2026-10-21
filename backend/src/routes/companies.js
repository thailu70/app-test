const express = require('express');
const { DB } = require('../db');
const { isOperationallyEligible } = require('../services/onboarding');

const router = express.Router();

// Public directory is used during driver registration. Portal authentication and
// operations live only in company-portal.js so route order cannot shadow them.
router.get('/', async (_req, res) => {
  try {
    const companies = await DB.prepare("SELECT id, name FROM transport_companies WHERE status = 'ACTIVE' ORDER BY name ASC").all();
    res.setHeader('Cache-Control', 'no-store');
    const eligible = await Promise.all(companies.map(company => isOperationallyEligible(company.id, 'COMPANY')));
    res.json({ success: true, companies: companies.filter((_, index) => eligible[index]) });
  } catch (err) {
    console.error('[Companies] list failed:', err);
    res.status(503).json({ success: false, error: 'Company list unavailable.' });
  }
});

module.exports = router;
