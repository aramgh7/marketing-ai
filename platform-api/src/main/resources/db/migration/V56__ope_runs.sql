-- Off-Policy Evaluation Gate (vision §12.5, §23 Phase 3) — Phase 3 (arch §15.11)
-- Blocks autopilot upgrade until verdict = PASS

CREATE TABLE ope_runs (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID        NOT NULL REFERENCES tenants(id),
    policy_version  VARCHAR(50),
    logged_from     TIMESTAMPTZ NOT NULL,
    logged_to       TIMESTAMPTZ NOT NULL,
    ips             REAL,                               -- IPS estimate
    snips           REAL,                               -- Self-normalised IPS
    dr              REAL,                               -- Doubly-robust estimate
    ci_low          REAL,
    ci_high         REAL,
    ess             REAL,                               -- Effective sample size
    clipped_mass    REAL,                               -- Fraction of weight clipped
    verdict         VARCHAR(10),                        -- PASS, FAIL, INSUFFICIENT_DATA
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_ope_runs_tenant ON ope_runs(tenant_id, created_at DESC);
ALTER TABLE ope_runs ENABLE ROW LEVEL SECURITY;
ALTER TABLE ope_runs FORCE ROW LEVEL SECURITY;
CREATE POLICY ope_runs_tenant ON ope_runs
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON ope_runs TO mai_app;
