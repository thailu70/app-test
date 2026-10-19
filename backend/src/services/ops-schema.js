'use strict';

async function initOpsSchema(db) {
  await db.exec(`
    CREATE TABLE IF NOT EXISTS route_departure_settings (
      route_id TEXT PRIMARY KEY REFERENCES routes(id) ON DELETE CASCADE,
      latitude REAL,
      longitude REAL,
      radius_m INTEGER NOT NULL DEFAULT 300 CHECK (radius_m BETWEEN 25 AND 5000),
      updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
    );
    CREATE TABLE IF NOT EXISTS trip_departure_confirmations (
      trip_id TEXT PRIMARY KEY REFERENCES trips(id) ON DELETE CASCADE,
      driver_id TEXT NOT NULL,
      latitude REAL NOT NULL,
      longitude REAL NOT NULL,
      accuracy_m REAL NOT NULL,
      distance_m REAL NOT NULL,
      radius_m INTEGER NOT NULL,
      location_timestamp TEXT NOT NULL,
      confirmed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
    );
    CREATE INDEX IF NOT EXISTS idx_trip_roster ON checkin_records(tripId, passengerId, status);
    CREATE INDEX IF NOT EXISTS idx_subscription_vehicle_roster ON subscriptions(vehicleId, routeId, passengerId);
  `);
}

module.exports = { initOpsSchema };
