const express = require('express');
const router = express.Router();
const crypto = require('crypto');
const { DB } = require('../db');
const { authenticate, requireRole } = require('../middleware/auth');

/**
 * GET /api/notifications
 * Fetch notifications filtered by caller's role
 */
router.get('/', authenticate, (req, res) => {
  try {
    const role = req.user.role;
    let audienceCondition = "targetAudience = 'ALL'";

    if (role === 'PASSENGER') {
      audienceCondition = "(targetAudience = 'ALL' OR targetAudience = 'PASSENGERS')";
    } else if (role === 'DRIVER') {
      audienceCondition = "(targetAudience = 'ALL' OR targetAudience = 'TRANSPORTERS')";
    } else if (role === 'ADMIN') {
      audienceCondition = '1=1'; // Admins see all
    }

    const notifications = DB.prepare(`
      SELECT * FROM notifications WHERE ${audienceCondition} ORDER BY timestamp DESC LIMIT 50
    `).all();

    res.json({
      success: true,
      count: notifications.length,
      notifications
    });
  } catch (err) {
    res.status(500).json({ success: false, error: err.message });
  }
});

/**
 * POST /api/notifications/broadcast
 * Admin / Operator only: Dispatch service broadcast notification
 */
router.post('/broadcast', authenticate, requireRole('ADMIN'), (req, res) => {
  try {
    const {
      title,
      message,
      targetAudience = 'ALL',
      type = 'ALERT',
      senderName = 'Transport Operations Center'
    } = req.body;

    if (!title || !message) {
      return res.status(400).json({
        success: false,
        error: 'Notification title and message are required.'
      });
    }

    const validAudiences = ['ALL', 'PASSENGERS', 'TRANSPORTERS'];
    const finalAudience = validAudiences.includes(targetAudience.toUpperCase()) ? targetAudience.toUpperCase() : 'ALL';

    const notifId = `notif_${crypto.randomUUID().slice(0, 8)}`;

    DB.prepare(`
      INSERT INTO notifications (id, title, message, targetAudience, type, senderName)
      VALUES (?, ?, ?, ?, ?, ?)
    `).run(notifId, title.trim(), message.trim(), finalAudience, type, senderName);

    // Audit log
    DB.prepare(`
      INSERT INTO audit_logs (action, userId, role, details)
      VALUES ('BROADCAST_SENT', ?, 'ADMIN', ?)
    `).run(req.user.id, `Broadcast [${finalAudience}]: '${title}'`);

    const created = DB.prepare('SELECT * FROM notifications WHERE id = ?').get(notifId);

    // Broadcast via WebSockets
    if (req.app.locals.broadcastWs) {
      req.app.locals.broadcastWs({
        type: 'NOTIFICATION_BROADCAST',
        notification: created
      });
    }

    res.status(201).json({
      success: true,
      message: `Notification broadcast dispatched to ${finalAudience}.`,
      notification: created
    });
  } catch (err) {
    res.status(500).json({ success: false, error: err.message });
  }
});

module.exports = router;
