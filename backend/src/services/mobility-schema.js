'use strict';
const { DB } = require('../db');
const schemas = new WeakMap();
async function initMobilitySchema(db = DB) {
  if (!schemas.has(db)) schemas.set(db, Promise.resolve().then(() => db.exec(`
    CREATE TABLE IF NOT EXISTS mobility_trips (
      id TEXT PRIMARY KEY, driver_id TEXT NOT NULL REFERENCES users(id), vehicle_id TEXT NOT NULL REFERENCES vehicles(id),
      origin TEXT NOT NULL, destination TEXT NOT NULL, departure_at TEXT NOT NULL,
      latitude REAL NOT NULL, longitude REAL NOT NULL, seats INTEGER NOT NULL CHECK(seats BETWEEN 1 AND 100),
      fare_etb NUMERIC NOT NULL CHECK(fare_etb>0), status TEXT NOT NULL DEFAULT 'PENDING_REVIEW',
      review_note TEXT NOT NULL DEFAULT '', reviewed_by TEXT, reviewed_at TEXT, created_at TEXT NOT NULL
    );
    CREATE INDEX IF NOT EXISTS mobility_trip_search ON mobility_trips(status,departure_at);
    CREATE TABLE IF NOT EXISTS mobility_bookings (
      id TEXT PRIMARY KEY, trip_id TEXT NOT NULL REFERENCES mobility_trips(id), secret_hash TEXT NOT NULL,
      buyer_name TEXT NOT NULL, buyer_phone TEXT NOT NULL, amount_etb NUMERIC NOT NULL CHECK(amount_etb>0),
      status TEXT NOT NULL DEFAULT 'HOLD', payment_status TEXT NOT NULL DEFAULT 'UNPAID',
      order_id TEXT UNIQUE, prepay_id TEXT, checkout_url TEXT, payment_reference TEXT,
      checkout_phase TEXT, checkout_lease_until TEXT, expires_at TEXT NOT NULL, created_at TEXT NOT NULL
    );
    CREATE INDEX IF NOT EXISTS mobility_booking_trip ON mobility_bookings(trip_id,payment_status);
    CREATE TABLE IF NOT EXISTS mobility_travelers (
      id TEXT PRIMARY KEY, booking_id TEXT NOT NULL REFERENCES mobility_bookings(id), trip_id TEXT NOT NULL REFERENCES mobility_trips(id),
      name TEXT NOT NULL, phone TEXT NOT NULL, seat_number INTEGER NOT NULL, ticket_number TEXT NOT NULL UNIQUE,
      status TEXT NOT NULL DEFAULT 'RESERVED', boarded_at TEXT
    );
    CREATE TABLE IF NOT EXISTS mobility_seats (
      trip_id TEXT NOT NULL REFERENCES mobility_trips(id), seat_number INTEGER NOT NULL,
      traveler_id TEXT NOT NULL UNIQUE REFERENCES mobility_travelers(id), booking_id TEXT NOT NULL REFERENCES mobility_bookings(id),
      PRIMARY KEY(trip_id,seat_number)
    );
    CREATE TABLE IF NOT EXISTS mobility_requests (
      id TEXT PRIMARY KEY, kind TEXT NOT NULL CHECK(kind IN ('GROUP','CARGO')), secret_hash TEXT NOT NULL,
      origin TEXT NOT NULL, destination TEXT NOT NULL, travel_date TEXT NOT NULL, people INTEGER,
      vehicle_type TEXT NOT NULL, customer_name TEXT NOT NULL, customer_phone TEXT NOT NULL, details TEXT NOT NULL DEFAULT '',
      origin_latitude REAL, origin_longitude REAL, destination_latitude REAL, destination_longitude REAL,
      photo_storage TEXT, photo_type TEXT, photo_size INTEGER, status TEXT NOT NULL,
      driver_id TEXT, vehicle_id TEXT, quote_etb NUMERIC, review_note TEXT NOT NULL DEFAULT '',
      assigned_by TEXT, assigned_at TEXT, created_at TEXT NOT NULL
    );
    CREATE INDEX IF NOT EXISTS mobility_request_queue ON mobility_requests(kind,status,created_at);
    CREATE TABLE IF NOT EXISTS mobility_sms_outbox (
      id TEXT PRIMARY KEY, event_key TEXT NOT NULL UNIQUE, booking_id TEXT REFERENCES mobility_bookings(id),
      request_id TEXT REFERENCES mobility_requests(id), assignment_key TEXT,
      phone TEXT NOT NULL, message TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'PENDING',
      attempts INTEGER NOT NULL DEFAULT 0, next_attempt_at TEXT NOT NULL, created_at TEXT NOT NULL,
      lease_token TEXT, lease_until TEXT, sent_at TEXT
    );
    CREATE INDEX IF NOT EXISTS mobility_sms_delivery ON mobility_sms_outbox(status,next_attempt_at);
  `)).catch(error => { schemas.delete(db); throw error; }));
  return schemas.get(db);
}
module.exports = { initMobilitySchema };
