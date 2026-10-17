-- Phase 2: LLM Content Generation, RLHA Store, Compliance & IP Pre-Approval (text)
-- Architecture §15.4, §15.7

-- LLM-generated content drafts
CREATE TABLE content_generations (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID        NOT NULL REFERENCES tenants(id),
    entity_type     VARCHAR(20),                -- LANDING_VARIANT, BRIEF
    entity_id       UUID,
    block_event_id  VARCHAR(50),
    slot            VARCHAR(50),
    prompt_hash     VARCHAR(64),
    generated_text  TEXT        NOT NULL,
    model_version   VARCHAR(50),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE content_generations ENABLE ROW LEVEL SECURITY;
ALTER TABLE content_generations FORCE ROW LEVEL SECURITY;
CREATE POLICY content_generations_tenant ON content_generations
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON content_generations TO mai_app;
CREATE INDEX idx_content_gen_entity ON content_generations(tenant_id, entity_id, created_at DESC);

-- Marketer corrections of generated content (RLHA few-shot training data)
CREATE TABLE rlha_corrections (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID        NOT NULL REFERENCES tenants(id),
    generation_id   UUID        REFERENCES content_generations(id),
    generated_text  TEXT        NOT NULL,
    approved_text   TEXT        NOT NULL,
    delta_tags      TEXT[],                     -- TONE, LENGTH, FORBIDDEN_WORD, STRUCTURE, BRAND_CONTEXT
    context         JSONB,
    use_as_few_shot BOOLEAN     NOT NULL DEFAULT TRUE,
    edited_by       UUID        REFERENCES users(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE rlha_corrections ENABLE ROW LEVEL SECURITY;
ALTER TABLE rlha_corrections FORCE ROW LEVEL SECURITY;
CREATE POLICY rlha_corrections_tenant ON rlha_corrections
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON rlha_corrections TO mai_app;
CREATE INDEX idx_rlha_tenant_type ON rlha_corrections(tenant_id, created_at DESC);

-- Compliance pre-approval checks (text phase 2; media phase 4)
CREATE TABLE compliance_checks (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID        NOT NULL REFERENCES tenants(id),
    entity_type     VARCHAR(20) NOT NULL,       -- LANDING_VARIANT, CREATIVE, BRIEF, OFFER
    entity_id       UUID        NOT NULL,
    check_type      VARCHAR(30) NOT NULL,       -- META_POLICY, CLAIMS, TRADEMARK, FACE, MUSIC, AI_LABEL
    result          VARCHAR(10) NOT NULL,       -- PASS, WARN, FAIL
    findings        JSONB,                      -- [{rule, excerpt, severity, suggestion}]
    model_version   VARCHAR(50),
    overridden_by   UUID        REFERENCES users(id),
    override_reason TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE compliance_checks ENABLE ROW LEVEL SECURITY;
ALTER TABLE compliance_checks FORCE ROW LEVEL SECURITY;
CREATE POLICY compliance_checks_tenant ON compliance_checks
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON compliance_checks TO mai_app;
CREATE INDEX idx_compliance_entity ON compliance_checks(tenant_id, entity_id, created_at DESC);

-- Marketer-maintained product fact base (for claims verification)
CREATE TABLE product_facts (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID        NOT NULL REFERENCES tenants(id),
    fact            TEXT        NOT NULL,
    source          TEXT,
    verified_by     UUID        REFERENCES users(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE product_facts ENABLE ROW LEVEL SECURITY;
ALTER TABLE product_facts FORCE ROW LEVEL SECURITY;
CREATE POLICY product_facts_tenant ON product_facts
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON product_facts TO mai_app;

-- Niche-level trademark watchlist (platform-wide, no tenant_id)
CREATE TABLE trademark_watchlist (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    niche           VARCHAR(100),
    term            TEXT        NOT NULL,
    owner           TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
GRANT SELECT ON trademark_watchlist TO mai_app;
