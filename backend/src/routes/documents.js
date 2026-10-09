const express = require('express');
const router = express.Router();
const { DB } = require('../db');
const { authenticate, requireRole } = require('../middleware/auth');
const {
  decodeUpload,
  saveUpload,
  latestDataUrl,
  loadDocument,
  readDocumentBytes
} = require('../documents/store');

const ROLE_UPLOAD_TYPES = {
  DRIVER: new Set(['PROFILE_PHOTO', 'DRIVER_LICENSE_DOCUMENT', 'NATIONAL_ID_DOCUMENT', 'VEHICLE_PHOTO', 'VEHICLE_TRADE_LICENSE']),
  PASSENGER: new Set(['PROFILE_PHOTO'])
};
const ACTIVE_SUBSCRIPTION = "s.subscriptionStatus = 'ACTIVE' AND s.paymentStatus = 'PAID' AND s.daysRemaining > 0";

router.get('/roster', authenticate, requireRole('DRIVER', 'PASSENGER'), async (req, res) => {
  try {
    const today = new Date().toISOString().slice(0, 10);
    if (req.user.role === 'DRIVER') {
      const vehicle = await DB.prepare('SELECT * FROM vehicles WHERE driverId = ? ORDER BY updatedAt DESC LIMIT 1').get(req.user.id);
      if (!vehicle || !vehicle.assignedRouteId) {
        return res.json({ success: true, role: 'DRIVER', vehicle: null, people: [], message: 'Your administrator must assign a route and passengers before the roster is available.' });
      }
      const rows = await DB.prepare(`
        SELECT s.id AS subscription_id, s.startDate AS subscription_start, s.endDate AS subscription_end,
               s.morningSchedule AS morning_schedule, s.eveningSchedule AS evening_schedule,
               s.pickupStopId AS pickup_stop_id, s.destinationStopId AS destination_stop_id,
               p.id AS person_id, p.fullName AS person_name, p.role AS person_role,
               (SELECT rs.stopName FROM route_stops rs WHERE rs.id = s.pickupStopId LIMIT 1) AS pickup_stop_name,
               (SELECT rs.stopName FROM route_stops rs WHERE rs.id = s.destinationStopId LIMIT 1) AS destination_stop_name
        FROM subscriptions s
        JOIN users p ON p.id = s.passengerId AND p.role = 'PASSENGER' AND p.status = 'ACTIVE'
        WHERE s.vehicleId = ? AND s.routeId = ?
          AND ${ACTIVE_SUBSCRIPTION} AND s.endDate >= ?
        ORDER BY p.fullName ASC
      `).all(vehicle.id, vehicle.assignedRouteId, today);
      const people = await Promise.all(rows.map(async row => ({
        personId: row.person_id,
        fullName: row.person_name,
        role: 'PASSENGER',
        profilePhotoDataUrl: await latestDataUrl(DB, row.person_id, 'PROFILE_PHOTO'),
        subscriptionId: row.subscription_id,
        subscriptionStart: row.subscription_start,
        subscriptionEnd: row.subscription_end,
        morningSchedule: row.morning_schedule,
        eveningSchedule: row.evening_schedule,
        pickupStopName: row.pickup_stop_name || '',
        destinationStopName: row.destination_stop_name || ''
      })));
      res.setHeader('Cache-Control', 'private, no-store');
      return res.json({
        success: true,
        role: 'DRIVER',
        vehicle: { id: vehicle.id, plateNumber: vehicle.plateNumber, model: vehicle.model, vehicleType: vehicle.vehicleType, routeId: vehicle.assignedRouteId },
        people,
        message: people.length ? 'Passengers with active monthly subscriptions assigned to your vehicle.' : 'No active monthly passenger subscriptions are currently assigned to your vehicle.'
      });
    }

    const assignment = await DB.prepare(`
      SELECT s.id AS subscription_id, s.startDate AS subscription_start, s.endDate AS subscription_end,
             s.morningSchedule AS morning_schedule, s.eveningSchedule AS evening_schedule,
             v.id AS vehicle_id, v.plateNumber AS vehicle_plate, v.model AS vehicle_model,
             v.vehicleType AS vehicle_type, v.driverId AS driver_id, v.assignedRouteId AS route_id,
             d.fullName AS driver_name, d.phone AS driver_phone, d.status AS driver_status
      FROM subscriptions s
      JOIN vehicles v ON v.id = s.vehicleId AND v.assignedRouteId = s.routeId
      JOIN users d ON d.id = v.driverId AND d.role = 'DRIVER' AND d.status = 'ACTIVE'
      WHERE s.passengerId = ? AND ${ACTIVE_SUBSCRIPTION} AND s.endDate >= ?
      ORDER BY s.updatedAt DESC LIMIT 1
    `).get(req.user.id, today);
    if (!assignment) {
      res.setHeader('Cache-Control', 'private, no-store');
      return res.json({ success: true, role: 'PASSENGER', vehicle: null, people: [], message: 'Your driver will appear after your monthly subscription is active and the administrator assigns a vehicle.' });
    }
    const person = {
      personId: assignment.driver_id,
      fullName: assignment.driver_name,
      role: 'DRIVER',
      profilePhotoDataUrl: await latestDataUrl(DB, assignment.driver_id, 'PROFILE_PHOTO'),
      vehiclePhotoDataUrl: await latestDataUrl(DB, assignment.driver_id, 'VEHICLE_PHOTO', assignment.vehicle_id),
      vehicleId: assignment.vehicle_id,
      vehiclePlate: assignment.vehicle_plate,
      vehicleModel: assignment.vehicle_model,
      vehicleType: assignment.vehicle_type,
      subscriptionId: assignment.subscription_id,
      subscriptionStart: assignment.subscription_start,
      subscriptionEnd: assignment.subscription_end,
      morningSchedule: assignment.morning_schedule,
      eveningSchedule: assignment.evening_schedule
    };
    res.setHeader('Cache-Control', 'private, no-store');
    return res.json({
      success: true,
      role: 'PASSENGER',
      vehicle: { id: assignment.vehicle_id, plateNumber: assignment.vehicle_plate, model: assignment.vehicle_model, vehicleType: assignment.vehicle_type, routeId: assignment.route_id },
      people: [person],
      message: 'Your assigned driver for the active monthly subscription.'
    });
  } catch (err) {
    console.error('[Documents] roster query failed:', err);
    return res.status(500).json({ success: false, error: 'Could not load the assigned passenger/driver roster.' });
  }
});

