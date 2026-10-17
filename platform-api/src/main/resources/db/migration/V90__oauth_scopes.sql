-- Tenant OAuth scope recording (vision §12.7)
CREATE TABLE IF NOT EXISTS tenant_oauth_scopes (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID        NOT NULL REFERENCES tenants(id),
    provider    VARCHAR(20) NOT NULL,
    scopes      TEXT[]      NOT NULL,
    granted_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at  TIMESTAMPTZ,
    UNIQUE (tenant_id, provider)
);

ALTER TABLE tenant_oauth_scopes ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_oauth_scopes FORCE ROW LEVEL SECURITY;

CREATE POLICY oauth_scopes_tenant ON tenant_oauth_scopes
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE ON tenant_oauth_scopes TO mai_app;
