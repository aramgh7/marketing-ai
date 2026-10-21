-- Roadmap Phase 10 (landing hosting + variant sets):
--  tenant_domains    — client domains for landings (CNAME to our edge) or a self-hosted landing runner on the
--                      client's own server; verified by DNS TXT (+ CNAME). Each client lives on its own domain so a
--                      Meta block on one account never touches another client's domain.
--  runner_keys       — per-domain keys for the self-hosted runner: it calls /public/decide and /public/l/{slug}
--                      from the client's infrastructure without the internal ML key.
--  landing_variants  — set_id groups the N variants generated from one brief (compare side by side);
--                      reject_reason / reject_note feed RLHA; style_angle records which angle produced the variant.
CREATE TABLE IF NOT EXISTS tenant_domains (
    id                 UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          UUID         NOT NULL REFERENCES tenants(id),
    host               VARCHAR(253) NOT NULL UNIQUE,
    kind               VARCHAR(10)  NOT NULL DEFAULT 'LANDING' CHECK (kind IN ('LANDING', 'COLLECT')),
    hosting            VARCHAR(12)  NOT NULL DEFAULT 'CNAME'   CHECK (hosting IN ('CNAME', 'SELF_HOSTED')),
    default_slug       VARCHAR(100),
    verification_token VARCHAR(64)  NOT NULL,
    status             VARCHAR(10)  NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'VERIFIED', 'FAILED', 'DISABLED')),
    verified_at        TIMESTAMPTZ,
    last_check_at      TIMESTAMPTZ,
    last_error         TEXT,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_tenant_domains_tenant ON tenant_domains(tenant_id);
ALTER TABLE tenant_domains ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_domains FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenant_domains
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid
           OR current_setting('app.rls_bypass', true) = 'on');
GRANT SELECT, INSERT, UPDATE, DELETE ON tenant_domains TO mai_app;
GRANT SELECT ON tenant_domains TO mai_ml;

CREATE TABLE IF NOT EXISTS runner_keys (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    UUID         NOT NULL REFERENCES tenants(id),
    domain_id    UUID         REFERENCES tenant_domains(id) ON DELETE CASCADE,
    key_hash     VARCHAR(64)  NOT NULL UNIQUE,
    label        VARCHAR(100),
    last_seen_at TIMESTAMPTZ,
    revoked_at   TIMESTAMPTZ,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_runner_keys_tenant ON runner_keys(tenant_id);
ALTER TABLE runner_keys ENABLE ROW LEVEL SECURITY;
ALTER TABLE runner_keys FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON runner_keys
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid
           OR current_setting('app.rls_bypass', true) = 'on');
GRANT SELECT, INSERT, UPDATE ON runner_keys TO mai_app;
GRANT SELECT ON runner_keys TO mai_ml;

ALTER TABLE landing_variants ADD COLUMN IF NOT EXISTS set_id        UUID;
ALTER TABLE landing_variants ADD COLUMN IF NOT EXISTS reject_reason VARCHAR(30);
ALTER TABLE landing_variants ADD COLUMN IF NOT EXISTS reject_note   TEXT;
ALTER TABLE landing_variants ADD COLUMN IF NOT EXISTS style_angle   VARCHAR(120);
CREATE INDEX IF NOT EXISTS idx_landing_variants_set ON landing_variants(tenant_id, set_id);
