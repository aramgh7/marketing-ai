-- Roadmap 3-13: Site Health Guard full — manual confirmation of resume after an incident
-- (3 consecutive passes AND a marketer's confirmation), plus the browser-based form check.
ALTER TABLE site_health_incidents ADD COLUMN IF NOT EXISTS consecutive_passes  INT         NOT NULL DEFAULT 0;
ALTER TABLE site_health_incidents ADD COLUMN IF NOT EXISTS resume_alerted_at   TIMESTAMPTZ;      -- "confirm resume" alert sent
ALTER TABLE site_health_incidents ADD COLUMN IF NOT EXISTS resume_confirmed_at TIMESTAMPTZ;
ALTER TABLE site_health_incidents ADD COLUMN IF NOT EXISTS resume_confirmed_by UUID;
ALTER TABLE site_health_incidents ADD COLUMN IF NOT EXISTS resumed_at          TIMESTAMPTZ;      -- ad sets / form re-enabled

-- form check method per run: http | browser | unavailable
ALTER TABLE site_health_checks ADD COLUMN IF NOT EXISTS method VARCHAR(20);
