-- 8-5: Form Self-Qualification + Value Calibration

CREATE TABLE form_submissions (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID        NOT NULL REFERENCES tenants(id),
    session_id      UUID        REFERENCES sessions(id),
    funnel_key      VARCHAR(40),
    self_accredited BOOLEAN,
    ticket_bucket   VARCHAR(20),
    first_name      VARCHAR(100),
    last_name       VARCHAR(100),
    email           VARCHAR(255),
    phone           VARCHAR(30),
    company         VARCHAR(200),
    utm_source      VARCHAR(100),
    utm_campaign    VARCHAR(100),
    raw_payload     JSONB,
    consent_level   VARCHAR(20) NOT NULL DEFAULT 'basic',
    submitted_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    ghl_contact_id  VARCHAR(100)
);
ALTER TABLE form_submissions ENABLE ROW LEVEL SECURITY;
ALTER TABLE form_submissions FORCE ROW LEVEL SECURITY;
CREATE POLICY form_submissions_tenant ON form_submissions
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON form_submissions TO mai_app;

CREATE INDEX form_submissions_session_idx ON form_submissions(session_id);
CREATE INDEX form_submissions_tenant_idx  ON form_submissions(tenant_id, submitted_at DESC);

CREATE TABLE value_calibration_log (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID        NOT NULL REFERENCES tenants(id),
    session_id      UUID        REFERENCES sessions(id),
    predicted_value REAL,
    actual_value    REAL,
    ticket_bucket   VARCHAR(20),
    self_accredited BOOLEAN,
    stage_key       VARCHAR(40),
    logged_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE value_calibration_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE value_calibration_log FORCE ROW LEVEL SECURITY;
CREATE POLICY value_calibration_log_tenant ON value_calibration_log
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON value_calibration_log TO mai_app;
