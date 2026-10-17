-- Cross-Device Identity (vision §7.4, arch §4.5)
-- sessions.external_id_hash already added in V46; adding bounce_corrected_at + linked_session_ids

ALTER TABLE sessions ADD COLUMN IF NOT EXISTS bounce_corrected_at  TIMESTAMPTZ;
ALTER TABLE sessions ADD COLUMN IF NOT EXISTS linked_session_ids   UUID[];

-- Retro-correction run audit
CREATE TABLE retro_correction_runs (
    id                 UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          UUID        NOT NULL REFERENCES tenants(id),
    run_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    sessions_matched   INT         NOT NULL DEFAULT 0,
    sessions_corrected INT         NOT NULL DEFAULT 0
);
ALTER TABLE retro_correction_runs ENABLE ROW LEVEL SECURITY;
ALTER TABLE retro_correction_runs FORCE ROW LEVEL SECURITY;
CREATE POLICY retro_correction_runs_tenant ON retro_correction_runs
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON retro_correction_runs TO mai_app;
