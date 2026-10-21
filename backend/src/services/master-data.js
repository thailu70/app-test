'use strict';

const { DB } = require('../db');
const DEFAULT_VEHICLE_TYPES = [
  { code: 'MINIVAN_8', name: 'Minivan', capacity: 8, active: true },
  { code: 'MINIBUS_14', name: 'Minibus', capacity: 14, active: true },
  { code: 'HIGER_24', name: 'Higer bus', capacity: 24, active: true },
  { code: 'ANBESSA_BUS_30', name: 'Anbessa bus', capacity: 30, active: true }
];
const GPS_RADIUS_KEY = 'GPS_BOARDING_RADIUS_METERS';
const GPS_SHARING_KEY = 'GPS_SHARING_INTERVAL_SECONDS';

async function initMasterDataSchema(db = DB) {
  await db.exec(`CREATE TABLE IF NOT EXISTS vehicle_types (
    code VARCHAR(30) PRIMARY KEY, name VARCHAR(80) NOT NULL,
    capacity INTEGER NOT NULL CHECK (capacity BETWEEN 1 AND 100),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
  );
  CREATE TABLE IF NOT EXISTS app_settings (
    key VARCHAR(100) PRIMARY KEY, value TEXT NOT NULL DEFAULT '', updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
  );`);
  for (const type of DEFAULT_VEHICLE_TYPES) await db.prepare('INSERT INTO vehicle_types(code,name,capacity,active) VALUES(?,?,?,TRUE) ON CONFLICT(code) DO NOTHING')
    .run(type.code, type.name, type.capacity);
  if (db.isPostgres) {
    // Replace the original four-value enumeration; new catalog entries still use the existing
    // vehicle_type column and its 30-character limit. Existing vehicle records are untouched.
    await db.prepare('ALTER TABLE vehicles DROP CONSTRAINT IF EXISTS vehicles_vehicle_type_check').run();
    await db.prepare("ALTER TABLE vehicles ADD CONSTRAINT vehicles_vehicle_type_check CHECK (vehicle_type ~ '^[A-Z][A-Z0-9_]{1,29}$')").run();
  }
}

function missingTable(error, table) {
  return (error?.code === '42P01' || /no such table/i.test(String(error?.message))) && String(error?.message).includes(table);
}

function catalogType(row) {
  return { code: row.code, name: row.name, capacity: Number(row.capacity),
    active: row.active === true || row.active === 1 || row.active === 'true' };
}

async function listVehicleTypes(db = DB) {
  try { return (await db.prepare('SELECT code,name,capacity,active FROM vehicle_types ORDER BY capacity ASC,code ASC').all()).map(catalogType); }
  catch (error) {
    // Standalone legacy integrations can continue using the original four types until startup
    // migrations run. A real database failure is still an error, never an invented registry.
    if (missingTable(error, 'vehicle_types')) return DEFAULT_VEHICLE_TYPES.map(type => ({ ...type }));
    throw error;
  }
}

async function getVehicleType(code, db = DB) {
  return (await listVehicleTypes(db)).find(type => type.code === code && type.active) || null;
}

async function setting(key, db = DB) {
  try { return (await db.prepare('SELECT value FROM app_settings WHERE key=?').get(key))?.value ?? null; }
  catch (error) { if (missingTable(error, 'app_settings')) return null; throw error; }
}

async function getGlobalGpsRadius(db = DB) {
  const raw = await setting(GPS_RADIUS_KEY, db), radius = raw === null ? NaN : Number(raw);
  const overrideEnabled = Number.isInteger(radius) && radius >= 25 && radius <= 5000;
  return { radiusMeters: overrideEnabled ? radius : 300, scope: 'ALL_VEHICLES', overrideEnabled };
}

async function effectiveBoardingRadius(db, fallback = 300) {
  const global = await getGlobalGpsRadius(db);
  return global.overrideEnabled ? global.radiusMeters : fallback;
}

async function getGpsSharing(db = DB) {
  const raw = await setting(GPS_SHARING_KEY, db), interval = raw === null ? NaN : Number(raw);
  return { intervalSeconds: Number.isInteger(interval) && interval >= 1 && interval <= 60 ? interval : 5 };
}

module.exports = { initMasterDataSchema, listVehicleTypes, getVehicleType, getGlobalGpsRadius,
  effectiveBoardingRadius, getGpsSharing, GPS_RADIUS_KEY, GPS_SHARING_KEY };
