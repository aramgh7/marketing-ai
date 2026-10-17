-- Phase 2: Quality Score (arch §15.5) + session additional columns (arch §4.8 v1.2)

-- Quality score on sessions (computed by QualityScoreScheduler every 15 min on closed sessions)
ALTER TABLE sessions ADD COLUMN IF NOT EXISTS quality_score        REAL;
ALTER TABLE sessions ADD COLUMN IF NOT EXISTS linked_session_ids   TEXT[];
ALTER TABLE sessions ADD COLUMN IF NOT EXISTS bounce_corrected_at  TIMESTAMPTZ;
ALTER TABLE sessions ADD COLUMN IF NOT EXISTS external_ctx         JSONB;

-- Creative/variant quality aggregate table
CREATE TABLE creative_quality (
    id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID        NOT NULL,
    creative_id         UUID,       -- NULL for variant-level aggregates
    variant_id          UUID,       -- NULL for creative-level aggregates
    window              VARCHAR(20) NOT NULL DEFAULT '30d', -- 7d, 30d, 90d
    quality_score       REAL        NOT NULL DEFAULT 0,
    clickbait_suspect   BOOLEAN     NOT NULL DEFAULT FALSE,
    components          JSONB,      -- {match_score, bounce_rate, scroll_depth_norm, form_completion_rate}
    computed_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
GRANT SELECT, INSERT, UPDATE, DELETE ON creative_quality TO mai_app;
CREATE INDEX idx_creative_quality_cid ON creative_quality(tenant_id, creative_id, window)
    WHERE creative_id IS NOT NULL;
CREATE INDEX idx_creative_quality_vid ON creative_quality(tenant_id, variant_id, window)
    WHERE variant_id IS NOT NULL;
