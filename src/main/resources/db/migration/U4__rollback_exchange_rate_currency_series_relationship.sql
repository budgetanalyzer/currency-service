-- Rollback migration for V4: Remove exchange_rate to currency_series relationship
--
-- WARNING: This rollback script is provided for documentation purposes.
-- Flyway Community Edition does NOT automatically execute undo migrations.
-- To rollback, you must manually execute this script.
--
-- Rollback Impact:
--   - Removes foreign key constraint between exchange_rate and currency_series
--   - Removes currency_series_id column from exchange_rate table
--   - Removes index on currency_series_id
--   - NO DATA LOSS: target_currency column is preserved (was never removed)
--
-- When to use this rollback:
--   1. Critical bug discovered in relationship implementation
--   2. Performance issues with foreign key constraint (unlikely)
--   3. Need to revert architecture decision (should be rare)
--
-- Steps to rollback:
--   1. Drop index on currency_series_id
--   2. Drop foreign key constraint
--   3. Drop currency_series_id column
--
-- Note: target_currency column and its indexes remain unchanged (they were
-- never modified in V4 migration due to denormalization design).

-- Step 1: Drop index on currency_series_id
DROP INDEX IF EXISTS idx_exchange_rate_currency_series_id;

-- Step 2: Drop foreign key constraint
ALTER TABLE exchange_rate
    DROP CONSTRAINT IF EXISTS fk_exchange_rate_currency_series;

-- Step 3: Drop currency_series_id column
ALTER TABLE exchange_rate
    DROP COLUMN IF EXISTS currency_series_id;

-- Result: exchange_rate table returns to its pre-V4 state
--   - target_currency column still exists (unchanged)
--   - All existing indexes remain (idx_exchange_rate_target_currency, etc.)
--   - No data loss (target_currency was never removed)
--   - Foreign key relationship removed
