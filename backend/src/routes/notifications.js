const express = require('express');
const router = express.Router();
const crypto = require('crypto');
const { DB } = require('../db');
const { authenticate, requireRole } = require('../middleware/auth');

/**
 * GET /api/notifications
 * Fetch notifications filtered by caller's role
 */
router.get('/', authenticate, async (req, res) => {
  try {
    const role = String(req.user.role || '').toUpperCase();
    let notifications;
    if (role === 'ADMIN') {
      notifications = await DB.prepare('SELECT * FROM notifications ORDER BY timestamp DESC LIMIT 50').all();
    } else if (role === 'PASSENGER') {
      notifications = await DB.prepare("SELECT * FROM notifications WHERE target_user_id = ? OR (target_user_id IS NULL AND targetAudience IN ('ALL', 'PASSENGERS')) ORDER BY timestamp DESC LIMIT 50")
        .all(req.user.id);
    } else if (role === 'DRIVER') {
      notifications = await DB.prepare("SELECT * FROM notifications WHERE target_user_id = ? OR (target_user_id IS NULL AND targetAudience IN ('ALL', 'TRANSPORTERS')) ORDER BY timestamp DESC LIMIT 50")
        .all(req.user.id);
    } else {
      return res.status(403).json({ success: false, error: 'Notifications are not available for this role.' });
    }
    res.json({ success: true, count: notifications.length, notifications });
  } catch (err) {
    console.error('[RoutePass] notification list failed:', err.code || 'NOTIFICATION_LIST_ERROR');
    res.status(500).json({ success: false, error: 'Internal server error.' });
  }
});

/**
 * POST /api/notifications/broadcast
 * Admin / Operator only: Dispatch service broadcast notification
 */
router.post('/broadcast', authenticate, requireRole('ADMIN'), async (req, res) => {
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

    await DB.prepare(`
      INSERT INTO notifications (id, title, message, targetAudience, type, senderName)
      VALUES (?, ?, ?, ?, ?, ?)
    `).run(notifId, title.trim(), message.trim(), finalAudience, type, senderName);

    // Audit log
    await DB.prepare(`
      INSERT INTO audit_logs (action, userId, role, details)
      VALUES ('BROADCAST_SENT', ?, 'ADMIN', ?)
    `).run(req.user.id, `Broadcast [${finalAudience}]: '${title}'`);

    const created = await DB.prepare('SELECT * FROM notifications WHERE id = ?').get(notifId);

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
    console.error('[RoutePass] request failed:', err);
    res.status(500).json({ success: false, error: 'Internal server error.' });
  }
});

module.exports = router;
