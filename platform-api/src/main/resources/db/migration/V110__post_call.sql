-- Phase 8-18: Post-Call Intelligence
-- call_notes: coordinator-entered structured call outcome + objection tags
-- call_objections: individual objection instances linked to creative + segment (for brief)
-- objection_summary: per-tenant aggregate for feedback loop and dashboard

CREATE TABLE call_notes (
    id                      UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id               UUID        NOT NULL REFERENCES tenants(id),
    session_id              VARCHAR(100) REFERENCES sessions(id),
    appointment_id          UUID        REFERENCES appointments(id),
    call_outcome            VARCHAR(20) NOT NULL
        CHECK (call_outcome IN ('COMMITTED','INTERESTED_FOLLOW','COLD','NO_SHOW','RESCHEDULED')),
    next_step               VARCHAR(30) NOT NULL DEFAULT 'NONE'
        CHECK (next_step IN ('SEND_DECK','WIRE_INSTRUCTIONS','SCHEDULE_FOLLOWUP','NONE')),
    objection_tags          JSONB       NOT NULL DEFAULT '[]',
    coordinator_note        TEXT,
    follow_up_arm_override  VARCHAR(40),
    signal_sent_at          TIMESTAMPTZ,
    created_by              VARCHAR(40) DEFAULT 'coordinator',
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE call_notes ENABLE ROW LEVEL SECURITY;
ALTER TABLE call_notes FORCE ROW LEVEL SECURITY;
CREATE POLICY call_notes_tenant ON call_notes
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON call_notes TO mai_app;

CREATE INDEX call_notes_session     ON call_notes(session_id);
CREATE INDEX call_notes_appointment ON call_notes(appointment_id);
CREATE INDEX call_notes_tenant_ts   ON call_notes(tenant_id, created_at DESC);

-- Detailed per-objection records linked back to creative + ticket bucket
CREATE TABLE call_objections (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID        NOT NULL REFERENCES tenants(id),
    call_note_id    UUID        NOT NULL REFERENCES call_notes(id) ON DELETE CASCADE,
    objection_tag   VARCHAR(50) NOT NULL,
    ticket_bucket   VARCHAR(20),
    creative_id     UUID        REFERENCES creatives(id),
    stage_after     VARCHAR(30),  -- funnel stage after the call
    resolved        BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE call_objections ENABLE ROW LEVEL SECURITY;
ALTER TABLE call_objections FORCE ROW LEVEL SECURITY;
CREATE POLICY call_objections_tenant ON call_objections
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON call_objections TO mai_app;

CREATE INDEX call_objections_note       ON call_objections(call_note_id);
CREATE INDEX call_objections_creative   ON call_objections(creative_id);
CREATE INDEX call_objections_tag_tenant ON call_objections(tenant_id, objection_tag);

-- Aggregate per tenant (upserted on each new call_note)
CREATE TABLE objection_summary (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID        NOT NULL REFERENCES tenants(id),
    objection_tag   VARCHAR(50) NOT NULL,
    count_total     INT         NOT NULL DEFAULT 0,
    count_converted INT         NOT NULL DEFAULT 0,
    last_seen_at    TIMESTAMPTZ,
    UNIQUE (tenant_id, objection_tag)
);
ALTER TABLE objection_summary ENABLE ROW LEVEL SECURITY;
ALTER TABLE objection_summary FORCE ROW LEVEL SECURITY;
CREATE POLICY objection_summary_tenant ON objection_summary
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON objection_summary TO mai_app;
