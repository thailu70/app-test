'use strict';

const express = require('express');
const { DB } = require('../db');
const { authenticate, requireRole } = require('../middleware/auth');
const { listVehicleTypes, getGlobalGpsRadius, getGpsSharing, GPS_RADIUS_KEY, GPS_SHARING_KEY } = require('../services/master-data');
const router = express.Router();
const admin = [authenticate, requireRole('ADMIN')];
const fail = (status, message) => Object.assign(new Error(message), { status });
const route = handler => (req, res, next) => Promise.resolve(handler(req, res)).catch(next);
const boolean = value => value === true || value === 1 || value === 'true';
function objectBody(req, _res, next) {
  if (!req.body || typeof req.body !== 'object' || Array.isArray(req.body)) return next(fail(400, 'Provide a JSON object with the settings to update.'));
  next();
}

function code(value) {
  const result = typeof value === 'string' ? value.trim().toUpperCase() : '';
  if (!/^[A-Z][A-Z0-9_]{1,29}$/.test(result)) throw fail(400, 'Vehicle type code must use 2–30 uppercase letters, numbers or underscores and start with a letter.');
  return result;
}
function name(value) {
  const result = typeof value === 'string' ? value.trim() : '';
  if (result.length < 2 || result.length > 80 || /[\x00-\x1f\x7f]/.test(result)) throw fail(400, 'Vehicle type name must contain 2–80 characters.');
  return result;
}
function integer(value, minimum, maximum, label) {
  if (typeof value !== 'number' || !Number.isInteger(value) || value < minimum || value > maximum)
    throw fail(400, `${label} must be a whole number from ${minimum} to ${maximum}.`);
  return value;
}
async function saveSetting(key, value, req, auditAction) {
  await DB.transaction(async tx => {
    await tx.prepare('INSERT INTO app_settings(key,value,updated_at) VALUES(?,?,CURRENT_TIMESTAMP) ON CONFLICT(key) DO UPDATE SET value=excluded.value,updated_at=CURRENT_TIMESTAMP')
      .run(key, String(value));
    await tx.prepare('INSERT INTO audit_logs(action,userId,role,details) VALUES(?,?,?,?)')
      .run(auditAction, req.user.id, 'ADMIN', JSON.stringify({ [key]: value }));
  });
}

// Catalog values contain no personal data; companies and signup clients need the same choices.
router.get('/vehicle-types', route(async (_req, res) => {
  res.set('Cache-Control', 'no-store');
  res.json({ success: true, vehicleTypes: await listVehicleTypes() });
}));
router.post('/vehicle-types', ...admin, objectBody, route(async (req, res) => {
  const type = { code: code(req.body.code), name: name(req.body.name), capacity: integer(req.body.capacity, 1, 100, 'Seat capacity'), active: req.body.active ?? true };
  if (typeof type.active !== 'boolean') throw fail(400, 'Active must be true or false.');
  if (await DB.prepare('SELECT code FROM vehicle_types WHERE code=?').get(type.code)) throw fail(409, 'That vehicle type code already exists.');
  await DB.transaction(async tx => {
    await tx.prepare('INSERT INTO vehicle_types(code,name,capacity,active) VALUES(?,?,?,?)').run(type.code, type.name, type.capacity, tx.isPostgres ? type.active : Number(type.active));
    await tx.prepare('INSERT INTO audit_logs(action,userId,role,details) VALUES(?,?,?,?)').run('VEHICLE_TYPE_CREATED', req.user.id, 'ADMIN', JSON.stringify(type));
  });
  res.status(201).json({ success: true, vehicleType: type });
}));
router.patch('/vehicle-types/:code', ...admin, objectBody, route(async (req, res) => {
  const key = code(req.params.code);
  if (req.body.code !== undefined && code(req.body.code) !== key) throw fail(400, 'Vehicle type code cannot be changed.');
  if (!['name', 'capacity', 'active'].some(key => Object.prototype.hasOwnProperty.call(req.body, key))) throw fail(400, 'Choose a vehicle type setting to update.');
  const current = await DB.prepare('SELECT code,name,capacity,active FROM vehicle_types WHERE code=?').get(key);
  if (!current) throw fail(404, 'Vehicle type not found.');
  const type = { code: key, name: req.body.name === undefined ? current.name : name(req.body.name),
    capacity: req.body.capacity === undefined ? Number(current.capacity) : integer(req.body.capacity, 1, 100, 'Seat capacity'),
    active: req.body.active === undefined ? boolean(current.active) : req.body.active };
  if (typeof type.active !== 'boolean') throw fail(400, 'Active must be true or false.');
  await DB.transaction(async tx => {
    await tx.prepare('UPDATE vehicle_types SET name=?,capacity=?,active=?,updated_at=CURRENT_TIMESTAMP WHERE code=?')
      .run(type.name, type.capacity, tx.isPostgres ? type.active : Number(type.active), key);
    await tx.prepare('INSERT INTO audit_logs(action,userId,role,details) VALUES(?,?,?,?)').run('VEHICLE_TYPE_EDITED', req.user.id, 'ADMIN', JSON.stringify(type));
  });
  res.json({ success: true, vehicleType: type });
}));
router.get('/gps-radius', ...admin, route(async (_req, res) => {
  res.set('Cache-Control', 'no-store');
  res.json({ success: true, ...await getGlobalGpsRadius() });
}));
router.put('/gps-radius', ...admin, objectBody, route(async (req, res) => {
  const radiusMeters = integer(req.body.radiusMeters, 25, 5000, 'GPS boarding radius');
  await saveSetting(GPS_RADIUS_KEY, radiusMeters, req, 'GPS_RADIUS_UPDATED');
  res.json({ success: true, radiusMeters, scope: 'ALL_VEHICLES', overrideEnabled: true });
}));
router.get('/gps-sharing', authenticate, route(async (_req, res) => {
  res.set('Cache-Control', 'no-store');
  res.json({ success: true, ...await getGpsSharing() });
}));
router.put('/gps-sharing', ...admin, objectBody, route(async (req, res) => {
  const intervalSeconds = integer(req.body.intervalSeconds, 1, 60, 'GPS sharing interval');
  await saveSetting(GPS_SHARING_KEY, intervalSeconds, req, 'GPS_SHARING_UPDATED');
  res.json({ success: true, intervalSeconds });
}));
router.use((error, _req, res, _next) => {
  const duplicate = error?.code === '23505' || /UNIQUE constraint failed: vehicle_types.code/i.test(String(error?.message));
  const status = duplicate ? 409 : error.status || 500;
  if (status === 500) console.error('[Master data]', error);
  res.status(status).json({ success: false, error: duplicate ? 'That vehicle type code already exists.' : status === 500 ? 'Could not update master data.' : error.message });
});

module.exports = router;
