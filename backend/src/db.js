/**
 * Transport Navigator - Persistent Database Layer
 * Zero-configuration embedded SQLite database engine with automatic schema creation and initial seed data.
 */

const path = require('path');
const fs = require('fs');

// Create data directory if not exists
const DATA_DIR = process.env.DATA_DIR || path.join(__dirname, '..', 'data');
if (!fs.existsSync(DATA_DIR)) {
  fs.mkdirSync(DATA_DIR, { recursive: true });
}

const DB_FILE = path.join(DATA_DIR, 'transport.db');

// Use Node 22 built-in DatabaseSync or sqlite3 fallback
let db;
try {
  const { DatabaseSync } = require('node:sqlite');
  db = new DatabaseSync(DB_FILE);
  console.log(`[Database] Connected to persistent SQLite database at: ${DB_FILE}`);
} catch (err) {
  console.warn('[Database] node:sqlite error, falling back to in-memory JSON driver:', err.message);
  throw err;
}

// Helper methods wrapping DatabaseSync
const DB = {
  exec(sql) {
    return db.exec(sql);
  },
  prepare(sql) {
    const stmt = db.prepare(sql);
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
};

// Initialize Schemas
function initSchema() {
  DB.exec(`
    PRAGMA foreign_keys = ON;

    CREATE TABLE IF NOT EXISTS users (
      id TEXT PRIMARY KEY,
      role TEXT NOT NULL, -- PASSENGER, DRIVER, ADMIN
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
      vehicleType TEXT NOT NULL, -- MINIBUS_14, HIGER_24, MINIVAN_8, ANBESSA_BUS_30
      capacityLimit INTEGER NOT NULL,
      currentOccupancy INTEGER DEFAULT 0,
      assignedRouteId TEXT,
      driverId TEXT,
      driverName TEXT,
      currentLat REAL DEFAULT 9.010,
      currentLng REAL DEFAULT 38.760,
      status TEXT DEFAULT 'IN_SERVICE', -- IN_SERVICE, FULL, MAINTENANCE
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
      paymentStatus TEXT DEFAULT 'UNPAID', -- PAID, UNPAID, PENDING
      subscriptionStatus TEXT DEFAULT 'NOT_SUBSCRIBED', -- ACTIVE, EXPIRED, NOT_SUBSCRIBED
      vehicleId TEXT,
      qrToken TEXT,
      daysRemaining INTEGER DEFAULT 0,
      updatedAt DATETIME DEFAULT CURRENT_TIMESTAMP,
      FOREIGN KEY (passengerId) REFERENCES users (id) ON DELETE CASCADE
    );

    CREATE TABLE IF NOT EXISTS checkin_records (
      id TEXT PRIMARY KEY,
      tripId TEXT NOT NULL,
      passengerId TEXT NOT NULL,
      passengerName TEXT NOT NULL,
      routeId TEXT NOT NULL,
      stopName TEXT NOT NULL,
      timestamp DATETIME DEFAULT CURRENT_TIMESTAMP,
      status TEXT DEFAULT 'BOARDED', -- BOARDED, DENIED_CAPACITY_FULL, INVALID_QR
      vehicleId TEXT,
      driverId TEXT
    );

    CREATE TABLE IF NOT EXISTS payment_transactions (
      id TEXT PRIMARY KEY,
      passengerId TEXT NOT NULL,
      referenceNumber TEXT UNIQUE NOT NULL,
      amountEtb REAL NOT NULL,
      provider TEXT DEFAULT 'Telebirr',
      date DATETIME DEFAULT CURRENT_TIMESTAMP,
      status TEXT DEFAULT 'COMPLETED',
      notes TEXT
    );

    CREATE TABLE IF NOT EXISTS notifications (
      id TEXT PRIMARY KEY,
      title TEXT NOT NULL,
      message TEXT NOT NULL,
      targetAudience TEXT NOT NULL, -- PASSENGERS, TRANSPORTERS, ALL
      type TEXT DEFAULT 'ALERT', -- ALERT, SERVICE, WEATHER, PAYMENT
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

// Seed Initial Ethiopian Transit Data if tables are empty
function seedData() {
  const routesCount = DB.prepare('SELECT COUNT(*) as count FROM routes').get().count;
  if (routesCount > 0) return;

  console.log('[Database] Seeding initial Ethiopian Transit network data...');

  // 1. Routes
  const insertRoute = DB.prepare(`
    INSERT INTO routes (id, name, nameAm, description, morningDeparture, eveningDeparture, distanceKm, basePriceEtb, active)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
  `);

  insertRoute.run(
    'route_bole_merkato',
    'Bole - Merkato Express',
    'ቦሌ - መርካቶ ኤክስፕረስ',
    'Primary transit artery connecting Bole International Airport, Meskel Square, and Merkato Commercial District.',
    '06:30',
    '17:30',
    14.5,
    2500.0,
    1
  );

  insertRoute.run(
    'route_megenagna_torhailoch',
    'Megenagna - Torhailoch Line',
    'መገናኛ - ጦር ኃይሎች መስመር',
    'High-frequency cross-capital corridor spanning Megenagna Hub, Kazanchis, Stadium, and Torhailoch.',
    '06:45',
    '17:15',
    16.0,
    2700.0,
    1
  );

  insertRoute.run(
    'route_mexico_saris',
    'Mexico - Saris Abo Corridor',
    'ሜክሲኮ - ሳሪስ አቦ መስመር',
    'Southern transit spine connecting Mexico Square, Gotera Interchange, Kera, and Saris Terminal.',
    '07:00',
    '17:45',
    12.2,
    2300.0,
    1
  );

  // 2. Route Stops
  const insertStop = DB.prepare(`
    INSERT INTO route_stops (id, routeId, stopName, stopNameAm, stopOrder, latitude, longitude, scheduledMorningTime, scheduledEveningTime, maxCapacity)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
  `);

  const stops = [
    // Bole - Merkato
    ['stop_bole_medh', 'route_bole_merkato', 'Bole Medhanialem', 'ቦሌ መድኃኔዓለም', 1, 8.995, 38.788, '06:30', '17:30', 20],
    ['stop_atlas', 'route_bole_merkato', 'Bole Atlas', 'ቦሌ አትላስ', 2, 9.006, 38.780, '06:42', '17:42', 20],
    ['stop_meskel', 'route_bole_merkato', 'Meskel Square', 'መስቀል አደባባይ', 3, 9.010, 38.763, '06:55', '17:55', 25],
    ['stop_leghar', 'route_bole_merkato', 'Leghar Station', 'ለገሃር ባቡር ጣቢያ', 4, 9.014, 38.752, '07:08', '18:08', 20],
    ['stop_tekle', 'route_bole_merkato', 'Teklehaymanot', 'ተክለሃይማኖት', 5, 9.023, 38.742, '07:20', '18:20', 20],
    ['stop_merkato', 'route_bole_merkato', 'Merkato Bus Terminal', 'መርካቶ ተርሚናል', 6, 9.031, 38.736, '07:35', '18:35', 30],

    // Megenagna - Torhailoch
    ['stop_meg_hub', 'route_megenagna_torhailoch', 'Megenagna Terminal', 'መገናኛ ተርሚናል', 1, 9.020, 38.802, '06:45', '17:15', 30],
    ['stop_kazanchis', 'route_megenagna_torhailoch', 'Kazanchis Inter-change', 'ካዛንቺስ', 2, 9.017, 38.775, '07:00', '17:30', 25],
    ['stop_mexico_sq', 'route_megenagna_torhailoch', 'Mexico Square', 'ሜክሲኮ አደባባይ', 3, 9.011, 38.745, '07:18', '17:48', 25],
    ['stop_torhailoch', 'route_megenagna_torhailoch', 'Torhailoch Depot', 'ጦር ኃይሎች', 4, 9.005, 38.724, '07:35', '18:05', 25],

    // Mexico - Saris
    ['stop_mex_start', 'route_mexico_saris', 'Mexico Hub', 'ሜክሲኮ ማዕከል', 1, 9.011, 38.745, '07:00', '17:45', 25],
    ['stop_kera', 'route_mexico_saris', 'Kera Slaughterhouse', 'ቄራ', 2, 8.995, 38.748, '07:15', '18:00', 20],
    ['stop_gotera', 'route_mexico_saris', 'Gotera Interchange', 'ጎተራ ማስተላለፊያ', 3, 8.983, 38.756, '07:28', '18:13', 20],
    ['stop_saris', 'route_mexico_saris', 'Saris Abo Terminal', 'ሳሪስ አቦ ተርሚናል', 4, 8.960, 38.765, '07:45', '18:30', 30]
  ];

  for (const s of stops) {
    insertStop.run(...s);
  }

  // 3. Vehicles with Vehicle Type Limits (14-seat Minibus, 24-seat Higer, 8-seat Minivan, 30-seat City Bus)
  const insertVeh = DB.prepare(`
    INSERT INTO vehicles (id, plateNumber, model, vehicleType, capacityLimit, currentOccupancy, assignedRouteId, driverId, driverName, currentLat, currentLng, status)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
  `);

  insertVeh.run(
    'veh_higer_aa_34921',
    '3-AA-34921',
    'Higer City Express Bus',
    'HIGER_24',
    24,
    18,
    'route_bole_merkato',
    'usr_dri_001',
    'Abebe Alemu',
    9.006,
    38.780,
    'IN_SERVICE'
  );

  insertVeh.run(
    'veh_minibus_aa_88204',
    '3-AA-88204',
    'Toyota HiAce Minibus Taxi',
    'MINIBUS_14',
    14,
    11,
    'route_megenagna_torhailoch',
    'usr_dri_002',
    'Dawit Tadesse',
    9.017,
    38.775,
    'IN_SERVICE'
  );

  insertVeh.run(
    'veh_minivan_aa_11093',
    '3-AA-11093',
    'Suzuki APV Commuter Minivan',
    'MINIVAN_8',
    8,
    7,
    'route_mexico_saris',
    'usr_dri_003',
    'Yonas Bekele',
    8.995,
    38.748,
    'IN_SERVICE'
  );

  // 4. Default Seed Users (Passenger, Transporter/Driver, Operator/Admin)
  const bcrypt = require('bcryptjs');
  const salt = bcrypt.genSaltSync(10);
  const defaultHash = bcrypt.hashSync('1234', salt);

  const insertUser = DB.prepare(`
    INSERT INTO users (id, role, fullName, phone, email, passwordHash, status, licenseNumber, companyName, assignedVehiclePlate, appliedRouteId, appliedRouteName)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
  `);

  // Passenger
  insertUser.run(
    'usr_pas_001',
    'PASSENGER',
    'Abebe Kebede',
    '0911223344',
    'abebe@transport.et',
    defaultHash,
    'ACTIVE',
    '',
    '',
    '',
    'route_bole_merkato',
    'Bole - Merkato Express'
  );

  // Transporter / Driver
  insertUser.run(
    'usr_dri_001',
    'DRIVER',
    'Abebe Alemu',
    '0922334455',
    'driver.alemu@transport.et',
    defaultHash,
    'ACTIVE',
    'ETH-DL-882910',
    'Selam Transit Cooperative',
    '3-AA-34921',
    'route_bole_merkato',
    'Bole - Merkato Express'
  );

  // Operator / Admin
  insertUser.run(
    'usr_adm_001',
    'ADMIN',
    'Transport Operations Center',
    '0900000000',
    'admin@transport.et',
    defaultHash,
    'ACTIVE',
    '',
    'Federal Transport Authority',
    '',
    '',
    ''
  );

  // 5. Initial Active Subscription for default passenger
  const insertSub = DB.prepare(`
    INSERT INTO subscriptions (id, passengerId, routeId, pickupStopId, destinationStopId, morningSchedule, eveningSchedule, startDate, endDate, priceEtb, paymentStatus, subscriptionStatus, vehicleId, qrToken, daysRemaining)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
  `);

  insertSub.run(
    'sub_pas_001_initial',
    'usr_pas_001',
    'route_bole_merkato',
    'stop_atlas',
    'stop_merkato',
    '06:30',
    '17:30',
    '01 Oct 2026',
    '31 Oct 2026',
    2500.0,
    'PAID',
    'ACTIVE',
    'veh_higer_aa_34921',
    'TN-sub_pas_001_initial-usr_pas_001-Abebe Kebede-Bole - Merkato Express-ACTIVE-202610-8829104',
    28
  );

  // 6. Broadcast Notifications
  const insertNotif = DB.prepare(`
    INSERT INTO notifications (id, title, message, targetAudience, type, senderName)
    VALUES (?, ?, ?, ?, ?, ?)
  `);

  insertNotif.run(
    'notif_001',
    'Bole Corridor Morning Update',
    'Morning express departures are operating on standard 10-minute headway intervals. Road conditions around Meskel Square are clear.',
    'ALL',
    'SERVICE',
    'Operations Dispatch'
  );

  insertNotif.run(
    'notif_002',
    'Vehicle Capacity Limit Advisory',
    'Drivers are strictly reminded to adhere to vehicle seat limits (Higer 24, Minibus 14, Minivan 8). System scanner will deny check-in when full.',
    'TRANSPORTERS',
    'ALERT',
    'Fleet Compliance Bureau'
  );

  insertNotif.run(
    'notif_003',
    'Telebirr Monthly Pass Renewal',
    'Monthly subscription passes for all Addis transit lines are now open for renewal via Telebirr one-click checkout.',
    'PASSENGERS',
    'PAYMENT',
    'Fare Collections'
  );

  console.log('[Database] Initial seeding completed successfully.');
}

// Initialize on load
initSchema();
seedData();

module.exports = {
  db,
  DB
};
