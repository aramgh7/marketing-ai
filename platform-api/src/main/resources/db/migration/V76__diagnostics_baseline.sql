-- Tenant-specific baselines per placement × device (vision §3.2, arch §15.21)
CREATE TABLE IF NOT EXISTS diagnostic_baselines (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID        NOT NULL REFERENCES tenants(id),
    placement       VARCHAR(30) NOT NULL,
    device_type     VARCHAR(10) NOT NULL,
    metric          VARCHAR(30) NOT NULL,
    baseline_value  REAL        NOT NULL,
    sample_size     INT         NOT NULL,
    confidence_lo   REAL,
    confidence_hi   REAL,
    computed_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, placement, device_type, metric)
);
ALTER TABLE diagnostic_baselines ENABLE ROW LEVEL SECURITY;
ALTER TABLE diagnostic_baselines FORCE ROW LEVEL SECURITY;
CREATE POLICY diagnostic_baselines_tenant ON diagnostic_baselines
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON diagnostic_baselines TO mai_app;
