-- Support explicit route type, origin, destination and a single intercity departure.
-- Safe to rerun against existing PostgreSQL databases.
BEGIN;
ALTER TABLE routes ADD COLUMN IF NOT EXISTS route_type TEXT NOT NULL DEFAULT 'DAILY_COMMUTE';
ALTER TABLE routes ADD COLUMN IF NOT EXISTS origin TEXT NOT NULL DEFAULT '';
ALTER TABLE routes ADD COLUMN IF NOT EXISTS destination TEXT NOT NULL DEFAULT '';
ALTER TABLE routes ADD COLUMN IF NOT EXISTS departure_time TEXT NOT NULL DEFAULT '06:30';
COMMIT;
