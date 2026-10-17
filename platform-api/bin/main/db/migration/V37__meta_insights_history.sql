-- Phase 6-2: Meta ad-set insights time series (reach/frequency)
CREATE TABLE meta_insights_history (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID        NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    ad_set_id       TEXT        NOT NULL,
    reach           BIGINT      NOT NULL DEFAULT 0,
    impressions     BIGINT      NOT NULL DEFAULT 0,
    spend           NUMERIC(12,4) NOT NULL DEFAULT 0,
    frequency       NUMERIC(6,3) NOT NULL DEFAULT 0,
    clicks          BIGINT      NOT NULL DEFAULT 0,
    ctr             NUMERIC(8,6),
    recorded_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (ad_set_id, recorded_at)
);

CREATE INDEX idx_insights_tenant_adset ON meta_insights_history(tenant_id, ad_set_id, recorded_at DESC);

ALTER TABLE meta_insights_history ENABLE ROW LEVEL SECURITY;
CREATE POLICY insights_tenant_isolation ON meta_insights_history
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
