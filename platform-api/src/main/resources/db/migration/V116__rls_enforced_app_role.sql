-- Audit §3.1 / §3.3: make row-level security real.
--
--  1. mai_app becomes a LOGIN role; the Spring app connects as it (Flyway keeps the owner role mai).
--     Password comes from the Flyway placeholder app_role_password (spring.flyway.placeholders.*).
--  2. Every table with a tenant_id column gets ENABLE + FORCE ROW LEVEL SECURITY and ONE policy:
--         tenant_id = current tenant            (plus "OR tenant_id IS NULL" where the column is nullable:
--                                                those rows are platform-wide seeds, e.g. reviewer_personas)
--         OR app.rls_bypass = 'on'              (explicit system scope: scheduled jobs and bootstrap paths;
--                                                see TenantContext.runAsSystem — never set for HTTP requests)
--     Existing per-table policies are replaced so the predicate is uniform (they were all variants of the
--     same expression; some tables had RLS without FORCE, ten tenant tables had no RLS at all).
--  3. Grants: every current and future table/sequence to mai_app, minus the single-writer and
--     append-only exceptions.

ALTER ROLE mai_app LOGIN PASSWORD '${app_role_password}';

DO $$
DECLARE
    t   record;
    pol record;
    q   text;
BEGIN
    FOR t IN
        SELECT c.table_name, c.is_nullable
        FROM   information_schema.columns c
        JOIN   pg_class pc ON pc.relname = c.table_name
        JOIN   pg_namespace n ON n.oid = pc.relnamespace AND n.nspname = 'public'
        WHERE  c.table_schema = 'public'
          AND  c.column_name  = 'tenant_id'
          AND  pc.relkind IN ('r', 'p')
    LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t.table_name);
        EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY',  t.table_name);

        FOR pol IN SELECT policyname FROM pg_policies WHERE schemaname = 'public' AND tablename = t.table_name LOOP
            EXECUTE format('DROP POLICY IF EXISTS %I ON public.%I', pol.policyname, t.table_name);
        END LOOP;

        q := 'tenant_id = NULLIF(current_setting(''app.current_tenant'', true), '''')::uuid';
        IF t.is_nullable = 'YES' THEN
            q := q || ' OR tenant_id IS NULL';
        END IF;
        q := '(' || q || ') OR current_setting(''app.rls_bypass'', true) = ''on''';

        EXECUTE format('CREATE POLICY tenant_isolation ON public.%I USING (%s)', t.table_name, q);
    END LOOP;
END $$;

-- tenants itself is keyed by id, not tenant_id
ALTER TABLE tenants ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenants FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON tenants;
CREATE POLICY tenant_isolation ON tenants
    USING (id = NULLIF(current_setting('app.current_tenant', true), '')::uuid
           OR current_setting('app.rls_bypass', true) = 'on');

-- Grants (V25 granted existing tables only; later tables each needed their own GRANT and five lacked it)
GRANT USAGE ON SCHEMA public TO mai_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO mai_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO mai_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO mai_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO mai_app;

-- Single writer: only the ML service (owner role) updates thompson_arms (arch v1.1 §12 #12)
REVOKE INSERT, UPDATE, DELETE ON thompson_arms FROM mai_app;
-- Append-only logs: Spring may read, insert, and (for GDPR erasure / retention) delete, never rewrite
REVOKE UPDATE ON events, decision_log FROM mai_app;
REVOKE DELETE ON ghl_webhook_log FROM mai_app;
-- Flyway bookkeeping is not the app's business
REVOKE ALL ON flyway_schema_history FROM mai_app;
