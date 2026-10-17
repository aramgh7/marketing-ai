-- arch v1.1 §4.5, §12 #14: rename client_event_id → event_id.
-- V11 used "client_event_id" referencing sGTM which is removed in arch v1.1 §2.
-- event_id is now the canonical client-generated idempotency key.

DROP INDEX IF EXISTS idx_events_client_event_id;

-- Rename cascades to all partitions in PostgreSQL 14+
ALTER TABLE events RENAME COLUMN client_event_id TO event_id;

-- UNIQUE index — must include event_time (partition key) for partitioned table support
CREATE UNIQUE INDEX IF NOT EXISTS idx_events_tenant_event_dedup
    ON events(tenant_id, event_id, event_time)
    WHERE event_id IS NOT NULL;
