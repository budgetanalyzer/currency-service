-- Rollback script for V5__add_event_publication_table.sql
-- WARNING: This will delete all event publication records including audit history

DROP INDEX IF EXISTS idx_event_publication_publication_date;
DROP INDEX IF EXISTS idx_event_publication_event_type;
DROP INDEX IF EXISTS idx_event_publication_unpublished;
DROP TABLE IF EXISTS event_publication;
