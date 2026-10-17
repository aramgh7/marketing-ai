-- 3-9: System vs Baseline Benchmark — micro-holdout infrastructure (vision §21.5)
--
-- A small fraction of traffic (default 4%) receives a random variant instead of
-- Thompson Sampling. This creates a continuous control group that measures the
-- incremental lift of the AI system vs. a static/random baseline.
--
-- Toggle: tenants.holdout_enabled=true (default ON).
-- Rate:   tenants.holdout_rate (default 0.04 = 4%). Recommended 3–5%.
-- Assignment: deterministic hash of session_id → same session always same group.

ALTER TABLE tenants
    ADD COLUMN IF NOT EXISTS holdout_enabled BOOLEAN      NOT NULL DEFAULT true,
    ADD COLUMN IF NOT EXISTS holdout_rate    NUMERIC(4,3) NOT NULL DEFAULT 0.040
                                             CHECK (holdout_rate BETWEEN 0 AND 0.20);

ALTER TABLE decision_log
    ADD COLUMN IF NOT EXISTS is_holdout BOOLEAN NOT NULL DEFAULT false;

CREATE INDEX IF NOT EXISTS idx_decisions_holdout
    ON decision_log(tenant_id, is_holdout, decided_at);
