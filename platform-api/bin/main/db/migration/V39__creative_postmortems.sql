-- Phase 6-5: Creative graveyard / post-mortems
CREATE TABLE creative_postmortems (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID        NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    creative_id     UUID        NOT NULL,
    failure_mode    TEXT        NOT NULL,
    cvr             NUMERIC(8,6) NOT NULL,
    benchmark_cvr   NUMERIC(8,6) NOT NULL DEFAULT 0.02,
    sessions        INT         NOT NULL DEFAULT 0,
    ctr             NUMERIC(8,6),
    analysis        JSONB       NOT NULL DEFAULT '{}',
    postmortem_md   TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_postmortem_tenant        ON creative_postmortems(tenant_id, created_at DESC);
CREATE INDEX idx_postmortem_creative      ON creative_postmortems(tenant_id, creative_id);
CREATE INDEX idx_postmortem_failure_mode  ON creative_postmortems(tenant_id, failure_mode);

ALTER TABLE creative_postmortems ENABLE ROW LEVEL SECURITY;
CREATE POLICY postmortem_tenant_isolation ON creative_postmortems
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
