-- Rollback migration for V8__add_modulith_2_event_publication_columns.sql
--
-- WARNING: This rollback script is provided for documentation purposes.
-- Flyway Community Edition does NOT automatically execute undo migrations.
-- To rollback, you must manually execute this script.
--
-- Only run this script as part of a downgrade to Spring Modulith 1.x (Spring Boot 3).
-- Spring Modulith 2 maps completion_attempts to a primitive int and cannot load
-- event_publication rows without that column, so the current application will fail
-- once this script has run.
--
-- Rollback Impact:
--   - Removes status, completion_attempts, and last_resubmission_date from event_publication
--   - DATA LOSS: The values of those three columns are lost for every row
--   - NO ROW LOSS: No event publication rows are deleted
--   - Audit trail preserved: Spring Modulith 1.x distinguishes completed from incomplete
--     publications by completion_date, which this script does not touch
--   - Removes the V8 row from flyway_schema_history
--
-- Prerequisites before rollback:
--   - Stop every instance running Spring Modulith 2
--   - Backup event_publication if the lifecycle columns are needed later

BEGIN;

ALTER TABLE event_publication
    DROP COLUMN IF EXISTS status,
    DROP COLUMN IF EXISTS completion_attempts,
    DROP COLUMN IF EXISTS last_resubmission_date;

-- Remove the V8 history row so a later upgrade re-applies V8 and recreates the columns.
-- Without this, Flyway treats V8 as applied, skips it, and Spring Modulith 2 fails against
-- the rolled-back table.
DELETE FROM flyway_schema_history WHERE version = '8';

COMMIT;

-- Result: event_publication returns to its pre-V8 (V5) structure
--   - All publication rows remain, including completed rows kept as the audit trail
--   - Spring Modulith 1.x can read and resubmit incomplete publications
