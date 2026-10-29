package com.marketingai.controller;

import com.marketingai.service.TemplateService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/templates")
@RequiredArgsConstructor
public class TemplateController {

    private final TemplateService templateService;

    @GetMapping
    public List<Map<String, Object>> list() {
        return templateService.list();
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        return templateService.create(body);
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        return templateService.update(id, body);
    }

    @PostMapping("/{id}/fork")
    public Map<String, Object> fork(@PathVariable UUID id) {
        return templateService.fork(id);
    }

    @PostMapping("/seed-defaults")
    public Map<String, Object> seedDefaults() {
        templateService.seedDefaults();
        return Map.of("status", "ok");
    }
}
