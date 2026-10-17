-- Phase 7-1: Cross-channel account registry
CREATE TABLE channel_accounts (
    id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID        NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    channel             TEXT        NOT NULL CHECK (channel IN ('META', 'GOOGLE', 'TIKTOK', 'YOUTUBE')),
    account_id          TEXT        NOT NULL,
    name                TEXT,
    access_token_enc    BYTEA,
    refresh_token_enc   BYTEA,
    token_expires_at    TIMESTAMPTZ,
    status              TEXT        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE', 'ERROR')),
    settings            JSONB       NOT NULL DEFAULT '{}',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(tenant_id, channel, account_id)
);

CREATE INDEX idx_channel_accounts_tenant ON channel_accounts(tenant_id, channel);

ALTER TABLE channel_accounts ENABLE ROW LEVEL SECURITY;
ALTER TABLE channel_accounts FORCE ROW LEVEL SECURITY;
CREATE POLICY channel_accounts_tenant_isolation ON channel_accounts
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE, DELETE ON channel_accounts TO mai_app;
