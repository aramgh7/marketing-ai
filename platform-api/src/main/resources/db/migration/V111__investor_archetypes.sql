-- Phase 8-19: Investor Archetypes
-- investor_archetypes: per-session classification result
-- archetype_policies: maps cluster_label to path/format/followup/brief overrides

CREATE TABLE investor_archetypes (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID        NOT NULL REFERENCES tenants(id),
    session_id      UUID        REFERENCES sessions(id),
    archetype       VARCHAR(30) NOT NULL
        CHECK (archetype IN (
            'COMMITTED_QUICK','RESEARCHER','FENCE_SITTER',
            'HIGH_VALUE_QUIET','DISQUALIFIED','UNKNOWN'
        )),
    confidence      REAL        NOT NULL DEFAULT 0.5,
    signals         JSONB,
    classified_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, session_id)
);
ALTER TABLE investor_archetypes ENABLE ROW LEVEL SECURITY;
ALTER TABLE investor_archetypes FORCE ROW LEVEL SECURITY;
CREATE POLICY investor_archetypes_tenant ON investor_archetypes
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON investor_archetypes TO mai_app;

CREATE INDEX investor_archetypes_session ON investor_archetypes(session_id);
CREATE INDEX investor_archetypes_tenant  ON investor_archetypes(tenant_id, classified_at DESC);

-- Archetype policies: drives path selection, follow-up arm, and brief segment per cluster
CREATE TABLE archetype_policies (
    id                          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id                   UUID        NOT NULL REFERENCES tenants(id),
    cluster_label               VARCHAR(50) NOT NULL,  -- matches behavior_clusters.label
    funnel_path_key             VARCHAR(30),           -- path key to assign (8-14)
    first_touch_format          VARCHAR(30),           -- e.g. VIDEO, LONG_FORM, WEBINAR
    followup_audience_override  VARCHAR(50),           -- GHL tag override
    brief_segment               VARCHAR(50),           -- segment label for data-driven brief
    is_active                   BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, cluster_label)
);
ALTER TABLE archetype_policies ENABLE ROW LEVEL SECURITY;
ALTER TABLE archetype_policies FORCE ROW LEVEL SECURITY;
CREATE POLICY archetype_policies_tenant ON archetype_policies
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON archetype_policies TO mai_app;

-- Seed Client #1 archetype policies with known investor archetypes
-- cluster_label aligns with behavior_clusters seeded labels (ag insider / HNW generalist / family-office analyst / impulsive)
COMMENT ON TABLE archetype_policies IS
    'Maps investor archetype cluster labels to funnel path, follow-up, and brief segment overrides. '
    'Read by /decide/landing, /decide/followup, /brief/generate-data-driven.';
