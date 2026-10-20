-- Roadmap 9-8: columns for the scheduled jobs the architecture listed but the code lacked.
-- No new tables → RLS/grants are inherited from the owning tables (V116 predicate).

-- StageValueRefreshScheduler: E[value | session reached the stage] from closed cohorts (arch §16.4)
ALTER TABLE funnel_stages
    ADD COLUMN IF NOT EXISTS expected_value_usd REAL,
    ADD COLUMN IF NOT EXISTS value_n            INT,
    ADD COLUMN IF NOT EXISTS value_refreshed_at TIMESTAMPTZ;

-- SlotCapacityScheduler: the no-show mix the current max_overbook was derived from (arch §16.15)
ALTER TABLE slot_capacity_policies
    ADD COLUMN IF NOT EXISTS p_noshow    REAL,
    ADD COLUMN IF NOT EXISTS computed_at TIMESTAMPTZ;

-- Moderation probe (arch §16.17): probe ad sets are launched PAUSED and polled for review status
ALTER TABLE meta_ad_sets
    ADD COLUMN IF NOT EXISTS moderation_probe BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE moderation_results
    ADD COLUMN IF NOT EXISTS meta_ad_id VARCHAR(100),
    ADD COLUMN IF NOT EXISTS feedback   JSONB;

-- GhlSyncScheduler: when the polling reconciliation last touched the row
ALTER TABLE appointments
    ADD COLUMN IF NOT EXISTS ghl_synced_at TIMESTAMPTZ;
