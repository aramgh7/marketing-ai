-- Audit 6.3: MetaSyncScheduler added Meta's cumulative day-to-date figures to the running totals on
-- every 30-minute sync (~48x inflation per day). Totals are now advanced by the delta against the
-- previous sync of the same day, which needs the previous day-to-date counters.
ALTER TABLE meta_ad_sets ADD COLUMN IF NOT EXISTS impressions_today BIGINT NOT NULL DEFAULT 0;
ALTER TABLE meta_ad_sets ADD COLUMN IF NOT EXISTS clicks_today      BIGINT NOT NULL DEFAULT 0;
ALTER TABLE meta_ad_sets ADD COLUMN IF NOT EXISTS conversions_today BIGINT NOT NULL DEFAULT 0;

-- Audit 6.11: atom_registry.tenant_count was set to 1 on insert and never updated, so no CANDIDATE
-- atom could ever be CONFIRMED (needs >= 2 tenants). Track distinct tenants explicitly.
CREATE TABLE atom_registry_tenants (
    registry_id   UUID        NOT NULL REFERENCES atom_registry(id) ON DELETE CASCADE,
    tenant_id     UUID        NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (registry_id, tenant_id)
);
-- Platform-wide bookkeeping (no tenant data beyond the id); readable by the app role.
GRANT SELECT, INSERT ON atom_registry_tenants TO mai_app;
