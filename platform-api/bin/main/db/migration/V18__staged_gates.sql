-- Phase 3-4: Staged Gates — Sequential Elimination (§12.3)

-- Gate tracking per ad set
ALTER TABLE meta_ad_sets ADD COLUMN IF NOT EXISTS gate_status        VARCHAR(20) NOT NULL DEFAULT 'GATE_1';
-- GATE_1 | GATE_2 | GATE_3 | PASSED | KILLED

ALTER TABLE meta_ad_sets ADD COLUMN IF NOT EXISTS gate_kill_reason   TEXT;
ALTER TABLE meta_ad_sets ADD COLUMN IF NOT EXISTS gate_kill_at       TIMESTAMPTZ;
ALTER TABLE meta_ad_sets ADD COLUMN IF NOT EXISTS gate_1_evaluated_at TIMESTAMPTZ;
ALTER TABLE meta_ad_sets ADD COLUMN IF NOT EXISTS gate_2_evaluated_at TIMESTAMPTZ;
ALTER TABLE meta_ad_sets ADD COLUMN IF NOT EXISTS gate_3_evaluated_at TIMESTAMPTZ;

-- Anchor creative flag: anchor is the known-good control creative in a batch (§12.3 Gate 3)
ALTER TABLE meta_ad_sets ADD COLUMN IF NOT EXISTS is_anchor BOOLEAN NOT NULL DEFAULT FALSE;

-- Accumulated metrics (populated by MetaSyncScheduler)
ALTER TABLE meta_ad_sets ADD COLUMN IF NOT EXISTS impressions_total  BIGINT NOT NULL DEFAULT 0;
ALTER TABLE meta_ad_sets ADD COLUMN IF NOT EXISTS clicks_total       BIGINT NOT NULL DEFAULT 0;
ALTER TABLE meta_ad_sets ADD COLUMN IF NOT EXISTS conversions_total  BIGINT NOT NULL DEFAULT 0;

-- Max spend cap per ad set before L1 circuit breaker pauses it (§12.3, §12.5a L1)
ALTER TABLE meta_campaigns ADD COLUMN IF NOT EXISTS max_spend_per_adset REAL;

-- Index for gate scan queries
CREATE INDEX IF NOT EXISTS idx_adsets_gate_status ON meta_ad_sets(gate_status);
