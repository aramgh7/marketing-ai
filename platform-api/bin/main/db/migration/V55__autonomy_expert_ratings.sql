-- Autonomy Levels + Expert Prior/Veto (vision §12.4) — Phase 3 (arch §15.13)

ALTER TABLE tenants ADD COLUMN IF NOT EXISTS autonomy_level VARCHAR(16) NOT NULL DEFAULT 'ADVISOR'
    CHECK (autonomy_level IN ('OBSERVER', 'ADVISOR', 'SEMI_AUTOPILOT', 'AUTOPILOT'));

CREATE TABLE expert_ratings (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID        NOT NULL REFERENCES tenants(id),
    entity_type VARCHAR(20) NOT NULL,                   -- CREATIVE, VARIANT, OFFER_CANDIDATE
    entity_id   UUID        NOT NULL,
    rating      SMALLINT    CHECK (rating BETWEEN 1 AND 5),
    veto        BOOLEAN     NOT NULL DEFAULT FALSE,
    reason      TEXT,
    rated_by    UUID        REFERENCES users(id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_expert_ratings_entity ON expert_ratings(tenant_id, entity_type, entity_id);
ALTER TABLE expert_ratings ENABLE ROW LEVEL SECURITY;
ALTER TABLE expert_ratings FORCE ROW LEVEL SECURITY;
CREATE POLICY expert_ratings_tenant ON expert_ratings
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON expert_ratings TO mai_app;
