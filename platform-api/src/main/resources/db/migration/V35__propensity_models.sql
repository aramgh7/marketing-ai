-- Phase 5-2: Propensity Model Storage
-- Stores trained GradientBoosting model per tenant (base64-encoded joblib bytes).

CREATE TABLE propensity_models (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID NOT NULL REFERENCES tenants(id),
    model_b64   TEXT NOT NULL,               -- base64-encoded joblib Pipeline bytes
    n_samples   INT NOT NULL DEFAULT 0,
    n_positives INT NOT NULL DEFAULT 0,
    fitted_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE propensity_models ENABLE ROW LEVEL SECURITY;
ALTER TABLE propensity_models FORCE ROW LEVEL SECURITY;

CREATE POLICY propensity_models_tenant ON propensity_models
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE, DELETE ON propensity_models TO mai_app;

CREATE INDEX idx_propensity_models_tenant_fitted
    ON propensity_models (tenant_id, fitted_at DESC);
