-- Per-segment data-driven brief support (5-8, vision §5.5.4)
ALTER TABLE creator_briefs ADD COLUMN IF NOT EXISTS voc_section TEXT;
ALTER TABLE creator_briefs ADD COLUMN IF NOT EXISTS meta        JSONB;
