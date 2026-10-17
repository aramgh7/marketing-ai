-- V85: model_quality_snapshots — periodic ML model quality metrics (Block F §8)
CREATE TABLE model_quality_snapshots (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID         NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    model_name      VARCHAR(64)  NOT NULL,   -- thompson, cluster, glm, mmm, burnout
    metric_name     VARCHAR(64)  NOT NULL,   -- log_loss, brier, calibration_error, rmse
    metric_value    NUMERIC(12,6) NOT NULL,
    sample_size     INT          NOT NULL DEFAULT 0,
    context_key     VARCHAR(128),
    evaluated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    window_days     INT          NOT NULL DEFAULT 30
);

ALTER TABLE model_quality_snapshots ENABLE ROW LEVEL SECURITY;
ALTER TABLE model_quality_snapshots FORCE ROW LEVEL SECURITY;

CREATE POLICY mqs_tenant_isolation ON model_quality_snapshots
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE, DELETE ON model_quality_snapshots TO mai_app;

-- index for fast latest-snapshot lookups
CREATE INDEX idx_mqs_tenant_model ON model_quality_snapshots(tenant_id, model_name, evaluated_at DESC);
