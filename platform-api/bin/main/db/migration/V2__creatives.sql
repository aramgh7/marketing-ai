CREATE TABLE creatives (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID NOT NULL REFERENCES tenants(id),
    external_id    VARCHAR(255),
    type           VARCHAR(20) NOT NULL,   -- VIDEO, IMAGE, CAROUSEL
    title          VARCHAR(500),
    file_url       TEXT,
    thumbnail_url  TEXT,
    transcript     TEXT,                   -- Whisper output for video
    duration_sec   REAL,
    status         VARCHAR(20) NOT NULL DEFAULT 'UPLOADED',  -- UPLOADED, ANALYZING, ACTIVE, ARCHIVED
    is_anchor      BOOLEAN DEFAULT FALSE,
    meta_ad_set_id VARCHAR(100),
    created_at     TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE creative_atoms (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    creative_id  UUID NOT NULL REFERENCES creatives(id) ON DELETE CASCADE,
    tenant_id    UUID NOT NULL REFERENCES tenants(id),
    atom_type    VARCHAR(50) NOT NULL,    -- HOOK, STRUCTURE, EDIT_PACE, FACE, SUBTITLES,
                                         -- TONE, PROOF_TYPE, MUSIC, DURATION, CTA, PRODUCTION_STYLE
    atom_value   VARCHAR(500) NOT NULL,
    confidence   REAL,
    extracted_by VARCHAR(50),            -- "claude-vision", "whisper+llm", "manual"
    embedding    vector(768),
    created_at   TIMESTAMPTZ DEFAULT now()
);

CREATE INDEX idx_atoms_creative  ON creative_atoms(creative_id);
CREATE INDEX idx_atoms_type_val  ON creative_atoms(tenant_id, atom_type, atom_value);
