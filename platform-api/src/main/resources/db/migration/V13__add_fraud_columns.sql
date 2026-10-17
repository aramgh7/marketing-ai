-- Phase 0-6: Fraud Protection — add behavioral scoring columns to sessions

ALTER TABLE sessions ADD COLUMN IF NOT EXISTS ip_address  VARCHAR(45);
ALTER TABLE sessions ADD COLUMN IF NOT EXISTS user_agent  TEXT;
ALTER TABLE sessions ADD COLUMN IF NOT EXISTS fraud_score  REAL    DEFAULT 0;
ALTER TABLE sessions ADD COLUMN IF NOT EXISTS fraud_reasons JSONB  DEFAULT '[]'::jsonb;

-- Index for IP clustering detection (hourly fraud review)
CREATE INDEX IF NOT EXISTS idx_sessions_ip_started
    ON sessions (ip_address, started_at DESC)
    WHERE ip_address IS NOT NULL;

-- Index to efficiently query suspicious sessions per tenant
CREATE INDEX IF NOT EXISTS idx_sessions_suspicious
    ON sessions (tenant_id, is_suspicious, started_at DESC)
    WHERE is_suspicious = true;
