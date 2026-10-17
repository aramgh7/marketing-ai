-- Personalization rules DB table — replaces hard-coded CLUSTER_BLOCK_RULES (vision §5.4.1)
-- Marketer must approve; only approved rules are active.
-- System NEVER generates new text — only reorders pre-existing blocks.

CREATE TABLE personalization_rules (
    id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID        NOT NULL REFERENCES tenants(id),
    cluster_label       VARCHAR(50) NOT NULL,
    allowed_block_order TEXT[]      NOT NULL,
    description         TEXT,
    approved_by         UUID        REFERENCES users(id),
    approved_at         TIMESTAMPTZ,
    is_active           BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, cluster_label)
);
ALTER TABLE personalization_rules ENABLE ROW LEVEL SECURITY;
ALTER TABLE personalization_rules FORCE ROW LEVEL SECURITY;
CREATE POLICY personalization_rules_tenant ON personalization_rules
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON personalization_rules TO mai_app;
