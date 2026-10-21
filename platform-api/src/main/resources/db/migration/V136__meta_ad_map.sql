-- Roadmap Phase 11 (.ai/creative-publishing.md): a creative reaches Meta only through the platform.
--  meta_ad_map   — one row per Meta ad: which of our creatives it carries, the landing entry slug, the
--                  destination URL and url_tags the platform set. MANAGED rows are written by the cabinet
--                  (publish); RECONCILED rows by the hourly read-back of the ad account (hand-made ads are
--                  UNMANAGED and stay outside learning); MANUAL rows are linked by a human in the cabinet.
--  creatives     — publish_status (NOT_IN_META → PENDING_REVIEW → APPROVED_PAUSED → ACTIVE_IN_META …),
--                  creative_code (short code placed in the Meta ad name so a hand-made ad can still be
--                  matched), Meta video / creative ids, published_at.
--  tenants       — meta_page_id / meta_instagram_actor_id (object_story_spec needs a page).
CREATE TABLE IF NOT EXISTS meta_ad_map (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID         NOT NULL REFERENCES tenants(id),
    meta_ad_id        VARCHAR(100) NOT NULL,
    meta_adset_id     VARCHAR(100),
    meta_campaign_id  VARCHAR(100),
    meta_creative_id  VARCHAR(100),
    meta_video_id     VARCHAR(100),
    meta_image_hash   VARCHAR(100),
    creative_id       UUID         REFERENCES creatives(id),
    entry_slug        VARCHAR(100),
    destination_url   TEXT,
    url_tags          TEXT,
    ad_name           VARCHAR(255),
    source            VARCHAR(12)  NOT NULL DEFAULT 'MANAGED'
        CHECK (source IN ('MANAGED', 'RECONCILED', 'MANUAL')),
    status            VARCHAR(16)  NOT NULL DEFAULT 'PAUSED'
        CHECK (status IN ('PAUSED', 'PENDING_REVIEW', 'APPROVED', 'ACTIVE', 'REJECTED', 'ARCHIVED', 'UNMANAGED', 'DRIFT', 'UNKNOWN')),
    drift             JSONB,
    drift_detected_at TIMESTAMPTZ,
    last_seen_at      TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, meta_ad_id)
);
CREATE INDEX IF NOT EXISTS idx_meta_ad_map_creative ON meta_ad_map(tenant_id, creative_id);
CREATE INDEX IF NOT EXISTS idx_meta_ad_map_status   ON meta_ad_map(tenant_id, status);
ALTER TABLE meta_ad_map ENABLE ROW LEVEL SECURITY;
ALTER TABLE meta_ad_map FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON meta_ad_map
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid
           OR current_setting('app.rls_bypass', true) = 'on');
GRANT SELECT, INSERT, UPDATE, DELETE ON meta_ad_map TO mai_app;
GRANT SELECT ON meta_ad_map TO mai_ml;

ALTER TABLE creatives ADD COLUMN IF NOT EXISTS publish_status   VARCHAR(20)  NOT NULL DEFAULT 'NOT_IN_META';
ALTER TABLE creatives ADD COLUMN IF NOT EXISTS creative_code    VARCHAR(12);
ALTER TABLE creatives ADD COLUMN IF NOT EXISTS meta_video_id    VARCHAR(100);
ALTER TABLE creatives ADD COLUMN IF NOT EXISTS meta_creative_id VARCHAR(100);
ALTER TABLE creatives ADD COLUMN IF NOT EXISTS published_at     TIMESTAMPTZ;
UPDATE creatives
SET creative_code = 'MAI-' || upper(substr(replace(id::text, '-', ''), 1, 6))
WHERE creative_code IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_creatives_code ON creatives(tenant_id, creative_code);

ALTER TABLE tenants ADD COLUMN IF NOT EXISTS meta_page_id            VARCHAR(50);
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS meta_instagram_actor_id VARCHAR(50);
