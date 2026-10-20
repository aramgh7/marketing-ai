-- Roadmap 9-6 (arch §4.5): event stream hygiene.
--  1. events.schema_version gets the documented DEFAULT 1 (V46 added it nullable without a default);
--     existing NULLs are backfilled so readers can branch on it.
--  2. event_id stays nullable at the column level (partitioned table, historical rows), but the
--     ingestion now rejects rows without it (events_rejected.reject_reason = 'missing_event_id') —
--     the tracker always sends one, so the partial unique index idx_events_tenant_event_dedup covers
--     every new row.
--  3. The app role may create next months' partitions (EventPartitionScheduler); the parent is owned
--     by the migration role, so the scheduler needs CREATE on the schema — partitions of a table
--     owned by another role additionally require ownership or the parent's owner privilege.
--     We therefore let the owner role keep creating partitions through a SECURITY DEFINER function.

ALTER TABLE events ALTER COLUMN schema_version SET DEFAULT 1;
UPDATE events SET schema_version = 1 WHERE schema_version IS NULL;

CREATE OR REPLACE FUNCTION public.ensure_events_partition(p_name text, p_from date, p_to date)
RETURNS boolean
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    created boolean := false;
BEGIN
    IF p_name !~ '^events_[0-9]{4}_[0-9]{2}$' THEN
        RAISE EXCEPTION 'invalid partition name %', p_name;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                   WHERE n.nspname = 'public' AND c.relname = p_name) THEN
        EXECUTE format('CREATE TABLE %I PARTITION OF events FOR VALUES FROM (%L) TO (%L)', p_name, p_from, p_to);
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', p_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', p_name);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING ('
                       || 'tenant_id = NULLIF(current_setting(''app.current_tenant'', true), '''')::uuid'
                       || ' OR current_setting(''app.rls_bypass'', true) = ''on'')', p_name);
        EXECUTE format('GRANT SELECT, INSERT, DELETE ON %I TO mai_app', p_name);
        EXECUTE format('GRANT SELECT, INSERT, DELETE ON %I TO mai_ml', p_name);
        created := true;
    END IF;
    RETURN created;
END $$;

REVOKE ALL ON FUNCTION public.ensure_events_partition(text, date, date) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.ensure_events_partition(text, date, date) TO mai_app;
