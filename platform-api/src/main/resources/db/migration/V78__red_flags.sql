-- Red flag events log (vision §15.12.5)
CREATE TABLE IF NOT EXISTS red_flag_events (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID        NOT NULL REFERENCES tenants(id),
    landing_id      UUID,
    variant_id      UUID,
    p_exceed        REAL        NOT NULL,
    threshold       REAL        NOT NULL,
    bounced         INT         NOT NULL,
    total_visits    INT         NOT NULL,
    label           VARCHAR(20) NOT NULL,
    action_taken    VARCHAR(20) NOT NULL DEFAULT 'ALERT' CHECK (action_taken IN ('ALERT','PAUSED')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE red_flag_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE red_flag_events FORCE ROW LEVEL SECURITY;
CREATE POLICY red_flag_events_tenant ON red_flag_events
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON red_flag_events TO mai_app;
