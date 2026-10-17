-- Graveyard retry candidate flag (v1.1 — vision §13.2)
ALTER TABLE creative_postmortems ADD COLUMN IF NOT EXISTS retry_candidate  BOOLEAN     NOT NULL DEFAULT FALSE;
ALTER TABLE creative_postmortems ADD COLUMN IF NOT EXISTS retry_reason     TEXT;
ALTER TABLE creative_postmortems ADD COLUMN IF NOT EXISTS retry_flagged_at TIMESTAMPTZ;
