-- Brief versioning, pairing, and creator random effect (vision §5.5.3)

ALTER TABLE creator_briefs ADD COLUMN IF NOT EXISTS version              INT          NOT NULL DEFAULT 1;
ALTER TABLE creator_briefs ADD COLUMN IF NOT EXISTS result_creative_id   UUID         REFERENCES creatives(id);
ALTER TABLE creator_briefs ADD COLUMN IF NOT EXISTS paired_brief_id      UUID         REFERENCES creator_briefs(id);
ALTER TABLE creator_briefs ADD COLUMN IF NOT EXISTS brief_type           VARCHAR(20)  NOT NULL DEFAULT 'GENERAL'
    CHECK (brief_type IN ('GENERAL', 'VIDEO', 'LANDING', 'STATIC_IMAGE'));

-- Creator random effect on creatives (vision §5.5.3)
ALTER TABLE creatives ADD COLUMN IF NOT EXISTS creator_id UUID REFERENCES users(id);

-- Immutable brief version history
CREATE TABLE brief_versions (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    brief_id    UUID        NOT NULL REFERENCES creator_briefs(id),
    tenant_id   UUID        NOT NULL REFERENCES tenants(id),
    version     INT         NOT NULL,
    snapshot    JSONB       NOT NULL,
    changed_by  UUID        REFERENCES users(id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE brief_versions ENABLE ROW LEVEL SECURITY;
ALTER TABLE brief_versions FORCE ROW LEVEL SECURITY;
CREATE POLICY brief_versions_tenant ON brief_versions
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON brief_versions TO mai_app;

CREATE INDEX IF NOT EXISTS brief_versions_brief_id_idx ON brief_versions(brief_id);
