const crypto = require('crypto');
const fs = require('fs/promises');
const path = require('path');

const DOCUMENT_TYPES = new Set([
  'PROFILE_PHOTO',
  'DRIVER_LICENSE_DOCUMENT',
  'NATIONAL_ID_DOCUMENT',
  'VEHICLE_PHOTO',
  'VEHICLE_TRADE_LICENSE'
]);
const MIME_TYPES = new Set(['image/jpeg', 'image/png', 'image/webp', 'application/pdf']);
const MAX_FILE_BYTES = 1500000;
const MAX_TOTAL_BYTES = 5500000;

function uploadsDirectory() {
  return path.resolve(process.env.DATA_DIR || path.join(__dirname, '..', '..', 'data'), 'uploads');
}

function decodeUpload(upload) {
  if (!upload || typeof upload !== 'object') throw new Error('Each uploaded file must include documentType, fileName, mimeType and dataBase64.');
  const documentType = String(upload.documentType || '').trim().toUpperCase();
  const mimeType = String(upload.mimeType || '').trim().toLowerCase();
  if (!DOCUMENT_TYPES.has(documentType)) throw new Error('Unsupported document type.');
  if (!MIME_TYPES.has(mimeType)) throw new Error('Only JPEG, PNG, WebP images and PDF documents are accepted.');
  let encoded = String(upload.dataBase64 || '').trim();
  if (encoded.startsWith('data:')) encoded = encoded.slice(encoded.indexOf(',') + 1);
  if (!encoded || encoded.length > Math.ceil(MAX_FILE_BYTES * 4 / 3) + 16 || !/^[A-Za-z0-9+/]*={0,2}$/.test(encoded)) {
    throw new Error('The uploaded file is empty or exceeds the 1.5 MB per-file limit.');
  }
  const buffer = Buffer.from(encoded, 'base64');
  if (!buffer.length || buffer.length > MAX_FILE_BYTES) throw new Error('Each file must be smaller than 1.5 MB. Compress images or choose a smaller PDF.');
  const isJpeg = buffer.length > 3 && buffer[0] === 0xff && buffer[1] === 0xd8 && buffer[2] === 0xff;
  const isPng = buffer.length > 8 && buffer.subarray(0, 8).equals(Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]));
  const isWebp = buffer.length > 12 && buffer.toString('ascii', 0, 4) === 'RIFF' && buffer.toString('ascii', 8, 12) === 'WEBP';
  const isPdf = buffer.length > 5 && buffer.toString('ascii', 0, 5) === '%PDF-';
  if ((mimeType === 'image/jpeg' && !isJpeg) ||
      (mimeType === 'image/png' && !isPng) ||
      (mimeType === 'image/webp' && !isWebp) ||
      (mimeType === 'application/pdf' && !isPdf)) {
    throw new Error('The file content does not match its declared file type.');
  }
  const rawName = String(upload.fileName || documentType).replace(/\\/g, '/').split('/').pop();
  const fileName = (rawName || documentType).replace(/[\u0000-\u001f\u007f]/g, '').slice(0, 120) || documentType;
  return { documentType, mimeType, fileName, buffer };
}

function validateRegistrationUploads(role, uploads) {
  const normalizedRole = String(role || '').toUpperCase();
  const required = normalizedRole === 'DRIVER'
    ? ['PROFILE_PHOTO', 'DRIVER_LICENSE_DOCUMENT', 'NATIONAL_ID_DOCUMENT', 'VEHICLE_PHOTO', 'VEHICLE_TRADE_LICENSE']
    : normalizedRole === 'PASSENGER' ? ['PROFILE_PHOTO'] : [];
  if (!required.length) return [];
  if (!Array.isArray(uploads)) throw new Error('Please upload the required registration photo and documents.');
  const decoded = uploads.map(decodeUpload);
  const byType = new Map();
  let totalBytes = 0;
  for (const file of decoded) {
    if (byType.has(file.documentType)) throw new Error('Only one file per document type can be uploaded during registration.');
    byType.set(file.documentType, file);
    totalBytes += file.buffer.length;
  }
  if (decoded.some(file => normalizedRole === 'PASSENGER' ? file.documentType !== 'PROFILE_PHOTO' : !required.includes(file.documentType))) {
    throw new Error('One or more uploads do not match the registration role.');
  }
  const missing = required.filter(type => !byType.has(type));
  if (missing.length) throw new Error('Missing required uploads: ' + missing.join(', ').replace(/_/g, ' ') + '.');
  if (totalBytes > MAX_TOTAL_BYTES) throw new Error('Combined uploads are too large. Keep all selected files under 5.5 MB in total.');
  return decoded;
}

