-- Proxy-Value Calibration + CRM Discipline (vision §16.1, §22) — Phase 3 (arch §15.19)

CREATE TABLE proxy_calibration_runs (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID        NOT NULL REFERENCES tenants(id),
    run_date        DATE        NOT NULL,
    cohort_from     TIMESTAMPTZ,
    cohort_to       TIMESTAMPTZ,
    stage           VARCHAR(20) NOT NULL,               -- LEAD, SCHEDULE, PURCHASE
    proxy_value_avg REAL,
    realised_value_avg REAL,
    bias_pct        REAL,
    adjusted_multiplier REAL,
    sample_n        INT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_proxy_cal_tenant ON proxy_calibration_runs(tenant_id, run_date DESC);
ALTER TABLE proxy_calibration_runs ENABLE ROW LEVEL SECURITY;
ALTER TABLE proxy_calibration_runs FORCE ROW LEVEL SECURITY;
CREATE POLICY proxy_cal_tenant ON proxy_calibration_runs
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON proxy_calibration_runs TO mai_app;
