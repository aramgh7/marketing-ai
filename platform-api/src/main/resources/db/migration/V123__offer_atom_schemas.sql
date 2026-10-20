-- Roadmap 8-8: Investor offer atom schema (vision §19.11; arch §16.8)
-- Offers are described by vertical-specific atoms instead of the fixed B2B columns.
-- tenant_id NULL = platform-wide schema; a tenant row with the same schema_key overrides it.
CREATE TABLE offer_atom_schemas (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID         REFERENCES tenants(id) ON DELETE CASCADE,
    schema_key  VARCHAR(40)  NOT NULL,
    vertical    VARCHAR(60)  NOT NULL,
    description TEXT,
    -- [{key, label, type: enum|bool, values: [...], required: bool, must_be_real: bool}]
    fields      JSONB        NOT NULL DEFAULT '[]',
    -- INVESTOR_RAISE: the margin-floor rule is replaced by a compliance PASS on the offer text
    validation_mode VARCHAR(20) NOT NULL DEFAULT 'MARGIN_FLOOR'
        CHECK (validation_mode IN ('MARGIN_FLOOR', 'COMPLIANCE_PASS')),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_offer_atom_schemas ON offer_atom_schemas (COALESCE(tenant_id, '00000000-0000-0000-0000-000000000000'::uuid), schema_key);
ALTER TABLE offer_atom_schemas ENABLE ROW LEVEL SECURITY;
ALTER TABLE offer_atom_schemas FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON offer_atom_schemas
    USING ((tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid OR tenant_id IS NULL)
           OR current_setting('app.rls_bypass', true) = 'on');
GRANT SELECT, INSERT, UPDATE, DELETE ON offer_atom_schemas TO mai_app;

-- variant_offers: atoms per schema; legacy B2B columns stay
ALTER TABLE variant_offers ADD COLUMN IF NOT EXISTS schema_key VARCHAR(40);
ALTER TABLE variant_offers ADD COLUMN IF NOT EXISTS atoms      JSONB;
ALTER TABLE variant_offers ADD COLUMN IF NOT EXISTS offer_text TEXT;          -- text the compliance gate checks

INSERT INTO offer_atom_schemas (tenant_id, schema_key, vertical, description, validation_mode, fields) VALUES (
    NULL, 'INVESTOR_RAISE', 'investor_raise',
    'Reg D 506(c) investor funnel offer atoms (vision §19.11). Urgency must be real (round close date / allocation left).',
    'COMPLIANCE_PASS',
    '[
      {"key":"first_touch_format","label":"First-touch format","type":"enum","required":true,
       "values":["call","webinar","event","video_presentation"]},
      {"key":"entry_threshold_shown","label":"Entry threshold shown","type":"enum","required":true,
       "values":["none","from_25k","from_50k"]},
      {"key":"proof_type","label":"Proof type","type":"enum","required":true,
       "values":["adoption_speed","field_footage","founder","round_structure"]},
      {"key":"urgency","label":"Urgency","type":"enum","required":true,"must_be_real":true,
       "values":["none","round_close_date","allocation_left"]},
      {"key":"founder_presence","label":"Founder present","type":"bool","required":false},
      {"key":"followup_offer","label":"Follow-up offer","type":"enum","required":true,
       "values":["agenda","round_materials_before_call","none"]}
    ]'::jsonb
) ON CONFLICT DO NOTHING;
