-- 0-11: Event Schema Registry, Retention, Right to Erasure (arch §15.12)

-- Event schema registry (platform-wide, no RLS)
CREATE TABLE event_schemas (
    event_type      VARCHAR(50)  NOT NULL,
    schema_version  SMALLINT     NOT NULL,
    json_schema     JSONB        NOT NULL,
    introduced_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deprecated_at   TIMESTAMPTZ,
    PRIMARY KEY (event_type, schema_version)
);
GRANT SELECT ON event_schemas TO mai_app;

-- Rejected events quarantine
CREATE TABLE events_rejected (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID        NOT NULL,
    event_type    VARCHAR(50),
    event_data    JSONB,
    reject_reason TEXT        NOT NULL,
    received_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE events_rejected ENABLE ROW LEVEL SECURITY;
ALTER TABLE events_rejected FORCE ROW LEVEL SECURITY;
CREATE POLICY events_rejected_tenant ON events_rejected
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON events_rejected TO mai_app;

-- GDPR erasure requests audit trail
CREATE TABLE erasure_requests (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        UUID        NOT NULL REFERENCES tenants(id),
    subject_key_type VARCHAR(20) NOT NULL CHECK (subject_key_type IN ('EXTERNAL_ID_HASH', 'CRM_USER_ID')),
    subject_key_hash VARCHAR(64) NOT NULL,
    requested_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at     TIMESTAMPTZ,
    rows_deleted     JSONB,
    requested_by     UUID        REFERENCES users(id)
);
ALTER TABLE erasure_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE erasure_requests FORCE ROW LEVEL SECURITY;
CREATE POLICY erasure_requests_tenant ON erasure_requests
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON erasure_requests TO mai_app;

-- Add columns to sessions for erasure and cross-device linkage
ALTER TABLE sessions ADD COLUMN IF NOT EXISTS external_id_hash VARCHAR(64);
ALTER TABLE sessions ADD COLUMN IF NOT EXISTS visit_count       INT NOT NULL DEFAULT 1;
CREATE INDEX IF NOT EXISTS idx_sessions_external_id ON sessions(tenant_id, external_id_hash)
    WHERE external_id_hash IS NOT NULL;

-- schema_version on events (nullable, non-breaking)
ALTER TABLE events ADD COLUMN IF NOT EXISTS schema_version SMALLINT;

-- Aggregation tables (materialised before retention sweep drops partitions)
CREATE TABLE IF NOT EXISTS agg_variant_daily (
    tenant_id   UUID NOT NULL,
    variant_id  UUID NOT NULL,
    day         DATE NOT NULL,
    sessions    INT  NOT NULL DEFAULT 0,
    conversions INT  NOT NULL DEFAULT 0,
    revenue     REAL NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id, variant_id, day)
);
GRANT SELECT, INSERT, UPDATE, DELETE ON agg_variant_daily TO mai_app;

CREATE TABLE IF NOT EXISTS agg_creative_daily (
    tenant_id   UUID NOT NULL,
    creative_id UUID NOT NULL,
    day         DATE NOT NULL,
    sessions    INT  NOT NULL DEFAULT 0,
    conversions INT  NOT NULL DEFAULT 0,
    revenue     REAL NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id, creative_id, day)
);
GRANT SELECT, INSERT, UPDATE, DELETE ON agg_creative_daily TO mai_app;

-- Seed v1 schemas for all known event types
INSERT INTO event_schemas (event_type, schema_version, json_schema) VALUES
('PAGE_VIEW',            1, '{"type":"object","properties":{"url":{"type":"string"}}}'::jsonb),
('SCROLL',               1, '{"type":"object","properties":{"depth":{"type":"number","minimum":0,"maximum":100}}}'::jsonb),
('CLICK',                1, '{"type":"object"}'::jsonb),
('HOVER',                1, '{"type":"object","properties":{"target":{"type":"string"}}}'::jsonb),
('BLOCK_VIEW',           1, '{"type":"object","properties":{"block_event_id":{"type":"string"}}}'::jsonb),
('FORM_START',           1, '{"type":"object"}'::jsonb),
('FORM_FIELD',           1, '{"type":"object","properties":{"field_name":{"type":"string"}}}'::jsonb),
('FORM_SUBMIT',          1, '{"type":"object"}'::jsonb),
('CONVERSION',           1, '{"type":"object"}'::jsonb),
('PURCHASE',             1, '{"type":"object","properties":{"value":{"type":"number","minimum":0}}}'::jsonb),
('LEAD',                 1, '{"type":"object"}'::jsonb),
('IMPRESSION',           1, '{"type":"object"}'::jsonb),
('CRM_CALL_ANSWERED',    1, '{"type":"object"}'::jsonb),
('CRM_MEETING_ATTENDED', 1, '{"type":"object"}'::jsonb),
('CRM_NO_SHOW',          1, '{"type":"object"}'::jsonb),
('CRM_DEAL_WON',         1, '{"type":"object","properties":{"value":{"type":"number"}}}'::jsonb),
('CRM_DEAL_LOST',        1, '{"type":"object"}'::jsonb)
ON CONFLICT DO NOTHING;
