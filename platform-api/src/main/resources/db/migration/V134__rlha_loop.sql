-- Roadmap 9-20 (vision §13.3 RLHA): close the learning loop from human actions.
--  content_generations  — every generated slot is recorded (was never written before 9-20)
--  rlha_corrections     — pairs are written on landing / brief edits and manual input; delta tags from ML
--  render_feedback      — approve / reject of video renders with a reason → avoid-list for the next montage plan
ALTER TABLE content_generations ADD COLUMN IF NOT EXISTS content_type VARCHAR(30);        -- landing_block | brief
ALTER TABLE rlha_corrections   ADD COLUMN IF NOT EXISTS content_type VARCHAR(30);
ALTER TABLE rlha_corrections   ADD COLUMN IF NOT EXISTS edit_reason  VARCHAR(40);         -- TONE | FACTS | LEGAL | LENGTH | STRUCTURE | OTHER
ALTER TABLE rlha_corrections   ADD COLUMN IF NOT EXISTS entity_type  VARCHAR(20);
ALTER TABLE rlha_corrections   ADD COLUMN IF NOT EXISTS entity_id    UUID;
CREATE INDEX IF NOT EXISTS idx_rlha_corrections_tenant_type ON rlha_corrections(tenant_id, content_type, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_content_generations_entity ON content_generations(tenant_id, entity_id, block_event_id, slot, created_at DESC);
GRANT SELECT, INSERT, UPDATE ON content_generations TO mai_ml;
GRANT SELECT, INSERT, UPDATE ON rlha_corrections TO mai_ml;

CREATE TABLE IF NOT EXISTS render_feedback (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID        NOT NULL REFERENCES tenants(id),
    render_id   UUID        NOT NULL,
    decision    VARCHAR(10) NOT NULL CHECK (decision IN ('APPROVED', 'REJECTED')),
    reason      VARCHAR(30),                 -- EDITING | TEXT | MUSIC | FACE | COMPLIANCE | PACE | OTHER (rejects)
    note        TEXT,
    decided_by  UUID,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_render_feedback_tenant ON render_feedback(tenant_id, created_at DESC);
ALTER TABLE render_feedback ENABLE ROW LEVEL SECURITY;
ALTER TABLE render_feedback FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON render_feedback
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid
           OR current_setting('app.rls_bypass', true) = 'on');
GRANT SELECT, INSERT ON render_feedback TO mai_app;
GRANT SELECT ON render_feedback TO mai_ml;
