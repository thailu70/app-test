'use strict';
const { DB } = require('../db');

// Separate tables keep anonymous intercity tickets out of commuter subscriptions.
async function initIntercitySchema() {
  await DB.exec(`
    CREATE TABLE IF NOT EXISTS intercity_departures (
      id TEXT PRIMARY KEY, company_id TEXT NOT NULL, route_id TEXT NOT NULL,
      driver_id TEXT NOT NULL, vehicle_id TEXT NOT NULL,
      origin TEXT NOT NULL, destination TEXT NOT NULL, departure_at TEXT NOT NULL,
      latitude REAL NOT NULL, longitude REAL NOT NULL, radius_m INTEGER NOT NULL DEFAULT 300,
      seats INTEGER NOT NULL CHECK(seats > 0), fare_etb NUMERIC NOT NULL CHECK(fare_etb > 0),
      status TEXT NOT NULL DEFAULT 'SCHEDULED', created_at TEXT NOT NULL
    );
    CREATE INDEX IF NOT EXISTS intercity_departure_search ON intercity_departures(status, departure_at);
    CREATE INDEX IF NOT EXISTS intercity_departure_company ON intercity_departures(company_id, departure_at);
    CREATE TABLE IF NOT EXISTS intercity_bookings (
      id TEXT PRIMARY KEY, departure_id TEXT NOT NULL REFERENCES intercity_departures(id),
      company_id TEXT NOT NULL, secret_hash TEXT NOT NULL, passenger_name TEXT NOT NULL DEFAULT '',
      passenger_phone TEXT NOT NULL DEFAULT '', seat_number INTEGER NOT NULL,
      amount_etb NUMERIC NOT NULL, status TEXT NOT NULL DEFAULT 'HOLD',
      payment_status TEXT NOT NULL DEFAULT 'UNPAID', payment_method TEXT,
      payment_reference TEXT, collected_by TEXT, collected_note TEXT,
      order_id TEXT UNIQUE, prepay_id TEXT, checkout_url TEXT,
      expires_at TEXT NOT NULL, boarded_at TEXT, created_at TEXT NOT NULL
    );
    CREATE INDEX IF NOT EXISTS intercity_booking_roster ON intercity_bookings(departure_id, payment_status);
    CREATE TABLE IF NOT EXISTS intercity_seats (
      departure_id TEXT NOT NULL REFERENCES intercity_departures(id), seat_number INTEGER NOT NULL,
      booking_id TEXT NOT NULL UNIQUE REFERENCES intercity_bookings(id),
      PRIMARY KEY(departure_id, seat_number)
    );
    CREATE TABLE IF NOT EXISTS intercity_route_requests (
      id TEXT PRIMARY KEY, origin TEXT NOT NULL, destination TEXT NOT NULL,
      travel_date TEXT NOT NULL, passenger_name TEXT NOT NULL, passenger_phone TEXT NOT NULL,
      company_id TEXT, status TEXT NOT NULL DEFAULT 'REQUESTED', created_at TEXT NOT NULL
    );
  `);
  // Preserve existing departure rows when this optional setting is introduced.
  if (DB.isPostgres) {
    await DB.exec('ALTER TABLE intercity_departures ADD COLUMN IF NOT EXISTS radius_m INTEGER NOT NULL DEFAULT 300');
  } else {
    const columns = await DB.prepare('PRAGMA table_info(intercity_departures)').all();
    if (!columns.some(column => column.name === 'radius_m')) await DB.exec('ALTER TABLE intercity_departures ADD COLUMN radius_m INTEGER NOT NULL DEFAULT 300');
  }
}

module.exports = { initIntercitySchema };
