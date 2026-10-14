CREATE TABLE sessions (
    id                 VARCHAR(100) PRIMARY KEY,  -- cookie-based session ID
    tenant_id          UUID NOT NULL,
    creative_id        UUID,
    variant_id         UUID,
    device             VARCHAR(20),
    geo                VARCHAR(10),
    utm_source         VARCHAR(100),
    utm_medium         VARCHAR(100),
    utm_campaign       VARCHAR(100),
    consent_level      VARCHAR(20),
    crm_user_id        VARCHAR(255),
    -- GHL CRM fields (§13.3)
    ghl_contact_id     VARCHAR(100),
    ghl_opportunity_id VARCHAR(100),
    pipeline_stage     VARCHAR(100),
    deal_value         REAL,
    deal_status        VARCHAR(30),   -- open, won, lost
    is_suspicious      BOOLEAN DEFAULT FALSE,
    started_at         TIMESTAMPTZ DEFAULT now(),
    last_event_at      TIMESTAMPTZ,
    converted          BOOLEAN DEFAULT FALSE,
    conversion_type    VARCHAR(50),   -- LEAD, PURCHASE, FORM_SUBMIT
    revenue            REAL
);

CREATE INDEX idx_sessions_tenant ON sessions(tenant_id, started_at);
CREATE INDEX idx_sessions_ghl    ON sessions(ghl_contact_id) WHERE ghl_contact_id IS NOT NULL;

-- High-volume table: partitioned by month
CREATE TABLE events (
    id             UUID DEFAULT gen_random_uuid(),
    tenant_id      UUID NOT NULL,
    session_id     VARCHAR(100) NOT NULL,
    event_type     VARCHAR(50) NOT NULL,   -- IMPRESSION, CLICK, PAGE_VIEW, SCROLL, HOVER,
                                           -- BLOCK_VIEW, FORM_START, FORM_FIELD, FORM_SUBMIT,
                                           -- CONVERSION, PURCHASE, LEAD
    creative_id    UUID,
    variant_id     UUID,
    block_event_id VARCHAR(50),
    event_data     JSONB,
    revenue        REAL,
    device         VARCHAR(20),
    geo            VARCHAR(10),
    utm_source     VARCHAR(100),
    utm_medium     VARCHAR(100),
    utm_campaign   VARCHAR(100),
    consent_level  VARCHAR(20),
    is_suspicious  BOOLEAN DEFAULT FALSE,
    event_time     TIMESTAMPTZ NOT NULL DEFAULT now(),
    received_at    TIMESTAMPTZ DEFAULT now(),
    PRIMARY KEY (id, event_time)            -- partition key must be in PK
) PARTITION BY RANGE (event_time);

CREATE TABLE events_2026_10 PARTITION OF events
    FOR VALUES FROM ('2026-10-01') TO ('2026-11-01');
CREATE TABLE events_2026_11 PARTITION OF events
    FOR VALUES FROM ('2026-11-01') TO ('2026-12-01');
CREATE TABLE events_2026_12 PARTITION OF events
    FOR VALUES FROM ('2026-12-01') TO ('2027-01-01');
CREATE TABLE events_2027_01 PARTITION OF events
    FOR VALUES FROM ('2027-01-01') TO ('2027-02-01');
CREATE TABLE events_2027_02 PARTITION OF events
    FOR VALUES FROM ('2027-02-01') TO ('2027-03-01');
CREATE TABLE events_2027_03 PARTITION OF events
    FOR VALUES FROM ('2027-03-01') TO ('2027-04-01');

CREATE INDEX idx_events_session ON events(tenant_id, session_id, event_time);
CREATE INDEX idx_events_variant ON events(tenant_id, variant_id, event_type, event_time);
