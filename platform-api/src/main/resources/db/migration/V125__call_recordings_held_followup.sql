-- Roadmap 8-18: post-call intelligence — call recordings (Whisper + PII redaction) and the
-- HELD follow-up audience (material sequence after the call). vision §19.18.5; arch §16.18.

CREATE TABLE call_recordings (
    id                   UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id            UUID         NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    appointment_id       UUID         REFERENCES appointments(id),
    session_id           VARCHAR(100) REFERENCES sessions(id),
    ghl_contact_id       VARCHAR(100),
    ghl_appointment_id   VARCHAR(100),
    provider             VARCHAR(20)  NOT NULL DEFAULT 'GHL',      -- GHL | ZOOM | OTHER
    recording_url        TEXT         NOT NULL,
    duration_sec         INT,
    state_code           VARCHAR(2),                                -- US state of the lead (consent law)
    consent_state        VARCHAR(20)  NOT NULL,                     -- GRANTED | NOT_REQUIRED | REQUIRED_MISSING | UNKNOWN
    status               VARCHAR(20)  NOT NULL DEFAULT 'RECEIVED'
        CHECK (status IN ('RECEIVED', 'TRANSCRIBED', 'SKIPPED_CONSENT', 'FAILED')),
    transcript_redacted  TEXT,                                      -- never the raw transcript
    pii_counts           JSONB        NOT NULL DEFAULT '{}',
    objection_tags       JSONB        NOT NULL DEFAULT '[]',        -- [{tag, hits, evidence}]
    summary              TEXT,
    transcribe_provider  VARCHAR(20),
    received_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    transcribed_at       TIMESTAMPTZ,
    error                TEXT
);
CREATE INDEX idx_call_recordings_tenant_time ON call_recordings(tenant_id, received_at DESC);
CREATE INDEX idx_call_recordings_appt ON call_recordings(appointment_id) WHERE appointment_id IS NOT NULL;
ALTER TABLE call_recordings ENABLE ROW LEVEL SECURITY;
ALTER TABLE call_recordings FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON call_recordings
    USING ((tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
           OR current_setting('app.rls_bypass', true) = 'on');
GRANT SELECT, INSERT, UPDATE, DELETE ON call_recordings TO mai_app;

-- Follow-up audience HELD: sequence of materials after a held call
ALTER TABLE followup_variants DROP CONSTRAINT IF EXISTS followup_variants_audience_check;
ALTER TABLE followup_variants ADD CONSTRAINT followup_variants_audience_check
    CHECK (audience IN ('BOOKED', 'NO_SHOW', 'FORM_NO_SLOT', 'HELD'));

-- One assignment per (appointment, audience): the BOOKED sequence and the HELD sequence coexist
ALTER TABLE followup_assignments ADD COLUMN IF NOT EXISTS audience VARCHAR(30) NOT NULL DEFAULT 'BOOKED';
ALTER TABLE followup_assignments DROP CONSTRAINT IF EXISTS followup_assignments_appointment_id_key;
ALTER TABLE followup_assignments ADD CONSTRAINT followup_assignments_appointment_audience_key UNIQUE (appointment_id, audience);
