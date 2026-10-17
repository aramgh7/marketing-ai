-- 0-12: Multi-Tenant Security Hardening — Token Rotation + Meta API Audit Log (arch §15.15)

-- Meta token lifecycle columns on tenants
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS meta_token_status
    VARCHAR(10) DEFAULT 'OK' CHECK (meta_token_status IN ('OK', 'EXPIRING', 'INVALID'));
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS meta_token_rotated_at TIMESTAMPTZ;
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS meta_token_created_at TIMESTAMPTZ;

-- Immutable Meta API Audit Log (mai_app: INSERT + SELECT only — append semantics)
CREATE TABLE meta_api_audit (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID         REFERENCES tenants(id),
    actor_type    VARCHAR(10)  NOT NULL CHECK (actor_type IN ('USER', 'JOB', 'SYSTEM')),
    actor_id      UUID,
    action        VARCHAR(100) NOT NULL,
    target_type   VARCHAR(50),
    target_id     VARCHAR(255),
    request_hash  VARCHAR(64),
    response_code INT,
    queue_lane    VARCHAR(3)   CHECK (queue_lane IN ('P0', 'P1', 'P2')),
    duration_ms   INT,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_meta_api_audit_tenant ON meta_api_audit(tenant_id, created_at);
GRANT SELECT, INSERT ON meta_api_audit TO mai_app;
