-- arch v1.1 §4.4: each arm is tied to a specific reward event type
-- (one binary count per event — no fractional increments).
-- The UNIQUE constraint must include reward_event so a single variant can have
-- separate arms for different reward events (e.g., CALL_ANSWERED vs MEETING_ATTENDED).

ALTER TABLE thompson_arms
    ADD COLUMN IF NOT EXISTS reward_event VARCHAR(50) NOT NULL DEFAULT 'FORM_SUBMIT';

-- Drop the old 3-column unique constraint (name assigned by PostgreSQL)
ALTER TABLE thompson_arms
    DROP CONSTRAINT IF EXISTS thompson_arms_tenant_id_variant_id_context_key_key;

-- Add the correct 4-column unique constraint including reward_event
ALTER TABLE thompson_arms
    ADD CONSTRAINT thompson_arms_tenant_variant_ctx_event_unique
        UNIQUE (tenant_id, variant_id, context_key, reward_event);

-- Update the existing comment so it reflects the arch v1.1 §4.4 binary reward rule
COMMENT ON COLUMN thompson_arms.reward_event IS
    'Event type this arm counts: CALL_ANSWERED, MEETING_ATTENDED, PURCHASE, etc. '
    'Binary: every qualifying event increments alpha by exactly 1.0 (arch v1.1 §4.4).';
