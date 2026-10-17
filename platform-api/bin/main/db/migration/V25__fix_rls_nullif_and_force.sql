-- arch v1.1 §8: two-part RLS fix
--
-- FIX 1 (I5): All policies used current_setting(...)::uuid without NULLIF.
--   When the GUC is unset, current_setting returns '' (empty string), and ''::uuid
--   throws "invalid input syntax for type uuid" — crashing every request that
--   runs outside a SET LOCAL block. NULLIF converts '' to NULL so the cast succeeds.
--
-- FIX 2 (I6): V10 removed FORCE ROW LEVEL SECURITY.
--   Without FORCE, the table owner role ('mai') bypasses all policies.
--   arch v1.1 §8 requires FORCE + a non-owner application role ('mai_app').
--
-- FIX 3: V14 (appointments) compared tenant_id::text = current_setting() — wrong type.
--        V15 (creator_briefs) called current_setting without the `, true` flag — errors on unset.

-- ─── Non-owner application role ───────────────────────────────────────────────
-- Create role only if it doesn't already exist
DO $$ BEGIN
    CREATE ROLE mai_app;
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;
-- Password and CONNECT privilege are set via docker-compose DB_APP_ROLE_PASSWORD env (not here)
GRANT USAGE ON SCHEMA public TO mai_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO mai_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO mai_app;

-- ─── Drop all existing tenant_isolation policies ──────────────────────────────
-- V9 tables
DROP POLICY IF EXISTS tenant_isolation ON tenants;
DROP POLICY IF EXISTS tenant_isolation ON users;
DROP POLICY IF EXISTS tenant_isolation ON creatives;
DROP POLICY IF EXISTS tenant_isolation ON creative_atoms;
DROP POLICY IF EXISTS tenant_isolation ON landing_variants;
DROP POLICY IF EXISTS tenant_isolation ON variant_offers;
DROP POLICY IF EXISTS tenant_isolation ON thompson_arms;
DROP POLICY IF EXISTS tenant_isolation ON sessions;
DROP POLICY IF EXISTS tenant_isolation ON events;
DROP POLICY IF EXISTS tenant_isolation ON decision_log;
DROP POLICY IF EXISTS tenant_isolation ON meta_pixels;
DROP POLICY IF EXISTS tenant_isolation ON meta_campaigns;
DROP POLICY IF EXISTS tenant_isolation ON meta_ad_sets;
DROP POLICY IF EXISTS tenant_isolation ON budget_guards;
DROP POLICY IF EXISTS tenant_isolation ON ghl_webhook_log;
-- V14 appointments (different policy name)
DROP POLICY IF EXISTS tenant_isolation_appointments ON appointments;
-- V15 creator_briefs
DROP POLICY IF EXISTS tenant_isolation ON creator_briefs;

-- ─── Recreate with NULLIF fix ─────────────────────────────────────────────────
CREATE POLICY tenant_isolation ON tenants
    USING (id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

CREATE POLICY tenant_isolation ON users
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

CREATE POLICY tenant_isolation ON creatives
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

CREATE POLICY tenant_isolation ON creative_atoms
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

CREATE POLICY tenant_isolation ON landing_variants
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

CREATE POLICY tenant_isolation ON variant_offers
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

CREATE POLICY tenant_isolation ON thompson_arms
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

CREATE POLICY tenant_isolation ON sessions
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

CREATE POLICY tenant_isolation ON events
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

CREATE POLICY tenant_isolation ON decision_log
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

CREATE POLICY tenant_isolation ON meta_pixels
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

CREATE POLICY tenant_isolation ON meta_campaigns
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

CREATE POLICY tenant_isolation ON meta_ad_sets
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

CREATE POLICY tenant_isolation ON budget_guards
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

CREATE POLICY tenant_isolation ON ghl_webhook_log
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

CREATE POLICY tenant_isolation ON appointments
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

CREATE POLICY tenant_isolation ON creator_briefs
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

-- ─── Re-enable FORCE ROW LEVEL SECURITY ──────────────────────────────────────
ALTER TABLE tenants           FORCE ROW LEVEL SECURITY;
ALTER TABLE users             FORCE ROW LEVEL SECURITY;
ALTER TABLE creatives         FORCE ROW LEVEL SECURITY;
ALTER TABLE creative_atoms    FORCE ROW LEVEL SECURITY;
ALTER TABLE landing_variants  FORCE ROW LEVEL SECURITY;
ALTER TABLE variant_offers    FORCE ROW LEVEL SECURITY;
ALTER TABLE thompson_arms     FORCE ROW LEVEL SECURITY;
ALTER TABLE sessions          FORCE ROW LEVEL SECURITY;
ALTER TABLE events            FORCE ROW LEVEL SECURITY;
ALTER TABLE decision_log      FORCE ROW LEVEL SECURITY;
ALTER TABLE meta_pixels       FORCE ROW LEVEL SECURITY;
ALTER TABLE meta_campaigns    FORCE ROW LEVEL SECURITY;
ALTER TABLE meta_ad_sets      FORCE ROW LEVEL SECURITY;
ALTER TABLE budget_guards     FORCE ROW LEVEL SECURITY;
ALTER TABLE ghl_webhook_log   FORCE ROW LEVEL SECURITY;
ALTER TABLE appointments      FORCE ROW LEVEL SECURITY;
ALTER TABLE creator_briefs    FORCE ROW LEVEL SECURITY;
