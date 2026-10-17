-- CAPI events log: records every event queued or sent to Meta Conversions API
-- Status flow: PENDING → SENT | FAILED | SKIPPED
-- PENDING = credentials not yet configured; SKIPPED = quality gate rejected
CREATE TABLE capi_events (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL,
    session_id      VARCHAR(100),
    event_name      VARCHAR(100) NOT NULL,   -- Lead, Purchase, Schedule, CompleteRegistration
    event_id        VARCHAR(100),            -- client_event_id for dedup with browser pixel
    pixel_id        VARCHAR(100),
    proxy_value     REAL,
    currency        VARCHAR(10) DEFAULT 'USD',
    payload         JSONB NOT NULL DEFAULT '{}',   -- full CAPI payload sent
    meta_response   JSONB,                          -- Meta Graph API response
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING', -- PENDING|SENT|FAILED|SKIPPED
    ghl_event_type  VARCHAR(100),            -- originating GHL event (call.completed, etc.)
    ghl_contact_id  VARCHAR(100),
    sent_at         TIMESTAMPTZ,
    created_at      TIMESTAMPTZ DEFAULT now()
);

CREATE INDEX idx_capi_tenant_time ON capi_events(tenant_id, created_at DESC);
CREATE INDEX idx_capi_event_id    ON capi_events(tenant_id, event_id) WHERE event_id IS NOT NULL;
CREATE INDEX idx_capi_pending     ON capi_events(tenant_id, status)   WHERE status IN ('PENDING', 'FAILED');
