-- Phase 6-6: Autopilot settings and action log
CREATE TABLE autopilot_settings (
    id                          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id                   UUID        NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    enabled                     BOOLEAN     NOT NULL DEFAULT FALSE,
    auto_pause_burnout           BOOLEAN     NOT NULL DEFAULT FALSE,
    auto_scale_winner            BOOLEAN     NOT NULL DEFAULT FALSE,
    auto_retire_graveyard        BOOLEAN     NOT NULL DEFAULT FALSE,
    max_l1_triggers_per_day      INT         NOT NULL DEFAULT 3,
    min_p_best_to_scale          NUMERIC(5,4) NOT NULL DEFAULT 0.9500,
    budget_scale_factor          NUMERIC(5,2) NOT NULL DEFAULT 1.20,
    created_at                   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id)
);

CREATE TABLE autopilot_actions (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID        NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    action_type     TEXT        NOT NULL,
    target_id       TEXT,
    payload         JSONB       NOT NULL DEFAULT '{}',
    status          TEXT        NOT NULL DEFAULT 'PENDING'
                                CHECK (status IN ('PENDING','EXECUTED','UNDONE','FAILED')),
    executed_at     TIMESTAMPTZ,
    undone_at       TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_autopilot_actions_tenant ON autopilot_actions(tenant_id, created_at DESC);
CREATE INDEX idx_autopilot_actions_status ON autopilot_actions(tenant_id, status);

ALTER TABLE autopilot_settings ENABLE ROW LEVEL SECURITY;
CREATE POLICY autopilot_settings_isolation ON autopilot_settings
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE autopilot_actions ENABLE ROW LEVEL SECURITY;
CREATE POLICY autopilot_actions_isolation ON autopilot_actions
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
