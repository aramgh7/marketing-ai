package com.marketingai.controller;

import com.marketingai.service.RewardService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/reward")
@RequiredArgsConstructor
public class RewardController {

    private final RewardService rewardService;

    @GetMapping("/ladder")
    public ResponseEntity<Map<String, Object>> getLadder(
            @RequestAttribute("tenantId") UUID tenantId) {
        return ResponseEntity.ok(rewardService.getRewardLadder(tenantId));
    }

    @PostMapping("/stage-reached")
    public ResponseEntity<Void> stageReached(
            @RequestAttribute("tenantId") UUID tenantId,
            @RequestBody Map<String, Object> body) {
        String sessionId = (String) body.get("session_id");  // sessions.id is VARCHAR(100)
        UUID variantId  = UUID.fromString((String) body.get("variant_id"));
        String stageKey = (String) body.get("stage_key");
        rewardService.onStageReached(tenantId, sessionId, variantId, stageKey);
        return ResponseEntity.ok().build();
    }
}
