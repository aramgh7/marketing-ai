-- Voice of Customer: sources (marketer uploads)
CREATE TABLE voc_sources (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    UUID         NOT NULL REFERENCES tenants(id),
    source_type  VARCHAR(20)  NOT NULL
                     CHECK (source_type IN ('REVIEW','SUPPORT_CHAT','SURVEY','CALL_TRANSCRIPT','AD_COMMENT','OTHER')),
    source_name  VARCHAR(200),
    record_count INT          NOT NULL DEFAULT 0,
    imported_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    imported_by  UUID         REFERENCES users(id)
);
ALTER TABLE voc_sources ENABLE ROW LEVEL SECURITY;
ALTER TABLE voc_sources FORCE ROW LEVEL SECURITY;
CREATE POLICY voc_sources_tenant ON voc_sources
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON voc_sources TO mai_app;

-- Voice of Customer: extracted objections
CREATE TABLE voc_objections (
    id                   UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id            UUID        NOT NULL REFERENCES tenants(id),
    source_id            UUID        REFERENCES voc_sources(id),
    category             VARCHAR(30) NOT NULL
                             CHECK (category IN ('PRICE','TRUST','UNDERSTANDING','URGENCY','FIT','PROCESS','COMPETITOR','OTHER')),
    objection_text       TEXT        NOT NULL,
    representative_quote TEXT,
    count                INT         NOT NULL DEFAULT 1,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE voc_objections ENABLE ROW LEVEL SECURITY;
ALTER TABLE voc_objections FORCE ROW LEVEL SECURITY;
CREATE POLICY voc_objections_tenant ON voc_objections
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON voc_objections TO mai_app;
CREATE INDEX idx_voc_objections_tenant ON voc_objections(tenant_id, category);
