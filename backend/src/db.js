/**
 * RoutePass / Transport Navigator - Database Access Layer
 * Supports PostgreSQL (Production VPS with pg pool & transaction locks)
 * and SQLite (Local development & automated test runner fallback).
 */

const path = require('path');
const fs = require('fs');

const isPostgres = Boolean(process.env.DATABASE_URL);
let pgPool = null;
let sqliteDb = null;

if (isPostgres) {
  try {
    const { Pool } = require('pg');
    pgPool = new Pool({
      connectionString: process.env.DATABASE_URL,
      ssl: process.env.DATABASE_SSL === 'true' ? { rejectUnauthorized: false } : false,
      max: 20,
      idleTimeoutMillis: 30000,
      connectionTimeoutMillis: 5000,
    });
    console.log('[Database] Initialized PostgreSQL connection pool');
  } catch (err) {
    console.warn('[Database] Failed to initialize PostgreSQL pool, falling back to SQLite:', err.message);
  }
}

// Fallback or default to SQLite when PostgreSQL is not configured / available
if (!pgPool) {
  const DATA_DIR = process.env.DATA_DIR || path.join(__dirname, '..', 'data');
  if (!fs.existsSync(DATA_DIR)) {
    fs.mkdirSync(DATA_DIR, { recursive: true });
  }
  const DB_FILE = path.join(DATA_DIR, 'transport.db');
  const { DatabaseSync } = require('node:sqlite');
  sqliteDb = new DatabaseSync(DB_FILE);
  console.log(`[Database] Initialized SQLite database at: ${DB_FILE}`);
}

/**
 * Universal Database Interface
 */
const DB = {
  isPostgres: Boolean(pgPool),

  // Execute raw query (sync/async depending on driver)
  exec(sql) {
    if (sqliteDb) {
      return sqliteDb.exec(sql);
    }
    return pgPool.query(sql);
  },

  // Prepared statement abstraction for both SQLite and PostgreSQL
  prepare(sql) {
    if (sqliteDb) {
      const stmt = sqliteDb.prepare(sql);
      return {
        run(...params) {
          return stmt.run(...params);
        },
        get(...params) {
          return stmt.get(...params);
        },
        all(...params) {
          return stmt.all(...params);
        }
      };
    }

    // PostgreSQL async prepared wrapper
    // Converts SQLite '?' placeholders to PostgreSQL '$1', '$2', ...
    let paramIndex = 1;
    const pgSql = sql.replace(/\?/g, () => `$${paramIndex++}`);

    return {
      async run(...params) {
        const res = await pgPool.query(pgSql, params);
        return { changes: res.rowCount };
      },
      async get(...params) {
        const res = await pgPool.query(pgSql, params);
        return res.rows[0] || null;
      },
      async all(...params) {
        const res = await pgPool.query(pgSql, params);
        return res.rows;
      }
    };
  },

  // Direct Async Query helper
  async query(sql, params = []) {
    if (sqliteDb) {
      const stmt = sqliteDb.prepare(sql);
      if (sql.trim().toUpperCase().startsWith('SELECT')) {
        return stmt.all(...params);
      }
      return stmt.run(...params);
    }
    let paramIndex = 1;
    const pgSql = sql.replace(/\?/g, () => `$${paramIndex++}`);
    const res = await pgPool.query(pgSql, params);
    return res.rows;
  },

  // Transaction runner for atomic capacity checks
  async transaction(fn) {
    if (sqliteDb) {
      sqliteDb.exec('BEGIN IMMEDIATE');
      try {
        const result = await fn(sqliteDb);
        sqliteDb.exec('COMMIT');
        return result;
      } catch (err) {
        sqliteDb.exec('ROLLBACK');
        throw err;
      }
    } else {
      const client = await pgPool.connect();
      try {
        await client.query('BEGIN');
        const result = await fn(client);
        await client.query('COMMIT');
        return result;
      } catch (err) {
        await client.query('ROLLBACK');
        throw err;
      } finally {
        client.release();
      }
    }
  }
};

