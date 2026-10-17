-- Message-Market Fit Diagnostics + Dynamic Offer Explorer (vision §18) — Phase 3 (arch §15.6)

CREATE TABLE mmf_diagnoses (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID        NOT NULL REFERENCES tenants(id),
    audience_key    VARCHAR(100) NOT NULL,
    p_any_above_breakeven REAL,
    between_variant_var REAL,
    verdict         VARCHAR(20) NOT NULL,               -- OFFER_PROBLEM, CREATIVE_PROBLEM, OK
    signals         JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_mmf_tenant ON mmf_diagnoses(tenant_id, created_at DESC);
ALTER TABLE mmf_diagnoses ENABLE ROW LEVEL SECURITY;
ALTER TABLE mmf_diagnoses FORCE ROW LEVEL SECURITY;
CREATE POLICY mmf_tenant ON mmf_diagnoses
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON mmf_diagnoses TO mai_app;

CREATE TABLE offer_candidates (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID        NOT NULL REFERENCES tenants(id),
    audience_key    VARCHAR(100),
    sub_atoms       JSONB       NOT NULL,               -- discount, guarantee, bonus, urgency, payment
    hypothesis      TEXT        NOT NULL,
    rank_score      REAL,
    status          VARCHAR(15) NOT NULL DEFAULT 'PROPOSED', -- PROPOSED, APPROVED, REJECTED, LAUNCHED
    approved_by     UUID        REFERENCES users(id),
    approved_at     TIMESTAMPTZ,
    variant_id      UUID        REFERENCES landing_variants(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_offer_candidates_tenant ON offer_candidates(tenant_id, status, created_at DESC);
ALTER TABLE offer_candidates ENABLE ROW LEVEL SECURITY;
ALTER TABLE offer_candidates FORCE ROW LEVEL SECURITY;
CREATE POLICY offer_candidates_tenant ON offer_candidates
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON offer_candidates TO mai_app;
