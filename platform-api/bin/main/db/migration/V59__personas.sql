-- Synthetic Persona Sandboxing (vision §15.12.12) — Phase 3 (arch §15.9)

CREATE TABLE personas (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID        NOT NULL REFERENCES tenants(id),
    name        VARCHAR(100) NOT NULL,
    description TEXT,
    traits      JSONB,
    source_refs JSONB,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE personas ENABLE ROW LEVEL SECURITY;
ALTER TABLE personas FORCE ROW LEVEL SECURITY;
CREATE POLICY personas_tenant ON personas
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON personas TO mai_app;

CREATE TABLE persona_scores (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID        NOT NULL REFERENCES tenants(id),
    persona_id      UUID        NOT NULL REFERENCES personas(id),
    candidate_type  VARCHAR(20) NOT NULL,               -- CREATIVE, VARIANT, OFFER_CANDIDATE
    candidate_id    UUID        NOT NULL,
    scores          JSONB       NOT NULL,               -- {relevance, trust, p_click}
    model_version   VARCHAR(50),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE persona_scores ENABLE ROW LEVEL SECURITY;
ALTER TABLE persona_scores FORCE ROW LEVEL SECURITY;
CREATE POLICY persona_scores_tenant ON persona_scores
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON persona_scores TO mai_app;

-- persona_mode settings stored in tenants.settings JSONB (no schema change needed)