// Initialize schema in SQLite if in SQLite mode
function initSqliteSchema() {
  if (!sqliteDb) return;

  sqliteDb.exec(`
    PRAGMA foreign_keys = ON;

    CREATE TABLE IF NOT EXISTS users (
      id TEXT PRIMARY KEY,
      role TEXT NOT NULL,
      fullName TEXT NOT NULL,
      phone TEXT UNIQUE NOT NULL,
      email TEXT,
      passwordHash TEXT NOT NULL,
      status TEXT DEFAULT 'ACTIVE',
      licenseNumber TEXT DEFAULT '',
      companyName TEXT DEFAULT '',
      assignedVehiclePlate TEXT DEFAULT '',
      appliedRouteId TEXT DEFAULT '',
      appliedRouteName TEXT DEFAULT '',
      createdAt DATETIME DEFAULT CURRENT_TIMESTAMP
    );

    CREATE TABLE IF NOT EXISTS routes (
      id TEXT PRIMARY KEY,
      name TEXT NOT NULL,
      nameAm TEXT NOT NULL,
      description TEXT,
      morningDeparture TEXT DEFAULT '06:30',
      eveningDeparture TEXT DEFAULT '17:30',
      distanceKm REAL DEFAULT 12.0,
      basePriceEtb REAL DEFAULT 2500.0,
      active INTEGER DEFAULT 1,
      createdAt DATETIME DEFAULT CURRENT_TIMESTAMP
    );

    CREATE TABLE IF NOT EXISTS route_stops (
      id TEXT PRIMARY KEY,
      routeId TEXT NOT NULL,
      stopName TEXT NOT NULL,
      stopNameAm TEXT NOT NULL,
      stopOrder INTEGER NOT NULL,
      latitude REAL NOT NULL,
      longitude REAL NOT NULL,
      scheduledMorningTime TEXT,
      scheduledEveningTime TEXT,
      maxCapacity INTEGER DEFAULT 20,
      FOREIGN KEY (routeId) REFERENCES routes (id) ON DELETE CASCADE
    );

    CREATE TABLE IF NOT EXISTS vehicles (
      id TEXT PRIMARY KEY,
      plateNumber TEXT UNIQUE NOT NULL,
      model TEXT NOT NULL,
      vehicleType TEXT NOT NULL,
      capacityLimit INTEGER NOT NULL,
      currentOccupancy INTEGER DEFAULT 0,
      assignedRouteId TEXT,
      driverId TEXT,
      driverName TEXT,
      currentLat REAL DEFAULT 9.010,
      currentLng REAL DEFAULT 38.760,
      status TEXT DEFAULT 'IN_SERVICE',
      updatedAt DATETIME DEFAULT CURRENT_TIMESTAMP
    );

    CREATE TABLE IF NOT EXISTS subscriptions (
      id TEXT PRIMARY KEY,
      passengerId TEXT NOT NULL,
      routeId TEXT NOT NULL,
      pickupStopId TEXT,
      destinationStopId TEXT,
      morningSchedule TEXT,
      eveningSchedule TEXT,
      startDate TEXT,
      endDate TEXT,
      priceEtb REAL NOT NULL,
      paymentStatus TEXT DEFAULT 'UNPAID',
      subscriptionStatus TEXT DEFAULT 'PENDING',
      vehicleId TEXT,
      qrToken TEXT,
      daysRemaining INTEGER DEFAULT 0,
      updatedAt DATETIME DEFAULT CURRENT_TIMESTAMP,
      FOREIGN KEY (passengerId) REFERENCES users (id) ON DELETE CASCADE
    );

    CREATE TABLE IF NOT EXISTS trips (
      id TEXT PRIMARY KEY,
      driverId TEXT NOT NULL,
      vehicleId TEXT NOT NULL,
      routeId TEXT NOT NULL,
      direction TEXT DEFAULT 'OUTBOUND',
      currentStop TEXT,
      currentOccupancy INTEGER DEFAULT 0,
      status TEXT DEFAULT 'IN_PROGRESS',
      startTime DATETIME DEFAULT CURRENT_TIMESTAMP,
      endTime DATETIME,
      updatedAt DATETIME DEFAULT CURRENT_TIMESTAMP
    );

    CREATE TABLE IF NOT EXISTS checkin_records (
      id TEXT PRIMARY KEY,
      tripId TEXT NOT NULL,
      passengerId TEXT NOT NULL,
      passengerName TEXT NOT NULL,
      routeId TEXT NOT NULL,
      stopName TEXT NOT NULL,
      timestamp DATETIME DEFAULT CURRENT_TIMESTAMP,
      status TEXT DEFAULT 'BOARDED',
      vehicleId TEXT,
      driverId TEXT
    );

    CREATE TABLE IF NOT EXISTS payment_transactions (
      id TEXT PRIMARY KEY,
      passengerId TEXT NOT NULL,
      referenceNumber TEXT UNIQUE NOT NULL,
      idempotencyKey TEXT UNIQUE,
      amountEtb REAL NOT NULL,
      provider TEXT DEFAULT 'Telebirr',
      phoneNumber TEXT,
      date DATETIME DEFAULT CURRENT_TIMESTAMP,
      status TEXT DEFAULT 'COMPLETED',
      notes TEXT
    );

    CREATE TABLE IF NOT EXISTS complaints (
      id TEXT PRIMARY KEY,
      passengerId TEXT NOT NULL,
      passengerName TEXT NOT NULL,
      category TEXT NOT NULL,
      description TEXT NOT NULL,
      status TEXT DEFAULT 'OPEN',
      createdAt DATETIME DEFAULT CURRENT_TIMESTAMP
    );

    CREATE TABLE IF NOT EXISTS notifications (
      id TEXT PRIMARY KEY,
      title TEXT NOT NULL,
      message TEXT NOT NULL,
      targetAudience TEXT NOT NULL,
      type TEXT DEFAULT 'ALERT',
      senderName TEXT DEFAULT 'Transport Operations',
      timestamp DATETIME DEFAULT CURRENT_TIMESTAMP
    );

    CREATE TABLE IF NOT EXISTS audit_logs (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      action TEXT NOT NULL,
      userId TEXT,
      role TEXT,
      details TEXT,
      timestamp DATETIME DEFAULT CURRENT_TIMESTAMP
    );
  `);
}

