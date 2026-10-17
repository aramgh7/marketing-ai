-- Phase 3-5: Financial Circuit Breaker L2-L3 (§12.5a)

-- Circuit breaker state per tenant
CREATE TABLE IF NOT EXISTS circuit_breaker_events (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL,
    level           VARCHAR(5) NOT NULL,       -- L1 | L2 | L3
    trigger_reason  TEXT NOT NULL,
    bayesian_prob   REAL,                      -- P-value that caused the trigger
    contour         VARCHAR(10),               -- TEST | SCALE | ALL
    action_taken    TEXT NOT NULL,             -- PAUSE_ADSET | THROTTLE_TEST | PAUSE_SCALE | PAUSE_ALL
    resolved_at     TIMESTAMPTZ,               -- NULL = active
    resolved_by     TEXT,                      -- user email or "auto"
    created_at      TIMESTAMPTZ DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_cb_tenant_active  ON circuit_breaker_events(tenant_id, resolved_at);
CREATE INDEX IF NOT EXISTS idx_cb_tenant_created ON circuit_breaker_events(tenant_id, created_at);

-- Budget config per campaign (needed for CB thresholds)
ALTER TABLE meta_campaigns ADD COLUMN IF NOT EXISTS monthly_budget_ceiling REAL;
ALTER TABLE meta_campaigns ADD COLUMN IF NOT EXISTS target_cpa              REAL;
ALTER TABLE meta_campaigns ADD COLUMN IF NOT EXISTS breakeven_roas          REAL;

-- Track daily CPM history for spike detection (z-score)
CREATE TABLE IF NOT EXISTS campaign_cpm_history (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    campaign_id UUID NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    cpm         REAL NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_cpm_campaign ON campaign_cpm_history(campaign_id, recorded_at);
