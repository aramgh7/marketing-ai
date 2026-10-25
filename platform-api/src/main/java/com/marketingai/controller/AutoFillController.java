package com.marketingai.controller;

import com.marketingai.service.AutoFillService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/v1/templates")
@RequiredArgsConstructor
public class AutoFillController {

    private final AutoFillService autoFillService;

    /**
     * POST /api/v1/templates/{id}/auto-fill
     * Body: {facts: {...}, visual_atoms: [{slot, l1_tag, coefficient}]}
     * Returns: assembled block list
     */
    @PostMapping("/{id}/auto-fill")
    public List<Map<String, Object>> autoFill(
            @PathVariable UUID id,
            @RequestBody Map<String, Object> body
    ) {
        @SuppressWarnings("unchecked")
        Map<String, Object> facts = body.get("facts") instanceof Map<?,?> m
                ? (Map<String, Object>) m : Map.of();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> visualAtoms = body.get("visual_atoms") instanceof List<?> l
                ? (List<Map<String, Object>>) l : List.of();
        return autoFillService.assemble(id, facts, visualAtoms);
    }
}
