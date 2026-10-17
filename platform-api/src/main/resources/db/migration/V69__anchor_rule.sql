-- Anchor Placement Rule (vision §11.2, arch §15.2)
-- launchTestBatch() requires ≥2 anchor ad sets. Override with reason → logged here.

CREATE TABLE anchor_override_log (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID        NOT NULL REFERENCES tenants(id),
    batch_id      UUID,
    anchor_count  INT         NOT NULL DEFAULT 0,
    reason        TEXT        NOT NULL,
    overridden_by UUID        REFERENCES users(id),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE anchor_override_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE anchor_override_log FORCE ROW LEVEL SECURITY;
CREATE POLICY anchor_override_log_tenant ON anchor_override_log
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON anchor_override_log TO mai_app;
