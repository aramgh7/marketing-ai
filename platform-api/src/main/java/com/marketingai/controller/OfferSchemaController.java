package com.marketingai.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketingai.service.MlServiceClient;
import com.marketingai.service.OfferSchemaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Offer atom schemas (roadmap 8-8):
 *   GET  /offer-schemas                    — platform schemas + tenant overrides
 *   PUT  /offer-schemas/{key}              — tenant override (fields, validation_mode)
 *   POST /offer-schemas/{key}/validate     — validate an atom set
 *   POST /offer-schemas/{key}/explore      — Dynamic Offer Explorer (3-19) over the schema →
 *                                            candidates stored in offer_candidates (status CANDIDATE)
 */
@RestController
@RequestMapping("/offer-schemas")
@RequiredArgsConstructor
@Slf4j
public class OfferSchemaController {

    private final OfferSchemaService schemaService;
    private final MlServiceClient mlClient;
    private final JdbcTemplate jdbc;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list(@RequestAttribute("tenantId") UUID tenantId) {
        return ResponseEntity.ok(schemaService.listForTenant(tenantId));
    }

    @PutMapping("/{key}")
    public ResponseEntity<Map<String, Object>> upsert(@RequestAttribute("tenantId") UUID tenantId,
                                                      @PathVariable String key,
                                                      @RequestBody Map<String, Object> body) {
        Object fields = body.getOrDefault("fields", List.of());
        String vertical = String.valueOf(body.getOrDefault("vertical", key.toLowerCase()));
        String mode = String.valueOf(body.getOrDefault("validation_mode", "MARGIN_FLOOR"));
        if (!"MARGIN_FLOOR".equals(mode) && !"COMPLIANCE_PASS".equals(mode)) {
            return ResponseEntity.badRequest().body(Map.of("error", "validation_mode must be MARGIN_FLOOR or COMPLIANCE_PASS"));
        }
        jdbc.update("""
                INSERT INTO offer_atom_schemas (tenant_id, schema_key, vertical, description, validation_mode, fields)
                VALUES (?, ?, ?, ?, ?, ?::jsonb)
                ON CONFLICT (COALESCE(tenant_id, '00000000-0000-0000-0000-000000000000'::uuid), schema_key)
                DO UPDATE SET vertical = EXCLUDED.vertical, description = EXCLUDED.description,
                              validation_mode = EXCLUDED.validation_mode, fields = EXCLUDED.fields, updated_at = now()
                """, tenantId, key, vertical, body.get("description"), mode, toJson(fields));
        return ResponseEntity.ok(Map.of("schema_key", key, "status", "saved"));
    }

    @PostMapping("/{key}/validate")
    public ResponseEntity<Map<String, Object>> validate(@RequestAttribute("tenantId") UUID tenantId,
                                                        @PathVariable String key,
                                                        @RequestBody Map<String, Object> body) {
        OfferSchemaService.Schema schema = schemaService.load(tenantId, key)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown schema " + key));
        @SuppressWarnings("unchecked")
        Map<String, Object> atoms = (Map<String, Object>) body.getOrDefault("atoms", Map.of());
        List<String> errors = OfferSchemaService.validateAtoms(atoms, schema.fields(),
                body.get("urgency_source") != null ? body.get("urgency_source").toString() : null);
        return ResponseEntity.ok(Map.of("valid", errors.isEmpty(), "errors", errors,
                "validation_mode", schema.validationMode()));
    }

    /** Dynamic Offer Explorer (3-19) generating over the tenant's schema instead of B2B price/guarantee knobs. */
    @PostMapping("/{key}/explore")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> explore(@RequestAttribute("tenantId") UUID tenantId,
                                                       @PathVariable String key,
                                                       @RequestBody Map<String, Object> body) {
        OfferSchemaService.Schema schema = schemaService.load(tenantId, key)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown schema " + key));
        Map<String, Object> base = (Map<String, Object>) body.getOrDefault("base_atoms", Map.of());
        Map<String, Object> req = new HashMap<>();
        req.put("fields", schema.fields());
        req.put("base_atoms", base);
        req.put("max_variants", body.getOrDefault("max_variants", 8));
        req.put("urgency_source", body.get("urgency_source"));

        Map<String, Object> result = mlClient.post("/mmf/offers/generate-from-schema", req, Map.class);
        List<Map<String, Object>> candidates = result == null ? List.of()
                : (List<Map<String, Object>>) result.getOrDefault("candidates", List.of());

        List<Map<String, Object>> stored = new ArrayList<>();
        for (Map<String, Object> c : candidates) {
            if ("baseline".equals(c.get("label"))) continue;
            UUID id = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO offer_candidates (id, tenant_id, audience_key, sub_atoms, hypothesis, rank_score, status)
                    VALUES (?, ?, ?, ?::jsonb, ?, NULL, 'CANDIDATE')
                    """, id, tenantId, key, toJson(c.get("atoms")), "Explorer: change " + c.get("changed") + " → " + c.get("label"));
            Map<String, Object> row = new HashMap<>(c);
            row.put("candidate_id", id.toString());
            stored.add(row);
        }
        return ResponseEntity.ok(Map.of("schema_key", key, "validation_mode", schema.validationMode(),
                "baseline", base, "candidates", stored));
    }

    private static String toJson(Object o) {
        try { return MAPPER.writeValueAsString(o); } catch (Exception e) { return "[]"; }
    }
}
