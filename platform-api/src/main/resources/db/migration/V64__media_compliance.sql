-- Media compliance assets: records each asset subject to FACE/MUSIC/AI_LABEL checks
CREATE TABLE media_compliance_assets (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    UUID        NOT NULL REFERENCES tenants(id),
    entity_type  VARCHAR(40) NOT NULL,
    entity_id    UUID        NOT NULL,
    asset_url    TEXT        NOT NULL,
    metadata     JSONB       NOT NULL DEFAULT '{}',
    submitted_by UUID        REFERENCES users(id),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE media_compliance_assets ENABLE ROW LEVEL SECURITY;
ALTER TABLE media_compliance_assets FORCE ROW LEVEL SECURITY;
CREATE POLICY media_compliance_assets_tenant ON media_compliance_assets
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON media_compliance_assets TO mai_app;
CREATE INDEX idx_mca_entity ON media_compliance_assets(tenant_id, entity_id);

-- NOTE: compliance_checks.check_type is VARCHAR with no CHECK constraint (entity already allows
-- FACE / MUSIC / AI_LABEL — see ComplianceCheck.java comment §15.4). No ALTER needed.
