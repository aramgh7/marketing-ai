-- Audit fix 1.3: VariantOffer entity maps estimated_margin / requires_legal_review on
-- variant_offers, but V74 added those columns to landing_variants only.
-- Hibernate ddl-auto=validate refused to start. Add the columns where the entity expects them.
ALTER TABLE variant_offers ADD COLUMN IF NOT EXISTS estimated_margin      REAL;
ALTER TABLE variant_offers ADD COLUMN IF NOT EXISTS requires_legal_review BOOLEAN NOT NULL DEFAULT FALSE;
