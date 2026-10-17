-- 3-7: Delayed Churn Penalty infrastructure (vision §6.7)
--
-- When a deal is lost (GHL OpportunityStatusUpdate/status=lost), the Thompson arm
-- that was shown to that lead's session receives a retroactive β penalty.
-- Rules (§6.7): min cohort ≥ 20, decay λ=0.02/day, cap 30% of (α+β) per recalc.
--
-- Attribution chain:
--   sessions.deal_status='lost' + ghl_contact_id
--   → decision_log.session_id → chosen_variant_id + context_key
--   → thompson_arms.variant_id → arm_id + current posteriors

-- Track whether a lost session has been through the penalty scan.
-- Sessions older than 90 days with churn_penalized_at IS NULL are force-closed
-- (cohort never reached min size 20 — penalty skipped, session marked anyway).
ALTER TABLE sessions
    ADD COLUMN IF NOT EXISTS churn_penalized_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_sessions_churn_pending
    ON sessions(tenant_id, deal_status, started_at)
    WHERE deal_status = 'lost' AND churn_penalized_at IS NULL;

-- Every retroactive β correction applied to a Thompson arm
CREATE TABLE churn_penalty_log (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    UUID         NOT NULL,
    arm_id       UUID         NOT NULL REFERENCES thompson_arms(id) ON DELETE CASCADE,
    variant_id   UUID         NOT NULL,
    context_key  VARCHAR(100) NOT NULL DEFAULT 'global',
    beta_delta   NUMERIC(8,4) NOT NULL CHECK (beta_delta >= 0),
    cohort_size  INT          NOT NULL CHECK (cohort_size >= 0),
    churn_count  INT          NOT NULL CHECK (churn_count >= 0),
    weighted_sum NUMERIC(8,4) NOT NULL,
    capped       BOOLEAN      NOT NULL DEFAULT false,
    applied_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_churn_penalty_tenant ON churn_penalty_log(tenant_id, applied_at DESC);

ALTER TABLE churn_penalty_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE churn_penalty_log FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON churn_penalty_log
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
GRANT SELECT, INSERT ON churn_penalty_log TO mai_app;
