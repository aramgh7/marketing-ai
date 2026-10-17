-- V82: brief_voc_signals — Voice-of-Customer signals attached to briefs (Block F §3)
CREATE TABLE brief_voc_signals (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID        NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    brief_id    UUID        NOT NULL REFERENCES creator_briefs(id) ON DELETE CASCADE,
    source      VARCHAR(32) NOT NULL,   -- REVIEW, SUPPORT_TICKET, SURVEY, CALL_TRANSCRIPT
    signal_text TEXT        NOT NULL,
    sentiment   VARCHAR(16),            -- POSITIVE, NEGATIVE, NEUTRAL
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

ALTER TABLE brief_voc_signals ENABLE ROW LEVEL SECURITY;
ALTER TABLE brief_voc_signals FORCE ROW LEVEL SECURITY;

CREATE POLICY bvs_tenant_isolation ON brief_voc_signals
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE, DELETE ON brief_voc_signals TO mai_app;
