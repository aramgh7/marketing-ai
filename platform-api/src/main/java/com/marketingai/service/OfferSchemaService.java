package com.marketingai.service;

import com.marketingai.util.Jsonb;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Offer atom schemas per tenant / vertical (roadmap 8-8; vision §19.11; arch §16.8).
 *
 * A tenant selects its schema with {@code tenants.settings.offer_schema} (e.g. INVESTOR_RAISE).
 * A tenant row in {@code offer_atom_schemas} overrides the platform row with the same key.
 * Validation of atoms against a schema is pure and mirrors
 * {@code ml-service/app/services/offer_schema_service.py}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OfferSchemaService {

    private final JdbcTemplate jdbc;

    public static final Set<String> REAL_URGENCY_SOURCES =
            Set.of("crm_deadline", "round_close_date", "allocation_api", "inventory_api", "seasonal_window");
    private static final Set<String> NEUTRAL = Set.of("none", "");

    public record Schema(String schemaKey, String vertical, String validationMode, List<Map<String, Object>> fields) {
        public boolean complianceReplacesMargin() {
            return "COMPLIANCE_PASS".equals(validationMode);
        }
    }

    /** Schema key the tenant has chosen in settings, or empty (legacy B2B offer columns). */
    public Optional<String> tenantSchemaKey(UUID tenantId) {
        try {
            List<Object> rows = jdbc.queryForList(
                    "SELECT settings FROM tenants WHERE id = ?", Object.class, tenantId);
            if (rows.isEmpty()) return Optional.empty();
            Object key = Jsonb.asMap(rows.get(0)).get("offer_schema");
            return key == null || key.toString().isBlank() ? Optional.empty() : Optional.of(key.toString());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** Tenant override first, platform row second. */
    public Optional<Schema> load(UUID tenantId, String schemaKey) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT schema_key, vertical, validation_mode, fields
                FROM offer_atom_schemas
                WHERE schema_key = ? AND (tenant_id = ? OR tenant_id IS NULL)
                ORDER BY (tenant_id IS NULL) ASC
                LIMIT 1
                """, schemaKey, tenantId);
        if (rows.isEmpty()) return Optional.empty();
        Map<String, Object> r = rows.get(0);
        List<Map<String, Object>> fields = new ArrayList<>();
        for (Object o : Jsonb.asList(r.get("fields"))) {
            if (o instanceof Map<?, ?> m) {
                @SuppressWarnings("unchecked") Map<String, Object> mm = (Map<String, Object>) m;
                fields.add(mm);
            }
        }
        return Optional.of(new Schema((String) r.get("schema_key"), (String) r.get("vertical"),
                (String) r.get("validation_mode"), fields));
    }

    public List<Map<String, Object>> listForTenant(UUID tenantId) {
        return jdbc.queryForList("""
                SELECT schema_key, vertical, description, validation_mode, fields,
                       (tenant_id IS NOT NULL) AS tenant_override, updated_at
                FROM offer_atom_schemas
                WHERE tenant_id = ? OR tenant_id IS NULL
                ORDER BY schema_key, (tenant_id IS NULL) ASC
                """, tenantId).stream().map(r -> {
                    r.put("fields", Jsonb.asList(r.get("fields")));
                    return r;
                }).toList();
    }

    /** Pure validation (mirrors validate_offer_atoms in the ML service). Empty list = valid. */
    public static List<String> validateAtoms(Map<String, Object> atoms, List<Map<String, Object>> fields,
                                             String urgencySource) {
        List<String> errors = new ArrayList<>();
        Map<String, Object> a = atoms == null ? Map.of() : atoms;
        Set<String> known = new java.util.HashSet<>();
        for (Map<String, Object> f : fields) {
            String key = String.valueOf(f.get("key"));
            known.add(key);
            String type = String.valueOf(f.getOrDefault("type", "enum"));
            boolean required = Boolean.TRUE.equals(f.get("required"));
            boolean mustBeReal = Boolean.TRUE.equals(f.get("must_be_real"));
            Object value = a.get(key);
            boolean neutral = value == null || NEUTRAL.contains(value.toString());
            boolean missing = value == null || value.toString().isBlank();   // "none" is a legitimate enum value
            if (missing && required && !"bool".equals(type)) {
                errors.add(key + " is required");
                continue;
            }
            if (value == null) continue;
            if ("enum".equals(type)) {
                List<Object> allowed = f.get("values") instanceof List<?> l ? new ArrayList<>(l) : List.of();
                if (!allowed.contains(value.toString())) {
                    errors.add(key + "='" + value + "' is not allowed; must be one of " + allowed);
                    continue;
                }
                if (mustBeReal && !neutral) {
                    String src = urgencySource != null ? urgencySource
                            : a.get("urgency_source") != null ? a.get("urgency_source").toString() : "";
                    if (!REAL_URGENCY_SOURCES.contains(src.trim().toLowerCase())) {
                        errors.add(key + "='" + value + "' must be backed by a real source (urgency_source in "
                                + REAL_URGENCY_SOURCES + ")");
                    }
                }
            } else if ("bool".equals(type)) {
                String s = value.toString().trim().toLowerCase();
                if (!(value instanceof Boolean) && !Set.of("true", "false", "yes", "no", "1", "0", "on", "off").contains(s)) {
                    errors.add(key + " must be a boolean");
                }
            }
        }
        for (String key : a.keySet()) {
            if (!known.contains(key) && !"urgency_source".equals(key)) {
                errors.add("unknown atom '" + key + "' for this schema");
            }
        }
        return errors;
    }
}
