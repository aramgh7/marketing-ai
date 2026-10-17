-- V83: bid_cap_log — audit trail of bid cap recommendations (Block F §6)
CREATE TABLE bid_cap_log (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        UUID          NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    campaign_id      VARCHAR(64)   NOT NULL,
    recommended_cap  NUMERIC(12,2) NOT NULL,
    previous_cap     NUMERIC(12,2),
    cpa_p95          NUMERIC(12,2),
    basis            VARCHAR(64)   NOT NULL DEFAULT 'TWO_CONTOUR',
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

ALTER TABLE bid_cap_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE bid_cap_log FORCE ROW LEVEL SECURITY;

CREATE POLICY bcl_tenant_isolation ON bid_cap_log
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE, DELETE ON bid_cap_log TO mai_app;
