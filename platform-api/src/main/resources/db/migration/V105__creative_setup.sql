-- 8-11: Creative Production & Exploration Set-up (vision §19.10)
-- Adds client_label + informed priors to creatives; creative_aliases for variation tracking.

ALTER TABLE creatives
    ADD COLUMN IF NOT EXISTS client_label         VARCHAR(32),        -- 'winner' | 'loser' | 'questionable'
    ADD COLUMN IF NOT EXISTS informed_prior_alpha NUMERIC(10,4) NOT NULL DEFAULT 1.0,
    ADD COLUMN IF NOT EXISTS informed_prior_beta  NUMERIC(10,4) NOT NULL DEFAULT 1.0;

-- winner → Beta(5,1); loser → Beta(1,5); questionable → Beta(2,2); default → Beta(1,1)
-- Applied at import time by CreativeSetupController.

-- Aliases link variation stream entries back to a source creative
CREATE TABLE IF NOT EXISTS creative_aliases (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    creative_id     UUID NOT NULL REFERENCES creatives(id) ON DELETE CASCADE,
    alias_type      VARCHAR(32) NOT NULL,   -- 'hook_variant' | 'body_variant' | 'cta_variant' | 'voice_variant'
    label           VARCHAR(255) NOT NULL,
    notes           TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_creative_aliases_tenant
    ON creative_aliases(tenant_id, creative_id);

ALTER TABLE creative_aliases ENABLE ROW LEVEL SECURITY;
ALTER TABLE creative_aliases FORCE ROW LEVEL SECURITY;

CREATE POLICY ca_tenant ON creative_aliases
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE, DELETE ON creative_aliases TO mai_app;
