-- External Signals Module (vision §11.3, §8.2) — Phase 3 (arch §15.1)
-- Weather is critical for client #1 (agricultural SAP) — default ON

CREATE TABLE external_signals (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID        REFERENCES tenants(id),    -- NULL = platform-wide
    signal_type     VARCHAR(30) NOT NULL,                   -- CPM_INDEX, CALENDAR, WEATHER, NEWS, COMPETITOR
    geo             VARCHAR(10),
    observed_at     TIMESTAMPTZ NOT NULL,
    valid_from      TIMESTAMPTZ,
    valid_to        TIMESTAMPTZ,
    value_num       REAL,
    value_tags      TEXT[],                                 -- ["drought_moderate","season_start"]
    source          VARCHAR(50),                            -- meta_insights, open_meteo, newsapi, manual
    raw             JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_ext_signals ON external_signals(tenant_id, signal_type, observed_at DESC);
CREATE INDEX idx_ext_signals_global ON external_signals(signal_type, observed_at DESC) WHERE tenant_id IS NULL;
GRANT SELECT, INSERT, UPDATE, DELETE ON external_signals TO mai_app;

CREATE TABLE event_calendar (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID        REFERENCES tenants(id),    -- NULL = global
    name            VARCHAR(200) NOT NULL,
    tag             VARCHAR(50)  NOT NULL,
    starts_on       DATE         NOT NULL,
    ends_on         DATE         NOT NULL,
    geo             VARCHAR(10),
    expected_effect VARCHAR(20),                           -- CPM_UP, DEMAND_UP, DEMAND_DOWN, UNKNOWN
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
GRANT SELECT, INSERT, UPDATE, DELETE ON event_calendar TO mai_app;
