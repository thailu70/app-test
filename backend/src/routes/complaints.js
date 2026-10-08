const express = require('express');
const router = express.Router();
const crypto = require('crypto');
const { DB } = require('../db');
const { authenticate, requireRole } = require('../middleware/auth');

/**
 * POST /api/complaints
 * Commuters file service feedback or complaint
 */
router.post('/', authenticate, requireRole('PASSENGER'), (req, res) => {
  try {
    const passengerId = req.user.id;
    const { category, description } = req.body;

    if (!category || !description) {
      return res.status(400).json({ success: false, error: 'Category and description are required.' });
    }

    const user = DB.prepare('SELECT fullName FROM users WHERE id = ?').get(passengerId);
    const complaintId = `cmp_${crypto.randomUUID().slice(0, 8)}`;

    DB.prepare(`
      INSERT INTO complaints (id, passengerId, passengerName, category, description, status)
      VALUES (?, ?, ?, ?, ?, 'OPEN')
    `).run(complaintId, passengerId, user?.fullName || 'Passenger', category, description);

    res.status(201).json({
      success: true,
      message: 'Complaint submitted successfully.',
      complaintId
    });
  } catch (err) {
    res.status(500).json({ success: false, error: err.message });
  }
});

module.exports = router;
