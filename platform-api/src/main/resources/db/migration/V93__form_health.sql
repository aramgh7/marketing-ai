-- Form health check columns for synthetic form check scheduler (vision §11.4)
ALTER TABLE landing_variants
    ADD COLUMN IF NOT EXISTS form_endpoint_url        VARCHAR(500),
    ADD COLUMN IF NOT EXISTS consecutive_form_passes  INT     NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS form_paused              BOOLEAN NOT NULL DEFAULT FALSE;
