-- Platform-wide atom registry (no RLS — shared across all tenants)
CREATE TABLE atom_registry (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    atom_type        VARCHAR(50)  NOT NULL,
    canonical_value  VARCHAR(100) NOT NULL,
    description      TEXT,
    centroid         vector(768),
    status           VARCHAR(12)  NOT NULL DEFAULT 'CANDIDATE'
                         CHECK (status IN ('CANDIDATE', 'CONFIRMED')),
    appearance_count INT          NOT NULL DEFAULT 0,
    tenant_count     INT          NOT NULL DEFAULT 0,
    merged_into      UUID         REFERENCES atom_registry(id),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_atom_registry_type ON atom_registry(atom_type, status);
-- mai_app full access (platform-wide, no RLS needed)
GRANT SELECT, INSERT, UPDATE, DELETE ON atom_registry TO mai_app;

-- Add registry linkage columns to creative_atoms
ALTER TABLE creative_atoms
    ADD COLUMN IF NOT EXISTS registry_id    UUID REFERENCES atom_registry(id),
    ADD COLUMN IF NOT EXISTS alignment_sim  REAL;

CREATE INDEX IF NOT EXISTS idx_atoms_registry ON creative_atoms(registry_id)
    WHERE registry_id IS NOT NULL;
