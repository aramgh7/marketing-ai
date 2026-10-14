-- Every Thompson Sampling decision logged with full context (mandatory for off-policy eval, §23)
CREATE TABLE decision_log (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID NOT NULL,
    session_id        VARCHAR(100) NOT NULL,
    creative_id       UUID,
    chosen_variant_id UUID NOT NULL,
    -- Full candidate set with probabilities: [{"variant_id":"...","sample_value":0.42,"alpha":12,"beta":88},...]
    candidates        JSONB NOT NULL,
    context           JSONB,   -- device, geo, utm_*, time_of_day, etc.
    model_version     VARCHAR(50),
    decided_at        TIMESTAMPTZ DEFAULT now()
);

CREATE INDEX idx_decisions_tenant_time ON decision_log(tenant_id, decided_at);
