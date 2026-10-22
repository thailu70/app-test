const express = require('express');
const crypto = require('crypto');
const fs = require('fs/promises');
const path = require('path');
const { DB } = require('../db');
const { authenticate, requireRole } = require('../middleware/auth');
const { initProfileSchema } = require('../services/profile-schema');
const { passengerAssignment } = require('../services/passenger-assignment');
const { invalidateOnboarding } = require('../services/onboarding');

const router = express.Router();
const MAX_DOCUMENT_BYTES = 5 * 1024 * 1024;
const uploadDirectory = path.resolve(process.env.UPLOAD_DIR || path.join(process.env.DATA_DIR || path.join(__dirname, '..', '..', 'data'), 'uploads'), 'profiles');
const requirements = { DRIVER: ['LICENSE', 'VEHICLE_REGISTRATION', 'INSURANCE', 'PHOTO'], PASSENGER: ['PHOTO', 'ID'] };
const companyId = user => String(user.companyId || user.company_id || '');

function fail(res, status, error) { return res.status(status).json({ success: false, error }); }
const asyncRoute = fn => (req, res, next) => Promise.resolve(fn(req, res, next)).catch(next);

router.use(authenticate);
router.use(asyncRoute(async (req, res, next) => { await initProfileSchema(); next(); }));

async function profileFor(user) {
  const details = await DB.prepare('SELECT * FROM user_profiles WHERE user_id = ?').get(user.id) || {};
  const documents = await DB.prepare('SELECT kind, content_type, byte_size, uploaded_at FROM user_profile_documents WHERE user_id = ? ORDER BY kind').all(user.id);
  const required = requirements[user.role] || [];
  const missing = required.filter(kind => !documents.some(doc => doc.kind === kind));
  if (user.role === 'DRIVER') {
    if (!details.emergency_contact_name) missing.push('EMERGENCY_CONTACT_NAME');
    if (!details.emergency_contact_phone) missing.push('EMERGENCY_CONTACT_PHONE');
    if (!details.vehicle_color) missing.push('VEHICLE_COLOR');
  }
  return {
    userId: user.id, role: user.role, required, missing, complete: missing.length === 0,
    emergencyContactName: details.emergency_contact_name || '',
    emergencyContactPhone: details.emergency_contact_phone || '',
    vehicleColor: details.vehicle_color || '',
    photoUrl: documents.some(doc => doc.kind === 'PHOTO') ? `/api/profiles/${encodeURIComponent(user.id)}/photo` : null,
    documents: documents.map(doc => ({
      kind: doc.kind, contentType: doc.content_type, size: doc.byte_size,
      uploadedAt: doc.uploaded_at, url: `/api/profiles/me/documents/${doc.kind}`
    }))
  };
}

router.get('/me', requireRole('DRIVER', 'PASSENGER'), asyncRoute(async (req, res) => {
  res.json({ success: true, profile: await profileFor(req.user) });
}));

router.patch('/me', requireRole('DRIVER'), asyncRoute(async (req, res) => {
  const input = req.body || {};
  if (Object.keys(input).some(key => !['emergencyContactName', 'emergencyContactPhone', 'vehicleColor'].includes(key))) {
    return fail(res, 400, 'Only emergency contact name, emergency contact phone and vehicle color may be changed here.');
  }
  for (const [key, value] of Object.entries(input)) {
    if (typeof value !== 'string' || /[\u0000-\u001f]/.test(value)) return fail(res, 400, `Invalid ${key}.`);
  }
  if (input.emergencyContactName !== undefined && (input.emergencyContactName.trim().length < 2 || input.emergencyContactName.length > 100)) return fail(res, 400, 'Enter an emergency contact name between 2 and 100 characters.');
  if (input.emergencyContactPhone !== undefined && !/^\+?[0-9 ()-]{7,24}$/.test(input.emergencyContactPhone.trim())) return fail(res, 400, 'Enter a valid emergency contact phone number.');
  if (input.vehicleColor !== undefined && (input.vehicleColor.trim().length < 2 || input.vehicleColor.length > 40)) return fail(res, 400, 'Enter a vehicle color between 2 and 40 characters.');
  const current = await DB.prepare('SELECT * FROM user_profiles WHERE user_id = ?').get(req.user.id) || {};
  await DB.transaction(async tx => {
  const lockedUser = await tx.prepare(tx.isPostgres ? 'SELECT id,status FROM users WHERE id=? FOR UPDATE' : 'SELECT id,status FROM users WHERE id=?').get(req.user.id);
  if (!lockedUser || lockedUser.status !== 'ACTIVE' || await require('../services/account-deletion').isAccountClosed(req.user.id, req.user.role, tx)) {
    const error = new Error('This account is no longer available.'); error.status = 401; throw error;
  }
  await tx.prepare(`INSERT INTO user_profiles (user_id, emergency_contact_name, emergency_contact_phone, vehicle_color, updated_at)
    VALUES (?, ?, ?, ?, ?) ON CONFLICT (user_id) DO UPDATE SET
    emergency_contact_name = excluded.emergency_contact_name, emergency_contact_phone = excluded.emergency_contact_phone,
    vehicle_color = excluded.vehicle_color, updated_at = excluded.updated_at`).run(
    req.user.id, (input.emergencyContactName === undefined ? current.emergency_contact_name || '' : input.emergencyContactName.trim()),
    (input.emergencyContactPhone === undefined ? current.emergency_contact_phone || '' : input.emergencyContactPhone.trim()),
    (input.vehicleColor === undefined ? current.vehicle_color || '' : input.vehicleColor.trim()), new Date().toISOString());
  const changed = (input.emergencyContactName !== undefined && input.emergencyContactName.trim() !== (current.emergency_contact_name || '')) ||
    (input.emergencyContactPhone !== undefined && input.emergencyContactPhone.trim() !== (current.emergency_contact_phone || '')) ||
    (input.vehicleColor !== undefined && input.vehicleColor.trim() !== (current.vehicle_color || ''));
  if (changed) await invalidateOnboarding(req.user.id, req.user.role, tx);
  });
  res.json({ success: true, profile: await profileFor(req.user) });
}));

