-- Multi-pixel support: prevents pixel pollution from bad campaigns (§6.8, vision §6.8)
CREATE TABLE meta_pixels (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id             UUID NOT NULL REFERENCES tenants(id),
    pixel_id              VARCHAR(100) NOT NULL,
    name                  VARCHAR(255),
    purpose               VARCHAR(30) NOT NULL DEFAULT 'PRIMARY',  -- PRIMARY, TEST, PRODUCT
    product_id            UUID,
    is_default            BOOLEAN DEFAULT FALSE,
    capi_access_token_enc BYTEA,
    quality_filter        JSONB DEFAULT '{}',  -- min lead quality score to forward event to Meta
    status                VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at            TIMESTAMPTZ DEFAULT now(),
    UNIQUE(tenant_id, pixel_id)
);

-- FK added here because meta_pixels table exists now
ALTER TABLE meta_campaigns
    ADD CONSTRAINT fk_campaigns_pixel
    FOREIGN KEY (pixel_id) REFERENCES meta_pixels(id);
