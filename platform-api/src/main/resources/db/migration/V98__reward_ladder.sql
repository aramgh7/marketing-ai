-- 8-3: Reward Ladder — reward_events + quality_gate_log
-- reward_weight columns on funnel_stages are for analytics calibration ONLY.
-- thompson_arms uses binary 1.0 increments (FastAPI sole writer).

ALTER TABLE funnel_stages
    ADD COLUMN IF NOT EXISTS reward_weight       REAL NOT NULL DEFAULT 1.0,
    ADD COLUMN IF NOT EXISTS micro_reward_weight REAL NOT NULL DEFAULT 0.0;

CREATE TABLE reward_events (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        UUID        NOT NULL REFERENCES tenants(id),
    session_id       UUID        REFERENCES sessions(id),
    variant_id       UUID        NOT NULL,
    stage_key        VARCHAR(40) NOT NULL,
    is_quality_stage BOOLEAN     NOT NULL DEFAULT FALSE,
    reward_value     REAL        NOT NULL DEFAULT 1.0,
    rewarded_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE reward_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE reward_events FORCE ROW LEVEL SECURITY;
CREATE POLICY reward_events_tenant ON reward_events
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON reward_events TO mai_app;

CREATE INDEX reward_events_variant_idx ON reward_events(variant_id, tenant_id);
CREATE INDEX reward_events_session_idx ON reward_events(session_id);
CREATE INDEX reward_events_stage_idx   ON reward_events(tenant_id, stage_key);

CREATE TABLE quality_gate_log (
    id             UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID        NOT NULL REFERENCES tenants(id),
    session_id     UUID        REFERENCES sessions(id),
    variant_id     UUID        NOT NULL,
    stage_key      VARCHAR(40) NOT NULL,
    gate_passed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE quality_gate_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE quality_gate_log FORCE ROW LEVEL SECURITY;
CREATE POLICY quality_gate_log_tenant ON quality_gate_log
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON quality_gate_log TO mai_app;

CREATE UNIQUE INDEX quality_gate_log_session_variant
    ON quality_gate_log(session_id, variant_id, stage_key)
    WHERE session_id IS NOT NULL;
