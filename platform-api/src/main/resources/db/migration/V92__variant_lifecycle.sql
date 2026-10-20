-- Variant approve→launch state machine + anti-conditioning guard (vision §18.5)
ALTER TABLE landing_variants
    ADD COLUMN IF NOT EXISTS lifecycle_state       VARCHAR(20) DEFAULT 'DRAFT'
        CHECK (lifecycle_state IN ('DRAFT','PENDING_REVIEW','APPROVED','ACTIVE','PAUSED','RETIRED')),
    ADD COLUMN IF NOT EXISTS approved_by            UUID,
    ADD COLUMN IF NOT EXISTS approved_at            TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS launched_at            TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS anti_cond_check_passed BOOLEAN NOT NULL DEFAULT FALSE;
