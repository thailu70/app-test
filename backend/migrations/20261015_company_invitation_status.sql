-- RoutePass company invitation status compatibility migration
-- Safe to run repeatedly. Preserves existing ACTIVE and INACTIVE values.
BEGIN;

ALTER TABLE transport_companies
  DROP CONSTRAINT IF EXISTS transport_companies_status_check;

ALTER TABLE transport_companies
  ADD CONSTRAINT transport_companies_status_check
  CHECK (status IN ('ACTIVE', 'INACTIVE', 'INVITED'));

COMMIT;
