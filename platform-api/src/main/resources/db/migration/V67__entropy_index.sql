-- Niche Creative Entropy Index (6-8)
-- Platform-wide table: NO RLS (cross-tenant aggregates, no raw tenant data)
-- Tracks creative diversity across tenants per niche to detect "tragedy of the commons"
CREATE TABLE niche_entropy_scores (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    niche           VARCHAR(100) NOT NULL,
    tenant_count    INT         NOT NULL,
    entropy         FLOAT       NOT NULL,  -- 1 - mean_cosine_similarity; range [0,1]
    mean_similarity FLOAT       NOT NULL,
    diversity_flag  BOOLEAN     NOT NULL DEFAULT FALSE,  -- TRUE when entropy < 0.3
    recommendation  TEXT,
    computed_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_nes_niche_time ON niche_entropy_scores(niche, computed_at DESC);
GRANT SELECT, INSERT ON niche_entropy_scores TO mai_app;
