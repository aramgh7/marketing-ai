-- Micro-conversion funnel events per landing block (2-x, vision §7.2)
CREATE TABLE micro_conversion_events (
    id             UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID        NOT NULL REFERENCES tenants(id),
    session_id     VARCHAR(100) REFERENCES sessions(id),
    landing_id     UUID,
    block_id       VARCHAR(50) NOT NULL,
    event_type     VARCHAR(20) NOT NULL CHECK (event_type IN ('BLOCK_VIEW','CTA_CLICK','FORM_START','FORM_SUBMIT')),
    block_event_id VARCHAR(50),
    occurred_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE micro_conversion_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE micro_conversion_events FORCE ROW LEVEL SECURITY;
CREATE POLICY micro_conversion_events_tenant ON micro_conversion_events
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON micro_conversion_events TO mai_app;

CREATE INDEX micro_conv_session_idx ON micro_conversion_events(session_id);
CREATE INDEX micro_conv_block_idx   ON micro_conversion_events(block_id, event_type);
CREATE INDEX micro_conv_tenant_idx  ON micro_conversion_events(tenant_id, occurred_at DESC);
