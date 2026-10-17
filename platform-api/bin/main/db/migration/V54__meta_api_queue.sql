-- Meta API Priority Queue + Learning Phase Log (vision §10.3, §6.5) — Phase 3 (arch §15.20)

CREATE TABLE learning_phase_log (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    ad_set_id   UUID        NOT NULL REFERENCES meta_ad_sets(id),
    tenant_id   UUID        NOT NULL REFERENCES tenants(id),
    phase_before VARCHAR(30),                            -- LEARNING, LEARNING_LIMITED, ACTIVE, etc.
    phase_after  VARCHAR(30),
    action      VARCHAR(50) NOT NULL,                    -- LAUNCH, PAUSE, BUDGET_CHANGE, RESUME
    logged_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_lp_log_adset ON learning_phase_log(ad_set_id, logged_at DESC);
GRANT SELECT, INSERT, UPDATE, DELETE ON learning_phase_log TO mai_app;
