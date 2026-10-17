-- Geo-Lift tests: synthetic-control incrementality (5-7)
-- Notes: uses client sales data, NOT pixel data; credible intervals on all figures (§17.3)
CREATE TABLE geo_lift_tests (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID        NOT NULL REFERENCES tenants(id),
    test_name       TEXT        NOT NULL,
    control_geos    TEXT[]      NOT NULL,
    test_geos       TEXT[]      NOT NULL,
    start_date      DATE        NOT NULL,
    end_date        DATE,
    status          VARCHAR(20) NOT NULL DEFAULT 'DRAFT', -- DRAFT | RUNNING | COMPLETED | CANCELLED
    result          JSONB,      -- lift_estimate, ci_lower, ci_upper, p_positive, method
    notes           TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE geo_lift_tests ENABLE ROW LEVEL SECURITY;
ALTER TABLE geo_lift_tests FORCE ROW LEVEL SECURITY;
CREATE POLICY geo_lift_tests_tenant ON geo_lift_tests
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON geo_lift_tests TO mai_app;
CREATE INDEX idx_glt_tenant ON geo_lift_tests(tenant_id, status);
