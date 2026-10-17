-- Phase 0-10: Google Calendar Integration
-- Add calendar credentials storage to tenants
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS calendar_credentials_enc BYTEA;

-- Appointment tracking table: GHL appointment.scheduled/completed/no_show + Calendar fallback
CREATE TABLE IF NOT EXISTS appointments (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id            UUID NOT NULL REFERENCES tenants(id),
    session_id           VARCHAR(100),
    ghl_contact_id       VARCHAR(100),
    ghl_appointment_id   VARCHAR(100),
    calendar_event_id    VARCHAR(255),
    calendar_id          VARCHAR(255) DEFAULT 'primary',
    attendee_email       VARCHAR(255),
    scheduled_at         TIMESTAMPTZ NOT NULL,
    status               VARCHAR(20) NOT NULL DEFAULT 'SCHEDULED',
    source               VARCHAR(30),   -- GHL_WEBHOOK, CALENDAR_FALLBACK, TIMEOUT
    calendar_checked_at  TIMESTAMPTZ,
    ghl_updated_at       TIMESTAMPTZ,
    created_at           TIMESTAMPTZ DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_appointments_tenant
    ON appointments(tenant_id, scheduled_at DESC);

-- Partial index: only SCHEDULED rows — used by the pending-check query
CREATE INDEX IF NOT EXISTS idx_appointments_pending
    ON appointments(scheduled_at)
    WHERE status = 'SCHEDULED';

-- Unique index: prevent duplicate registrations for the same GHL appointment
CREATE UNIQUE INDEX IF NOT EXISTS idx_appointments_ghl_id
    ON appointments(tenant_id, ghl_appointment_id)
    WHERE ghl_appointment_id IS NOT NULL;

-- RLS
ALTER TABLE appointments ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_appointments ON appointments
    USING (tenant_id::text = current_setting('app.current_tenant', true));
