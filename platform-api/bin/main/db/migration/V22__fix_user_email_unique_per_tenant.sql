-- arch v1.1 §4.1: users.email must be unique per-tenant, not globally.
-- One person can belong to two tenants (e.g. an agency employee managing multiple accounts).
-- Original V1 created a global UNIQUE constraint on email; this migration replaces it.

-- Drop the global unique constraint (name auto-assigned by PostgreSQL as users_email_key)
ALTER TABLE users DROP CONSTRAINT IF EXISTS users_email_key;

-- Add per-tenant uniqueness
ALTER TABLE users ADD CONSTRAINT users_tenant_email_unique UNIQUE (tenant_id, email);

-- Drop the global index created by V1 (if it exists separately)
DROP INDEX IF EXISTS idx_users_email;

-- Add per-tenant index (replaces the global one)
CREATE INDEX IF NOT EXISTS idx_users_tenant_email ON users(tenant_id, email);
