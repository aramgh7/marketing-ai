-- 3-11: Pixel Pollution Auto-Detection — Level 3 (vision §6.8)
--
-- Monitors drift between incoming lead volume and confirmed-quality lead rate.
-- Quality rate = CRM_MEETING_ATTENDED / FORM_SUBMIT per UTM campaign (14-day window).
-- Alert fires when quality_rate < 0.7 × baseline_30d AND volume ≥ 20 forms.
-- Creates a CircuitBreaker L2 event + Telegram alert.

CREATE TABLE pixel_pollution_scans (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        UUID         NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    utm_campaign     VARCHAR(100),                -- NULL = tenant-level aggregate
    scan_date        DATE         NOT NULL DEFAULT CURRENT_DATE,
    forms_14d        INT          NOT NULL DEFAULT 0,
    meetings_14d     INT          NOT NULL DEFAULT 0,
    quality_rate_14d NUMERIC(6,4),               -- meetings_14d / forms_14d
    baseline_rate_30d NUMERIC(6,4),
    drift_pct        NUMERIC(7,2),               -- (quality_rate_14d / baseline_rate_30d - 1) * 100
    alert_fired      BOOLEAN      NOT NULL DEFAULT false,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX idx_pixel_pollution_dedup
    ON pixel_pollution_scans(tenant_id, COALESCE(utm_campaign, ''), scan_date);
CREATE INDEX idx_pixel_pollution_tenant
    ON pixel_pollution_scans(tenant_id, scan_date DESC);

ALTER TABLE pixel_pollution_scans ENABLE ROW LEVEL SECURITY;
ALTER TABLE pixel_pollution_scans FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON pixel_pollution_scans
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
GRANT SELECT, INSERT ON pixel_pollution_scans TO mai_app;
