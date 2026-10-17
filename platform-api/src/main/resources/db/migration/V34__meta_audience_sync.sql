-- Phase 5-5: Meta Custom Audience Sync (§7.3 Этап 5)
-- Tracks behavioral segments pushed to Meta as custom audiences via CAPI.
-- Spring Boot is the ONLY CAPI sender (arch §12 #14).

CREATE TABLE meta_audience_sync (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID NOT NULL REFERENCES tenants(id),
    segment_label  VARCHAR(100) NOT NULL,        -- "price_seeker", "high_uplift", etc.
    audience_id    VARCHAR(100),                  -- Meta custom audience ID
    session_count  INT NOT NULL DEFAULT 0,
    synced_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE meta_audience_sync ENABLE ROW LEVEL SECURITY;
ALTER TABLE meta_audience_sync FORCE ROW LEVEL SECURITY;

CREATE POLICY meta_audience_sync_tenant ON meta_audience_sync
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE, DELETE ON meta_audience_sync TO mai_app;

CREATE INDEX idx_meta_audience_sync_tenant
    ON meta_audience_sync (tenant_id, segment_label, synced_at DESC);
