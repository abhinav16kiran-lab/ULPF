package com.ulpf.controlplane.controller;

import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.ulpf.common.UlpfPrincipal;
import com.ulpf.controlplane.service.ApiKeyService;
import com.ulpf.controlplane.service.ApiKeyService.IssuedApiKey;
import com.ulpf.controlplane.service.OnboardingService;

/**
 * Vendor onboarding endpoints for USER and VENDOR accounts (admins are refused by the security rules): submit a new
 * source or a schema update, withdraw a request, list the user's requests and sources, and create or regenerate the
 * API key of an approved source. The key is in the response of that last call only.
 */
@RestController
@RequestMapping("/v1")
public class OnboardingController {

    private final OnboardingService onboardingService;
    private final ApiKeyService apiKeyService;

    OnboardingController(OnboardingService onboardingService, ApiKeyService apiKeyService) {
        this.onboardingService = onboardingService;
        this.apiKeyService = apiKeyService;
    }

    @PostMapping(value = "/onboard/{username}", consumes = "multipart/form-data")
    public ResponseEntity<?> onboard(
            @AuthenticationPrincipal UlpfPrincipal principal,
            @PathVariable String username,
            @RequestParam String vendorName,
            @RequestParam String sourceName,
            @RequestParam String sourceType,
            @RequestParam(required = false) String logType,
            @RequestParam(required = false) Double delta,
            @RequestParam(required = false) Long maxIntervalMs,
            @RequestParam(required = false) String sensorField,
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
                username, vendorName, sourceName, sourceType, logType, delta, maxIntervalMs, sensorField, sampleLogFile, schemaFile
        );

        return ResponseEntity.status(201).body(Map.of(
                "requestId", saved.requestId(),
                "sourceId", saved.sourceId(),
                "vendorId", saved.vendorId(),
                "status", saved.status(),
                "message", saved.message()
        ));
    }

    @PostMapping(value = "/onboard/update/{username}", consumes = "multipart/form-data")
    public ResponseEntity<?> updateSource(
            @AuthenticationPrincipal UlpfPrincipal principal,
            @PathVariable String username,
            @RequestParam String sourceId,
            @RequestParam(required = false) String sourceType,
            @RequestParam(required = false) String logType,
            @RequestParam(required = false) Double delta,
            @RequestParam(required = false) Long maxIntervalMs,
            @RequestParam(required = false) String sensorField,
            @RequestParam(required = false) MultipartFile sampleLogFile,
            @RequestParam(required = false) MultipartFile schemaFile
    ) {
        if (!principal.username().equals(username)) {
            return ResponseEntity.status(403).body(Map.of(
                    "error", "you can only submit onboarding updates for your own account"
            ));
        }

        if (isBlank(sourceId)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "sourceId is required"
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

        try {
            var saved = onboardingService.submitUpdateRequest(
                    username, sourceId, sourceType, logType, delta, maxIntervalMs, sensorField, sampleLogFile, schemaFile
            );

            java.util.Map<String, Object> respMap = new java.util.HashMap<>();
            respMap.put("requestId", saved.requestId());
            respMap.put("sourceId", saved.sourceId());
            respMap.put("vendorId", saved.vendorId());
            respMap.put("status", saved.status());
            respMap.put("message", saved.message());

            return ResponseEntity.status(201).body(respMap);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.CONFLICT).body(Map.of(
                    "error", e.getMessage()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.BAD_REQUEST).body(Map.of(
                    "error", e.getMessage()
            ));
        }
    }

    @PostMapping("/onboard/withdraw/{requestId}")
    public ResponseEntity<?> withdrawRequest(
            @AuthenticationPrincipal UlpfPrincipal principal,
            @PathVariable String requestId
    ) {
        try {
            var updated = onboardingService.withdrawRequest(principal.username(), requestId);
            return ResponseEntity.ok(Map.of(
                    "requestId", updated.requestId(),
                    "status", updated.status(),
                    "message", "Request withdrawn successfully"
            ));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.CONFLICT).body(Map.of(
                    "error", e.getMessage()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.BAD_REQUEST).body(Map.of(
                    "error", e.getMessage()
            ));
        }
    }

    @GetMapping("/onboard/my-requests")
    public ResponseEntity<?> getMyRequests(@AuthenticationPrincipal UlpfPrincipal principal) {
        var requests = onboardingService.getUserRequests(principal.username());
        return ResponseEntity.ok(Map.of("requests", requests));
    }

    @GetMapping("/onboard/my-sources")
    public ResponseEntity<?> getMySources(@AuthenticationPrincipal UlpfPrincipal principal) {
        var sources = onboardingService.getUserSources(principal.username());
        return ResponseEntity.ok(Map.of("sources", sources));
    }

    /** Body of the key request. {@code replaceExisting} must be true to regenerate a key that exists. */
    public record ApiKeyRequest(Boolean replaceExisting) {}

    @PostMapping("/onboard/sources/{sourceId}/api-key")
    public ResponseEntity<?> createApiKey(
            @AuthenticationPrincipal UlpfPrincipal principal,
            @PathVariable String sourceId,
            @RequestBody(required = false) ApiKeyRequest request) {
        boolean replace = request != null && Boolean.TRUE.equals(request.replaceExisting());
        IssuedApiKey issued = apiKeyService.issueKey(principal.username(), sourceId, replace);
        return ResponseEntity.status(201)
                .cacheControl(CacheControl.noStore())
                .body(Map.of(
                        "sourceId", issued.sourceId(),
                        "apiKey", issued.apiKey(),
                        "createdAt", issued.createdAt(),
                        "replacedPreviousKey", issued.replacedPreviousKey()));
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