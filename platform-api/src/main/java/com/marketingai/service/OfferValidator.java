package com.marketingai.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;

/**
 * Validates offer field combinations against architecture §15.6 validity mask.
 * Pure logic — no DB access.
 */
@Service
public class OfferValidator {

    private static final Set<String> VALID_URGENCY_SOURCES =
            Set.of("inventory_api", "crm_deadline", "seasonal_window");

    private static final Set<String> INSTALLMENT_TERMS =
            Set.of("installment", "installment_3", "installment_6");

    public record ValidationResult(boolean valid, List<String> errors, boolean requiresLegalReview) {}

    /** Backward-compatible overload without margin. */
    public ValidationResult validate(Map<String, Object> offer) {
        return validate(offer, null);
    }

    public ValidationResult validate(Map<String, Object> offer, Double minMargin) {
        List<String> errors = new ArrayList<>();

        String discountType = str(offer, "discount_type");
        Object discountDepth = offer.get("discount_depth");
        String guaranteeType = str(offer, "guarantee_type");
        Object guaranteeDays = offer.get("guarantee_days");
        Object hasRealUrgency = offer.get("has_real_urgency");
        String urgencySource = str(offer, "urgency_source");

        // discount_type=none ⇒ discount_depth must be 0
        if ("none".equalsIgnoreCase(discountType)) {
            if (discountDepth != null) {
                double depth = toDouble(discountDepth);
                if (depth != 0.0) {
                    errors.add("discount_type=none requires discount_depth=0 (got " + depth + ")");
                }
            }
        }

        // guarantee_type=none ⇒ guarantee_days must be null
        if ("none".equalsIgnoreCase(guaranteeType)) {
            if (guaranteeDays != null) {
                errors.add("guarantee_type=none requires guarantee_days=null");
            }
        }

        // has_real_urgency=true ⇒ urgency_source must be in allowed set
        if (Boolean.TRUE.equals(hasRealUrgency)) {
            if (urgencySource == null || urgencySource.isBlank()) {
                errors.add("has_real_urgency=true requires urgency_source to be set");
            } else if (!VALID_URGENCY_SOURCES.contains(urgencySource)) {
                errors.add("urgency_source '" + urgencySource + "' is not allowed; " +
                           "must be one of: " + VALID_URGENCY_SOURCES);
            }
        }

        // estimated_margin >= tenants.settings.min_margin (vision §18.5)
        Object estimatedMarginObj = offer.get("estimated_margin");
        if (minMargin != null && estimatedMarginObj != null) {
            double em = toDouble(estimatedMarginObj);
            if (!Double.isNaN(em) && em < minMargin) {
                errors.add(String.format(
                    "estimated_margin=%.4f < min_margin=%.4f (tenant margin floor §18.5)",
                    em, minMargin));
            }
        }

        // Installment terms require legal review flag
        String paymentTerms = str(offer, "payment_terms");
        boolean requiresLegalReview = paymentTerms != null
                && INSTALLMENT_TERMS.contains(paymentTerms.toLowerCase());

        return new ValidationResult(errors.isEmpty(), errors, requiresLegalReview);
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v != null ? v.toString() : null;
    }

    private static double toDouble(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(v.toString()); } catch (Exception e) { return Double.NaN; }
    }
}
