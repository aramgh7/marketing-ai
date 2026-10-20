-- 8-16: Predicted Lead Value at Day 0 (vision §19.18.3; arch §16.16)
-- Formula: p_show × p_verified|show × p_invest|verified × E[ticket|bucket]
-- Acceptance: calibration slope 0.8–1.2 on >=200 realised leads

CREATE TABLE lead_value_predictions (
    id                      UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id               UUID        NOT NULL REFERENCES tenants(id),
    session_id              VARCHAR(100) REFERENCES sessions(id),
    appointment_id          UUID        REFERENCES appointments(id),
    p_show                  REAL,
    p_verified_given_show   REAL,
    p_invest_given_verified REAL        DEFAULT 0.21,
    e_ticket                REAL,
    predicted_value         REAL,
    realised_value          REAL,
    realised_at             TIMESTAMPTZ,
    model_version           VARCHAR(30) DEFAULT 'seed_v1',
    predicted_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE lead_value_predictions ENABLE ROW LEVEL SECURITY;
ALTER TABLE lead_value_predictions FORCE ROW LEVEL SECURITY;
CREATE POLICY lead_value_predictions_tenant ON lead_value_predictions
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON lead_value_predictions TO mai_app;

CREATE INDEX lead_value_predictions_session     ON lead_value_predictions(session_id);
CREATE INDEX lead_value_predictions_appointment ON lead_value_predictions(appointment_id);
