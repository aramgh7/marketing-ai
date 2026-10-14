-- Row-Level Security: all tenant-scoped tables are isolated by app.current_tenant
-- Spring Boot sets: SET LOCAL app.current_tenant = '<uuid>' at the start of each request
-- current_setting('app.current_tenant', true) returns NULL (not error) when unset

-- FORCE ROW LEVEL SECURITY is required because the DB user 'mai' owns the tables;
-- without FORCE, the table owner bypasses RLS by default in PostgreSQL

ALTER TABLE tenants ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenants FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenants
    USING (id = current_setting('app.current_tenant', true)::uuid);

ALTER TABLE users ENABLE ROW LEVEL SECURITY;
ALTER TABLE users FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON users
    USING (tenant_id = current_setting('app.current_tenant', true)::uuid);

ALTER TABLE creatives ENABLE ROW LEVEL SECURITY;
ALTER TABLE creatives FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON creatives
    USING (tenant_id = current_setting('app.current_tenant', true)::uuid);

ALTER TABLE creative_atoms ENABLE ROW LEVEL SECURITY;
ALTER TABLE creative_atoms FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON creative_atoms
    USING (tenant_id = current_setting('app.current_tenant', true)::uuid);

ALTER TABLE landing_variants ENABLE ROW LEVEL SECURITY;
ALTER TABLE landing_variants FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON landing_variants
    USING (tenant_id = current_setting('app.current_tenant', true)::uuid);

ALTER TABLE variant_offers ENABLE ROW LEVEL SECURITY;
ALTER TABLE variant_offers FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON variant_offers
    USING (tenant_id = current_setting('app.current_tenant', true)::uuid);

ALTER TABLE thompson_arms ENABLE ROW LEVEL SECURITY;
ALTER TABLE thompson_arms FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON thompson_arms
    USING (tenant_id = current_setting('app.current_tenant', true)::uuid);

ALTER TABLE sessions ENABLE ROW LEVEL SECURITY;
ALTER TABLE sessions FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON sessions
    USING (tenant_id = current_setting('app.current_tenant', true)::uuid);

-- Partitioned table: policy on parent automatically applies to all partitions
ALTER TABLE events ENABLE ROW LEVEL SECURITY;
ALTER TABLE events FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON events
    USING (tenant_id = current_setting('app.current_tenant', true)::uuid);

ALTER TABLE decision_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE decision_log FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON decision_log
    USING (tenant_id = current_setting('app.current_tenant', true)::uuid);

ALTER TABLE meta_pixels ENABLE ROW LEVEL SECURITY;
ALTER TABLE meta_pixels FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON meta_pixels
    USING (tenant_id = current_setting('app.current_tenant', true)::uuid);

ALTER TABLE meta_campaigns ENABLE ROW LEVEL SECURITY;
ALTER TABLE meta_campaigns FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON meta_campaigns
    USING (tenant_id = current_setting('app.current_tenant', true)::uuid);

ALTER TABLE meta_ad_sets ENABLE ROW LEVEL SECURITY;
ALTER TABLE meta_ad_sets FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON meta_ad_sets
    USING (tenant_id = current_setting('app.current_tenant', true)::uuid);

ALTER TABLE budget_guards ENABLE ROW LEVEL SECURITY;
ALTER TABLE budget_guards FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON budget_guards
    USING (tenant_id = current_setting('app.current_tenant', true)::uuid);

ALTER TABLE ghl_webhook_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE ghl_webhook_log FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON ghl_webhook_log
    USING (tenant_id = current_setting('app.current_tenant', true)::uuid);