function detectType(bytes) {
  if (bytes.length >= 32 && bytes.subarray(0, 8).equals(Buffer.from([137,80,78,71,13,10,26,10])) && bytes.subarray(12, 16).toString() === 'IHDR') {
    const width = bytes.readUInt32BE(16), height = bytes.readUInt32BE(20);
    if (width > 0 && height > 0 && width * height <= 24000000) return { mime: 'image/png', extension: 'png' };
  }
  if (bytes.length >= 32 && bytes[0] === 255 && bytes[1] === 216 && bytes[2] === 255 && bytes[bytes.length - 2] === 255 && bytes[bytes.length - 1] === 217) return { mime: 'image/jpeg', extension: 'jpg' };
  if (bytes.length >= 16 && bytes.subarray(0, 5).toString() === '%PDF-' && bytes.subarray(Math.max(0, bytes.length - 1024)).includes(Buffer.from('%%EOF'))) return { mime: 'application/pdf', extension: 'pdf' };
  return null;
}

router.put('/me/documents/:kind', requireRole('DRIVER', 'PASSENGER'),
  express.raw({ type: () => true, limit: MAX_DOCUMENT_BYTES }), asyncRoute(async (req, res) => {
    const kind = String(req.params.kind).toUpperCase();
    if (!(requirements[req.user.role] || []).includes(kind)) return fail(res, 400, 'That document is not required for this account.');
    if (!Buffer.isBuffer(req.body) || !req.body.length) return fail(res, 400, 'Select a JPEG, PNG or PDF file.');
    const detected = detectType(req.body);
    const declared = String(req.headers['content-type'] || '').split(';')[0].trim().toLowerCase();
    if (!detected || detected.mime !== declared || (kind === 'PHOTO' && detected.mime === 'application/pdf')) {
      return fail(res, 415, kind === 'PHOTO' ? 'A profile photo must be a valid JPEG or PNG image.' : 'Upload a valid JPEG, PNG or PDF matching its file type.');
    }
    await fs.mkdir(uploadDirectory, { recursive: true, mode: 0o700 });
    const storageName = `${crypto.randomUUID()}.${detected.extension}`;
    const destination = path.join(uploadDirectory, storageName);
    await fs.writeFile(destination, req.body, { flag: 'wx', mode: 0o600 });
    let previous;
    try {
      await DB.transaction(async tx => {
        // Lock the user so simultaneous uploads serialize on both database adapters.
        const lockedUser = await tx.prepare(tx.isPostgres ? 'SELECT id,status FROM users WHERE id = ? FOR UPDATE' : 'SELECT id,status FROM users WHERE id = ?').get(req.user.id);
        if (!lockedUser || lockedUser.status !== 'ACTIVE' || await require('../services/account-deletion').isAccountClosed(req.user.id, req.user.role, tx)) {
          const error = new Error('This account is no longer available.'); error.status = 401; throw error;
        }
        previous = await tx.prepare('SELECT storage_name FROM user_profile_documents WHERE user_id = ? AND kind = ?').get(req.user.id, kind);
        await tx.prepare(`INSERT INTO user_profile_documents (user_id, kind, storage_name, content_type, byte_size, uploaded_at)
          VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (user_id, kind) DO UPDATE SET storage_name = excluded.storage_name,
          content_type = excluded.content_type, byte_size = excluded.byte_size, uploaded_at = excluded.uploaded_at`).run(
          req.user.id, kind, storageName, detected.mime, req.body.length, new Date().toISOString());
        await invalidateOnboarding(req.user.id, req.user.role, tx);
      });
    } catch (err) { await fs.unlink(destination).catch(() => {}); throw err; }
    if (previous && /^[a-f0-9-]+\.(jpg|png|pdf)$/.test(previous.storage_name)) await fs.unlink(path.join(uploadDirectory, previous.storage_name)).catch(() => {});
    res.json({ success: true, profile: await profileFor(req.user) });
  }));

