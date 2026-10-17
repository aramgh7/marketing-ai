-- Follow-up sequence optimisation — Thompson Sampling bandit (vision §19.6 / arch §16.6)

CREATE TABLE followup_variants (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name         VARCHAR(120) NOT NULL,
    audience     VARCHAR(30) NOT NULL CHECK (audience IN ('BOOKED','NO_SHOW','FORM_NO_SLOT')),
    cadence      JSONB NOT NULL DEFAULT '[]',   -- [{day: 1, channel: "SMS", template_id: "..."}]
    channels     TEXT[] NOT NULL DEFAULT '{}',
    is_control   BOOLEAN NOT NULL DEFAULT FALSE,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'DRAFT'
                    CHECK (lifecycle_state IN ('DRAFT','PENDING_REVIEW','APPROVED','PAUSED','ARCHIVED')),
    approved_by  UUID,
    approved_at  TIMESTAMPTZ,
    alpha        FLOAT NOT NULL DEFAULT 1.0,
    beta         FLOAT NOT NULL DEFAULT 1.0,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE followup_variants ENABLE ROW LEVEL SECURITY;
CREATE POLICY rls_followup_variants ON followup_variants
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
ALTER TABLE followup_variants FORCE ROW LEVEL SECURITY;
GRANT SELECT, INSERT, UPDATE, DELETE ON followup_variants TO mai_app;

CREATE TABLE followup_assignments (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    appointment_id  UUID NOT NULL REFERENCES appointments(id) ON DELETE CASCADE,
    variant_id      UUID NOT NULL REFERENCES followup_variants(id),
    context_key     VARCHAR(80) NOT NULL,   -- e.g. "domestic:high_ticket"
    outcome         VARCHAR(20) CHECK (outcome IN ('SHOWED','NO_SHOW','UNKNOWN')),
    assigned_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at     TIMESTAMPTZ,
    UNIQUE (appointment_id)
);

ALTER TABLE followup_assignments ENABLE ROW LEVEL SECURITY;
CREATE POLICY rls_followup_assignments ON followup_assignments
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
ALTER TABLE followup_assignments FORCE ROW LEVEL SECURITY;
GRANT SELECT, INSERT, UPDATE, DELETE ON followup_assignments TO mai_app;
