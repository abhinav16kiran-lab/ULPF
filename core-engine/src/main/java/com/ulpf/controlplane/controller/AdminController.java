package com.ulpf.controlplane.controller;

import com.ulpf.common.db.OnboardingRepository.OnboardingRequestRecord;
import com.ulpf.controlplane.service.OnboardingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.PatchMapping;

@RestController
@RequestMapping("/v1/admin")
public class AdminController {

    private static final Set<String> VALID_DECISIONS = Set.of("APPROVED", "REJECTED");

    private final OnboardingService onboardingService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AdminController(OnboardingService onboardingService) {
        this.onboardingService = onboardingService;
    }

    @GetMapping("/onboard")
    public ResponseEntity<?> listRequests() {
        List<OnboardingRequestRecord> requests = onboardingService.getAllRequests();
        return ResponseEntity.ok(Map.of("requests", requests));
    }

    @PutMapping("/onboard/{requestId}")
    public ResponseEntity<?> decideRequest(
            @PathVariable String requestId,
            @RequestBody DecisionRequest body
    ) {
        if (body.decision() == null || !VALID_DECISIONS.contains(body.decision())) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "decision must be APPROVED or REJECTED"
            ));
        }

        try {
            OnboardingRequestRecord updated = onboardingService.processAdminDecision(requestId, body.decision());
            return ResponseEntity.ok(Map.of(
                    "requestId", updated.requestId(),
                    "status", updated.status()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        }
    }

    @PatchMapping("/onboard/{requestId}/mapping")
    public ResponseEntity<?> editCandidateMapping(
            @PathVariable String requestId,
            @RequestBody Map<String, Object> body
    ) {
        if (!body.containsKey("mappingJson")) {
            return ResponseEntity.badRequest().body(Map.of("error", "mappingJson field is required"));
        }

        try {
            Object raw = body.get("mappingJson");
            String mappingJsonStr = (raw instanceof String str) ? str : objectMapper.writeValueAsString(raw);
            onboardingService.updateCandidateMapping(requestId, mappingJsonStr);
            return ResponseEntity.ok(Map.of(
                    "requestId", requestId,
                    "message", "Candidate mapping updated successfully"
            ));
        } catch (Exception e) {
            return ResponseEntity.status(400).body(Map.of("error", e.getMessage()));
        }
    }
}

record DecisionRequest(String decision) {}