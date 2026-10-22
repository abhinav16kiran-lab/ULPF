package com.ulpf.controlplane.controller;

import com.ulpf.common.UlpfPrincipal;
import com.ulpf.controlplane.service.VendorWebhookService;
import com.ulpf.controlplane.service.VendorWebhookService.VendorWebhookResponse;
import com.ulpf.controlplane.service.VendorWebhookService.WebhookConfigRequest;
import com.ulpf.dataplane.service.AlertDispatcher.DeliveryResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * REST API endpoints for vendor webhook configuration and management.
 * Secured to VENDOR and ADMIN roles only.
 */
@RestController
@RequestMapping("/v1/vendor/webhook")
@PreAuthorize("hasRole('VENDOR') or hasRole('ADMIN')")
public class VendorWebhookController {

    private static final Logger log = LoggerFactory.getLogger(VendorWebhookController.class);

    private final VendorWebhookService vendorWebhookService;

    public VendorWebhookController(VendorWebhookService vendorWebhookService) {
        this.vendorWebhookService = vendorWebhookService;
    }

    /**
     * GET /v1/vendor/webhook
     * Get current vendor webhook config & recent delivery logs.
     */
    @GetMapping
    public ResponseEntity<?> getWebhook(@AuthenticationPrincipal UlpfPrincipal principal) {
        try {
            VendorWebhookResponse response = vendorWebhookService.getWebhook(principal.id());
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("[WEBHOOK API] Failed to get webhook config: {}", e.getMessage());
            return ResponseEntity.status(500).body(Map.of("error", "Failed to retrieve webhook configuration"));
        }
    }

    /**
     * PUT /v1/vendor/webhook
     * Register or update webhook URL and secret.
     */
    @PutMapping
    public ResponseEntity<?> saveOrUpdateWebhook(
            @AuthenticationPrincipal UlpfPrincipal principal,
            @RequestBody WebhookConfigRequest request) {
        try {
            VendorWebhookResponse response = vendorWebhookService.saveOrUpdateWebhook(principal.id(), request);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("[WEBHOOK API] Failed to save webhook config: {}", e.getMessage());
            return ResponseEntity.status(500).body(Map.of("error", "Failed to save webhook configuration"));
        }
    }

    /**
     * DELETE /v1/vendor/webhook
     * Remove webhook configuration.
     */
    @DeleteMapping
    public ResponseEntity<?> deleteWebhook(@AuthenticationPrincipal UlpfPrincipal principal) {
        try {
            vendorWebhookService.deleteWebhook(principal.id());
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("[WEBHOOK API] Failed to delete webhook config: {}", e.getMessage());
            return ResponseEntity.status(500).body(Map.of("error", "Failed to delete webhook configuration"));
        }
    }

    /**
     * POST /v1/vendor/webhook/test
     * Send immediate test ping to configured webhook.
     */
    @PostMapping("/test")
    public ResponseEntity<?> sendTestPing(@AuthenticationPrincipal UlpfPrincipal principal) {
        try {
            DeliveryResult result = vendorWebhookService.sendTestPing(principal.id());
            return ResponseEntity.ok(Map.of(
                "success", result.success(),
                "statusCode", result.statusCode(),
                "latencyMs", result.latencyMs(),
                "errorMessage", result.errorMessage() != null ? result.errorMessage() : ""
            ));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("[WEBHOOK API] Test ping failed: {}", e.getMessage());
            return ResponseEntity.status(500).body(Map.of("error", "Test ping failed: " + e.getMessage()));
        }
    }
}
