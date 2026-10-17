-- CAPI Chain v2: per-tenant value coefficients (vision §19.4 / arch §16.4)
-- k_accr and k_ticket multipliers stored as JSONB for ValueFormulaService.

ALTER TABLE tenants
    ADD COLUMN IF NOT EXISTS value_coeffs JSONB NOT NULL DEFAULT '{
        "k_accr": {"yes": 1.0, "no": 0.0},
        "k_ticket": {
            "LT25K":    0.3,
            "25_50K":   0.6,
            "50_100K":  1.0,
            "100_250K": 1.8,
            "GT250K":   3.0
        },
        "base_lead":     4000,
        "base_schedule": 7600,
        "base_reg":      15800
    }';
