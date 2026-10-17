-- 8-10: External Signals Role per Funnel (vision §19.13; arch §16.10)
-- Adds funnel_signal_config JSONB to tenants and calendar events table.

ALTER TABLE tenants
    ADD COLUMN IF NOT EXISTS funnel_signal_config JSONB NOT NULL DEFAULT '{
        "investor": {
            "weather_role": "NARRATIVE",
            "news_tags":    ["drought", "water_policy", "agtech_funding", "sec_reg", "rate_change"]
        },
        "grower": {
            "weather_role": "DEMAND",
            "news_tags":    ["drought", "water_policy", "crop_report"]
        }
    }';

-- Calendar events for investor/grower scheduling context
CREATE TABLE IF NOT EXISTS funnel_calendar_events (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    funnel_key      VARCHAR(64) NOT NULL,          -- 'investor' | 'grower'
    event_type      VARCHAR(64) NOT NULL,          -- 'round_close' | 'regulatory' | 'webinar' | 'field_event'
    event_name      VARCHAR(255) NOT NULL,
    event_date      DATE NOT NULL,
    jurisdiction    VARCHAR(64),                   -- e.g. 'IL', 'MO' for PTI/Missouri events
    metadata        JSONB NOT NULL DEFAULT '{}',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_funnel_calendar_events_tenant
    ON funnel_calendar_events(tenant_id, funnel_key, event_date);

ALTER TABLE funnel_calendar_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE funnel_calendar_events FORCE ROW LEVEL SECURITY;

CREATE POLICY fce_tenant ON funnel_calendar_events
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE, DELETE ON funnel_calendar_events TO mai_app;
