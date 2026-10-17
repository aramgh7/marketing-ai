-- arch v1.1 §2: Google Calendar integration removed from MVP.
-- Attendance is determined solely by the sales rep setting appointmentStatus in GHL
-- (AppointmentUpdate with appointmentStatus=showed/noshow), not Calendar API.

ALTER TABLE appointments DROP COLUMN IF EXISTS calendar_event_id;
ALTER TABLE appointments DROP COLUMN IF EXISTS calendar_id;
ALTER TABLE appointments DROP COLUMN IF EXISTS calendar_checked_at;
ALTER TABLE appointments DROP COLUMN IF EXISTS attendee_email;

-- Also remove calendar credentials from tenants (stored but never used without Calendar integration)
ALTER TABLE tenants DROP COLUMN IF EXISTS calendar_credentials_enc;
