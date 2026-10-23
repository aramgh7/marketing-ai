-- M-10/D-3: BudgetGuardService must be able to fetch real-time spend from Meta API.
-- meta_adset_id links a PER_CREATIVE_STOP_LOSS guard to the Meta ad set to poll;
-- when NULL the guard falls back to the manually entered current_spend.
ALTER TABLE budget_guards
    ADD COLUMN IF NOT EXISTS meta_adset_id VARCHAR(100);

COMMENT ON COLUMN budget_guards.meta_adset_id IS
    'Meta ad set ID for real-time spend polling via /insights; NULL = manual spend update only';
