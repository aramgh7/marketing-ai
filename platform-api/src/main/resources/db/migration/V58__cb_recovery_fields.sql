-- Circuit-Breaker Recovery Protocol (vision §12.5b) — Phase 3 (arch §15.14)

ALTER TABLE circuit_breaker_events
    ADD COLUMN IF NOT EXISTS diagnosis          JSONB,
    ADD COLUMN IF NOT EXISTS confirmed_cause    VARCHAR(30),
    ADD COLUMN IF NOT EXISTS confirm_plan       TEXT,
    ADD COLUMN IF NOT EXISTS resume_steps       JSONB,
    ADD COLUMN IF NOT EXISTS outcome            VARCHAR(20),
    ADD COLUMN IF NOT EXISTS pause_duration_sec BIGINT;
