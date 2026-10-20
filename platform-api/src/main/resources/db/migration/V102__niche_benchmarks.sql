-- Renamed from niche_benchmarks: V77 already owns that name with a different (effect-size) schema.
CREATE TABLE niche_metric_benchmarks (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    niche_key        VARCHAR(50) NOT NULL,
    metric_key       VARCHAR(50) NOT NULL,
    value            REAL,
    tenant_count     INT         NOT NULL DEFAULT 0,
    computed_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    data_window_days INT         NOT NULL DEFAULT 30
);
-- No RLS — platform-wide aggregate, no tenant info exposed
GRANT SELECT, INSERT, UPDATE ON niche_metric_benchmarks TO mai_app;

CREATE INDEX niche_metric_benchmarks_niche_metric ON niche_metric_benchmarks(niche_key, metric_key, computed_at DESC);

ALTER TABLE tenants ADD COLUMN IF NOT EXISTS niche_key VARCHAR(50) DEFAULT 'generic';
