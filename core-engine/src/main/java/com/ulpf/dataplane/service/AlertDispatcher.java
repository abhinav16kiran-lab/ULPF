package com.ulpf.dataplane.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ulpf.common.db.OnboardingRepository;
import com.ulpf.common.db.UserRepository;
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

        // Webhook Dispatch
        sendWebhookAlertSafely(payload);
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
