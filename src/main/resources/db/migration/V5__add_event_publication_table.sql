-- Spring Modulith Event Publication Table
-- This table stores domain events for the transactional outbox pattern, ensuring guaranteed
-- message delivery even if the application crashes before publishing events to external systems.
--
-- How it works:
-- 1. Domain events are persisted to this table within the same database transaction as business data
-- 2. Spring Modulith asynchronously polls for unpublished events after transaction commits
-- 3. Event listeners process events and publish to external systems (e.g., RabbitMQ)
-- 4. Successfully processed events are marked with completion_date (but not deleted for audit trail)
--
-- Benefits:
-- - 100% guaranteed delivery - Events survive application crashes
-- - Exactly-once semantics - Events saved atomically with business data
-- - Event replay capability - Failed events can be manually replayed
-- - Audit trail - Completed events retained for troubleshooting

CREATE TABLE event_publication (
    id UUID PRIMARY KEY,
    listener_id VARCHAR(512) NOT NULL,
    event_type VARCHAR(512) NOT NULL,
    serialized_event TEXT NOT NULL,
    publication_date TIMESTAMP NOT NULL,
    completion_date TIMESTAMP
);

-- Index for finding unpublished events (completion_date IS NULL)
-- Note: Partial index (WHERE clause) not supported by H2, so we index all rows
CREATE INDEX idx_event_publication_unpublished
    ON event_publication(completion_date);

-- Index for finding events by type (useful for debugging/replay)
CREATE INDEX idx_event_publication_event_type
    ON event_publication(event_type);

-- Index for finding events by publication date (useful for monitoring/cleanup)
CREATE INDEX idx_event_publication_publication_date
    ON event_publication(publication_date);

COMMENT ON TABLE event_publication IS
    'Spring Modulith transactional outbox for guaranteed event delivery';

COMMENT ON COLUMN event_publication.id IS
    'Unique identifier for the event publication record';

COMMENT ON COLUMN event_publication.listener_id IS
    'Fully qualified method name of the event listener (e.g., com.example.Listener.onEvent)';

COMMENT ON COLUMN event_publication.event_type IS
    'Fully qualified class name of the domain event (e.g., com.example.CurrencyCreatedEvent)';

COMMENT ON COLUMN event_publication.serialized_event IS
    'JSON-serialized event payload for deserialization during processing';

COMMENT ON COLUMN event_publication.publication_date IS
    'Timestamp when the event was first published (persisted to this table)';

COMMENT ON COLUMN event_publication.completion_date IS
    'Timestamp when the event was successfully processed. NULL indicates pending/failed events.';