async function mayReview(principal, target) {
  if (principal.id === target.id) return true;
  if (principal.role === 'ADMIN') return !companyId(target);
  return principal.role === 'COMPANY' && companyId(target) === principal.id;
}

async function maySeePhoto(principal, target) {
  if (await mayReview(principal, target)) return true;
  if (companyId(target) || companyId(principal)) return false;
  const driver = principal.role === 'DRIVER' && target.role === 'PASSENGER' ? principal : target.role === 'DRIVER' && principal.role === 'PASSENGER' ? target : null;
  const passenger = driver === principal ? target : principal;
  if (!driver || !await DB.prepare("SELECT id FROM users WHERE id=? AND role='PASSENGER' AND status='ACTIVE' AND COALESCE(companyId,'')=''").get(passenger.id)) return false;
  // Use the same current pass, expiry and exact vehicle assignment as tracking.
  // An older paid pass must not grant photo access after a replacement is pending.
  const assignment = await passengerAssignment(passenger.id);
  return Boolean(assignment.active && assignment.vehicle?.driverId === driver.id);
}

async function deliverDocument(res, userId, kind) {
  const document = await DB.prepare('SELECT * FROM user_profile_documents WHERE user_id = ? AND kind = ?').get(userId, kind);
  if (!document || !/^[a-f0-9-]+\.(jpg|png|pdf)$/.test(document.storage_name)) return fail(res, 404, 'Document not uploaded.');
  let bytes;
  try { bytes = await fs.readFile(path.join(uploadDirectory, document.storage_name)); }
  catch (err) { if (err.code === 'ENOENT') return fail(res, 404, 'Document not available. Please upload it again.'); throw err; }
  res.set('Content-Type', document.content_type);
  res.set('Content-Length', String(bytes.length));
  res.set('Content-Disposition', `${kind === 'PHOTO' ? 'inline' : 'attachment'}; filename="${kind.toLowerCase()}.${document.storage_name.split('.').pop()}"`);
  res.set('Cache-Control', 'private, no-store');
  res.set('X-Content-Type-Options', 'nosniff');
  res.send(bytes);
}

router.get('/me/documents/:kind', requireRole('DRIVER', 'PASSENGER'), asyncRoute(async (req, res) => {
  await deliverDocument(res, req.user.id, String(req.params.kind).toUpperCase());
}));

router.get('/:userId/photo', asyncRoute(async (req, res) => {
  const target = await DB.prepare('SELECT id, role, companyId FROM users WHERE id = ?').get(req.params.userId);
  if (!target || !await maySeePhoto(req.user, target)) return fail(res, 404, 'Photo unavailable.');
  await deliverDocument(res, target.id, 'PHOTO');
}));

router.get('/:userId/documents/:kind', asyncRoute(async (req, res) => {
  const target = await DB.prepare('SELECT id, role, companyId FROM users WHERE id = ?').get(req.params.userId);
  if (!target || !await mayReview(req.user, target)) return fail(res, 404, 'Document unavailable.');
  await deliverDocument(res, target.id, String(req.params.kind).toUpperCase());
}));

router.use((err, req, res, next) => {
  if (err.type === 'entity.too.large') return fail(res, 413, 'Each file must be 5 MB or smaller.');
  if (err.status === 401) return fail(res, 401, 'This account is no longer available.');
  console.error('[Profiles] request failed:', err.message);
  if (!res.headersSent) return fail(res, 500, 'Profile update unavailable. Please try again.');
  next(err);
});

module.exports = router;
module.exports.initProfileSchema = initProfileSchema;
