-- Phase 8-21: Reg CF Waitlist as an Asset
-- ⚠️ FROZEN — OI-3: awaiting counsel answer before marketing to non-accredited segment
-- DO NOT activate Meta audience until regcf_activation_gate.status = 'ACTIVE'

CREATE TABLE regcf_waitlist (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID        NOT NULL REFERENCES tenants(id),
    email           VARCHAR(254) NOT NULL,
    ticket_interest VARCHAR(20),
    source_session  UUID        REFERENCES sessions(id),
    consent_given   BOOLEAN     NOT NULL DEFAULT FALSE,
    consent_ts      TIMESTAMPTZ,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING','NOTIFIED','CONVERTED','REMOVED')),
    added_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, email)
);
ALTER TABLE regcf_waitlist ENABLE ROW LEVEL SECURITY;
ALTER TABLE regcf_waitlist FORCE ROW LEVEL SECURITY;
CREATE POLICY regcf_waitlist_tenant ON regcf_waitlist
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON regcf_waitlist TO mai_app;

-- Tracks OI-3 unlock lifecycle. Status stays FROZEN until counsel confirms.
CREATE TABLE regcf_activation_gate (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID        NOT NULL REFERENCES tenants(id),
    status          VARCHAR(20) NOT NULL DEFAULT 'FROZEN'
        CHECK (status IN ('FROZEN','PENDING_REVIEW','APPROVED','ACTIVE')),
    counsel_answer  TEXT,
    unlocked_by     VARCHAR(100),
    unlocked_at     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id)
);
ALTER TABLE regcf_activation_gate ENABLE ROW LEVEL SECURITY;
ALTER TABLE regcf_activation_gate FORCE ROW LEVEL SECURITY;
CREATE POLICY regcf_gate_tenant ON regcf_activation_gate
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE ON regcf_activation_gate TO mai_app;

-- Weekly value estimate: n_leads * p_convert_regcf * avg_ticket_regcf with CI
CREATE TABLE waitlist_value_estimates (
    id                  UUID    PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID    NOT NULL REFERENCES tenants(id),
    estimated_at        DATE    NOT NULL DEFAULT CURRENT_DATE,
    n_leads             INT     NOT NULL DEFAULT 0,
    p_convert_regcf     REAL    NOT NULL DEFAULT 0.05,
    avg_ticket_regcf    REAL    NOT NULL DEFAULT 10000.0,
    estimated_value     REAL    GENERATED ALWAYS AS (n_leads * p_convert_regcf * avg_ticket_regcf) STORED,
    ci_lower            REAL,
    ci_upper            REAL,
    UNIQUE (tenant_id, estimated_at)
);
ALTER TABLE waitlist_value_estimates ENABLE ROW LEVEL SECURITY;
ALTER TABLE waitlist_value_estimates FORCE ROW LEVEL SECURITY;
CREATE POLICY waitlist_value_estimates_tenant ON waitlist_value_estimates
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE ON waitlist_value_estimates TO mai_app;

-- Seed: all existing tenants start FROZEN (OI-3)
INSERT INTO regcf_activation_gate (tenant_id, status)
SELECT id, 'FROZEN' FROM tenants
ON CONFLICT (tenant_id) DO NOTHING;
