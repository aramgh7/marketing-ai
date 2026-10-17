-- Phase 8-20: Agency Measurability
-- creative_sources: AGENCY / INHOUSE / CREATOR
-- creatives.source_id: links creative to a source for per-source cost-per-held-call analysis
-- agency_accounts + agency_kpi_snapshots: optional multi-tenant agency dashboard

CREATE TABLE creative_sources (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID        NOT NULL REFERENCES tenants(id),
    name        VARCHAR(120) NOT NULL,
    kind        VARCHAR(20) NOT NULL
        CHECK (kind IN ('AGENCY','INHOUSE','CREATOR')),
    contact     VARCHAR(254),
    notes       TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, name)
);
ALTER TABLE creative_sources ENABLE ROW LEVEL SECURITY;
ALTER TABLE creative_sources FORCE ROW LEVEL SECURITY;
CREATE POLICY creative_sources_tenant ON creative_sources
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON creative_sources TO mai_app;

-- Link creatives to source (nullable — NULL = unknown)
ALTER TABLE creatives ADD COLUMN IF NOT EXISTS source_id UUID REFERENCES creative_sources(id);

-- Agency accounts: one agency may manage many tenants
CREATE TABLE agency_accounts (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(120) NOT NULL,
    slug        VARCHAR(60)  NOT NULL UNIQUE,
    plan_tier   VARCHAR(20)  NOT NULL DEFAULT 'starter'
        CHECK (plan_tier IN ('starter','growth','agency','enterprise')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
GRANT SELECT, INSERT, UPDATE ON agency_accounts TO mai_app;

ALTER TABLE tenants ADD COLUMN IF NOT EXISTS agency_id UUID REFERENCES agency_accounts(id);
CREATE INDEX tenants_agency ON tenants(agency_id) WHERE agency_id IS NOT NULL;

-- Daily KPI snapshot per agency (aggregated, no per-tenant RLS needed)
CREATE TABLE agency_kpi_snapshots (
    id               UUID   PRIMARY KEY DEFAULT gen_random_uuid(),
    agency_id        UUID   NOT NULL REFERENCES agency_accounts(id),
    snapshot_date    DATE   NOT NULL,
    tenant_count     INT    NOT NULL DEFAULT 0,
    total_lpv        BIGINT NOT NULL DEFAULT 0,
    total_held_calls BIGINT NOT NULL DEFAULT 0,
    total_committed  BIGINT NOT NULL DEFAULT 0,
    avg_cph_usd      REAL,
    UNIQUE (agency_id, snapshot_date)
);
GRANT SELECT, INSERT, UPDATE ON agency_kpi_snapshots TO mai_app;
