-- Rollback script for V1__initial_schema.sql
-- NOTE: Flyway only executes undo migrations in Teams/Enterprise editions
-- This file serves as documentation for manual rollback procedures

-- Drop indexes
DROP INDEX IF EXISTS idx_exchange_rate_target_date;
DROP INDEX IF EXISTS idx_exchange_rate_date;
DROP INDEX IF EXISTS idx_exchange_rate_target_currency;

-- Drop table
DROP TABLE IF EXISTS exchange_rate;
