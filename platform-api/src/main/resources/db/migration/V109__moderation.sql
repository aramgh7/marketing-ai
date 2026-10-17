-- 8-17: Compliance by Construction + Moderation Probe + Reviewer Persona (vision §19.18.4; arch §16.17)

CREATE TABLE moderation_results (
    id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID        NOT NULL REFERENCES tenants(id),
    creative_id         UUID        REFERENCES creatives(id),
    ad_set_id           UUID        REFERENCES meta_ad_sets(id),
    probe_type          VARCHAR(20) NOT NULL DEFAULT 'meta_review',
    result              VARCHAR(20) NOT NULL
        CHECK (result IN ('APPROVED','REJECTED','PENDING','NOT_DELIVERED')),
    meta_rejection_code VARCHAR(50),
    checked_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at         TIMESTAMPTZ,
    recovery_action     VARCHAR(30)
);
ALTER TABLE moderation_results ENABLE ROW LEVEL SECURITY;
ALTER TABLE moderation_results FORCE ROW LEVEL SECURITY;
CREATE POLICY moderation_results_tenant ON moderation_results
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON moderation_results TO mai_app;

CREATE INDEX moderation_results_creative ON moderation_results(creative_id);

-- Personas: reviewer persona sandbox (META_REVIEWER + future INVESTOR_50K, etc.)
CREATE TABLE personas (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID        REFERENCES tenants(id),
    persona_key VARCHAR(30) NOT NULL,
    description TEXT,
    rulebases   JSONB,
    traits      JSONB,
    is_active   BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, persona_key)
);
GRANT SELECT, INSERT, UPDATE ON personas TO mai_app;

-- Seed META_REVIEWER persona (platform-wide, tenant_id NULL)
INSERT INTO personas (persona_key, description, rulebases, traits, is_active)
VALUES (
    'META_REVIEWER',
    'Simulates Meta ad review: financial products policy + personal attributes policy',
    '[{"rulebase_key": "META_FINANCIAL", "weight": 1.0}, {"rulebase_key": "META_PERSONAL_ATTR", "weight": 1.0}]'::jsonb,
    '{"focus": "policy_compliance", "strictness": "high", "financial_services": true}'::jsonb,
    TRUE
)
ON CONFLICT (tenant_id, persona_key) DO NOTHING;