async function saveUpload(DB, ownerUserId, vehicleId, file) {
  const decoded = file.buffer ? file : decodeUpload(file);
  const storageName = crypto.randomUUID();
  const directory = uploadsDirectory();
  const fullPath = path.join(directory, storageName);
  await fs.mkdir(directory, { recursive: true, mode: 0o700 });
  await fs.writeFile(fullPath, decoded.buffer, { flag: 'wx', mode: 0o600 });
  try {
    const id = 'doc_' + crypto.randomUUID().replace(/-/g, '').slice(0, 20);
    await DB.prepare(`
      INSERT INTO routepass_documents
        (id, owner_user_id, vehicle_id, document_type, storage_name, original_name, mime_type, size_bytes)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?)
    `).run(id, ownerUserId, vehicleId || null, decoded.documentType, storageName, decoded.fileName, decoded.mimeType, decoded.buffer.length);
    return { id, documentType: decoded.documentType, fileName: decoded.fileName, mimeType: decoded.mimeType, sizeBytes: decoded.buffer.length };
  } catch (err) {
    await fs.unlink(fullPath).catch(() => {});
    throw err;
  }
}

async function latestDataUrl(DB, ownerUserId, documentType, vehicleId = null) {
  const row = await DB.prepare(`
    SELECT storage_name, mime_type FROM routepass_documents
    WHERE owner_user_id = ? AND document_type = ?
      AND COALESCE(vehicle_id, '') = COALESCE(?, '')
    ORDER BY created_at DESC LIMIT 1
  `).get(ownerUserId, documentType, vehicleId || '');
  if (!row) return null;
  try {
    const data = await fs.readFile(path.join(uploadsDirectory(), path.basename(row.storage_name)));
    if (data.length > MAX_FILE_BYTES) return null;
    return 'data:' + row.mime_type + ';base64,' + data.toString('base64');
  } catch (_) {
    return null;
  }
}

async function loadDocument(DB, id) {
  return DB.prepare(`
    SELECT d.*, u.fullName AS owner_name, u.role AS owner_role, v.plateNumber AS vehicle_plate, v.driverId AS vehicle_driver_id
    FROM routepass_documents d
    LEFT JOIN users u ON u.id = d.owner_user_id
    LEFT JOIN vehicles v ON v.id = d.vehicle_id
    WHERE d.id = ?
  `).get(id);
}

async function readDocumentBytes(row) {
  return fs.readFile(path.join(uploadsDirectory(), path.basename(row.storage_name)));
}

function documentMetadata(row) {
  return {
    id: row.id,
    ownerUserId: row.owner_user_id,
    ownerName: row.owner_name || '',
    ownerRole: row.owner_role || '',
    vehicleId: row.vehicle_id || null,
    vehiclePlate: row.vehicle_plate || '',
    documentType: row.document_type,
    fileName: row.original_name,
    mimeType: row.mime_type,
    sizeBytes: Number(row.size_bytes || 0),
    uploadedAt: row.created_at
  };
}

module.exports = {
  DOCUMENT_TYPES,
  decodeUpload,
  validateRegistrationUploads,
  saveUpload,
  latestDataUrl,
  loadDocument,
  readDocumentBytes,
  documentMetadata
};
