-- 0-13: Site Health HTTP Check — alert-only MVP (arch §15.3)

CREATE TABLE site_health_checks (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID        NOT NULL REFERENCES tenants(id),
    variant_id  UUID        REFERENCES landing_variants(id),
    check_type  VARCHAR(20) NOT NULL CHECK (check_type IN ('HTTP', 'SYNTHETIC_FORM', 'CVR_COLLAPSE')),
    status      VARCHAR(10) NOT NULL CHECK (status IN ('PASS', 'FAIL')),
    latency_ms  INT,
    http_status INT,
    detail      JSONB,
    checked_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE site_health_checks ENABLE ROW LEVEL SECURITY;
ALTER TABLE site_health_checks FORCE ROW LEVEL SECURITY;
CREATE POLICY site_health_checks_tenant ON site_health_checks
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON site_health_checks TO mai_app;
CREATE INDEX idx_site_health_tenant ON site_health_checks(tenant_id, checked_at DESC);

CREATE TABLE site_health_incidents (
    id                UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID        NOT NULL REFERENCES tenants(id),
    variant_id        UUID        REFERENCES landing_variants(id),
    cause             VARCHAR(20) NOT NULL CHECK (cause IN ('HTTP_5XX', 'TIMEOUT', 'FORM_ERROR', 'CVR_COLLAPSE')),
    opened_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at       TIMESTAMPTZ,
    consecutive_fails INT         NOT NULL DEFAULT 2,
    alerted           BOOLEAN     NOT NULL DEFAULT FALSE,
    detail            JSONB
);
ALTER TABLE site_health_incidents ENABLE ROW LEVEL SECURITY;
ALTER TABLE site_health_incidents FORCE ROW LEVEL SECURITY;
CREATE POLICY site_health_incidents_tenant ON site_health_incidents
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON site_health_incidents TO mai_app;
CREATE INDEX idx_site_health_incidents_tenant ON site_health_incidents(tenant_id, opened_at DESC);
