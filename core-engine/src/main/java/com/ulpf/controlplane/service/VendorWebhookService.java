package com.ulpf.controlplane.service;

import com.ulpf.common.db.VendorRepository;
import com.ulpf.common.db.VendorRepository.VendorRecord;
import com.ulpf.common.db.VendorWebhookRepository;
import com.ulpf.common.db.VendorWebhookRepository.VendorWebhookRecord;
import com.ulpf.common.db.VendorWebhookRepository.WebhookDeliveryLogRecord;
import com.ulpf.dataplane.service.AlertDispatcher;
import com.ulpf.dataplane.service.AlertDispatcher.DeliveryResult;
import com.ulpf.dataplane.service.AlertPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Service layer for managing vendor webhook configurations,
 * test pings, and retrieving masked webhook data for the UI.
 */
@Service
public class VendorWebhookService {

    private static final Logger log = LoggerFactory.getLogger(VendorWebhookService.class);

    private final VendorWebhookRepository vendorWebhookRepository;
    private final VendorRepository vendorRepository;
    private final AlertDispatcher alertDispatcher;

    public VendorWebhookService(
            VendorWebhookRepository vendorWebhookRepository,
            VendorRepository vendorRepository,
            AlertDispatcher alertDispatcher) {
        this.vendorWebhookRepository = vendorWebhookRepository;
        this.vendorRepository = vendorRepository;
        this.alertDispatcher = alertDispatcher;
    }

    // ── Response DTOs ────────────────────────────────────────────────

    public record VendorWebhookResponse(
        boolean configured,
        String webhookUrl,
        String maskedSecret,
        String status,
        Integer lastStatusCode,
        LocalDateTime lastAttemptAt,
        int consecutiveFails,
        List<DeliveryLogEntry> recentLogs
    ) {}

    public record DeliveryLogEntry(
        String alertType,
        Integer statusCode,
        boolean success,
        Integer latencyMs,
        LocalDateTime attemptedAt
    ) {}

    public record WebhookConfigRequest(
        String webhookUrl,
        String secretKey
    ) {}

    // ── Service Methods ──────────────────────────────────────────────

    /**
     * Gets the current webhook configuration for the vendor owned by the given userId.
     * Masks the secret key for safe display.
     */
    public VendorWebhookResponse getWebhook(String userId) {
        String vendorId = resolveVendorId(userId);

        var webhookOpt = vendorWebhookRepository.findByVendorId(vendorId);
        if (webhookOpt.isEmpty()) {
            return new VendorWebhookResponse(false, null, null, null, null, null, 0, List.of());
        }

        VendorWebhookRecord wh = webhookOpt.get();
        List<WebhookDeliveryLogRecord> rawLogs = vendorWebhookRepository.findRecentLogsByVendorId(vendorId, 5);

        List<DeliveryLogEntry> logEntries = rawLogs.stream()
            .map(l -> new DeliveryLogEntry(l.alertType(), l.statusCode(), l.success(), l.latencyMs(), l.attemptedAt()))
            .toList();

        return new VendorWebhookResponse(
            true,
            wh.webhookUrl(),
            maskSecret(wh.secretKey()),
            wh.status(),
            wh.lastStatusCode(),
            wh.lastAttemptAt(),
            wh.consecutiveFails(),
            logEntries
        );
    }

    /**
     * Saves or updates the webhook configuration for the vendor.
     * Resets status to UNTESTED when URL or secret changes.
     */
    public VendorWebhookResponse saveOrUpdateWebhook(String userId, WebhookConfigRequest request) {
        // Validate URL format and enforce SSRF restrictions
        validateWebhookUrl(request.webhookUrl());
        String url = request.webhookUrl().trim();

        String vendorId = resolveVendorId(userId);

        // If secretKey is provided, use it. If omitted or blank, preserve existing secret if already configured.
        String secretToSave = null;
        if (request.secretKey() != null && !request.secretKey().isBlank()) {
            secretToSave = request.secretKey().trim();
        } else {
            secretToSave = vendorWebhookRepository.findByVendorId(vendorId)
                    .map(VendorWebhookRecord::secretKey)
                    .orElse(null);
        }

        VendorWebhookRecord record = new VendorWebhookRecord(
            null, vendorId, url, secretToSave,
            true, "UNTESTED", null, null, 0, null, null
        );

        vendorWebhookRepository.saveOrUpdate(record);
        log.info("[WEBHOOK] Vendor {} saved/updated webhook configuration: {}", vendorId, url);

        return getWebhook(userId);
    }

