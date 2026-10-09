/**
 * RoutePass / Transport Navigator - Database Access Layer
 * Supports PostgreSQL (Production VPS with pg pool & transaction locks)
 * and SQLite (Local development & automated test runner fallback).
 */

const path = require('path');
const fs = require('fs');

const isPostgres = Boolean(process.env.DATABASE_URL);
if (process.env.NODE_ENV === 'production' && !isPostgres) {
  throw new Error('[FATAL CONFIGURATION ERROR] DATABASE_URL is required in production; refusing to start with local SQLite.');
}
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
    if (process.env.NODE_ENV === 'production') {
      throw new Error(`[FATAL DATABASE ERROR] Cannot initialize PostgreSQL driver: ${err.message}`);
    }
    console.warn('[Database] Failed to initialize PostgreSQL pool; using SQLite only in non-production:', err.message);
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

function normalizeRow(row) {
  if (!row || typeof row !== 'object') return row;
  const normalized = { ...row };
  for (const [key, val] of Object.entries(row)) {
    const lowerKey = key.toLowerCase();
    if (lowerKey === 'fullname' || lowerKey === 'full_name') normalized.fullName = val;
    if (lowerKey === 'passwordhash' || lowerKey === 'password_hash') normalized.passwordHash = val;
    if (lowerKey === 'platenumber' || lowerKey === 'plate_number') normalized.plateNumber = val;
    if (lowerKey === 'vehicletype' || lowerKey === 'vehicle_type') normalized.vehicleType = val;
    if (lowerKey === 'capacitylimit' || lowerKey === 'capacity_limit') normalized.capacityLimit = val;
    if (lowerKey === 'currentoccupancy' || lowerKey === 'current_occupancy') normalized.currentOccupancy = val;
    if (lowerKey === 'assignedrouteid' || lowerKey === 'assigned_route_id') normalized.assignedRouteId = val;
    if (lowerKey === 'vehicleid' || lowerKey === 'vehicle_id') normalized.vehicleId = val;
    if (lowerKey === 'tripid' || lowerKey === 'trip_id') normalized.tripId = val;
    if (lowerKey === 'currentstop' || lowerKey === 'current_stop') normalized.currentStop = val;
    if (lowerKey === 'distancekm' || lowerKey === 'distance_km') normalized.distanceKm = val;
    if (lowerKey === 'scheduledmorningtime' || lowerKey === 'scheduled_morning_time') normalized.scheduledMorningTime = val;
    if (lowerKey === 'scheduledeveningtime' || lowerKey === 'scheduled_evening_time') normalized.scheduledEveningTime = val;
    if (lowerKey === 'maxcapacity' || lowerKey === 'max_capacity') normalized.maxCapacity = val;
    if (lowerKey === 'driverid' || lowerKey === 'driver_id') normalized.driverId = val;
    if (lowerKey === 'drivername' || lowerKey === 'driver_name') normalized.driverName = val;
    if (lowerKey === 'currentlat' || lowerKey === 'current_lat') normalized.currentLat = val;
    if (lowerKey === 'currentlng' || lowerKey === 'current_lng') normalized.currentLng = val;
    if (lowerKey === 'licensenumber' || lowerKey === 'license_number') normalized.licenseNumber = val;
    if (lowerKey === 'companyname' || lowerKey === 'company_name') normalized.companyName = val;
    if (lowerKey === 'assignedvehicleplate' || lowerKey === 'assigned_vehicle_plate') normalized.assignedVehiclePlate = val;
    if (lowerKey === 'appliedrouteid' || lowerKey === 'applied_route_id') normalized.appliedRouteId = val;
    if (lowerKey === 'appliedroutename' || lowerKey === 'applied_route_name') normalized.appliedRouteName = val;
    if (lowerKey === 'passengerid' || lowerKey === 'passenger_id') normalized.passengerId = val;
    if (lowerKey === 'passengername' || lowerKey === 'passenger_name') normalized.passengerName = val;
    if (lowerKey === 'routeid' || lowerKey === 'route_id') normalized.routeId = val;
    if (lowerKey === 'routename' || lowerKey === 'route_name') normalized.routeName = val;
    if (lowerKey === 'routenameam' || lowerKey === 'route_name_am') normalized.routeNameAm = val;
    if (lowerKey === 'nameam' || lowerKey === 'name_am') normalized.nameAm = val;
    if (lowerKey === 'stopname' || lowerKey === 'stop_name') normalized.stopName = val;
    if (lowerKey === 'stopnameam' || lowerKey === 'stop_name_am') normalized.stopNameAm = val;
    if (lowerKey === 'stoporder' || lowerKey === 'stop_order') normalized.stopOrder = val;
    if (lowerKey === 'morningdeparture' || lowerKey === 'morning_departure') normalized.morningDeparture = val;
    if (lowerKey === 'eveningdeparture' || lowerKey === 'evening_departure') normalized.eveningDeparture = val;
    if (lowerKey === 'morningschedule' || lowerKey === 'morning_schedule') normalized.morningSchedule = val;
    if (lowerKey === 'eveningschedule' || lowerKey === 'evening_schedule') normalized.eveningSchedule = val;
    if (lowerKey === 'pickupstopid' || lowerKey === 'pickup_stop_id') normalized.pickupStopId = val;
    if (lowerKey === 'destinationstopid' || lowerKey === 'destination_stop_id') normalized.destinationStopId = val;
    if (lowerKey === 'startdate' || lowerKey === 'start_date') normalized.startDate = val;
    if (lowerKey === 'enddate' || lowerKey === 'end_date') normalized.endDate = val;
    if (lowerKey === 'priceetb' || lowerKey === 'price_etb') normalized.priceEtb = val;
    if (lowerKey === 'basepriceetb' || lowerKey === 'base_price_etb') normalized.basePriceEtb = val;
    if (lowerKey === 'amountetb' || lowerKey === 'amount_etb') normalized.amountEtb = val;
    if (lowerKey === 'paymentstatus' || lowerKey === 'payment_status') normalized.paymentStatus = val;
    if (lowerKey === 'subscriptionstatus' || lowerKey === 'subscription_status') normalized.subscriptionStatus = val;
    if (lowerKey === 'qrtoken' || lowerKey === 'qr_token') normalized.qrToken = val;
    if (lowerKey === 'daysremaining' || lowerKey === 'days_remaining') normalized.daysRemaining = val;
    if (lowerKey === 'starttime' || lowerKey === 'start_time') normalized.startTime = val;
    if (lowerKey === 'endtime' || lowerKey === 'end_time') normalized.endTime = val;
    if (lowerKey === 'referencenumber' || lowerKey === 'reference_number') normalized.referenceNumber = val;
    if (lowerKey === 'idempotencykey' || lowerKey === 'idempotency_key') normalized.idempotencyKey = val;
    if (lowerKey === 'targetaudience' || lowerKey === 'target_audience') normalized.targetAudience = val;
    if (lowerKey === 'sendername' || lowerKey === 'sender_name') normalized.senderName = val;
    if (lowerKey === 'createdat' || lowerKey === 'created_at') normalized.createdAt = val;
    if (lowerKey === 'updatedat' || lowerKey === 'updated_at') normalized.updatedAt = val;
  }
  return normalized;
}

