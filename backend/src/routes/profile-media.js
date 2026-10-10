const express = require('express');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const { DB } = require('../db');
const { authenticate } = require('../middleware/auth');

const router = express.Router();
const DATA_DIR = process.env.DATA_DIR || path.join(__dirname, '..', '..', 'data');
const PRIVATE_MEDIA_DIR = process.env.ROUTEPASS_PRIVATE_MEDIA_DIR || path.join(DATA_DIR, 'private-media');
const MAX_BYTES = 5 * 1024 * 1024;
const ALLOWED_TYPES = {
  'image/jpeg': '.jpg',
  'image/png': '.png',
  'image/webp': '.webp',
  'application/pdf': '.pdf'
};
const IMAGE_TYPES = new Set(['image/jpeg', 'image/png', 'image/webp']);
const DRIVER_TYPES = new Set(['DRIVER_LICENSE', 'VEHICLE_PHOTO', 'TRADE_LICENSE']);
const PASSENGER_TYPES = new Set(['PROFILE_PHOTO']);

const schemaReady = DB.prepare(`
  CREATE TABLE IF NOT EXISTS profile_media (
    id TEXT PRIMARY KEY,
    owner_id TEXT NOT NULL,
    asset_type TEXT NOT NULL,
    stored_name TEXT NOT NULL UNIQUE,
    content_type TEXT NOT NULL,
    original_name TEXT NOT NULL,
    byte_size INTEGER NOT NULL,
    created_at TEXT DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(owner_id, asset_type)
  )
`).run();

function validMagic(bytes, type) {
  if (type === 'image/jpeg') return bytes.length >= 3 && bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff;
  if (type === 'image/png') return bytes.length >= 8 && bytes.subarray(0, 8).equals(Buffer.from([137,80,78,71,13,10,26,10]));
  if (type === 'image/webp') return bytes.length >= 12 && bytes.toString('ascii', 0, 4) === 'RIFF' && bytes.toString('ascii', 8, 12) === 'WEBP';
  if (type === 'application/pdf') return bytes.length >= 5 && bytes.toString('ascii', 0, 5) === '%PDF-';
  return false;
}

async function canReadMedia(user, row) {
  if (user.role === 'ADMIN' || row.owner_id === user.id) return true;
  if (user.role !== 'PASSENGER') return false;
  if (row.asset_type !== 'PROFILE_PHOTO') {
    const assigned = await DB.prepare(`
      SELECT 1 FROM subscriptions s
      JOIN vehicles v ON v.id = s.vehicleId
      WHERE s.passengerId = ? AND v.driverId = ? AND s.subscriptionStatus = 'ACTIVE'
      LIMIT 1
    `).get(user.id, row.owner_id);
    if (assigned) return true;
  }
  if (row.asset_type === 'PROFILE_PHOTO') {
    const assigned = await DB.prepare(`
      SELECT 1 FROM subscriptions s
      JOIN vehicles v ON v.id = s.vehicleId
      WHERE s.passengerId = ? AND v.driverId = ? AND s.subscriptionStatus = 'ACTIVE'
      LIMIT 1
    `).get(user.id, row.owner_id);
    return Boolean(assigned);
  }
  return false;
}

// JSON base64 is used to avoid a new multipart dependency. Files are private by default.
router.post('/upload', authenticate, async (req, res) => {
  try {
    await schemaReady;
    const assetType = String(req.body?.assetType || '').trim().toUpperCase();
    const contentType = String(req.body?.contentType || '').trim().toLowerCase();
    const originalName = String(req.body?.fileName || 'upload').replace(/[\\/\r\n]/g, '_').slice(0, 120);
    const encoded = req.body?.dataBase64;
    if (typeof encoded !== 'string' || !encoded || !ALLOWED_TYPES[contentType]) {
      return res.status(400).json({ success: false, error: 'Provide a supported file and content type.' });
    }
    const allowed = req.user.role === 'PASSENGER'
      ? PASSENGER_TYPES.has(assetType) && IMAGE_TYPES.has(contentType)
      : req.user.role === 'DRIVER'
        ? DRIVER_TYPES.has(assetType) && (assetType === 'VEHICLE_PHOTO' ? IMAGE_TYPES.has(contentType) : true)
        : false;
    if (!allowed) return res.status(403).json({ success: false, error: 'This role cannot upload that document type.' });

    const buffer = Buffer.from(encoded, 'base64');
    if (!buffer.length || buffer.length > MAX_BYTES || !validMagic(buffer, contentType)) {
      return res.status(400).json({ success: false, error: 'File is invalid or exceeds the 5 MB limit.' });
    }
    const id = crypto.randomUUID();
    const storedName = id + ALLOWED_TYPES[contentType];
    await fs.promises.mkdir(PRIVATE_MEDIA_DIR, { recursive: true, mode: 0o700 });
    const fullPath = path.join(PRIVATE_MEDIA_DIR, storedName);
    await fs.promises.writeFile(fullPath, buffer, { flag: 'wx', mode: 0o600 });
    try {
      await DB.prepare(`
        INSERT INTO profile_media (id, owner_id, asset_type, stored_name, content_type, original_name, byte_size)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(owner_id, asset_type) DO UPDATE SET
          id = excluded.id, stored_name = excluded.stored_name, content_type = excluded.content_type,
          original_name = excluded.original_name, byte_size = excluded.byte_size, created_at = CURRENT_TIMESTAMP
      `).run(id, req.user.id, assetType, storedName, contentType, originalName, buffer.length);
    } catch (err) {
      await fs.promises.unlink(fullPath).catch(() => {});
      throw err;
    }
    return res.status(201).json({ success: true, id, assetType, contentType, byteSize: buffer.length });
  } catch (err) {
    console.error('[Profile media] upload failed:', err);
    return res.status(500).json({ success: false, error: 'Could not store this file.' });
  }
});

router.get('/:ownerId/:assetType', authenticate, async (req, res) => {
  try {
    await schemaReady;
    const assetType = String(req.params.assetType || '').toUpperCase();
    const row = await DB.prepare('SELECT * FROM profile_media WHERE owner_id = ? AND asset_type = ?')
      .get(req.params.ownerId, assetType);
    if (!row) return res.status(404).json({ success: false, error: 'File not found.' });
    if (!await canReadMedia(req.user, row)) return res.status(403).json({ success: false, error: 'Not authorized to view this file.' });
    const fullPath = path.join(PRIVATE_MEDIA_DIR, path.basename(row.stored_name));
    res.setHeader('Content-Type', row.content_type);
    res.setHeader('Content-Length', String(row.byte_size));
    res.setHeader('Cache-Control', 'private, no-store');
    res.setHeader('X-Content-Type-Options', 'nosniff');
    return res.sendFile(fullPath);
  } catch (err) {
    console.error('[Profile media] read failed:', err);
    return res.status(500).json({ success: false, error: 'Could not retrieve this file.' });
  }
});

module.exports = router;
