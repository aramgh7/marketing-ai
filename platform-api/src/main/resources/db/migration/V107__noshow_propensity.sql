-- 8-15: No-show Propensity + Risk-Tiered Handling + Slot Overbooking (vision §19.18.2; arch §16.15)

CREATE TABLE noshow_scores (
    id             UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID        NOT NULL REFERENCES tenants(id),
    session_id     VARCHAR(100) REFERENCES sessions(id),
    appointment_id UUID        REFERENCES appointments(id),
    noshow_prob    REAL        NOT NULL,
    risk_tier      VARCHAR(10) NOT NULL
        CHECK (risk_tier IN ('LOW','MED','HIGH')),
    features       JSONB,
    model_version  VARCHAR(30) DEFAULT 'rule_based_v1',
    scored_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE noshow_scores ENABLE ROW LEVEL SECURITY;
ALTER TABLE noshow_scores FORCE ROW LEVEL SECURITY;
CREATE POLICY noshow_scores_tenant ON noshow_scores
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON noshow_scores TO mai_app;

CREATE INDEX noshow_scores_appointment ON noshow_scores(appointment_id);
CREATE INDEX noshow_scores_session ON noshow_scores(session_id);

-- Slot overbooking policies (disabled by default — operator must enable)
CREATE TABLE slot_capacity_policies (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    UUID        NOT NULL REFERENCES tenants(id),
    calendar_id  VARCHAR(100),
    max_overbook INT         NOT NULL DEFAULT 1,
    target_fill  REAL        NOT NULL DEFAULT 0.90,
    enabled      BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, calendar_id)
);
ALTER TABLE slot_capacity_policies ENABLE ROW LEVEL SECURITY;
ALTER TABLE slot_capacity_policies FORCE ROW LEVEL SECURITY;
CREATE POLICY slot_capacity_policies_tenant ON slot_capacity_policies
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON slot_capacity_policies TO mai_app;
