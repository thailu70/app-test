-- RoutePass: support assigning company-owned vehicles to registered drivers.
-- Safe to run repeatedly; does not modify existing user records.
BEGIN;

ALTER TABLE users
  ADD COLUMN IF NOT EXISTS company_id TEXT;

COMMIT;
