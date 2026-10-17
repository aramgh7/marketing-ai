-- Phase 7-3: Cross-channel campaign registry
CREATE TABLE channel_campaigns (
    id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID        NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    channel_account_id  UUID        NOT NULL REFERENCES channel_accounts(id) ON DELETE CASCADE,
    channel             TEXT        NOT NULL,
    external_id         TEXT        NOT NULL,
    name                TEXT,
    status              TEXT,
    daily_budget        NUMERIC(10,2),
    objective           TEXT,
    synced_at           TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(tenant_id, channel, external_id)
);

ALTER TABLE channel_campaigns ENABLE ROW LEVEL SECURITY;
ALTER TABLE channel_campaigns FORCE ROW LEVEL SECURITY;
CREATE POLICY channel_campaigns_tenant_isolation ON channel_campaigns
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE, DELETE ON channel_campaigns TO mai_app;
