-- Roadmap 9-9: protocol switches specified in arch §15/§16 but not implemented.
--   §15.9  persona_mode   → meta_ad_sets.persona_arm (batch-level arm of the persona A/B)
--   §15.19 capi_value_mode → meta_ad_sets.value_mode + capi_value_mode_runs (weekly comparison)
--   §15.13 expert prior   → creatives.exploration_prior_alpha / beta
--   §15.6  margin floor   → variant_offers.estimated_margin (filtered in load_arms)
--   §16.5  FORM_NO_SLOT   → followup_assignments without an appointment (session-keyed)
--   §16.7  blocked_by_meta→ creative_postmortems.failure_mode CHECK + recovery brief link

-- persona A/B arm per test batch (all ad sets of a batch share it)
ALTER TABLE meta_ad_sets ADD COLUMN IF NOT EXISTS persona_arm VARCHAR(16)
    CHECK (persona_arm IS NULL OR persona_arm IN ('PREFILTER', 'CONTROL'));
-- CAPI value mode per ad set (PROXY = value-adjusted proxy, FIXED = base value with k_* = 1)
ALTER TABLE meta_ad_sets ADD COLUMN IF NOT EXISTS value_mode VARCHAR(8)
    CHECK (value_mode IS NULL OR value_mode IN ('PROXY', 'FIXED'));

CREATE TABLE IF NOT EXISTS capi_value_mode_runs (
    id                 UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          UUID        NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    window_days        INT         NOT NULL DEFAULT 28,
    proxy_spend        REAL        NOT NULL DEFAULT 0,
    proxy_held         INT         NOT NULL DEFAULT 0,
    fixed_spend        REAL        NOT NULL DEFAULT 0,
    fixed_held         INT         NOT NULL DEFAULT 0,
    proxy_cost_per_held REAL,
    fixed_cost_per_held REAL,
    winner             VARCHAR(8),                       -- PROXY | FIXED | TIE | INSUFFICIENT_DATA
    lift_pct           REAL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_capi_value_mode_runs_tenant ON capi_value_mode_runs(tenant_id, created_at DESC);
ALTER TABLE capi_value_mode_runs ENABLE ROW LEVEL SECURITY;
ALTER TABLE capi_value_mode_runs FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON capi_value_mode_runs
    USING ((tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
           OR current_setting('app.rls_bypass', true) = 'on');
GRANT SELECT, INSERT, UPDATE, DELETE ON capi_value_mode_runs TO mai_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON capi_value_mode_runs TO mai_ml;

-- expert prior shift (vision §12.4 "expert prior"): rating 1 → Beta(1,3), 3 → Beta(1,1), 5 → Beta(3,1)
ALTER TABLE creatives ADD COLUMN IF NOT EXISTS exploration_prior_alpha REAL;
ALTER TABLE creatives ADD COLUMN IF NOT EXISTS exploration_prior_beta  REAL;

-- margin floor input for load_arms (arch §15.6); tenants.settings.min_margin is the threshold
ALTER TABLE variant_offers ADD COLUMN IF NOT EXISTS estimated_margin REAL;

-- FORM_NO_SLOT follow-up: the lead submitted the form but never booked → no appointment row exists
ALTER TABLE followup_assignments ALTER COLUMN appointment_id DROP NOT NULL;
ALTER TABLE followup_assignments ADD COLUMN IF NOT EXISTS session_id VARCHAR(100);
ALTER TABLE followup_assignments DROP CONSTRAINT IF EXISTS followup_assignments_anchor_check;
ALTER TABLE followup_assignments ADD CONSTRAINT followup_assignments_anchor_check
    CHECK (appointment_id IS NOT NULL OR session_id IS NOT NULL);
CREATE UNIQUE INDEX IF NOT EXISTS idx_followup_assignments_session_audience
    ON followup_assignments(tenant_id, session_id, audience) WHERE appointment_id IS NULL;

-- graveyard reasons (arch §16.7): blocked_by_meta / no_show / low_accredited_share join the existing modes
ALTER TABLE creative_postmortems DROP CONSTRAINT IF EXISTS creative_postmortems_failure_mode_check;
ALTER TABLE creative_postmortems ADD CONSTRAINT creative_postmortems_failure_mode_check
    CHECK (failure_mode IN ('insufficient_data', 'hook_failure', 'landing_failure', 'offer_weakness',
                            'general_underperformance', 'technical', 'market', 'content',
                            'blocked_by_meta', 'no_show', 'low_accredited_share', 'unknown')) NOT VALID;
ALTER TABLE creative_postmortems ADD COLUMN IF NOT EXISTS recovery_brief_id UUID;
ALTER TABLE creative_postmortems ADD COLUMN IF NOT EXISTS recovery_requested_at TIMESTAMPTZ;
