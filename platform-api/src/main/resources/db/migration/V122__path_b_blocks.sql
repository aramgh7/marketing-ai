-- Roadmap 8-14: path B / C landing blocks (vision §19.18.1; arch §16.14)
-- MATERIAL_VIEW and DATAROOM_OPEN were registered in V94; WEBINAR_REG is new.
INSERT INTO event_schemas (event_type, schema_version, json_schema, introduced_at)
VALUES
    ('WEBINAR_REG', 1,
     '{"type":"object","properties":{"session_id":{"type":"string"},"webinar_at":{"type":["string","null"]},"browser_tz":{"type":"string"}}}',
     now())
ON CONFLICT DO NOTHING;

INSERT INTO atom_registry (atom_type, canonical_value, description, status)
VALUES
    ('materials',   'materials',   'Founder video + round materials list — fires MATERIAL_VIEW (path B engagement gate)', 'CONFIRMED'),
    ('data_room',   'data_room',   'Data-room-light with accreditation self-assertion gate — fires DATAROOM_OPEN',        'CONFIRMED'),
    ('webinar_reg', 'webinar_reg', 'Webinar registration form (path C < $50K tier) — fires WEBINAR_REG',                 'CONFIRMED')
ON CONFLICT DO NOTHING;
