CREATE TABLE touchpoints (
    id             UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID        NOT NULL REFERENCES tenants(id),
    session_id     UUID        REFERENCES sessions(id),
    source         VARCHAR(50),
    medium         VARCHAR(50),
    campaign       VARCHAR(100),
    channel        VARCHAR(30),
    touched_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    page_url       TEXT,
    is_first_touch BOOLEAN     NOT NULL DEFAULT FALSE,
    is_last_touch  BOOLEAN     NOT NULL DEFAULT FALSE
);
ALTER TABLE touchpoints ENABLE ROW LEVEL SECURITY;
ALTER TABLE touchpoints FORCE ROW LEVEL SECURITY;
CREATE POLICY touchpoints_tenant ON touchpoints
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON touchpoints TO mai_app;

CREATE INDEX touchpoints_session_idx ON touchpoints(session_id, touched_at);
CREATE INDEX touchpoints_tenant_idx  ON touchpoints(tenant_id, touched_at DESC);

CREATE TABLE attribution_credits (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        UUID        NOT NULL REFERENCES tenants(id),
    session_id       UUID        REFERENCES sessions(id),
    touchpoint_id    UUID        REFERENCES touchpoints(id),
    conversion_stage VARCHAR(40),
    model            VARCHAR(20) NOT NULL,
    credit           REAL        NOT NULL,
    computed_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE attribution_credits ENABLE ROW LEVEL SECURITY;
ALTER TABLE attribution_credits FORCE ROW LEVEL SECURITY;
CREATE POLICY attribution_credits_tenant ON attribution_credits
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON attribution_credits TO mai_app;

CREATE INDEX attribution_credits_session_idx ON attribution_credits(session_id, model);
CREATE INDEX attribution_credits_tenant_idx  ON attribution_credits(tenant_id, computed_at DESC);
