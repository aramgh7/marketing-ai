-- Roadmap 9-19 (.ai/meta-api-policy.md §6): Meta write policy enforced in code.
--
--  tenants.meta_write_stage     READ_ONLY → GUARDED_WRITES → FULL; forward moves only through
--                               MetaWriteStageService (readiness conditions) or an admin override with a reason
--  tenants.meta_write_policy    thresholds (JSONB): max_budget_change_pct, min_hours_between_budget_changes,
--                               max_writes_per_hour, learning_phase_lock, launch_requires_probe
--  meta_write_decisions         every policy decision (ALLOW / DENY / RECOMMEND) — the rate limit and the
--                               budget cadence read this table, so it is also the write ledger
--  meta_write_overrides         one-shot, time-boxed exceptions with a mandatory reason

ALTER TABLE tenants ADD COLUMN IF NOT EXISTS meta_write_stage VARCHAR(20) NOT NULL DEFAULT 'READ_ONLY'
    CHECK (meta_write_stage IN ('READ_ONLY', 'GUARDED_WRITES', 'FULL'));
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS meta_write_stage_changed_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS meta_write_policy JSONB NOT NULL DEFAULT '{}'::jsonb;

CREATE TABLE IF NOT EXISTS meta_write_decisions (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID        NOT NULL REFERENCES tenants(id),
    action          VARCHAR(40) NOT NULL,
    action_class    VARCHAR(20) NOT NULL,            -- PROTECTIVE | RESUME | BUDGET | LAUNCH | CREATE
    target_id       VARCHAR(255),
    initiator       VARCHAR(10) NOT NULL,            -- USER | JOB
    stage           VARCHAR(20) NOT NULL,
    autonomy_level  VARCHAR(20),
    verdict         VARCHAR(12) NOT NULL,            -- ALLOW | DENY | RECOMMEND
    reason          TEXT,
    budget_delta_pct REAL,
    override_id     UUID,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_meta_write_decisions_tenant ON meta_write_decisions(tenant_id, created_at DESC);
ALTER TABLE meta_write_decisions ENABLE ROW LEVEL SECURITY;
ALTER TABLE meta_write_decisions FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON meta_write_decisions
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid
           OR current_setting('app.rls_bypass', true) = 'on');
GRANT SELECT, INSERT ON meta_write_decisions TO mai_app;
GRANT SELECT, INSERT ON meta_write_decisions TO mai_ml;

CREATE TABLE IF NOT EXISTS meta_write_overrides (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID        NOT NULL REFERENCES tenants(id),
    action          VARCHAR(40) NOT NULL,            -- action or '*' for any
    target_id       VARCHAR(255),                    -- NULL = any target
    reason          TEXT        NOT NULL,
    created_by      UUID,
    expires_at      TIMESTAMPTZ NOT NULL,
    used_at         TIMESTAMPTZ,
    used_decision_id UUID,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_meta_write_overrides_tenant ON meta_write_overrides(tenant_id, expires_at DESC);
ALTER TABLE meta_write_overrides ENABLE ROW LEVEL SECURITY;
ALTER TABLE meta_write_overrides FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON meta_write_overrides
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid
           OR current_setting('app.rls_bypass', true) = 'on');
GRANT SELECT, INSERT, UPDATE ON meta_write_overrides TO mai_app;
GRANT SELECT ON meta_write_overrides TO mai_ml;
