-- M-7: distinguish User Access Tokens (expire in 60 days, need fb_exchange_token rotation)
--      from System User tokens (never expire, must NOT be rotated via fb_exchange_token).
ALTER TABLE tenants
    ADD COLUMN IF NOT EXISTS meta_token_type VARCHAR(20) DEFAULT 'USER'
        CHECK (meta_token_type IN ('USER', 'SYSTEM_USER'));

COMMENT ON COLUMN tenants.meta_token_type IS
    'USER = regular long-lived user access token (rotated via fb_exchange_token every 50 days); '
    'SYSTEM_USER = permanent system user token (never expires; rotation must be done via Business Manager, not this scheduler)';
