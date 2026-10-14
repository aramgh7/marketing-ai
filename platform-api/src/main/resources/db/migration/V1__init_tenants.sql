CREATE EXTENSION IF NOT EXISTS "pgcrypto";
CREATE EXTENSION IF NOT EXISTS "vector";

CREATE TABLE tenants (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name                  VARCHAR(255) NOT NULL,
    slug                  VARCHAR(100) UNIQUE NOT NULL,
    niche                 VARCHAR(100),              -- "b2b_manufacturing", "ecommerce_fashion"
    meta_app_id           VARCHAR(100),
    meta_access_token_enc BYTEA,                     -- encrypted (AES in MVP, Vault in prod)
    settings              JSONB DEFAULT '{}',
    created_at            TIMESTAMPTZ DEFAULT now(),
    updated_at            TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE users (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID NOT NULL REFERENCES tenants(id),
    email         VARCHAR(255) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role          VARCHAR(50) NOT NULL DEFAULT 'MARKETER',  -- ADMIN, MARKETER, VIEWER
    status        VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at    TIMESTAMPTZ DEFAULT now(),
    updated_at    TIMESTAMPTZ DEFAULT now()
);

CREATE INDEX idx_users_tenant ON users(tenant_id);
CREATE INDEX idx_users_email  ON users(email);
