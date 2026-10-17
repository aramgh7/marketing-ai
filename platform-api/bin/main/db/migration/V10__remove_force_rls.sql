-- Remove FORCE ROW LEVEL SECURITY from all tenant-scoped tables.
-- Phase 0 MVP: application user 'mai' is the table owner and bypasses RLS by default.
-- RLS policies remain in place (infrastructure ready for production enforcement).
-- Re-enable FORCE when a separate mai_app role is added for production deployment.
-- Without FORCE: 'mai' bypasses RLS; application enforces isolation via TenantContext WHERE clauses.

ALTER TABLE tenants        NO FORCE ROW LEVEL SECURITY;
ALTER TABLE users          NO FORCE ROW LEVEL SECURITY;
ALTER TABLE creatives      NO FORCE ROW LEVEL SECURITY;
ALTER TABLE creative_atoms NO FORCE ROW LEVEL SECURITY;
ALTER TABLE landing_variants NO FORCE ROW LEVEL SECURITY;
ALTER TABLE variant_offers NO FORCE ROW LEVEL SECURITY;
ALTER TABLE thompson_arms  NO FORCE ROW LEVEL SECURITY;
ALTER TABLE sessions       NO FORCE ROW LEVEL SECURITY;
ALTER TABLE events         NO FORCE ROW LEVEL SECURITY;
ALTER TABLE decision_log   NO FORCE ROW LEVEL SECURITY;
ALTER TABLE meta_pixels    NO FORCE ROW LEVEL SECURITY;
ALTER TABLE meta_campaigns NO FORCE ROW LEVEL SECURITY;
ALTER TABLE meta_ad_sets   NO FORCE ROW LEVEL SECURITY;
ALTER TABLE budget_guards  NO FORCE ROW LEVEL SECURITY;
ALTER TABLE ghl_webhook_log NO FORCE ROW LEVEL SECURITY;
