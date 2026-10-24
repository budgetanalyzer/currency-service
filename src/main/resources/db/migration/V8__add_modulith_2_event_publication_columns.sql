-- Spring Modulith 2 Event Publication Columns
-- Spring Modulith 2 tracks publication lifecycle state, completion attempts, and resubmission
-- time on each event publication.
--
-- completion_attempts is NOT NULL with DEFAULT 0 because Modulith 2's JPA entity maps it to a
-- primitive int and cannot load NULL. The default also fills existing rows and rows inserted by
-- Modulith 1 instances, which omit the column, during a rolling deploy or rollback.
--
-- status stays nullable. Existing completed rows are backfilled to COMPLETED, the value Modulith 2
-- writes on completion. Existing incomplete rows keep NULL, which Modulith 2 treats as a legacy
-- incomplete publication and still resubmits.

ALTER TABLE event_publication
    ADD COLUMN status VARCHAR(20),
    ADD COLUMN completion_attempts INT NOT NULL DEFAULT 0,
    ADD COLUMN last_resubmission_date TIMESTAMP;

UPDATE event_publication
SET status = 'COMPLETED'
WHERE completion_date IS NOT NULL;

COMMENT ON COLUMN event_publication.status IS
    'Publication lifecycle status (PUBLISHED, PROCESSING, COMPLETED, FAILED, RESUBMITTED)';

COMMENT ON COLUMN event_publication.completion_attempts IS
    'Number of attempts made to complete the publication';

COMMENT ON COLUMN event_publication.last_resubmission_date IS
    'Timestamp when the publication was last resubmitted for processing';
