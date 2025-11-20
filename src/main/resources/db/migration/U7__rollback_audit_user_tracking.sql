-- Rollback migration for V7__add_audit_user_tracking.sql

-- Drop indexes
DROP INDEX IF EXISTS idx_currency_series_updated_by;
DROP INDEX IF EXISTS idx_currency_series_created_by;
DROP INDEX IF EXISTS idx_exchange_rate_updated_by;
DROP INDEX IF EXISTS idx_exchange_rate_created_by;

-- Remove columns from currency_series table
ALTER TABLE currency_series DROP COLUMN IF EXISTS updated_by;
ALTER TABLE currency_series DROP COLUMN IF EXISTS created_by;

-- Remove columns from exchange_rate table
ALTER TABLE exchange_rate DROP COLUMN IF EXISTS updated_by;
ALTER TABLE exchange_rate DROP COLUMN IF EXISTS created_by;
