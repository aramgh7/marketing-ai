-- Fraud Layer 2 (vision §12.6.2, arch §15.18)
-- fraud_mode per tenant: LOG_ONLY (default) | ENFORCE
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS fraud_mode VARCHAR(10) NOT NULL DEFAULT 'LOG_ONLY'
    CHECK (fraud_mode IN ('LOG_ONLY', 'ENFORCE'));

-- share-of-suspicious alert log (24h window, per tenant)
CREATE TABLE fraud_share_alerts (
    id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID        NOT NULL REFERENCES tenants(id),
    window_start        TIMESTAMPTZ NOT NULL,
    window_end          TIMESTAMPTZ NOT NULL,
    total_sessions      INT         NOT NULL,
    suspicious_sessions INT         NOT NULL,
    share_pct           REAL        NOT NULL,
    sources             JSONB,
    alert_sent          BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE fraud_share_alerts ENABLE ROW LEVEL SECURITY;
ALTER TABLE fraud_share_alerts FORCE ROW LEVEL SECURITY;
CREATE POLICY fraud_share_alerts_tenant ON fraud_share_alerts
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON fraud_share_alerts TO mai_app;
