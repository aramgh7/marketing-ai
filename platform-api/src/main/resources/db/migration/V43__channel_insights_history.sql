-- Phase 7-2: Daily cross-channel insights for Bayesian MMM
CREATE TABLE channel_insights_history (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID        NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    channel         TEXT        NOT NULL,
    account_id      TEXT,
    campaign_id     TEXT,
    ad_set_id       TEXT,
    date            DATE        NOT NULL,
    spend           NUMERIC(12,2) NOT NULL DEFAULT 0,
    impressions     BIGINT      NOT NULL DEFAULT 0,
    clicks          BIGINT      NOT NULL DEFAULT 0,
    conversions     BIGINT      NOT NULL DEFAULT 0,
    revenue         NUMERIC(12,2) NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(tenant_id, channel, campaign_id, ad_set_id, date)
);

CREATE INDEX idx_channel_insights_tenant_channel ON channel_insights_history(tenant_id, channel);
CREATE INDEX idx_channel_insights_date ON channel_insights_history(tenant_id, date DESC);

ALTER TABLE channel_insights_history ENABLE ROW LEVEL SECURITY;
ALTER TABLE channel_insights_history FORCE ROW LEVEL SECURITY;
CREATE POLICY channel_insights_tenant_isolation ON channel_insights_history
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE, DELETE ON channel_insights_history TO mai_app;
