-- Phase 7-4: Bayesian MMM results storage
CREATE TABLE mmm_results (
    id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID        NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    model_version       TEXT        NOT NULL,
    channels            JSONB       NOT NULL,
    contributions       JSONB       NOT NULL,
    saturation_curves   JSONB       NOT NULL,
    budget_allocation   JSONB       NOT NULL,
    total_budget        NUMERIC(12,2),
    date_range_start    DATE        NOT NULL,
    date_range_end      DATE        NOT NULL,
    n_obs               INT         NOT NULL,
    fit_metrics         JSONB       NOT NULL DEFAULT '{}',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE mmm_results ENABLE ROW LEVEL SECURITY;
ALTER TABLE mmm_results FORCE ROW LEVEL SECURITY;
CREATE POLICY mmm_results_tenant_isolation ON mmm_results
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE, DELETE ON mmm_results TO mai_app;
