-- 3-10: Reactive Competitive Intelligence (vision §9.1)
--
-- Polls Meta Ad Library for competitor ads running > 14 days (proxy for profitability).
-- LLM extracts atoms → generates counter-hypotheses → alert to marketer for human approval.
-- ⚠️ Auto-deploy without human approval is FORBIDDEN (vision §9.1).

-- Competitor page IDs to monitor are stored per-tenant in tenants.settings JSONB:
--   {"competitor_page_ids": ["123456", "789012"]}

CREATE TABLE competitor_ads (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        UUID         NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    meta_ad_id       VARCHAR(100) NOT NULL,
    page_id          VARCHAR(100) NOT NULL,
    page_name        VARCHAR(255),
    ad_title         TEXT,
    ad_body          TEXT,
    snapshot_url     TEXT,
    running_since    DATE,
    run_days         INT,
    atoms_extracted  JSONB,       -- LLM-extracted creative atoms
    first_seen_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_seen_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, meta_ad_id)
);
CREATE INDEX idx_competitor_ads_tenant ON competitor_ads(tenant_id, run_days DESC);

-- Alert record: groups competitor ads into a test-batch recommendation
CREATE TABLE competitive_intel_alerts (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID         NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    status            VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
                                   CHECK (status IN ('PENDING','APPROVED','DISMISSED')),
    competitor_ad_ids UUID[]       NOT NULL,     -- competitor_ads.id[] that triggered this alert
    counter_hypotheses JSONB       NOT NULL,     -- LLM-generated counter-creatives
    approved_by       UUID,                      -- user who approved (NULL until approved)
    approved_at       TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_ci_alerts_tenant ON competitive_intel_alerts(tenant_id, status, created_at DESC);

ALTER TABLE competitor_ads           ENABLE ROW LEVEL SECURITY;
ALTER TABLE competitor_ads           FORCE ROW LEVEL SECURITY;
ALTER TABLE competitive_intel_alerts ENABLE ROW LEVEL SECURITY;
ALTER TABLE competitive_intel_alerts FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON competitor_ads
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
CREATE POLICY tenant_isolation ON competitive_intel_alerts
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE ON competitor_ads, competitive_intel_alerts TO mai_app;
