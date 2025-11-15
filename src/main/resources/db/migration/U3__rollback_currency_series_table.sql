-- Rollback migration for V3__create_currency_series_table.sql
--
-- WARNING: This rollback script is provided for documentation purposes.
-- Flyway Community Edition does NOT automatically execute undo migrations.
-- To rollback, you must manually execute this script.
--
-- Rollback Impact:
--   - Removes currency_series table
--   - Removes all indexes on currency_series table (cascaded)
--   - DATA LOSS: All currency series records will be deleted
--   - BREAKING CHANGE: Any foreign keys referencing currency_series will fail
--
-- When to use this rollback:
--   1. Critical bug discovered in table structure
--   2. Need to redesign currency_series schema
--   3. Reverting to hardcoded currency configuration
--
-- Prerequisites before rollback:
--   - Must rollback V4 first (removes foreign key from exchange_rate table)
--   - Backup currency_series data if needed for future restoration
--
-- Note: This will cascade drop all indexes (idx_currency_series_provider_series_id,
-- idx_currency_series_enabled) automatically.

DROP TABLE IF EXISTS currency_series;

-- Result: currency_series table completely removed
--   - All data deleted (including any default currencies from V6)
--   - All indexes dropped automatically
--   - Application will need alternative currency configuration method
