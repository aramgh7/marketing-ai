-- Client-generated event ID for deduplication (sGTM + CAPI overlap prevention)
-- Global unique constraint is impossible on partitioned tables; Redis handles dedup logic.
ALTER TABLE events ADD COLUMN IF NOT EXISTS client_event_id VARCHAR(100);

CREATE INDEX idx_events_client_event_id ON events(tenant_id, client_event_id)
    WHERE client_event_id IS NOT NULL;
