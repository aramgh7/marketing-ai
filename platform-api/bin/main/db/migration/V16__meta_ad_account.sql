-- Phase 3-3: Meta Ad Account ID for Marketing API calls
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS meta_ad_account_id VARCHAR(50);
-- format: "act_123456789"

-- Add meta_ad_id to meta_ad_sets (actual ad creative ID in Meta, separate from our internal ad_set_id)
ALTER TABLE meta_ad_sets ADD COLUMN IF NOT EXISTS meta_ad_id VARCHAR(100);

-- Add spend tracking to meta_campaigns and meta_ad_sets
ALTER TABLE meta_campaigns  ADD COLUMN IF NOT EXISTS spend_today   REAL DEFAULT 0;
ALTER TABLE meta_campaigns  ADD COLUMN IF NOT EXISTS spend_total   REAL DEFAULT 0;
ALTER TABLE meta_ad_sets    ADD COLUMN IF NOT EXISTS spend_today   REAL DEFAULT 0;
ALTER TABLE meta_ad_sets    ADD COLUMN IF NOT EXISTS spend_total   REAL DEFAULT 0;

-- Index for sync queries
CREATE INDEX IF NOT EXISTS idx_campaigns_tenant ON meta_campaigns(tenant_id);
CREATE INDEX IF NOT EXISTS idx_adsets_campaign  ON meta_ad_sets(campaign_id);
