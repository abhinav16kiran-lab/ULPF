package com.ulpf.controlplane.controller;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.ulpf.common.UlpfPrincipal;
import com.ulpf.controlplane.service.OnboardingService;

@RestController
@RequestMapping("/v1")
public class OnboardingController {

    private final OnboardingService onboardingService;

    OnboardingController(OnboardingService onboardingService) {
        this.onboardingService = onboardingService;
    }

    @PostMapping(value = "/onboard/{username}", consumes = "multipart/form-data")
    public ResponseEntity<?> onboard(
            @AuthenticationPrincipal UlpfPrincipal principal,
            @PathVariable String username,
            @RequestParam String vendorName,
            @RequestParam String sourceName,
            @RequestParam String sourceType,
            @RequestParam(required = false) MultipartFile sampleLogFile,
            @RequestParam(required = false) MultipartFile schemaFile
    ) {
        // ensure the authenticated user matches the path username
        if (!principal.username().equals(username)) {
            return ResponseEntity.status(403).body(Map.of(
                    "error", "you can only submit onboarding requests for your own account"
            ));
        }

        if (isBlank(vendorName) || isBlank(sourceName) || isBlank(sourceType)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "vendorName, sourceName, and sourceType are required"
            ));
        }

        if (sampleLogFile != null && !sampleLogFile.isEmpty() && !isAllowedFileType(sampleLogFile)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "sample log file must be .log, .csv, .json, or .txt"
            ));
        }

        if (schemaFile != null && !schemaFile.isEmpty() && !isAllowedFileType(schemaFile)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "schema file must be .log, .csv, .json, or .txt"
            ));
        }

        var saved = onboardingService.submitRequest(
                username, vendorName, sourceName, sourceType, sampleLogFile, schemaFile
        );

        return ResponseEntity.status(201).body(Map.of(
                "requestId", saved.requestId(),
                "sourceId", saved.sourceId(),
                "vendorId", saved.vendorId(),
                "apiKey", saved.rawApiKey(),
                "status", saved.status(),
                "message", saved.message()
        ));
    }

    @org.springframework.web.bind.annotation.GetMapping("/onboard/my-requests")
    public ResponseEntity<?> getMyRequests(@AuthenticationPrincipal UlpfPrincipal principal) {
        var requests = onboardingService.getUserRequests(principal.username());
        return ResponseEntity.ok(Map.of("requests", requests));
    }

    @org.springframework.web.bind.annotation.GetMapping("/onboard/my-sources")
    public ResponseEntity<?> getMySources(@AuthenticationPrincipal UlpfPrincipal principal) {
        var sources = onboardingService.getUserSources(principal.username());
        return ResponseEntity.ok(Map.of("sources", sources));
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private boolean isAllowedFileType(MultipartFile file) {
        String name = file.getOriginalFilename();
        return name != null && (
                name.endsWith(".log") || name.endsWith(".csv")
                || name.endsWith(".json") || name.endsWith(".txt")
        );
    }
}