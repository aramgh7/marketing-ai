-- V81: segment_creative_map — maps cluster segments to approved creative variants (Block F §1)
CREATE TABLE segment_creative_map (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    cluster_label VARCHAR(64)  NOT NULL,
    creative_id   UUID         NOT NULL REFERENCES creatives(id) ON DELETE CASCADE,
    channel       VARCHAR(32)  NOT NULL DEFAULT 'META',
    priority      INT          NOT NULL DEFAULT 0,
    is_active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_by    UUID         REFERENCES users(id),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    UNIQUE (tenant_id, cluster_label, creative_id, channel)
);

ALTER TABLE segment_creative_map ENABLE ROW LEVEL SECURITY;
ALTER TABLE segment_creative_map FORCE ROW LEVEL SECURITY;

CREATE POLICY scm_tenant_isolation ON segment_creative_map
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE, DELETE ON segment_creative_map TO mai_app;
