-- Creator Brief Skeleton (Phase 1-4, vision §5.5)
-- Phase 1: template-based, marketer-filled.
-- Phase 3-4: will be data-driven from model posteriors.

CREATE TABLE creator_briefs (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          UUID NOT NULL REFERENCES tenants(id),
    title              VARCHAR(500),
    creative_type      VARCHAR(20),             -- VIDEO, IMAGE, CAROUSEL
    target_audience    TEXT,                    -- free-text description
    product_info       TEXT,                    -- free-text product context
    recommended_atoms  JSONB DEFAULT '[]',      -- [{atomType, atomValue, cvr, creative_count, rationale}]
    avoid_atoms        JSONB DEFAULT '[]',      -- same structure, low performers
    structure_template TEXT,                    -- e.g. "Hook → Problem → Solution → Proof → CTA"
    benchmarks         JSONB DEFAULT '{}',      -- {cvr_avg, cvr_top_atom, top_hook, ...}
    notes              TEXT,                    -- marketer notes
    status             VARCHAR(20) DEFAULT 'DRAFT',  -- DRAFT, FINAL
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_briefs_tenant ON creator_briefs(tenant_id, created_at DESC);

ALTER TABLE creator_briefs ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON creator_briefs
    USING (tenant_id = current_setting('app.current_tenant')::uuid);
