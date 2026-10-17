CREATE TABLE experiments (
    id                UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID        NOT NULL REFERENCES tenants(id),
    name              VARCHAR(200) NOT NULL,
    hypothesis        TEXT,
    experiment_type   VARCHAR(30) NOT NULL DEFAULT 'landing',
    status            VARCHAR(20) NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT','PENDING_REVIEW','APPROVED','ACTIVE','PAUSED','CONCLUDED','RETIRED')),
    started_at        TIMESTAMPTZ,
    concluded_at      TIMESTAMPTZ,
    winner_variant_id UUID,
    min_ess           INT         NOT NULL DEFAULT 200,
    auto_stop_p       REAL        NOT NULL DEFAULT 0.95,
    created_by        UUID        REFERENCES users(id),
    approved_by       UUID        REFERENCES users(id),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE experiments ENABLE ROW LEVEL SECURITY;
ALTER TABLE experiments FORCE ROW LEVEL SECURITY;
CREATE POLICY experiments_tenant ON experiments
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON experiments TO mai_app;

CREATE INDEX experiments_tenant_status ON experiments(tenant_id, status);

ALTER TABLE landing_variants
    ADD COLUMN IF NOT EXISTS experiment_id UUID REFERENCES experiments(id),
    ADD COLUMN IF NOT EXISTS is_control    BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX landing_variants_experiment_idx ON landing_variants(experiment_id)
    WHERE experiment_id IS NOT NULL;

CREATE TABLE experiment_events (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    experiment_id   UUID        NOT NULL REFERENCES experiments(id),
    tenant_id       UUID        NOT NULL REFERENCES tenants(id),
    event_type      VARCHAR(30) NOT NULL,
    triggered_by    UUID        REFERENCES users(id),
    note            TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE experiment_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE experiment_events FORCE ROW LEVEL SECURITY;
CREATE POLICY experiment_events_tenant ON experiment_events
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON experiment_events TO mai_app;
