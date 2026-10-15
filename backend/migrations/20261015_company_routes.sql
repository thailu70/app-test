-- Add company ownership for company-created routes and vehicles.
-- Safe to rerun on existing PostgreSQL deployments.
BEGIN;
ALTER TABLE routes ADD COLUMN IF NOT EXISTS company_id TEXT;
ALTER TABLE vehicles ADD COLUMN IF NOT EXISTS company_id TEXT;
COMMIT;
