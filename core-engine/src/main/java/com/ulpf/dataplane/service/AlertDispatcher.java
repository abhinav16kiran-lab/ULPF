package com.ulpf.dataplane.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ulpf.common.db.OnboardingRepository;
import com.ulpf.common.db.UserRepository;
import com.ulpf.common.db.VendorRepository;
import com.ulpf.common.db.VendorWebhookRepository;
import com.ulpf.common.db.VendorWebhookRepository.VendorWebhookRecord;
import com.ulpf.common.db.VendorWebhookRepository.WebhookDeliveryLogRecord;
import com.ulpf.controlplane.model.Role;
import com.ulpf.controlplane.model.User;
import com.ulpf.controlplane.service.NotificationService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class AlertDispatcher {

    private static final Logger log = LoggerFactory.getLogger(AlertDispatcher.class);

    private final OnboardingRepository onboardingRepository;
    private final UserRepository userRepository;
    private NotificationService notificationService;
    private final ObjectMapper objectMapper;

    @Autowired(required = false)
    private VendorWebhookRepository vendorWebhookRepository;

    @Autowired(required = false)
    private VendorRepository vendorRepository;

    @Value("${ulpf.alert.webhook-url:}")
    private String webhookUrl;

    @Value("${ulpf.alert.webhook-secret:}")
    private String webhookSecret;

    private volatile URI validatedWebhookUri;

    private final HttpClient httpClient;
    private final AtomicLong lastWebhookWarnTime = new AtomicLong(0);
    private static final long WARN_WINDOW_MS = 60 * 1000L; // Log warning once per minute

    private final ExecutorService webhookExecutor = new ThreadPoolExecutor(
            2, 2, 0L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(100),
            r -> {
                Thread t = new Thread(r, "ulpf-alert-webhook");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.DiscardPolicy()
    );

    @Autowired
    public AlertDispatcher(
            OnboardingRepository onboardingRepository,
            @Autowired(required = false) UserRepository userRepository,
            @Autowired(required = false) NotificationService notificationService,
            @Autowired(required = false) ObjectMapper objectMapper) {
        this.onboardingRepository = onboardingRepository;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
        this.objectMapper = (objectMapper != null) ? objectMapper : new ObjectMapper();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    @PostConstruct
    public void init() {
        validateAndSetWebhookUri(this.webhookUrl);
    }

    @PreDestroy
    public void shutdown() {
        webhookExecutor.shutdown();
    }

    public void setNotificationService(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    public void setWebhookUrl(String webhookUrl) {
        this.webhookUrl = webhookUrl;
        validateAndSetWebhookUri(webhookUrl);
    }

    public void setWebhookSecret(String webhookSecret) {
        this.webhookSecret = webhookSecret;
    }

    private void validateAndSetWebhookUri(String url) {
        if (url == null || url.isBlank()) {
            this.validatedWebhookUri = null;
            return;
        }
        try {
            URI uri = URI.create(url.trim());
            String scheme = uri.getScheme();
            if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) || uri.getHost() == null) {
                log.warn("Invalid alert webhook URL scheme or format (must be http/https with host) - disabling webhook");
                this.validatedWebhookUri = null;
                return;
            }
            this.validatedWebhookUri = uri;
        } catch (Exception e) {
            log.warn("Failed to parse alert webhook URL - disabling webhook: {}", e.getMessage());
            this.validatedWebhookUri = null;
        }
    }

    private String getWebhookHostForLog() {
        return (validatedWebhookUri != null && validatedWebhookUri.getHost() != null)
                ? validatedWebhookUri.getHost()
                : "webhook-host";
    }

    // ── Decoupled Delivery Result Record ─────────────────────────────

    public record DeliveryResult(boolean success, int statusCode, String errorMessage, long latencyMs) {}

    // ── Phase 2: Decoupled Webhook Sender ────────────────────────────

    /**
     * Pure decoupled method: sends an alert to ANY valid URL with ANY secret key,
     * completely independent of .env.
     */
    public CompletableFuture<DeliveryResult> sendWebhook(
            URI targetUri,
            String secret,
            AlertPayload payload,
            String vendorId) {

        if (targetUri == null || payload == null) {
            return CompletableFuture.completedFuture(new DeliveryResult(false, 0, "Null URI or payload", 0));
        }

        return CompletableFuture.supplyAsync(() -> {
            long start = System.currentTimeMillis();
            try {
                String jsonBody = objectMapper.writeValueAsString(payload);
                HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                        .uri(targetUri)
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(3))
                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));

                if (secret != null && !secret.isBlank()) {
                    long ts = Instant.now().getEpochSecond();
                    String signature = computeHmacSha256(ts + "." + jsonBody, secret);
                    if (signature != null) {
                        reqBuilder.header("X-ULPF-Timestamp", String.valueOf(ts));
                        reqBuilder.header("X-ULPF-Signature", "sha256=" + signature);
                    }
                }

                HttpRequest request = reqBuilder.build();
                int statusCode = executeWithStatusCode(request);
                long latency = System.currentTimeMillis() - start;
                boolean success = (statusCode >= 200 && statusCode < 300);

                // Record delivery log in SQLite
                logDeliveryRecord(vendorId, targetUri.toString(), payload.alertType(), statusCode, success, null, (int) latency);

                // Handle failure & notify vendor inbox if vendor webhook failed
                handleVendorDeliveryOutcome(vendorId, targetUri.toString(), success, statusCode);

                log.info("[WEBHOOK] Dispatched {} to {} -> Status {} ({}ms)", payload.alertType(), targetUri.getHost(), statusCode, latency);
                return new DeliveryResult(success, statusCode, null, latency);

            } catch (Exception e) {
                long latency = System.currentTimeMillis() - start;
                logDeliveryRecord(vendorId, targetUri.toString(), payload.alertType(), 0, false, e.getMessage(), (int) latency);
                handleVendorDeliveryOutcome(vendorId, targetUri.toString(), false, 0);

                log.warn("[WEBHOOK ERROR] Failed to deliver to {}: {}", targetUri.getHost(), e.getMessage());
                return new DeliveryResult(false, 0, e.getMessage(), latency);
            }
        }, webhookExecutor);
    }

    /**
     * Executes the HTTP request and returns the status code. Retries once on failure.
     */
    private int executeWithStatusCode(HttpRequest request) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
                return response.statusCode();
            } catch (Exception e) {
                if (attempt == 1) {
                    throw new RuntimeException("HTTP request failed after 1 retry: " + e.getMessage(), e);
                }
            }
        }
        return 0;
    }

    /**
     * Logs a delivery attempt to the webhook_delivery_logs table.
     */
    private void logDeliveryRecord(String vendorId, String targetUrl, String alertType,
                                   int statusCode, boolean success, String errorMessage, int latencyMs) {
        try {
            if (vendorWebhookRepository != null) {
                vendorWebhookRepository.logDelivery(new WebhookDeliveryLogRecord(
                    null, vendorId, targetUrl, alertType,
                    statusCode > 0 ? statusCode : null,
                    success, errorMessage,
                    latencyMs, null
                ));
            }
        } catch (Exception e) {
            log.warn("[WEBHOOK] Failed to persist delivery log: {}", e.getMessage());
        }
    }

    /**
     * Handles vendor webhook delivery outcome: updates webhook health status,
     * and sends in-app notifications when the webhook endpoint is broken.
     */
    private void handleVendorDeliveryOutcome(String vendorId, String url, boolean success, int statusCode) {
        if (vendorId == null || vendorWebhookRepository == null) {
            return; // Global admin webhook or no repository available
        }

        try {
            var webhookOpt = vendorWebhookRepository.findByVendorId(vendorId);
            if (webhookOpt.isEmpty()) return;

            VendorWebhookRecord webhook = webhookOpt.get();

            if (success) {
                // Reset consecutive fails and mark HEALTHY
                vendorWebhookRepository.updateDeliveryStatus(vendorId, true, statusCode, 0);
            } else {
                int newFails = webhook.consecutiveFails() + 1;
                vendorWebhookRepository.updateDeliveryStatus(vendorId, false, statusCode, newFails);

                // Send in-app notification on first failure or every 5th consecutive failure
                if (newFails == 1 || newFails % 5 == 0) {
                    String ownerUserId = resolveVendorOwnerUserId(vendorId);
                    if (ownerUserId != null) {
                        String title = "⚠️ Webhook Endpoint Failing";
                        String message = "Your webhook to " + url + " returned HTTP " + statusCode
                            + ". Please check your endpoint settings in Notifications > Webhook Integration.";
                        saveNotificationSafely(ownerUserId, title, message);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[WEBHOOK] Failed to update vendor delivery outcome for {}: {}", vendorId, e.getMessage());
        }
    }

    /**
     * Resolves the owner user ID for a given vendor ID.
     */
    private String resolveVendorOwnerUserId(String vendorId) {
        if (vendorRepository != null) {
            try {
                return vendorRepository.findById(vendorId)
                    .map(VendorRepository.VendorRecord::ownerUserId)
                    .orElse(null);
            } catch (Exception e) {
                log.warn("Failed to resolve vendor owner for {}: {}", vendorId, e.getMessage());
            }
        }
        return null;
    }

    // ── Multi-Tenant Alert Dispatch ──────────────────────────────────

    /**
     * Dispatches an alert to all administrators and optionally the vendor owner,
     * followed by asynchronous webhook transmission.
     */
    public void dispatchAlert(
            AlertPayload payload,
            String adminTitle,
            String adminMessage,
            String vendorOwnerUserId,
            String vendorTitle,
            String vendorMessage) {
        dispatchAlert(payload, adminTitle, adminMessage, vendorOwnerUserId, vendorTitle, vendorMessage, false);
    }

    public void dispatchAlert(
            AlertPayload payload,
            String adminTitle,
            String adminMessage,
            String vendorOwnerUserId,
            String vendorTitle,
            String vendorMessage,
            boolean vendorFirst) {

        if (vendorFirst) {
            if (vendorOwnerUserId != null && vendorTitle != null && vendorMessage != null) {
                saveNotificationSafely(vendorOwnerUserId, vendorTitle, vendorMessage);
            }
            List<String> adminUserIds = resolveAdminUserIds();
            for (String adminId : adminUserIds) {
                if (!adminId.equals(vendorOwnerUserId)) {
                    saveNotificationSafely(adminId, adminTitle, adminMessage);
                }
            }
        } else {
            List<String> adminUserIds = resolveAdminUserIds();
            for (String adminId : adminUserIds) {
                if (vendorOwnerUserId != null && adminId.equals(vendorOwnerUserId)) {
                    continue;
                }
                saveNotificationSafely(adminId, adminTitle, adminMessage);
            }
            if (vendorOwnerUserId != null && vendorTitle != null && vendorMessage != null) {
                saveNotificationSafely(vendorOwnerUserId, vendorTitle, vendorMessage);
            }
        }

        // 1. Dispatch Global Admin Webhook (from .env)
        if (this.validatedWebhookUri == null && webhookUrl != null && !webhookUrl.isBlank()) {
            validateAndSetWebhookUri(webhookUrl);
        }
        if (this.validatedWebhookUri != null) {
            sendWebhook(this.validatedWebhookUri, this.webhookSecret, payload, null);
        }

        // 2. Dispatch Specific Vendor Webhook (from SQLite vendor_webhooks)
        if (payload.vendorId() != null && vendorWebhookRepository != null) {
            vendorWebhookRepository.findByVendorId(payload.vendorId())
                .filter(VendorWebhookRecord::isActive)
                .ifPresent(vw -> {
                    try {
                        URI uri = URI.create(vw.webhookUrl());
                        sendWebhook(uri, vw.secretKey(), payload, vw.vendorId());
                    } catch (Exception e) {
                        log.warn("Invalid vendor webhook URI for vendor {}: {}", vw.vendorId(), e.getMessage());
                    }
                });
        }
    }

    /**
     * Dispatches notification only to platform administrators and triggers webhook.
     */
    public void dispatchToAdmins(AlertPayload payload, String adminTitle, String adminMessage) {
        dispatchAlert(payload, adminTitle, adminMessage, null, null, null);
    }

    public void saveNotificationSafely(String userId, String title, String message) {
        try {
            if (notificationService != null) {
                notificationService.createNotification(userId, title, message);
            } else if (onboardingRepository != null) {
                onboardingRepository.saveNotification(userId, title, message);
            }
            log.info("Dispatched notification to user {}: {}", userId, title);
        } catch (Exception e) {
            log.warn("Failed to persist notification for user {}: {}", userId, e.getMessage());
        }
    }

    public List<String> resolveAdminUserIds() {
        if (userRepository != null) {
            try {
                List<User> admins = userRepository.findUsersByRole(Role.ADMIN);
                if (admins != null && !admins.isEmpty()) {
                    return admins.stream().map(User::userId).toList();
                }
            } catch (Exception e) {
                log.warn("Failed to lookup admin users: {}", e.getMessage());
            }
        }
        return List.of("admin");
    }

    // ── Legacy sendWebhookAlertSafely (preserved for backward compat) ──

    /**
     * Asynchronously and safely transmits the alert payload to the configured webhook endpoint.
     * Uses a bounded thread pool with DiscardPolicy to drop excess events during alert storms.
     * Retries once on failure, enforces 3s timeout, and signs payload (with timestamp) if secret is set.
     */
    public CompletableFuture<Void> sendWebhookAlertSafely(AlertPayload payload) {
        if (this.validatedWebhookUri == null && webhookUrl != null && !webhookUrl.isBlank()) {
            validateAndSetWebhookUri(webhookUrl);
        }
        final URI targetUri = this.validatedWebhookUri;
        if (targetUri == null || payload == null) {
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.runAsync(() -> {
            try {
                String jsonBody = objectMapper.writeValueAsString(payload);
                HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                        .uri(targetUri)
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(3))
                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));

                if (webhookSecret != null && !webhookSecret.isBlank()) {
                    long ts = Instant.now().getEpochSecond();
                    String signature = computeHmacSha256(ts + "." + jsonBody, webhookSecret);
                    if (signature != null) {
                        reqBuilder.header("X-ULPF-Timestamp", String.valueOf(ts));
                        reqBuilder.header("X-ULPF-Signature", "sha256=" + signature);
                    }
                }

                HttpRequest request = reqBuilder.build();
                boolean delivered = executeWithOneRetry(request);
                if (!delivered) {
                    logFailureOncePerWindow("Webhook delivery failed after 1 retry to host " + getWebhookHostForLog());
                }
            } catch (Exception e) {
                logFailureOncePerWindow("Failed to dispatch alert webhook to host " + getWebhookHostForLog() + ": " + e.getMessage());
            }
        }, webhookExecutor);
    }

    private boolean executeWithOneRetry(HttpRequest request) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    return true;
                }
            } catch (Exception ignored) {
                if (attempt == 1) {
                    return false;
                }
            }
        }
        return false;
    }

    private void logFailureOncePerWindow(String warningMessage) {
        long now = System.currentTimeMillis();
        long last = lastWebhookWarnTime.get();
        if ((now - last) >= WARN_WINDOW_MS && lastWebhookWarnTime.compareAndSet(last, now)) {
            log.warn("{}", warningMessage);
        } else {
            log.debug("{}", warningMessage);
        }
    }

    public static String computeHmacSha256(String data, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKey = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKey);
            byte[] rawHmac = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : rawHmac) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            log.warn("Could not compute HMAC signature: {}", e.getMessage());
            return null;
        }
    }
}