    /**
     * Removes the webhook configuration for the vendor.
     */
    public void deleteWebhook(String userId) {
        String vendorId = resolveVendorId(userId);
        vendorWebhookRepository.deleteByVendorId(vendorId);
        log.info("[WEBHOOK] Vendor {} deleted webhook configuration", vendorId);
    }

    /**
     * Sends a test ping to the vendor's configured webhook endpoint.
     * Returns the delivery result with status code and latency.
     */
    public DeliveryResult sendTestPing(String userId) {
        String vendorId = resolveVendorId(userId);

        var webhookOpt = vendorWebhookRepository.findByVendorId(vendorId);
        if (webhookOpt.isEmpty()) {
            throw new IllegalStateException("No webhook configured. Please save a webhook URL first.");
        }

        VendorWebhookRecord wh = webhookOpt.get();

        AlertPayload testPayload = new AlertPayload(
            "TEST_PING",
            "INFO",
            null, null,
            vendorId, null,
            null, null,
            0, Set.of(),
            Instant.now(),
            "Test alert from ULPF webhook integration",
            "No action required — this is a connectivity test."
        );

        try {
            URI uri = URI.create(wh.webhookUrl());
            CompletableFuture<DeliveryResult> future = alertDispatcher.sendWebhook(uri, wh.secretKey(), testPayload, vendorId);
            // Block for up to 10 seconds to return synchronous result to the UI
            return future.get(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("[WEBHOOK] Test ping failed for vendor {}: {}", vendorId, e.getMessage());
            return new DeliveryResult(false, 0, e.getMessage(), 0);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────

    /**
     * Resolves the vendor ID for a given user ID by looking up the vendors table.
     */
    private String resolveVendorId(String userId) {
        return vendorRepository.findByOwnerUserId(userId)
            .map(VendorRecord::vendorId)
            .orElseThrow(() -> new IllegalArgumentException("No vendor organization found for user: " + userId));
    }

    /**
     * Masks a secret key for safe display, e.g. "my-shared-hmac-secret" → "my-s••••cret"
     */
    static String maskSecret(String secret) {
        if (secret == null || secret.isBlank()) return null;
        int len = secret.length();
        if (len <= 4) return "••••";
        int showStart = Math.min(4, len / 3);
        int showEnd = Math.min(3, len / 4);
        return secret.substring(0, showStart) + "••••" + secret.substring(len - showEnd);
    }

    /**
     * Validates that the provided webhook URL is a valid HTTP/HTTPS URL
     * and does not target restricted loopback or link-local cloud metadata addresses (SSRF protection).
     */
    static void validateWebhookUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("Webhook URL is required");
        }
        try {
            URI uri = URI.create(url.trim());
            String scheme = uri.getScheme();
            if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) || uri.getHost() == null) {
                throw new IllegalArgumentException("Webhook URL must be a valid HTTP or HTTPS URL with a hostname");
            }

            String host = uri.getHost().toLowerCase();
            if (isRestrictedHost(host)) {
                throw new IllegalArgumentException("Webhook URL targets a restricted internal or link-local address: " + host);
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid webhook URL format: " + e.getMessage());
        }
    }

    static boolean isRestrictedHost(String host) {
        if (host == null || host.isBlank()) return true;
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }

        if ("localhost".equals(host) || host.endsWith(".localhost") || "metadata.google.internal".equals(host)) {
            return true;
        }
        if ("127.0.0.1".equals(host) || host.startsWith("127.") || "0.0.0.0".equals(host) || "::1".equals(host)) {
            return true;
        }
        if ("169.254.169.254".equals(host) || host.startsWith("169.254.")) {
            return true;
        }
        return false;
    }
}
