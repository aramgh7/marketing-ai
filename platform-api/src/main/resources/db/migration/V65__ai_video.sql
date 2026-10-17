-- AI Video requests and A/B acceptance results (5-6)
CREATE TABLE ai_video_requests (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        UUID        NOT NULL REFERENCES tenants(id),
    provider         VARCHAR(30) NOT NULL,           -- HEYGEN | SYNTHESIA | RUNWAY | MOCK
    script_text      TEXT        NOT NULL,
    voice_atom_ids   UUID[]      NOT NULL DEFAULT '{}',
    status           VARCHAR(20) NOT NULL DEFAULT 'PENDING', -- PENDING | PROCESSING | COMPLETED | FAILED
    provider_job_id  TEXT,
    result_url       TEXT,
    approved_by      UUID        REFERENCES users(id),
    approved_at      TIMESTAMPTZ,
    requires_ai_label BOOLEAN    NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE ai_video_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE ai_video_requests FORCE ROW LEVEL SECURITY;
CREATE POLICY ai_video_requests_tenant ON ai_video_requests
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON ai_video_requests TO mai_app;
CREATE INDEX idx_avr_tenant ON ai_video_requests(tenant_id, status);

-- A/B acceptance runs: AI-generated vs live-footage (P(AI >= live) > 0.9 required before scale)
CREATE TABLE ai_video_ab_results (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID        NOT NULL REFERENCES tenants(id),
    request_id      UUID        NOT NULL REFERENCES ai_video_requests(id),
    ai_conversions  INT         NOT NULL DEFAULT 0,
    ai_sessions     INT         NOT NULL DEFAULT 0,
    live_conversions INT        NOT NULL DEFAULT 0,
    live_sessions   INT         NOT NULL DEFAULT 0,
    p_ai_gte_live   FLOAT,
    acceptance      VARCHAR(20),  -- PASS | FAIL | INSUFFICIENT_DATA
    computed_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE ai_video_ab_results ENABLE ROW LEVEL SECURITY;
ALTER TABLE ai_video_ab_results FORCE ROW LEVEL SECURITY;
CREATE POLICY ai_video_ab_results_tenant ON ai_video_ab_results
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON ai_video_ab_results TO mai_app;
