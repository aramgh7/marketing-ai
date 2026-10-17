-- Refresh tokens for secure token rotation (0-3)
CREATE TABLE refresh_tokens (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID        NOT NULL REFERENCES tenants(id),
    user_id     UUID        NOT NULL REFERENCES users(id),
    token_hash  VARCHAR(64) NOT NULL UNIQUE,
    expires_at  TIMESTAMPTZ NOT NULL,
    revoked     BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE refresh_tokens ENABLE ROW LEVEL SECURITY;
ALTER TABLE refresh_tokens FORCE ROW LEVEL SECURITY;
CREATE POLICY refresh_tokens_tenant ON refresh_tokens
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON refresh_tokens TO mai_app;

CREATE INDEX refresh_tokens_hash_idx ON refresh_tokens(token_hash) WHERE NOT revoked;
CREATE INDEX refresh_tokens_user_idx  ON refresh_tokens(user_id, expires_at);
