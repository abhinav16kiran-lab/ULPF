package com.ulpf.controlplane.controller;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ulpf.controlplane.service.OnboardingService;

@RestController
@RequestMapping("/v1/admin")
public class AdminController {

    private static final Set<String> VALID_DECISIONS = Set.of("APPROVED", "REJECTED");

    private final OnboardingService onboardingService;

    AdminController(OnboardingService onboardingService) {
        this.onboardingService = onboardingService;
    }

    @GetMapping("/onboard")
    public ResponseEntity<?> listRequests() {
        List<OnboardingService.OnboardingRequest> requests = onboardingService.getAllRequests();
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

        var updated = onboardingService.updateStatus(requestId, body.decision());
        if (updated == null) {
            return ResponseEntity.status(404).body(Map.of("error", "onboarding request not found"));
        }

        return ResponseEntity.ok(Map.of(
                "requestId", updated.requestId(),
                "status", updated.status()
        ));
    }
}

record DecisionRequest(String decision) {}