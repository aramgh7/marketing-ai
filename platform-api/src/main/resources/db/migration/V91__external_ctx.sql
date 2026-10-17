-- External context snapshot for GLM covariates (vision §11.3)
ALTER TABLE sessions
    ADD COLUMN IF NOT EXISTS external_ctx JSONB;

COMMENT ON COLUMN sessions.external_ctx IS
    'External context at session start: {hour_utc, day_of_week, is_weekend, weather_flag?, news_sentiment?}';
