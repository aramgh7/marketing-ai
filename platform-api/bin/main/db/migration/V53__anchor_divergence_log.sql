-- Anchor Creatives Market-Shock Detector (vision §11.2) — Phase 3 (arch §15.2)

CREATE TABLE anchor_divergence_log (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID        NOT NULL REFERENCES tenants(id),
    campaign_id     UUID        REFERENCES meta_campaigns(id),
    window_hours    INT         NOT NULL DEFAULT 24,
    metric          VARCHAR(20) NOT NULL,               -- hook_rate, CTR, CVR
    p_anchor_drop   REAL        NOT NULL,               -- P(anchors dropped vs 14-day baseline)
    p_test_drop     REAL        NOT NULL,               -- P(tests dropped)
    verdict         VARCHAR(10) NOT NULL,               -- MARKET, CREATIVE, NONE
    external_ctx    JSONB,                              -- context from external_signals at scan time
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_anchor_div_tenant ON anchor_divergence_log(tenant_id, created_at DESC);
ALTER TABLE anchor_divergence_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE anchor_divergence_log FORCE ROW LEVEL SECURITY;
CREATE POLICY anchor_div_tenant ON anchor_divergence_log
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON anchor_divergence_log TO mai_app;
