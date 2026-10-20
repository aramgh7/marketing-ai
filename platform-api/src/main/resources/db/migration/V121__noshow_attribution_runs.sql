-- Roadmap 8-3: no-show attribution model runs (vision §19.5; arch §16.6)
-- Weekly fit of logit(show) = Σ β_j·atom_j + γ·booking_distance + δ·tz_known + segment.
CREATE TABLE noshow_attribution_runs (
    id             UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID        NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    run_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    n_bookings     INT         NOT NULL,
    show_rate      REAL,
    fitted         BOOLEAN     NOT NULL DEFAULT FALSE,
    model_version  VARCHAR(40),
    coefficients   JSONB       NOT NULL DEFAULT '[]',   -- [{feature, beta, se, z, odds_ratio}]
    drivers        JSONB       NOT NULL DEFAULT '[]',   -- atoms that lower P(show): [{atom_type, atom_value, beta, ...}]
    reason         TEXT
);
CREATE INDEX idx_noshow_attr_tenant_time ON noshow_attribution_runs(tenant_id, run_at DESC);
ALTER TABLE noshow_attribution_runs ENABLE ROW LEVEL SECURITY;
ALTER TABLE noshow_attribution_runs FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON noshow_attribution_runs
    USING ((tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
           OR current_setting('app.rls_bypass', true) = 'on');
GRANT SELECT, INSERT, UPDATE, DELETE ON noshow_attribution_runs TO mai_app;
