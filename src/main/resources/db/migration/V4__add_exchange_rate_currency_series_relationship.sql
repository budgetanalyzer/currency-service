-- Add foreign key relationship between exchange_rate and currency_series tables
--
-- This migration establishes referential integrity between exchange rates and their
-- corresponding currency series configuration. This ensures that:
--   1. Every exchange rate is linked to a valid currency series
--   2. We can trace which provider series each rate came from
--   3. Database enforces data consistency (can't have orphaned rates)
--   4. Query efficiency is maintained through denormalized target_currency column
--
-- Design Decision: Denormalized Relationship
-- ============================================
-- We use a DENORMALIZED design where both the foreign key (currency_series_id) AND
-- the target_currency column exist. This might seem redundant since target_currency
-- could be derived from currency_series.currency_code, but it provides critical
-- performance benefits:
--
-- Why Denormalization?
--   1. READ PERFORMANCE: API queries filter by target_currency (WHERE target_currency = 'THB')
--      - With denormalization: Direct index lookup on target_currency (FAST)
--      - Without denormalization: Must JOIN to currency_series table (SLOWER)
--
--   2. LAZY LOADING AVOIDANCE: API responses only need the currency code
--      - With denormalization: exchangeRate.getTargetCurrency() reads local column (no query)
--      - Without denormalization: exchangeRate.getCurrencySeries().getCurrencyCode() triggers
--        lazy load (N+1 query problem - one query per rate!)
--
--   3. INDEX EFFICIENCY: Existing queries use compound indexes on target_currency
--      - idx_exchange_rate_target_currency
--      - idx_exchange_rate_target_date
--      - These indexes would be useless without the denormalized column
--
--   4. BACKWARD COMPATIBILITY: Existing queries continue to work unchanged
--
-- Trade-off: ~3 bytes of storage redundancy per row in exchange for 10-100x query performance
--
-- Migration Steps:
--   1. Add currency_series_id column (nullable initially for data migration)
--   2. Populate currency_series_id by matching target_currency to currency_series.currency_code
--   3. Make currency_series_id NOT NULL (after data migration)
--   4. Add foreign key constraint with referential integrity
--   5. Add index on currency_series_id for JOIN performance
--   6. Keep target_currency column and existing indexes (denormalization for read performance)

-- Step 1: Add currency_series_id column (nullable initially to allow data migration)
ALTER TABLE exchange_rate
    ADD COLUMN currency_series_id BIGINT;

COMMENT ON COLUMN exchange_rate.currency_series_id IS 'Foreign key to currency_series table. Links each exchange rate to its source currency series configuration. Denormalized alongside target_currency column for query performance (avoids JOIN in common queries).';

-- Step 2: Populate currency_series_id by matching target_currency to currency_series.currency_code
--
-- This data migration links existing exchange rates to their corresponding currency series.
-- If an exchange rate exists for a currency that doesn't have a currency_series record,
-- this will fail (which is correct - we need the series configuration).
--
-- Example: If exchange_rate has target_currency='THB', this finds currency_series
--          where currency_code='THB' and sets currency_series_id to that series's id.
UPDATE exchange_rate er
SET currency_series_id = (
    SELECT cs.id
    FROM currency_series cs
    WHERE cs.currency_code = er.target_currency
);

-- Step 3: Make currency_series_id NOT NULL
--
-- After data migration, enforce that all exchange rates must have a currency series.
-- If this fails, it means there are exchange rates for currencies without a
-- corresponding currency_series record (data integrity violation).
ALTER TABLE exchange_rate
    ALTER COLUMN currency_series_id SET NOT NULL;

-- Step 4: Add foreign key constraint
--
-- ON DELETE RESTRICT: Prevent deletion of currency_series if exchange rates exist
--   - This is the safe default: don't lose historical data accidentally
--   - To remove a currency series, must first decide what to do with rates:
--     a) Delete rates manually first (if data no longer needed)
--     b) Change to ON DELETE CASCADE in future (if we want automatic cleanup)
--
-- Why RESTRICT? Exchange rate data is financial/historical data that should be preserved.
-- If we decide to stop supporting a currency (disable it in currency_series), we still
-- want the historical rates. Only explicit administrative action should delete rates.
ALTER TABLE exchange_rate
    ADD CONSTRAINT fk_exchange_rate_currency_series
        FOREIGN KEY (currency_series_id)
        REFERENCES currency_series (id)
        ON DELETE RESTRICT;

-- Note: COMMENT ON CONSTRAINT is PostgreSQL-specific and not supported by H2
-- In production (PostgreSQL), you can manually add this comment if desired:
-- COMMENT ON CONSTRAINT fk_exchange_rate_currency_series ON exchange_rate IS
--     'Ensures referential integrity between exchange rates and currency series. ' ||
--     'ON DELETE RESTRICT prevents accidental loss of historical exchange rate data.';

-- Step 5: Add index on currency_series_id for JOIN performance
--
-- This index is used when:
--   1. Joining exchange_rate to currency_series (e.g., for admin/audit queries)
--   2. Enforcing foreign key constraint (database uses this for cascading checks)
--   3. Querying rates by series: SELECT * FROM exchange_rate WHERE currency_series_id = ?
--
-- Note: Most API queries will still use idx_exchange_rate_target_currency because
-- they filter by currency code, not series ID. This index is for administrative
-- queries and JOIN operations.
CREATE INDEX idx_exchange_rate_currency_series_id
    ON exchange_rate (currency_series_id);

COMMENT ON INDEX idx_exchange_rate_currency_series_id IS 'Index on currency_series_id for JOIN performance and foreign key constraint enforcement. Used primarily for administrative queries and audit trails.';

-- Step 6: target_currency column remains unchanged
--
-- CRITICAL: We do NOT remove target_currency column or its indexes.
-- This is intentional denormalization for read performance.
--
-- Existing indexes (kept for performance):
--   - idx_exchange_rate_target_currency: For queries filtering by currency
--   - idx_exchange_rate_target_date: For queries filtering by currency AND date
--   - uk_exchange_rate_currency_date: Unique constraint on (base_currency, target_currency, date)
--
-- These indexes enable fast queries without JOINing to currency_series:
--   SELECT * FROM exchange_rate WHERE target_currency = 'THB' AND date BETWEEN '2024-01-01' AND '2024-12-31'
--
-- Performance comparison:
--   With denormalization: Single index scan on idx_exchange_rate_target_date
--   Without denormalization: JOIN to currency_series + index scan (2-5x slower)

-- Summary of Changes:
--   ✓ Added currency_series_id column with NOT NULL constraint
--   ✓ Migrated existing data (matched target_currency to currency_code)
--   ✓ Added foreign key constraint with ON DELETE RESTRICT
--   ✓ Added index on currency_series_id for JOINs
--   ✓ Kept target_currency column for query performance (denormalization)
--   ✓ Kept all existing indexes unchanged
--
-- Query Performance Impact:
--   - API queries (by target_currency): No change (still use existing indexes)
--   - Admin queries (JOIN to series): Improved (new index on currency_series_id)
--   - Data integrity: Improved (foreign key constraint)
--   - Storage cost: ~8 bytes per row (BIGINT foreign key)
