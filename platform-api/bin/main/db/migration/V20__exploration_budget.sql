-- Phase 3-6: Exploration Budget (§6.4)

-- Exploration rate per campaign: fraction of batch slots reserved for new/untested creatives
ALTER TABLE meta_campaigns ADD COLUMN IF NOT EXISTS exploration_rate REAL NOT NULL DEFAULT 0.15;
-- 0.15 = 15% exploration, 85% exploitation (EVI-ranked)

-- Track which ad sets are in exploration slots (vs exploitation)
ALTER TABLE meta_ad_sets ADD COLUMN IF NOT EXISTS is_exploration_slot BOOLEAN NOT NULL DEFAULT FALSE;

-- Optimistic prior: when a new creative enters Thompson, start alpha/beta higher than 1/1
-- Stored on the creative; read by ExplorationBudgetService when seeding a new arm
ALTER TABLE creatives ADD COLUMN IF NOT EXISTS exploration_prior_alpha REAL NOT NULL DEFAULT 2.0;
ALTER TABLE creatives ADD COLUMN IF NOT EXISTS exploration_prior_beta  REAL NOT NULL DEFAULT 1.0;
-- Default Beta(2,1) = slightly optimistic: P(win) ~ 0.67 at start, wide uncertainty
