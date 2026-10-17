-- Offer validity mask fields and margin floor (vision §4.3a, §18.5)

ALTER TABLE landing_variants ADD COLUMN IF NOT EXISTS estimated_margin      REAL;
ALTER TABLE landing_variants ADD COLUMN IF NOT EXISTS requires_legal_review BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE landing_variants ADD COLUMN IF NOT EXISTS urgency_source        VARCHAR(30)
    CHECK (urgency_source IS NULL OR urgency_source IN
           ('inventory_api', 'crm_deadline', 'seasonal_window', 'none'));

-- Offer validity violations log
CREATE TABLE offer_validity_violations (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID        NOT NULL REFERENCES tenants(id),
    variant_id  UUID,
    field       VARCHAR(50) NOT NULL,
    value_given TEXT,
    reason      TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE offer_validity_violations ENABLE ROW LEVEL SECURITY;
ALTER TABLE offer_validity_violations FORCE ROW LEVEL SECURITY;
CREATE POLICY offer_validity_violations_tenant ON offer_validity_violations
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON offer_validity_violations TO mai_app;
