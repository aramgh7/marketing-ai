-- Phase 4: Video Template Engine + Media Asset Pipeline
-- §5.2: footage → scenes → montage plan → renders
-- §23 Phase 4: S3/R2 object storage with lifecycle policy

CREATE TABLE video_footage (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        UUID        NOT NULL REFERENCES tenants(id),
    creative_id      UUID        REFERENCES creatives(id),
    filename         VARCHAR(500) NOT NULL,
    storage_url      TEXT        NOT NULL,
    duration_sec     REAL,
    transcript       TEXT,
    scenes           JSONB       NOT NULL DEFAULT '[]',
    file_size_bytes  BIGINT,
    -- §23 Phase 4: lifecycle — HOT (active) → COLD (90 days) → archived
    asset_tier       VARCHAR(10) NOT NULL DEFAULT 'HOT',
    uploaded_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    archived_at      TIMESTAMPTZ
);

ALTER TABLE video_footage ENABLE ROW LEVEL SECURITY;
ALTER TABLE video_footage FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON video_footage
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON video_footage TO mai_app;

CREATE INDEX idx_footage_tenant ON video_footage(tenant_id, uploaded_at DESC);


CREATE TABLE video_renders (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        UUID        NOT NULL REFERENCES tenants(id),
    footage_id       UUID        REFERENCES video_footage(id),
    creative_id      UUID        REFERENCES creatives(id),
    render_spec      JSONB       NOT NULL DEFAULT '{}',
    format           VARCHAR(10) NOT NULL,            -- '9:16', '4:5', '1:1'
    variation_type   VARCHAR(30) NOT NULL DEFAULT 'CONCEPT',  -- HOOK_VARIATION, OVERLAY_VARIATION, CONCEPT
    status           VARCHAR(20) NOT NULL DEFAULT 'PENDING',  -- PENDING, RENDERING, COMPLETED, FAILED
    storage_url      TEXT,
    render_job_id    VARCHAR(100),
    approved_by      UUID        REFERENCES users(id),
    approved_at      TIMESTAMPTZ,
    -- §23 Phase 4: losers deleted 30 days after Gate elimination
    killed_at        TIMESTAMPTZ,
    render_time_ms   INT,
    file_size_bytes  BIGINT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE video_renders ENABLE ROW LEVEL SECURITY;
ALTER TABLE video_renders FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON video_renders
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON video_renders TO mai_app;

CREATE INDEX idx_renders_tenant ON video_renders(tenant_id, created_at DESC);
CREATE INDEX idx_renders_footage ON video_renders(footage_id);
CREATE INDEX idx_renders_creative ON video_renders(creative_id);
