-- Phase 14: Visual Template System & Media Library
-- templates: visual shell (block order + layout variants); no text/media content
-- assets: per-tenant image/video library with 3-level tagging
-- asset_placements: tracks every context (landing slot, ad creative, materials) an asset appears in
-- tag_rlha_log: stores Claude Vision suggestion vs human confirmation diffs (RLHA signal)

CREATE TABLE IF NOT EXISTS templates (
    id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID        REFERENCES tenants(id),        -- NULL = platform default
    name                VARCHAR(255) NOT NULL,
    description         TEXT,
    version             INT         NOT NULL DEFAULT 1,
    blocks              JSONB       NOT NULL DEFAULT '[]',          -- [{block_type,position,layout_variant,slot_overrides}]
    default_bg_type     VARCHAR(20) NOT NULL DEFAULT 'color'
                            CHECK (default_bg_type IN ('color','gradient','image','video')),
    background_config   JSONB,
    tags                TEXT[]      NOT NULL DEFAULT '{}',          -- niche, tone, use_case
    forked_from         UUID        REFERENCES templates(id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_templates_tenant ON templates(tenant_id);
-- No RLS on templates: platform defaults (tenant_id=NULL) must be readable by all tenants.
-- Tenant-specific templates are filtered by application logic (tenant_id = current OR tenant_id IS NULL).
GRANT SELECT, INSERT, UPDATE ON templates TO mai_app;
GRANT SELECT ON templates TO mai_ml;

CREATE TABLE IF NOT EXISTS assets (
    id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID        NOT NULL REFERENCES tenants(id),
    s3_key              VARCHAR(1024) NOT NULL,
    mime_type           VARCHAR(100) NOT NULL,
    width               INT,
    height              INT,
    duration_s          REAL,
    tags_l1             TEXT[]      NOT NULL DEFAULT '{}',
    tags_l2             TEXT[]      NOT NULL DEFAULT '{}',
    tags_l3             TEXT[]      NOT NULL DEFAULT '{}',
    auto_tag_suggested  JSONB,
    tag_confirmed_at    TIMESTAMPTZ,
    uploaded_by         UUID        REFERENCES users(id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_assets_tenant ON assets(tenant_id);
ALTER TABLE assets ENABLE ROW LEVEL SECURITY;
ALTER TABLE assets FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON assets
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid
           OR current_setting('app.rls_bypass', true) = 'on');
GRANT SELECT, INSERT, UPDATE, DELETE ON assets TO mai_app;
GRANT SELECT ON assets TO mai_ml;

CREATE TABLE IF NOT EXISTS asset_placements (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_id        UUID        NOT NULL REFERENCES assets(id) ON DELETE CASCADE,
    context_type    VARCHAR(30) NOT NULL
                        CHECK (context_type IN ('LANDING_SLOT','AD_CREATIVE','MATERIALS_BLOCK')),
    context_id      UUID        NOT NULL,
    slot_name       VARCHAR(100),
    placed_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_asset_placements_asset ON asset_placements(asset_id);
GRANT SELECT, INSERT, DELETE ON asset_placements TO mai_app;
GRANT SELECT ON asset_placements TO mai_ml;

CREATE TABLE IF NOT EXISTS tag_rlha_log (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_id        UUID        NOT NULL REFERENCES assets(id) ON DELETE CASCADE,
    tag_level       VARCHAR(5)  NOT NULL CHECK (tag_level IN ('L1','L2','L3')),
    suggested_tags  TEXT[]      NOT NULL DEFAULT '{}',
    confirmed_tags  TEXT[]      NOT NULL DEFAULT '{}',
    was_corrected   BOOLEAN     NOT NULL,
    corrected_by    UUID        REFERENCES users(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_tag_rlha_log_asset ON tag_rlha_log(asset_id);
GRANT SELECT, INSERT ON tag_rlha_log TO mai_app;
GRANT SELECT ON tag_rlha_log TO mai_ml;

-- Add template reference to landing_variants
ALTER TABLE landing_variants ADD COLUMN IF NOT EXISTS template_id      UUID REFERENCES templates(id);
ALTER TABLE landing_variants ADD COLUMN IF NOT EXISTS template_version INT;