function translateSqlForPostgres(sql) {
  let paramIndex = 1;
  let pgSql = sql.replace(/\?/g, () => `$${paramIndex++}`);
  pgSql = pgSql
    .replace(/\bfullName\b/g, 'full_name')
    .replace(/\bpasswordHash\b/g, 'password_hash')
    .replace(/\blicenseNumber\b/g, 'license_number')
    .replace(/\bcompanyName\b/g, 'company_name')
    .replace(/\bassignedVehiclePlate\b/g, 'assigned_vehicle_plate')
    .replace(/\bappliedRouteId\b/g, 'applied_route_id')
    .replace(/\bappliedRouteName\b/g, 'applied_route_name')
    .replace(/\bplateNumber\b/g, 'plate_number')
    .replace(/\bvehicleType\b/g, 'vehicle_type')
    .replace(/\bcapacityLimit\b/g, 'capacity_limit')
    .replace(/\bcurrentOccupancy\b/g, 'current_occupancy')
    .replace(/\bassignedRouteId\b/g, 'assigned_route_id')
    .replace(/\bdriverId\b/g, 'driver_id')
    .replace(/\bdriverName\b/g, 'driver_name')
    .replace(/\bcurrentLat\b/g, 'current_lat')
    .replace(/\bcurrentLng\b/g, 'current_lng')
    .replace(/\bpassengerId\b/g, 'passenger_id')
    .replace(/\bpassengerName\b/g, 'passenger_name')
    .replace(/\brouteId\b/g, 'route_id')
    .replace(/\bnameAm\b/g, 'name_am')
    .replace(/\bstopNameAm\b/g, 'stop_name_am')
    .replace(/\bstopName\b/g, 'stop_name')
    .replace(/\bstopOrder\b/g, 'stop_order')
    .replace(/\bmorningDeparture\b/g, 'morning_departure')
    .replace(/\beveningDeparture\b/g, 'evening_departure')
    .replace(/\bmorningSchedule\b/g, 'morning_schedule')
    .replace(/\beveningSchedule\b/g, 'evening_schedule')
    .replace(/\bpickupStopId\b/g, 'pickup_stop_id')
    .replace(/\bdestinationStopId\b/g, 'destination_stop_id')
    .replace(/\bstartDate\b/g, 'start_date')
    .replace(/\bendDate\b/g, 'end_date')
    .replace(/\bpriceEtb\b/g, 'price_etb')
    .replace(/\bbasePriceEtb\b/g, 'base_price_etb')
    .replace(/\bdistanceKm\b/g, 'distance_km')
    .replace(/\bscheduledMorningTime\b/g, 'scheduled_morning_time')
    .replace(/\bscheduledEveningTime\b/g, 'scheduled_evening_time')
    .replace(/\bmaxCapacity\b/g, 'max_capacity')
    .replace(/\bcurrentStop\b/g, 'current_stop')
    .replace(/\bvehicleId\b/g, 'vehicle_id')
    .replace(/\btripId\b/g, 'trip_id')
    .replace(/\bamountEtb\b/g, 'amount_etb')
    .replace(/\bpaymentStatus\b/g, 'payment_status')
    .replace(/\bsubscriptionStatus\b/g, 'subscription_status')
    .replace(/\bqrToken\b/g, 'qr_token')
    .replace(/\bdaysRemaining\b/g, 'days_remaining')
    .replace(/\bstartTime\b/g, 'start_time')
    .replace(/\bendTime\b/g, 'end_time')
    .replace(/\breferenceNumber\b/g, 'reference_number')
    .replace(/\bidempotencyKey\b/g, 'idempotency_key')
    .replace(/\bphoneNumber\b/g, 'phone_number')
    .replace(/\btargetAudience\b/g, 'target_audience')
    .replace(/\bsenderName\b/g, 'sender_name')
    .replace(/\bcreatedAt\b/g, 'created_at')
    .replace(/\bupdatedAt\b/g, 'updated_at');
  return pgSql;
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
    return pgPool.query(translateSqlForPostgres(sql));
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
    const pgSql = translateSqlForPostgres(sql);

    return {
      async run(...params) {
        const res = await pgPool.query(pgSql, params);
        return { changes: res.rowCount };
      },
      async get(...params) {
        const res = await pgPool.query(pgSql, params);
        return res.rows[0] ? normalizeRow(res.rows[0]) : null;
      },
      async all(...params) {
        const res = await pgPool.query(pgSql, params);
        return res.rows.map(normalizeRow);
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
    const pgSql = translateSqlForPostgres(sql);
    const res = await pgPool.query(pgSql, params);
    return res.rows.map(normalizeRow);
  },

  // Transaction runner for atomic capacity checks
  async transaction(fn) {
    if (sqliteDb) {
      sqliteDb.exec('BEGIN IMMEDIATE');
      try {
        const result = await fn(DB);
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
        // All transaction work must use this client, never the shared pool.
        const tx = {
          isPostgres: true,
          prepare(sql) {
            const pgSql = translateSqlForPostgres(sql);
            return {
              async run(...params) {
                const res = await client.query(pgSql, params);
                return { changes: res.rowCount };
              },
              async get(...params) {
                const res = await client.query(pgSql, params);
                return res.rows[0] ? normalizeRow(res.rows[0]) : null;
              },
              async all(...params) {
                const res = await client.query(pgSql, params);
                return res.rows.map(normalizeRow);
              }
            };
          }
        };
        const result = await fn(tx);
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
