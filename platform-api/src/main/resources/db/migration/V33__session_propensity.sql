-- Phase 5-2: Session Propensity + Phase 5-3/5-4: Personalization group
-- Point-in-time propensity scores updated during session.

CREATE TABLE session_propensity (
    session_id     VARCHAR(100) NOT NULL,
    tenant_id      UUID NOT NULL REFERENCES tenants(id),
    p_convert      REAL NOT NULL DEFAULT 0.0,    -- P(conversion)
    main_objection VARCHAR(50),                   -- price|trust|urgency|understanding
    uplift_score   REAL NOT NULL DEFAULT 0.0,    -- incremental effect of personalisation
    cluster_id     INT,
    scored_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (session_id, tenant_id)
);

ALTER TABLE session_propensity ENABLE ROW LEVEL SECURITY;
ALTER TABLE session_propensity FORCE ROW LEVEL SECURITY;

CREATE POLICY session_propensity_tenant ON session_propensity
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE, DELETE ON session_propensity TO mai_app;

-- Phase 5-3/5-4: personalised vs control (§7.4: mandatory 5-10% control group)
ALTER TABLE sessions
    ADD COLUMN IF NOT EXISTS personalization_group VARCHAR(20)
        DEFAULT 'personalised'
        CHECK (personalization_group IN ('personalised', 'control'));
