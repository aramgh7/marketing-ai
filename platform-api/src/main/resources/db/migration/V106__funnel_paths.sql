-- 8-14: Funnel Path as Thompson Arm (vision §19.18.1; arch §16.14)
-- funnel_paths: 4 paths for client #1 investor funnel
-- landing_variants.funnel_path_id NULL = path A (CALL_4D control) — backwards-compatible

CREATE TABLE funnel_paths (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID        NOT NULL REFERENCES tenants(id),
    path_key        VARCHAR(30) NOT NULL,
    path_label      VARCHAR(120),
    description     TEXT,
    status          VARCHAR(20) NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT','PENDING_REVIEW','ACTIVE','PAUSED','RETIRED')),
    is_control      BOOLEAN     NOT NULL DEFAULT FALSE,
    ghl_workflow_id VARCHAR(100),
    config          JSONB       NOT NULL DEFAULT '{}',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, path_key)
);
ALTER TABLE funnel_paths ENABLE ROW LEVEL SECURITY;
ALTER TABLE funnel_paths FORCE ROW LEVEL SECURITY;
CREATE POLICY funnel_paths_tenant ON funnel_paths
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON funnel_paths TO mai_app;

-- Link landing variants to paths (NULL = path A / control)
ALTER TABLE landing_variants
    ADD COLUMN IF NOT EXISTS funnel_path_id UUID REFERENCES funnel_paths(id);

-- Link sessions to paths
ALTER TABLE sessions
    ADD COLUMN IF NOT EXISTS funnel_path_id UUID REFERENCES funnel_paths(id);

-- path_gate_log: records engagement gate decisions for path B (MATERIALS_FIRST)
CREATE TABLE path_gate_log (
    id             UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID        NOT NULL REFERENCES tenants(id),
    session_id     UUID        REFERENCES sessions(id),
    path_id        UUID        REFERENCES funnel_paths(id),
    gate_event     VARCHAR(40) NOT NULL,
    gate_passed    BOOLEAN     NOT NULL,
    gate_passed_at TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE path_gate_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE path_gate_log FORCE ROW LEVEL SECURITY;
CREATE POLICY path_gate_log_tenant ON path_gate_log
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON path_gate_log TO mai_app;

CREATE INDEX path_gate_log_session ON path_gate_log(session_id);
CREATE INDEX path_gate_log_path ON path_gate_log(path_id);
