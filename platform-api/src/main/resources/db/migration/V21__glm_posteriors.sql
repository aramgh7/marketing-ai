-- Bayesian GLM posterior summaries per tenant per atom effect
-- Fitted by ml-service (PyMC); read by /glm/posteriors and predict endpoint

CREATE TABLE glm_posteriors (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    model_stage VARCHAR(10) NOT NULL,    -- CTR, CVR, AOV
    atom_type   VARCHAR(100) NOT NULL,   -- β_hook, β_cta, β_offer, β_face, β_match_ctr, mu_aov …
    atom_value  VARCHAR(200) NOT NULL,   -- hook label or "__scalar__"
    mean        REAL NOT NULL,
    std         REAL NOT NULL,
    hdi_low     REAL,                    -- 94% HDI lower bound
    hdi_high    REAL,                    -- 94% HDI upper bound
    n_obs       INT NOT NULL DEFAULT 0,
    fitted_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(tenant_id, model_stage, atom_type, atom_value)
);
CREATE INDEX idx_glm_posteriors_tenant ON glm_posteriors(tenant_id, model_stage);

-- Audit trail of fit runs (one row per tenant per run)
CREATE TABLE glm_fit_runs (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    status      VARCHAR(20) NOT NULL DEFAULT 'RUNNING',   -- RUNNING, DONE, FAILED
    n_obs_ctr   INT,
    n_obs_cvr   INT,
    n_obs_aov   INT,
    r_hat_max   REAL,        -- max Gelman-Rubin statistic across all chains/params
    error_msg   TEXT,
    started_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ
);
