-- Phase 5-1: Behavioral Clusters (§7.3 Этап 1)
-- K-means session archetypes + LLM labels per tenant.

ALTER TABLE sessions
    ADD COLUMN IF NOT EXISTS cluster_id    INT,
    ADD COLUMN IF NOT EXISTS cluster_label VARCHAR(50);

CREATE TABLE behavior_clusters (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID NOT NULL REFERENCES tenants(id),
    cluster_idx   INT  NOT NULL,                 -- 0-based index (0..7)
    label         VARCHAR(50) NOT NULL,           -- LLM-generated: "price_seeker", etc.
    hypothesis    TEXT,                           -- LLM-generated behavioural hypothesis
    session_count INT NOT NULL DEFAULT 0,
    cvr_estimate  REAL,
    centroid      JSONB NOT NULL DEFAULT '{}',   -- feature centroid for online assignment
    fitted_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, cluster_idx)
);

ALTER TABLE behavior_clusters ENABLE ROW LEVEL SECURITY;
ALTER TABLE behavior_clusters FORCE ROW LEVEL SECURITY;

CREATE POLICY behavior_clusters_tenant ON behavior_clusters
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE, DELETE ON behavior_clusters TO mai_app;
