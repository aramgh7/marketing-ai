-- Niche-level aggregated benchmarks (platform-wide, NO RLS — aggregated, no PII)
-- vision §15.12.7: effect sizes + CI, aggregate floor ≥5 tenants
-- vision §25 p.19: FIRST_PARTY only until Meta Platform Terms legal check clears
CREATE TABLE IF NOT EXISTS niche_benchmarks (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    niche           VARCHAR(100) NOT NULL,
    placement       VARCHAR(30)  NOT NULL,
    device_type     VARCHAR(10)  NOT NULL,
    metric          VARCHAR(30)  NOT NULL,
    effect_size     REAL         NOT NULL,
    ci_lower        REAL         NOT NULL,
    ci_upper        REAL         NOT NULL,
    n_tenants       INT          NOT NULL,
    n_observations  INT          NOT NULL,
    is_published    BOOLEAN      NOT NULL DEFAULT FALSE,
    source          VARCHAR(20)  NOT NULL DEFAULT 'FIRST_PARTY',
    computed_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (niche, placement, device_type, metric)
);
-- Platform-wide table: NO RLS (aggregated across tenants, no tenant PII)
GRANT SELECT, INSERT, UPDATE, DELETE ON niche_benchmarks TO mai_app;
