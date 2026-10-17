CREATE TABLE thompson_arms (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID NOT NULL REFERENCES tenants(id),
    variant_id     UUID NOT NULL REFERENCES landing_variants(id),
    context_key    VARCHAR(255) NOT NULL DEFAULT 'global',
    -- Hurdle model: Binomial(conversion) × Lognormal(revenue)
    -- Reward logic (B2B): form_submit=wait, call_answered=α+=0.3, meeting_attended=α+=1.0,
    -- deal_won=revenue update, no_response_7d=β+=1.0. See vision §16.1
    alpha          REAL NOT NULL DEFAULT 1.0,   -- successes + 1 (Beta prior)
    beta           REAL NOT NULL DEFAULT 1.0,   -- failures + 1 (Beta prior)
    revenue_mu     REAL DEFAULT 0.0,            -- log-revenue mean
    revenue_sigma  REAL DEFAULT 1.0,            -- log-revenue std
    revenue_n      INT DEFAULT 0,
    total_samples  INT DEFAULT 0,
    last_decayed_at TIMESTAMPTZ DEFAULT now(),
    updated_at     TIMESTAMPTZ DEFAULT now(),
    UNIQUE(tenant_id, variant_id, context_key)
);

CREATE INDEX idx_arms_tenant ON thompson_arms(tenant_id, context_key);
