CREATE TABLE landing_variants (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID NOT NULL REFERENCES tenants(id),
    creative_id UUID REFERENCES creatives(id),
    name        VARCHAR(255) NOT NULL,
    slug        VARCHAR(100) NOT NULL,
    status      VARCHAR(20) NOT NULL DEFAULT 'DRAFT',  -- DRAFT, REVIEW, ACTIVE, PAUSED, ARCHIVED
    blocks      JSONB NOT NULL DEFAULT '[]',
    block_order TEXT[] NOT NULL DEFAULT '{}',
    match_score REAL,
    approved_by UUID REFERENCES users(id),
    approved_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ DEFAULT now(),
    updated_at  TIMESTAMPTZ DEFAULT now(),
    UNIQUE(tenant_id, slug)
);

CREATE TABLE variant_offers (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    variant_id       UUID NOT NULL REFERENCES landing_variants(id) ON DELETE CASCADE,
    tenant_id        UUID NOT NULL REFERENCES tenants(id),
    discount_type    VARCHAR(50),    -- percent, fixed, free_shipping, gift, none
    discount_depth   REAL,           -- 0.0–1.0 (normalized)
    guarantee_type   VARCHAR(50),    -- money_back_14, money_back_30, lifetime, none
    guarantee_days   INT,
    bonus            VARCHAR(200),
    has_real_urgency BOOLEAN DEFAULT FALSE,
    urgency_source   VARCHAR(200),
    urgency_days_left INT,
    payment_terms    VARCHAR(50),    -- full, installment_3, installment_6, trial, freemium
    margin_floor     REAL,
    created_at       TIMESTAMPTZ DEFAULT now()
);
