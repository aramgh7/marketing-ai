-- Phase 3-2: Two-Contour Meta Structure

-- Track confirmation run state on campaigns
ALTER TABLE meta_campaigns ADD COLUMN IF NOT EXISTS confirmation_ad_set_id VARCHAR(100);
ALTER TABLE meta_campaigns ADD COLUMN IF NOT EXISTS confirmation_status    VARCHAR(20) NOT NULL DEFAULT 'NONE';
-- NONE | RUNNING | PASSED | FAILED

-- Batch grouping: ad sets launched together have the same batch_id
ALTER TABLE meta_ad_sets ADD COLUMN IF NOT EXISTS batch_id          UUID;
ALTER TABLE meta_ad_sets ADD COLUMN IF NOT EXISTS batch_launched_at TIMESTAMPTZ;

-- For winner selection queries
CREATE INDEX IF NOT EXISTS idx_adsets_batch ON meta_ad_sets(batch_id);
