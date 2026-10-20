-- Audit 4.5: PENDING/FAILED CAPI events were never retried (events logged before the client pasted
-- the token, or transient Meta 5xx, were lost). CapiRetryScheduler resends them with backoff for
-- up to 7 days (Meta accepts event_time up to 7 days back).
ALTER TABLE capi_events ADD COLUMN IF NOT EXISTS pixel_role      VARCHAR(60);   -- which pixel to resolve credentials for (v2 map)
ALTER TABLE capi_events ADD COLUMN IF NOT EXISTS attempts        INT NOT NULL DEFAULT 0;
ALTER TABLE capi_events ADD COLUMN IF NOT EXISTS last_attempt_at TIMESTAMPTZ;
