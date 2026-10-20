-- Roadmap 9-4 (arch §8 v1.5): the ML service stops connecting as the owner/superuser role.
--
--  mai_ml  LOGIN, NOBYPASSRLS, not the table owner  → every RLS policy from V116 applies to it.
--          Grants = mai_app plus the single-writer tables the ML service owns semantically:
--          thompson_arms, thompson_reward_log, churn_penalty_log, glm_posteriors, glm_fit_runs,
--          followup_variants / followup_assignments (follow-up bandit), model_posteriors, atom_registry.
--  Scoping is done by ml-service app/services/db_client.py (ScopedPool):
--          tenant_pool(tenant_id) → set_config('app.current_tenant', …)   per acquired connection
--          system_pool()          → set_config('app.rls_bypass', 'on')    for cross-tenant jobs
--  mai_app keeps NO write grant on thompson_arms (arch §12 #12 single writer).

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'mai_ml') THEN
        CREATE ROLE mai_ml LOGIN NOBYPASSRLS PASSWORD '${ml_role_password}';
    ELSE
        ALTER ROLE mai_ml LOGIN NOBYPASSRLS PASSWORD '${ml_role_password}';
    END IF;
END $$;

GRANT USAGE ON SCHEMA public TO mai_ml;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO mai_ml;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO mai_ml;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO mai_ml;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO mai_ml;

-- Append-only logs stay append-only for the ML role too
REVOKE UPDATE ON events FROM mai_ml;
REVOKE DELETE ON ghl_webhook_log FROM mai_ml;
REVOKE ALL ON flyway_schema_history FROM mai_ml;