router.post('/upload', authenticate, requireRole('DRIVER', 'PASSENGER'), async (req, res) => {
  try {
    const user = await DB.prepare('SELECT id, role, status FROM users WHERE id = ?').get(req.user.id);
    if (!user || user.status !== 'ACTIVE') return res.status(403).json({ success: false, error: 'Only active accounts can update profile documents.' });
    const file = decodeUpload(req.body);
    if (!ROLE_UPLOAD_TYPES[user.role] || !ROLE_UPLOAD_TYPES[user.role].has(file.documentType)) {
      return res.status(403).json({ success: false, error: 'Your account role is not allowed to upload that document type.' });
    }
    let vehicleId = null;
    if (['VEHICLE_PHOTO', 'VEHICLE_TRADE_LICENSE'].includes(file.documentType)) {
      const vehicle = await DB.prepare('SELECT id FROM vehicles WHERE driverId = ? LIMIT 1').get(user.id);
      if (!vehicle) return res.status(409).json({ success: false, error: 'No vehicle is registered to this driver account.' });
      vehicleId = vehicle.id;
    }
    const saved = await saveUpload(DB, user.id, vehicleId, file);
    await DB.prepare('INSERT INTO audit_logs (action, userId, role, details) VALUES (?, ?, ?, ?)')
      .run('DOCUMENT_UPLOADED', user.id, user.role, 'Uploaded ' + file.documentType + (vehicleId ? ' for owned vehicle ' + vehicleId : ''));
    res.status(201).json({ success: true, document: saved, message: 'Document securely uploaded.' });
  } catch (err) {
    const status = /upload|file|document|mime|content|smaller|combined|type/i.test(err.message || '') ? 400 : 500;
    if (status === 500) console.error('[Documents] upload failed:', err);
    res.status(status).json({ success: false, error: status === 400 ? err.message : 'Could not upload document.' });
  }
});

async function mayReadDocument(req, row) {
  if (req.user.role === 'ADMIN') return true;
  if (row.owner_user_id === req.user.id) return true;
  if (row.vehicle_driver_id === req.user.id && row.vehicle_id) return true;

  const shareableTypes = new Set(['PROFILE_PHOTO', 'VEHICLE_PHOTO']);
  if (!shareableTypes.has(row.document_type)) return false;
  const today = new Date().toISOString().slice(0, 10);
  if (req.user.role === 'PASSENGER') {
    const assignedVehicleId = row.vehicle_id || (row.owner_role === 'DRIVER'
      ? (await DB.prepare('SELECT id FROM vehicles WHERE driverId = ? LIMIT 1').get(row.owner_user_id))?.id
      : null);
    if (!assignedVehicleId) return false;
    const relation = await DB.prepare(`
      SELECT s.id FROM subscriptions s
      WHERE s.passengerId = ? AND s.vehicleId = ?
        AND ${ACTIVE_SUBSCRIPTION} AND s.endDate >= ? LIMIT 1
    `).get(req.user.id, assignedVehicleId, today);
    return Boolean(relation);
  }
  if (req.user.role === 'DRIVER' && row.owner_role === 'PASSENGER') {
    const vehicle = await DB.prepare('SELECT id FROM vehicles WHERE driverId = ? LIMIT 1').get(req.user.id);
    if (!vehicle) return false;
    const relation = await DB.prepare(`
      SELECT s.id FROM subscriptions s
      WHERE s.passengerId = ? AND s.vehicleId = ?
        AND ${ACTIVE_SUBSCRIPTION} AND s.endDate >= ? LIMIT 1
    `).get(row.owner_user_id, vehicle.id, today);
    return Boolean(relation);
  }
  return false;
}

router.get('/:id/content', authenticate, async (req, res) => {
  try {
    const row = await loadDocument(DB, req.params.id);
    if (!row || !(await mayReadDocument(req, row))) {
      return res.status(404).json({ success: false, error: 'Document not found.' });
    }
    const bytes = await readDocumentBytes(row);
    res.setHeader('Content-Type', row.mime_type);
    res.setHeader('Content-Length', bytes.length);
    res.setHeader('Content-Disposition', "inline; filename*=UTF-8''" + encodeURIComponent(row.original_name || 'document'));
    res.setHeader('X-Content-Type-Options', 'nosniff');
    res.setHeader('Cache-Control', 'private, no-store');
    if (row.mime_type === 'application/pdf') res.setHeader('Content-Security-Policy', "default-src 'none'; sandbox");
    return res.send(bytes);
  } catch (err) {
    console.error('[Documents] document read failed:', err);
    return res.status(500).json({ success: false, error: 'Could not open document.' });
  }
});

module.exports = router;
