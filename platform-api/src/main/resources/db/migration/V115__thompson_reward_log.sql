-- Audit fix §2 (Thompson learning loop)

-- 2.4: /decide/landing is called before consent with session_id = null (arch §5.2, vision §12.1a)
ALTER TABLE decision_log ALTER COLUMN session_id DROP NOT NULL;

-- 2.8: one reward of a given type per decision (idempotent under webhook retries / double clicks).
-- 2.3: the NO_RESPONSE_7D sweep records its negative here so each decision is one Bernoulli observation.
CREATE TABLE thompson_reward_log (
    decision_id   UUID        NOT NULL REFERENCES decision_log(id) ON DELETE CASCADE,
    reward_type   VARCHAR(40) NOT NULL,
    tenant_id     UUID        NOT NULL REFERENCES tenants(id),
    arm_id        UUID        REFERENCES thompson_arms(id) ON DELETE SET NULL,
    field_updated VARCHAR(8)  NOT NULL,          -- alpha | beta | none
    applied_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (decision_id, reward_type)
);
CREATE INDEX idx_thompson_reward_log_tenant ON thompson_reward_log(tenant_id, applied_at DESC);
ALTER TABLE thompson_reward_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE thompson_reward_log FORCE ROW LEVEL SECURITY;
CREATE POLICY thompson_reward_log_tenant ON thompson_reward_log
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
-- DELETE is needed: a late positive reward reverses an earlier NO_RESPONSE_7D sweep row.
GRANT SELECT, INSERT, DELETE ON thompson_reward_log TO mai_app;

-- Sweep predicate: decisions in the attribution window with no reward row yet.
CREATE INDEX idx_decision_log_decided_at ON decision_log(decided_at);