function seedSqliteData() {
  if (!sqliteDb) return;
  const count = sqliteDb.prepare('SELECT COUNT(*) as c FROM routes').get().c;
  if (count > 0) return;

  const insertRoute = sqliteDb.prepare(`
    INSERT INTO routes (id, name, nameAm, description, morningDeparture, eveningDeparture, distanceKm, basePriceEtb, active)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1)
  `);

  insertRoute.run('route_bole_merkato', 'Bole - Merkato Express', 'ቦሌ - መርካቶ ኤክስፕረስ', 'Primary transit corridor connecting Bole, Meskel Square, and Merkato.', '06:30', '17:30', 14.5, 2500.0);
  insertRoute.run('route_megenagna_torhailoch', 'Megenagna - Torhailoch Line', 'መገናኛ - ጦር ኃይሎች መስመር', 'Cross-capital transit spanning Megenagna Hub, Kazanchis, and Torhailoch.', '06:45', '17:15', 16.0, 2700.0);
  insertRoute.run('route_mexico_saris', 'Mexico - Saris Abo Corridor', 'ሜክሲኮ - ሳሪስ አቦ መስመር', 'Southern transit line connecting Mexico Square and Saris.', '07:00', '17:45', 12.2, 2300.0);

  const insertStop = sqliteDb.prepare(`
    INSERT INTO route_stops (id, routeId, stopName, stopNameAm, stopOrder, latitude, longitude, scheduledMorningTime, scheduledEveningTime, maxCapacity)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 25)
  `);
  insertStop.run('stop_bole_medh', 'route_bole_merkato', 'Bole Medhanialem', 'ቦሌ መድኃኔዓለም', 1, 8.995, 38.788, '06:30', '17:30');
  insertStop.run('stop_atlas', 'route_bole_merkato', 'Bole Atlas', 'ቦሌ አትላስ', 2, 9.006, 38.780, '06:42', '17:42');
  insertStop.run('stop_meskel', 'route_bole_merkato', 'Meskel Square', 'መስቀል አደባባይ', 3, 9.010, 38.763, '06:55', '17:55');
  insertStop.run('stop_merkato', 'route_bole_merkato', 'Merkato Bus Terminal', 'መርካቶ ተርሚናል', 4, 9.031, 38.736, '07:35', '18:35');

  // Seed standard vehicles with capacities: MINIVAN=8, MINIBUS=14, HIGER=24, ANBESSA=30
  const insertVeh = sqliteDb.prepare(`
    INSERT INTO vehicles (id, plateNumber, model, vehicleType, capacityLimit, currentOccupancy, assignedRouteId, driverId, driverName, currentLat, currentLng, status)
    VALUES (?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?, 'IN_SERVICE')
  `);
  insertVeh.run('veh_higer_aa_34921', '3-AA-34921', 'Higer Midibus KLQ6758', 'HIGER_24', 24, 'route_bole_merkato', 'usr_drv_kassahun', 'Kassahun Tadesse', 9.006, 38.780);
  insertVeh.run('veh_minibus_aa_98210', '3-AA-98210', 'Toyota HiAce Commuter', 'MINIBUS_14', 14, 'route_megenagna_torhailoch', null, null, 9.020, 38.802);
  insertVeh.run('veh_minivan_aa_11093', '3-AA-11093', 'Hyundai H1 Van', 'MINIVAN_8', 8, 'route_mexico_saris', null, null, 9.011, 38.745);
  insertVeh.run('veh_anbessa_aa_55412', '3-AA-55412', 'DAF Anbessa Citybus', 'ANBESSA_BUS_30', 30, 'route_bole_merkato', null, null, 9.010, 38.763);

  // Seed default test users with bcrypt passwords
  const bcrypt = require('bcryptjs');
  const salt = bcrypt.genSaltSync(10);
  const hash = bcrypt.hashSync('123456', salt);

  const insertUser = sqliteDb.prepare(`
    INSERT INTO users (id, role, fullName, phone, email, passwordHash, status, licenseNumber, companyName, assignedVehiclePlate, appliedRouteId, appliedRouteName)
    VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?)
  `);
  insertUser.run('usr_adm_root', 'ADMIN', 'Addis Transit Administrator', '+251910001122', 'admin@transport.et', hash, '', 'Addis Ababa City Transport Bureau', '', '', '');
  insertUser.run('usr_drv_kassahun', 'DRIVER', 'Kassahun Tadesse', '+251911998877', 'kassahun@transport.et', hash, 'ET-DL-88991', 'Selam City Transport S.C.', '3-AA-34921', 'route_bole_merkato', 'Bole - Merkato Express');
  insertUser.run('usr_pas_alemayehu', 'PASSENGER', 'Alemayehu Haile', '+251911223344', 'alemayehu@gmail.com', hash, '', '', '', 'route_bole_merkato', 'Bole - Merkato Express');

  // Seed default notifications
  const insertNotif = sqliteDb.prepare(`
    INSERT INTO notifications (id, title, message, targetAudience, type, senderName)
    VALUES (?, ?, ?, ?, ?, 'Central Transport Dispatch')
  `);
  insertNotif.run('notif_1', 'Morning Commute Update', 'Bole - Merkato service operating smoothly.', 'ALL', 'SERVICE');
  insertNotif.run('notif_2', 'Capacity Regulation Reminder', 'All drivers must respect vehicle seating capacities (8, 14, 24, 30).', 'TRANSPORTERS', 'ALERT');
}

initSqliteSchema();
seedSqliteData();

module.exports = { DB };
