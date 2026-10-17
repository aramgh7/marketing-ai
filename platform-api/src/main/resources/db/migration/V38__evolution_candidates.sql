-- Phase 6-4: Evolutionary creative candidates
CREATE TABLE evolution_candidates (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID        NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    parent_a_id     UUID,
    parent_b_id     UUID,
    atoms           JSONB       NOT NULL DEFAULT '[]',
    status          TEXT        NOT NULL DEFAULT 'PROPOSED'
                                CHECK (status IN ('PROPOSED','APPROVED','REJECTED','LAUNCHED')),
    approved_by     UUID        REFERENCES users(id),
    approved_at     TIMESTAMPTZ,
    launched_at     TIMESTAMPTZ,
    meta_creative_id TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_evo_tenant_status ON evolution_candidates(tenant_id, status);

ALTER TABLE evolution_candidates ENABLE ROW LEVEL SECURITY;
CREATE POLICY evo_tenant_isolation ON evolution_candidates
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
