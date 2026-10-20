-- Roadmap 8-9: attribution across GHL sub-accounts (vision §19.12; arch §16.9)

-- One row per GHL location (sub-account). Webhooks from every location land on
-- /webhooks/ghl/{tenantId}; the payload's locationId selects the secret and the role.
-- Opportunities are trusted from MAIN only (AGENCY/WEBINAR/MAILING locations cannot mark deals won).
CREATE TABLE ghl_subaccounts (
    id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID         NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    location_id    VARCHAR(100) NOT NULL,
    name           VARCHAR(120),
    role           VARCHAR(20)  NOT NULL DEFAULT 'MAIN'
        CHECK (role IN ('MAIN', 'AGENCY', 'WEBINAR', 'MAILING')),
    webhook_secret VARCHAR(200),
    api_key_enc    BYTEA,                       -- LeadConnector private integration token (custom-field writes)
    is_active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, location_id)
);
ALTER TABLE ghl_subaccounts ENABLE ROW LEVEL SECURITY;
ALTER TABLE ghl_subaccounts FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON ghl_subaccounts
    USING ((tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
           OR current_setting('app.rls_bypass', true) = 'on');
GRANT SELECT, INSERT, UPDATE, DELETE ON ghl_subaccounts TO mai_app;

ALTER TABLE ghl_webhook_log ADD COLUMN IF NOT EXISTS location_id VARCHAR(100);
ALTER TABLE ghl_webhook_log ADD COLUMN IF NOT EXISTS link_method VARCHAR(20);   -- custom_field | email | phone | utm | none

-- Daily funnel integrity check: BOOKED (GHL) vs Schedule (Meta CAPI) vs FORM_SUBMIT (first-party)
CREATE TABLE funnel_integrity_checks (
    id             UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID        NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    check_date     DATE        NOT NULL,
    window_hours   INT         NOT NULL DEFAULT 24,
    form_submits   INT         NOT NULL DEFAULT 0,
    booked         INT         NOT NULL DEFAULT 0,
    schedule_sent  INT         NOT NULL DEFAULT 0,
    delta_booked_schedule REAL,
    delta_form_booked     REAL,
    verdict        VARCHAR(20) NOT NULL,          -- OK | DRIFT | NO_DATA
    alert_fired    BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, check_date)
);
ALTER TABLE funnel_integrity_checks ENABLE ROW LEVEL SECURITY;
ALTER TABLE funnel_integrity_checks FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON funnel_integrity_checks
    USING ((tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
           OR current_setting('app.rls_bypass', true) = 'on');
GRANT SELECT, INSERT, UPDATE, DELETE ON funnel_integrity_checks TO mai_app;
