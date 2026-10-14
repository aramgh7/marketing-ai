-- pixel_id is UUID without FK constraint here; FK to meta_pixels added in V8 (dependency order)
CREATE TABLE meta_campaigns (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        UUID NOT NULL REFERENCES tenants(id),
    pixel_id         UUID,
    meta_campaign_id VARCHAR(100) NOT NULL,
    name             VARCHAR(500),
    objective        VARCHAR(50),
    contour          VARCHAR(20) NOT NULL DEFAULT 'TEST',  -- TEST, SCALE (§6.5 two-contour)
    daily_budget     REAL,
    status           VARCHAR(20),
    learning_phase   VARCHAR(20),  -- LEARNING, ACTIVE, LEARNING_LIMITED
    synced_at        TIMESTAMPTZ,
    created_at       TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE meta_ad_sets (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID NOT NULL REFERENCES tenants(id),
    campaign_id    UUID NOT NULL REFERENCES meta_campaigns(id),
    meta_ad_set_id VARCHAR(100) NOT NULL,
    creative_id    UUID REFERENCES creatives(id),
    audience_spec  JSONB,
    daily_budget   REAL,
    status         VARCHAR(20),
    learning_phase VARCHAR(20),
    synced_at      TIMESTAMPTZ,
    created_at     TIMESTAMPTZ DEFAULT now()
);

-- Financial Circuit Breaker L1 (§12.5a)
CREATE TABLE budget_guards (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID NOT NULL REFERENCES tenants(id),
    guard_type    VARCHAR(30) NOT NULL,  -- PER_CREATIVE_STOP_LOSS, MONTHLY_CEILING
    reference_id  UUID,                  -- creative_id for per-creative, NULL for account-level
    threshold     REAL NOT NULL,
    current_spend REAL DEFAULT 0,
    is_triggered  BOOLEAN DEFAULT FALSE,
    triggered_at  TIMESTAMPTZ,
    period_start  DATE,
    created_at    TIMESTAMPTZ DEFAULT now()
);

-- GHL webhook audit log for replay and debugging (§13.3)
CREATE TABLE ghl_webhook_log (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          UUID NOT NULL REFERENCES tenants(id),
    event_type         VARCHAR(100) NOT NULL,
    ghl_contact_id     VARCHAR(100),
    ghl_opportunity_id VARCHAR(100),
    payload            JSONB NOT NULL,
    processed          BOOLEAN DEFAULT FALSE,
    received_at        TIMESTAMPTZ DEFAULT now()
);

CREATE INDEX idx_ghl_log_contact ON ghl_webhook_log(ghl_contact_id) WHERE ghl_contact_id IS NOT NULL;
