-- Learning Phase Log (vision §6.5, §25 p.8, arch §15.20)
-- Logged before/after every Meta write. phase_after re-read 24h later.

CREATE TABLE learning_phase_log (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        UUID        NOT NULL REFERENCES tenants(id),
    ad_set_id        VARCHAR(64) NOT NULL,
    action           VARCHAR(30) NOT NULL
                         CHECK (action IN ('LAUNCH','PAUSE','RESUME','BUDGET_CHANGE','CREATIVE_SWAP','STATUS_CHANGE')),
    phase_before     VARCHAR(30),
    phase_after      VARCHAR(30),
    phase_checked_at TIMESTAMPTZ,
    meta_response    JSONB,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE learning_phase_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE learning_phase_log FORCE ROW LEVEL SECURITY;
CREATE POLICY learning_phase_log_tenant ON learning_phase_log
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON learning_phase_log TO mai_app;

CREATE INDEX idx_learning_phase_pending
    ON learning_phase_log (created_at)
    WHERE phase_after IS NULL;
