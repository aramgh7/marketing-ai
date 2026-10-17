-- Funnel as Configuration (vision §19.3 / arch §16.3)
-- Replaces hard-coded B2B stage chain with per-tenant DB-driven funnel.

CREATE TABLE funnel_stages (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id  UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    stage_key  VARCHAR(60) NOT NULL,
    ordinal    INT NOT NULL,
    label      VARCHAR(120),
    is_quality_key  BOOLEAN NOT NULL DEFAULT FALSE,
    is_terminal     BOOLEAN NOT NULL DEFAULT FALSE,
    maps_to_ghl_status  VARCHAR(60),
    maps_to_event_type  VARCHAR(60),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, stage_key)
);

ALTER TABLE funnel_stages ENABLE ROW LEVEL SECURITY;
CREATE POLICY rls_funnel_stages ON funnel_stages
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
ALTER TABLE funnel_stages FORCE ROW LEVEL SECURITY;
GRANT SELECT, INSERT, UPDATE, DELETE ON funnel_stages TO mai_app;

-- CAPI event map: stage_key → which pixel to fire, with what value formula
CREATE TABLE capi_event_map (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    stage_key      VARCHAR(60) NOT NULL,
    pixel_role     VARCHAR(60) NOT NULL,  -- maps to meta_pixels.role_key
    meta_event_name VARCHAR(60) NOT NULL,
    value_formula  VARCHAR(200),          -- token expression, e.g. "4000*k_accr*k_ticket"
    send_condition VARCHAR(200),          -- SQL-like condition, e.g. "self_accredited = true"
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, stage_key, pixel_role)
);

ALTER TABLE capi_event_map ENABLE ROW LEVEL SECURITY;
CREATE POLICY rls_capi_event_map ON capi_event_map
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
ALTER TABLE capi_event_map FORCE ROW LEVEL SECURITY;
GRANT SELECT, INSERT, UPDATE, DELETE ON capi_event_map TO mai_app;

-- Session-level investor qualification columns
ALTER TABLE sessions
    ADD COLUMN IF NOT EXISTS self_accredited          BOOLEAN,
    ADD COLUMN IF NOT EXISTS ticket_bucket            VARCHAR(20),  -- LT25K/25_50K/50_100K/100_250K/GT250K
    ADD COLUMN IF NOT EXISTS accreditation_verified_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS funnel_key               VARCHAR(60) DEFAULT 'b2b';

-- Appointment enrichment for investor funnel
ALTER TABLE appointments
    ADD COLUMN IF NOT EXISTS booking_distance_h  FLOAT,
    ADD COLUMN IF NOT EXISTS slot_tz             VARCHAR(60),
    ADD COLUMN IF NOT EXISTS lead_tz             VARCHAR(60),
    ADD COLUMN IF NOT EXISTS coord_confirmed_at  TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS followup_variant_id UUID;

-- Pixel role key (used by capi_event_map to select the right pixel)
ALTER TABLE meta_pixels
    ADD COLUMN IF NOT EXISTS role_key VARCHAR(60) DEFAULT 'investor_primary';

-- New event schema entries for investor funnel tracking
INSERT INTO event_schemas (event_type, schema_version, json_schema, created_at)
VALUES
    ('CALENDAR_OPEN',  1, '{"type":"object","properties":{"session_id":{"type":"string"},"page":{"type":"string"}}}', now()),
    ('SLOT_SELECTED',  1, '{"type":"object","properties":{"session_id":{"type":"string"},"slot_ts":{"type":"string"},"slot_tz":{"type":"string"}}}', now()),
    ('MATERIAL_VIEW',  1, '{"type":"object","properties":{"session_id":{"type":"string"},"material_key":{"type":"string"}}}', now()),
    ('DATAROOM_OPEN',  1, '{"type":"object","properties":{"session_id":{"type":"string"},"dataroom_id":{"type":"string"}}}', now())
ON CONFLICT DO NOTHING;
