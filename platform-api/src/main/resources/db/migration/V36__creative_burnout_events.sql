-- Phase 6-1: Creative burnout event log
CREATE TABLE creative_burnout_events (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID        NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    creative_id     UUID        NOT NULL,
    ad_set_id       TEXT,
    frequency       NUMERIC(6,2) NOT NULL,
    ctr_current     NUMERIC(8,6) NOT NULL,
    ctr_baseline    NUMERIC(8,6) NOT NULL,
    burnout_score   NUMERIC(5,4) NOT NULL,
    stage           TEXT        NOT NULL CHECK (stage IN ('none','warning','critical')),
    signals         JSONB       NOT NULL DEFAULT '[]',
    resolved        BOOLEAN     NOT NULL DEFAULT FALSE,
    resolved_at     TIMESTAMPTZ,
    detected_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_burnout_tenant_creative ON creative_burnout_events(tenant_id, creative_id);
CREATE INDEX idx_burnout_unresolved       ON creative_burnout_events(tenant_id, stage) WHERE resolved = FALSE;

ALTER TABLE creative_burnout_events ENABLE ROW LEVEL SECURITY;
CREATE POLICY burnout_tenant_isolation ON creative_burnout_events
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
