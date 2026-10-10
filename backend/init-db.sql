-- ====================================================================
-- RoutePass / Transport Navigator - PostgreSQL Production Database Schema
-- Ethiopian Scheduled Commuter Transit Management System
-- ====================================================================

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- 1. Users Table
CREATE TABLE IF NOT EXISTS users (
    id VARCHAR(64) PRIMARY KEY,
    role VARCHAR(20) NOT NULL CHECK (role IN ('PASSENGER', 'DRIVER', 'ADMIN')),
    full_name VARCHAR(120) NOT NULL,
    phone VARCHAR(30) UNIQUE NOT NULL,
    email VARCHAR(120),
    password_hash VARCHAR(255) NOT NULL,
    status VARCHAR(20) DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'SUSPENDED', 'PENDING')),
    license_number VARCHAR(50) DEFAULT '',
    company_name VARCHAR(100) DEFAULT '',
    assigned_vehicle_plate VARCHAR(30) DEFAULT '',
    applied_route_id VARCHAR(64) DEFAULT '',
    applied_route_name VARCHAR(120) DEFAULT '',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 2. Transit Routes
CREATE TABLE IF NOT EXISTS routes (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    name_am VARCHAR(120) NOT NULL,
    description TEXT,
    morning_departure VARCHAR(10) DEFAULT '06:30',
    evening_departure VARCHAR(10) DEFAULT '17:30',
    distance_km NUMERIC(5,2) DEFAULT 12.0,
    base_price_etb NUMERIC(10,2) DEFAULT 2500.0,
    active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 3. Route Stops
CREATE TABLE IF NOT EXISTS route_stops (
    id VARCHAR(64) PRIMARY KEY,
    route_id VARCHAR(64) NOT NULL REFERENCES routes(id) ON DELETE CASCADE,
    stop_name VARCHAR(100) NOT NULL,
    stop_name_am VARCHAR(100) NOT NULL,
    stop_order INTEGER NOT NULL,
    latitude NUMERIC(10,6) NOT NULL,
    longitude NUMERIC(10,6) NOT NULL,
    scheduled_morning_time VARCHAR(10),
    scheduled_evening_time VARCHAR(10),
    max_capacity INTEGER DEFAULT 25
);

-- 4. Vehicles Table (with capacity limits: MINIVAN=8, MINIBUS=14, HIGER=24, ANBESSA=30)
CREATE TABLE IF NOT EXISTS vehicles (
    id VARCHAR(64) PRIMARY KEY,
    plate_number VARCHAR(30) UNIQUE NOT NULL,
    model VARCHAR(80) NOT NULL,
    vehicle_type VARCHAR(30) NOT NULL CHECK (vehicle_type IN ('MINIVAN_8', 'MINIBUS_14', 'HIGER_24', 'ANBESSA_BUS_30')),
    capacity_limit INTEGER NOT NULL,
    current_occupancy INTEGER DEFAULT 0 CHECK (current_occupancy >= 0),
    assigned_route_id VARCHAR(64) REFERENCES routes(id) ON DELETE SET NULL,
    driver_id VARCHAR(64) REFERENCES users(id) ON DELETE SET NULL,
    driver_name VARCHAR(120),
    current_lat NUMERIC(10,6) DEFAULT 9.010000,
    current_lng NUMERIC(10,6) DEFAULT 38.760000,
    status VARCHAR(20) DEFAULT 'IN_SERVICE' CHECK (status IN ('IN_SERVICE', 'FULL', 'MAINTENANCE', 'OFF_DUTY')),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Actual GPS reports are kept separately from seeded/default display coordinates.
CREATE TABLE IF NOT EXISTS vehicle_live_locations (
    vehicle_id VARCHAR(64) PRIMARY KEY REFERENCES vehicles(id) ON DELETE CASCADE,
    latitude NUMERIC(10,6) NOT NULL,
    longitude NUMERIC(10,6) NOT NULL,
    speed NUMERIC(7,2) DEFAULT 0,
    current_stop VARCHAR(100) DEFAULT '',
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 5. Subscriptions Table
CREATE TABLE IF NOT EXISTS subscriptions (
    id VARCHAR(64) PRIMARY KEY,
    passenger_id VARCHAR(64) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    route_id VARCHAR(64) NOT NULL REFERENCES routes(id),
    pickup_stop_id VARCHAR(64),
    destination_stop_id VARCHAR(64),
    morning_schedule VARCHAR(10),
    evening_schedule VARCHAR(10),
    start_date VARCHAR(30),
    end_date VARCHAR(30),
    price_etb NUMERIC(10,2) NOT NULL,
    payment_status VARCHAR(20) DEFAULT 'UNPAID' CHECK (payment_status IN ('UNPAID', 'PENDING', 'PAID', 'REFUNDED')),
    subscription_status VARCHAR(20) DEFAULT 'PENDING' CHECK (subscription_status IN ('PENDING', 'ACTIVE', 'EXPIRED', 'CANCELLED')),
    vehicle_id VARCHAR(64),
    qr_token TEXT,
    days_remaining INTEGER DEFAULT 0,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 6. Trips Table (Real backend driver trips)
CREATE TABLE IF NOT EXISTS trips (
    id VARCHAR(64) PRIMARY KEY,
    driver_id VARCHAR(64) NOT NULL REFERENCES users(id),
    vehicle_id VARCHAR(64) NOT NULL REFERENCES vehicles(id),
    route_id VARCHAR(64) NOT NULL REFERENCES routes(id),
    direction VARCHAR(20) DEFAULT 'OUTBOUND' CHECK (direction IN ('OUTBOUND', 'INBOUND')),
    current_stop VARCHAR(100),
    current_occupancy INTEGER DEFAULT 0,
    status VARCHAR(20) DEFAULT 'IN_PROGRESS' CHECK (status IN ('IN_PROGRESS', 'COMPLETED', 'CANCELLED')),
    start_time TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    end_time TIMESTAMP WITH TIME ZONE,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 7. Boarding & Check-in Records
CREATE TABLE IF NOT EXISTS checkin_records (
    id VARCHAR(64) PRIMARY KEY,
    trip_id VARCHAR(64) NOT NULL,
    passenger_id VARCHAR(64) NOT NULL,
    passenger_name VARCHAR(120) NOT NULL,
    route_id VARCHAR(64) NOT NULL,
    stop_name VARCHAR(100) NOT NULL,
    timestamp TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    status VARCHAR(30) DEFAULT 'BOARDED' CHECK (status IN ('BOARDED', 'DENIED_CAPACITY_FULL', 'INVALID_QR', 'ALREADY_CHECKED_IN')),
    vehicle_id VARCHAR(64),
    driver_id VARCHAR(64)
);

-- 8. Payment Transactions (with Idempotency support)
CREATE TABLE IF NOT EXISTS payment_transactions (
    id VARCHAR(64) PRIMARY KEY,
    passenger_id VARCHAR(64) NOT NULL REFERENCES users(id),
    reference_number VARCHAR(100) UNIQUE NOT NULL,
    idempotency_key VARCHAR(100) UNIQUE,
    amount_etb NUMERIC(10,2) NOT NULL,
    provider VARCHAR(30) DEFAULT 'Telebirr',
    phone_number VARCHAR(30),
    date TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    status VARCHAR(20) DEFAULT 'COMPLETED' CHECK (status IN ('COMPLETED', 'PENDING', 'FAILED', 'VERIFIED')),
    notes TEXT
);

-- Keep existing installations compatible with the VERIFIED webhook/payment state.
ALTER TABLE payment_transactions DROP CONSTRAINT IF EXISTS payment_transactions_status_check;
ALTER TABLE payment_transactions
    ADD CONSTRAINT payment_transactions_status_check
    CHECK (status IN ('COMPLETED', 'PENDING', 'FAILED', 'VERIFIED'));

-- Telebirr H5 hosted checkout orders; activation requires provider status verification.
CREATE TABLE IF NOT EXISTS telebirr_payment_orders (
    merchant_order_id VARCHAR(100) PRIMARY KEY,
    idempotency_key VARCHAR(100) UNIQUE,
    prepay_id VARCHAR(200),
    checkout_url TEXT,
    passenger_id VARCHAR(64) NOT NULL REFERENCES users(id),
    subscription_id VARCHAR(64) NOT NULL REFERENCES subscriptions(id),
    route_id VARCHAR(64) NOT NULL REFERENCES routes(id),
    amount_etb NUMERIC(10,2) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'PAID', 'FAILED')),
    payment_order_id VARCHAR(200),
    transaction_id VARCHAR(200),
    last_query_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_telebirr_payment_orders_passenger ON telebirr_payment_orders(passenger_id, created_at DESC);

-- 9. Passenger Complaints
CREATE TABLE IF NOT EXISTS complaints (
    id VARCHAR(64) PRIMARY KEY,
    passenger_id VARCHAR(64) NOT NULL REFERENCES users(id),
    passenger_name VARCHAR(120) NOT NULL,
    category VARCHAR(50) NOT NULL,
    description TEXT NOT NULL,
    status VARCHAR(20) DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'INVESTIGATING', 'RESOLVED')),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 10. Operational Notifications
CREATE TABLE IF NOT EXISTS notifications (
    id VARCHAR(64) PRIMARY KEY,
    title VARCHAR(150) NOT NULL,
    message TEXT NOT NULL,
    target_audience VARCHAR(30) NOT NULL CHECK (target_audience IN ('PASSENGERS', 'TRANSPORTERS', 'ALL')),
    type VARCHAR(30) DEFAULT 'ALERT' CHECK (type IN ('ALERT', 'SERVICE', 'WEATHER', 'PAYMENT')),
    sender_name VARCHAR(100) DEFAULT 'Transport Operations',
    timestamp TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 11. Security Audit Logs
CREATE TABLE IF NOT EXISTS audit_logs (
    id SERIAL PRIMARY KEY,
    action VARCHAR(50) NOT NULL,
    user_id VARCHAR(64),
    role VARCHAR(20),
    details TEXT,
    timestamp TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- ====================================================================
-- Initial Seed Data
-- ====================================================================

-- Routes
INSERT INTO routes (id, name, name_am, description, morning_departure, evening_departure, distance_km, base_price_etb, active)
VALUES
('route_bole_merkato', 'Bole - Merkato Express', 'ቦሌ - መርካቶ ኤክስፕረስ', 'Primary transit corridor connecting Bole, Meskel Square, and Merkato.', '06:30', '17:30', 14.5, 2500.0, TRUE),
('route_megenagna_torhailoch', 'Megenagna - Torhailoch Line', 'መገናኛ - ጦር ኃይሎች መስመር', 'Cross-capital transit spanning Megenagna Hub, Kazanchis, Stadium, and Torhailoch.', '06:45', '17:15', 16.0, 2700.0, TRUE),
('route_mexico_saris', 'Mexico - Saris Abo Corridor', 'ሜክሲኮ - ሳሪስ አቦ መስመር', 'Southern transit line connecting Mexico Square, Gotera Interchange, and Saris.', '07:00', '17:45', 12.2, 2300.0, TRUE)
ON CONFLICT (id) DO NOTHING;

-- Route Stops
INSERT INTO route_stops (id, route_id, stop_name, stop_name_am, stop_order, latitude, longitude, scheduled_morning_time, scheduled_evening_time, max_capacity)
VALUES
('stop_bole_medh', 'route_bole_merkato', 'Bole Medhanialem', 'ቦሌ መድኃኔዓለም', 1, 8.995000, 38.788000, '06:30', '17:30', 20),
('stop_atlas', 'route_bole_merkato', 'Bole Atlas', 'ቦሌ አትላስ', 2, 9.006000, 38.780000, '06:42', '17:42', 20),
('stop_meskel', 'route_bole_merkato', 'Meskel Square', 'መስቀል አደባባይ', 3, 9.010000, 38.763000, '06:55', '17:55', 25),
('stop_leghar', 'route_bole_merkato', 'Leghar Station', 'ለገሃር ባቡር ጣቢያ', 4, 9.014000, 38.752000, '07:08', '18:08', 20),
('stop_tekle', 'route_bole_merkato', 'Teklehaymanot', 'ተክለሃይማኖት', 5, 9.023000, 38.742000, '07:20', '18:20', 20),
('stop_merkato', 'route_bole_merkato', 'Merkato Bus Terminal', 'መርካቶ ተርሚናል', 6, 9.031000, 38.736000, '07:35', '18:35', 30),
('stop_meg_hub', 'route_megenagna_torhailoch', 'Megenagna Terminal', 'መገናኛ ተርሚናል', 1, 9.020000, 38.802000, '06:45', '17:15', 30),
('stop_kazanchis', 'route_megenagna_torhailoch', 'Kazanchis Inter-change', 'ካዛንቺስ', 2, 9.017000, 38.775000, '07:00', '17:30', 25),
('stop_mexico_sq', 'route_megenagna_torhailoch', 'Mexico Square', 'ሜክሲኮ አደባባይ', 3, 9.011000, 38.745000, '07:18', '17:48', 25),
('stop_torhailoch', 'route_megenagna_torhailoch', 'Torhailoch Depot', 'ጦር ኃይሎች', 4, 9.005000, 38.724000, '07:35', '18:05', 25)
ON CONFLICT (id) DO NOTHING;

-- Production installs deliberately do not seed user accounts or shared default passwords.
-- Create the first administrator via the secret-gated registration endpoint after deployment.

-- Seed Vehicles (Enforcing capacities: MINIVAN=8, MINIBUS=14, HIGER=24, ANBESSA=30)
INSERT INTO vehicles (id, plate_number, model, vehicle_type, capacity_limit, current_occupancy, assigned_route_id, driver_id, driver_name, current_lat, current_lng, status)
VALUES
('veh_higer_aa_34921', '3-AA-34921', 'Higer Midibus KLQ6758', 'HIGER_24', 24, 0, 'route_bole_merkato', NULL, NULL, 9.006000, 38.780000, 'IN_SERVICE'),
('veh_minibus_aa_98210', '3-AA-98210', 'Toyota HiAce Commuter', 'MINIBUS_14', 14, 0, 'route_megenagna_torhailoch', NULL, NULL, 9.020000, 38.802000, 'IN_SERVICE'),
('veh_minivan_aa_11093', '3-AA-11093', 'Hyundai H1 Van', 'MINIVAN_8', 8, 0, 'route_mexico_saris', NULL, NULL, 9.011000, 38.745000, 'IN_SERVICE'),
('veh_anbessa_aa_55412', '3-AA-55412', 'DAF Anbessa Citybus', 'ANBESSA_BUS_30', 30, 0, 'route_bole_merkato', NULL, NULL, 9.010000, 38.763000, 'IN_SERVICE')
ON CONFLICT (id) DO NOTHING;

-- Seed Notifications
INSERT INTO notifications (id, title, message, target_audience, type, sender_name)
VALUES
('notif_init_1', 'Morning Peak Rush Advisory', 'Heavy traffic observed along Meskel Square to Leghar. Commuters advised to board 10 minutes early.', 'ALL', 'SERVICE', 'Central Traffic Dispatch'),
('notif_init_2', 'Subscription Payment Policy', 'A subscription becomes active only after verified payment confirmation from the configured provider.', 'PASSENGERS', 'PAYMENT', 'Finance Department'),
('notif_init_3', 'Safety & Capacity Compliance', 'All transporters must adhere strictly to vehicle capacity limits (8, 14, 24, 30 seats). Overboarding strictly prohibited.', 'TRANSPORTERS', 'ALERT', 'Transport Safety Bureau')
ON CONFLICT (id) DO NOTHING;
