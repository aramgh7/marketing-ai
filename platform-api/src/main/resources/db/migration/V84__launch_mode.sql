-- V84: tenants.launch_mode — CREATIVE_TESTING_TOOL mode flag (Block F §7)
ALTER TABLE tenants
    ADD COLUMN IF NOT EXISTS launch_mode VARCHAR(32) NOT NULL DEFAULT 'NORMAL';
-- Allowed values: NORMAL | CREATIVE_TESTING_TOOL

COMMENT ON COLUMN tenants.launch_mode IS
    'NORMAL = standard Thompson+contour; CREATIVE_TESTING_TOOL = equal-split creative test mode';
