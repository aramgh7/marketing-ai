-- Roadmap 9-16 (vision §5.2, §25 p.11): creatives produced by an AI-video provider carry the AI label
-- explicitly; has_ai_disclosure must be set by the marketer before such a creative can go live.
ALTER TABLE creatives ADD COLUMN IF NOT EXISTS is_ai_generated   BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE creatives ADD COLUMN IF NOT EXISTS has_ai_disclosure BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE creatives ADD COLUMN IF NOT EXISTS ai_provider       VARCHAR(30);
