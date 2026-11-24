"use strict";
const initialized = new WeakMap();
async function initRidesSchema(db = require("../db").DB) {
  if (!initialized.has(db))
    initialized.set(
      db,
      Promise.resolve()
        .then(async () => {
          await require("./mobility-schema").initMobilitySchema(db);
          await require("./mobility-marketplace").initMarketplaceSchema(db);
          await db.exec(`
      CREATE TABLE IF NOT EXISTS ride_settings (id TEXT PRIMARY KEY,value TEXT NOT NULL);
      CREATE TABLE IF NOT EXISTS ride_driver_eligibility (driver_id TEXT PRIMARY KEY REFERENCES users(id),approved INTEGER NOT NULL DEFAULT 0,updated_at TEXT NOT NULL,actor_id TEXT NOT NULL);
      CREATE TABLE IF NOT EXISTS ride_vehicle_eligibility (vehicle_id TEXT PRIMARY KEY REFERENCES vehicles(id),driver_id TEXT NOT NULL REFERENCES users(id),approved INTEGER NOT NULL DEFAULT 0,tier TEXT NOT NULL,updated_at TEXT NOT NULL,actor_id TEXT NOT NULL);
      CREATE TABLE IF NOT EXISTS ride_availability (driver_id TEXT PRIMARY KEY REFERENCES users(id),vehicle_id TEXT REFERENCES vehicles(id),online INTEGER NOT NULL DEFAULT 0,latitude REAL,longitude REAL,accuracy_meters REAL,recorded_at TEXT,updated_at TEXT NOT NULL);
      CREATE TABLE IF NOT EXISTS ride_jobs (
        id TEXT PRIMARY KEY,passenger_id TEXT REFERENCES users(id),driver_id TEXT REFERENCES users(id),vehicle_id TEXT REFERENCES vehicles(id),status TEXT NOT NULL,source TEXT NOT NULL,tier TEXT NOT NULL,
        route_json TEXT NOT NULL,tariff_json TEXT NOT NULL,estimate_json TEXT NOT NULL,pin_hash TEXT,pin_cipher TEXT,idempotency_owner TEXT NOT NULL,idempotency_key TEXT NOT NULL,
        commission_cents INTEGER NOT NULL DEFAULT 0,settlement TEXT NOT NULL DEFAULT 'NONE',distance_m REAL NOT NULL DEFAULT 0,waiting_seconds INTEGER NOT NULL DEFAULT 0,
        last_sequence INTEGER NOT NULL DEFAULT 0,last_latitude REAL,last_longitude REAL,last_accuracy REAL,last_point_at TEXT,waiting_since TEXT,waiting_kind TEXT,waiting_verified_until TEXT,waiting_latitude REAL,waiting_longitude REAL,stop_requested INTEGER NOT NULL DEFAULT 0,
        created_at TEXT NOT NULL,accepted_at TEXT,arrived_at TEXT,started_at TEXT,completed_at TEXT,cancel_reason TEXT NOT NULL DEFAULT '',quality_flags TEXT NOT NULL DEFAULT '',
        UNIQUE(idempotency_owner,idempotency_key)
      );
      CREATE INDEX IF NOT EXISTS ride_job_pool ON ride_jobs(status,created_at);
      CREATE INDEX IF NOT EXISTS ride_job_driver ON ride_jobs(driver_id,status);
      CREATE INDEX IF NOT EXISTS ride_job_passenger ON ride_jobs(passenger_id,created_at);
      CREATE TABLE IF NOT EXISTS ride_samples (ride_id TEXT NOT NULL REFERENCES ride_jobs(id),sequence INTEGER NOT NULL,latitude REAL NOT NULL,longitude REAL NOT NULL,accuracy_meters REAL NOT NULL,recorded_at TEXT NOT NULL,accepted INTEGER NOT NULL,reason TEXT NOT NULL,distance_m REAL NOT NULL,PRIMARY KEY(ride_id,sequence));
      CREATE TABLE IF NOT EXISTS ride_shared_trips (
        id TEXT PRIMARY KEY,initiator_role TEXT NOT NULL,passenger_id TEXT REFERENCES users(id),driver_id TEXT REFERENCES users(id),vehicle_id TEXT REFERENCES vehicles(id),route_json TEXT NOT NULL,estimate_json TEXT NOT NULL,
        departure_at TEXT NOT NULL,seats INTEGER NOT NULL,fare_cents INTEGER,status TEXT NOT NULL,commission_cents INTEGER NOT NULL DEFAULT 0,settlement TEXT NOT NULL DEFAULT 'NONE',hold_version INTEGER NOT NULL DEFAULT 0,
        idempotency_owner TEXT NOT NULL,idempotency_key TEXT NOT NULL,created_at TEXT NOT NULL,started_at TEXT,completed_at TEXT,cancel_reason TEXT NOT NULL DEFAULT '',UNIQUE(idempotency_owner,idempotency_key)
      );
      CREATE INDEX IF NOT EXISTS ride_shared_discovery ON ride_shared_trips(status,departure_at);
      CREATE TABLE IF NOT EXISTS ride_shared_bookings (
        id TEXT PRIMARY KEY,trip_id TEXT NOT NULL REFERENCES ride_shared_trips(id),passenger_id TEXT NOT NULL REFERENCES users(id),seats_json TEXT NOT NULL,fare_cents INTEGER NOT NULL,total_cents BIGINT NOT NULL,status TEXT NOT NULL,
        pin_hash TEXT NOT NULL,pin_cipher TEXT NOT NULL,idempotency_key TEXT NOT NULL,created_at TEXT NOT NULL,boarded_at TEXT,UNIQUE(passenger_id,idempotency_key)
      );
      CREATE TABLE IF NOT EXISTS ride_shared_seats (trip_id TEXT NOT NULL REFERENCES ride_shared_trips(id),seat_number INTEGER NOT NULL,booking_id TEXT NOT NULL REFERENCES ride_shared_bookings(id),PRIMARY KEY(trip_id,seat_number));
      CREATE TABLE IF NOT EXISTS ride_shared_locations (trip_id TEXT PRIMARY KEY REFERENCES ride_shared_trips(id),driver_id TEXT NOT NULL,latitude REAL NOT NULL,longitude REAL NOT NULL,accuracy_meters REAL NOT NULL,recorded_at TEXT NOT NULL);
      CREATE TABLE IF NOT EXISTS ride_pin_attempts (ride_id TEXT NOT NULL,actor_id TEXT NOT NULL,attempts INTEGER NOT NULL DEFAULT 0,blocked_until TEXT,PRIMARY KEY(ride_id,actor_id));
      CREATE TABLE IF NOT EXISTS ride_sms_outbox (
        id TEXT PRIMARY KEY,event_key TEXT NOT NULL UNIQUE,ride_id TEXT NOT NULL,phone TEXT NOT NULL,message TEXT NOT NULL,
        status TEXT NOT NULL DEFAULT 'PENDING',attempts INTEGER NOT NULL DEFAULT 0,next_attempt_at TEXT NOT NULL,
        lease_token TEXT,lease_until TEXT,sent_at TEXT,created_at TEXT NOT NULL,gateway_job_id TEXT,failure_code TEXT
      );
      CREATE INDEX IF NOT EXISTS ride_sms_delivery ON ride_sms_outbox(status,next_attempt_at);
    `);
          if (db.isPostgres) {
            await db.prepare('ALTER TABLE ride_sms_outbox ADD COLUMN IF NOT EXISTS gateway_job_id TEXT').run();
            await db.prepare('ALTER TABLE ride_sms_outbox ADD COLUMN IF NOT EXISTS failure_code TEXT').run();
          } else {
            const columns = await db.prepare('PRAGMA table_info(ride_sms_outbox)').all();
            if (!columns.some(column => column.name === 'gateway_job_id')) await db.prepare('ALTER TABLE ride_sms_outbox ADD COLUMN gateway_job_id TEXT').run();
            if (!columns.some(column => column.name === 'failure_code')) await db.prepare('ALTER TABLE ride_sms_outbox ADD COLUMN failure_code TEXT').run();
          }
        })
        .catch((error) => {
          initialized.delete(db);
          throw error;
        }),
    );
  return initialized.get(db);
}
module.exports = { initRidesSchema };
