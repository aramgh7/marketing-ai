-- Extend circuit_breaker_events with trigger_type, metric, value (needed for Fraud L3)
ALTER TABLE circuit_breaker_events
    ADD COLUMN IF NOT EXISTS trigger_type VARCHAR(30),
    ADD COLUMN IF NOT EXISTS metric       VARCHAR(30),
    ADD COLUMN IF NOT EXISTS value        REAL;

-- Soft-delete support for personas
ALTER TABLE personas ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ;
