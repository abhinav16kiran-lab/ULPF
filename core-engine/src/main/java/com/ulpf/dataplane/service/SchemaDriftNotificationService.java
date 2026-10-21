package com.ulpf.dataplane.service;

import com.ulpf.common.db.OnboardingRepository;
import com.ulpf.common.db.SourceRepository;
import com.ulpf.common.db.SourceRepository.SourceRecord;
import com.ulpf.common.db.UserRepository;
import com.ulpf.common.db.VendorRepository;
import com.ulpf.common.db.VendorRepository.VendorRecord;
import com.ulpf.controlplane.model.Role;
import com.ulpf.controlplane.model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for detecting live log schema drift and notifying vendors and admins
 * with role-tailored messages.
 * Features in-memory deduplication caching with a 15-minute TTL per source and
 * key set.
 */
@Service
public class SchemaDriftNotificationService {

    private static final Logger log = LoggerFactory.getLogger(SchemaDriftNotificationService.class);
    @org.springframework.beans.factory.annotation.Value("${ulpf.schema-drift.cooldown-ms:3600000}")
    private long cooldownMs = 3600000L; // Default 1 Hour (configurable via ulpf.schema-drift.cooldown-ms)

    private final SourceRepository sourceRepository;
    private final VendorRepository vendorRepository;
    private final OnboardingRepository onboardingRepository;
    private final UserRepository userRepository;

    private final Map<String, Long> lastNotifiedCache = new ConcurrentHashMap<>();
    private final Set<String> notifiedHistoricalFallbacks = ConcurrentHashMap.newKeySet();

    public SchemaDriftNotificationService(
            SourceRepository sourceRepository,
            VendorRepository vendorRepository,
            OnboardingRepository onboardingRepository,
            @org.springframework.beans.factory.annotation.Autowired(required = false) UserRepository userRepository) {
        this.sourceRepository = sourceRepository;
        this.vendorRepository = vendorRepository;
        this.onboardingRepository = onboardingRepository;
        this.userRepository = userRepository;
    }

    private com.ulpf.controlplane.service.NotificationService notificationService;

    @org.springframework.beans.factory.annotation.Value("${ulpf.alert.webhook-url:}")
    private String webhookUrl;

    public void setNotificationService(com.ulpf.controlplane.service.NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    public void setWebhookUrl(String webhookUrl) {
        this.webhookUrl = webhookUrl;
    }

    public void setCooldownMs(long cooldownMs) {
        this.cooldownMs = cooldownMs;
    }

    public long getCooldownMs() {
        return this.cooldownMs;
    }

    /**
     * Inspects unmapped keys from a live log event and dispatches role-tailored
     * notifications to vendor and admin.
     * Deduplicates alerts per sourceId and key set within a cooldown window.
     */
    public void checkAndNotifyDrift(String sourceId, Set<String> unmappedKeys) {
        checkAndNotifyDrift(sourceId, unmappedKeys, unmappedKeys != null ? unmappedKeys.size() : 0, false);
    }

    /**
     * Overloaded method with total drift detection and unmapped ratio threshold support.
     */
    public void checkAndNotifyDrift(String sourceId, Set<String> unmappedKeys, int totalKeys, boolean isTotalDrift) {
        if (sourceId == null || unmappedKeys == null || unmappedKeys.isEmpty()) {
            return;
        }

        List<String> sortedKeys = new ArrayList<>(unmappedKeys);
        Collections.sort(sortedKeys);

        String cacheKey = sourceId + ":" + (isTotalDrift ? "TOTAL_DRIFT:" : "") + String.join(",", sortedKeys);
        long now = System.currentTimeMillis();
        java.util.concurrent.atomic.AtomicBoolean shouldNotify = new java.util.concurrent.atomic.AtomicBoolean(false);

        lastNotifiedCache.compute(cacheKey, (k, prev) -> {
            if (prev != null && (now - prev) < cooldownMs) {
                return prev;
            }
            shouldNotify.set(true);
            return now;
        });

        if (!shouldNotify.get()) {
            log.debug("Schema drift notification suppressed (cooldown active) for key: {}", cacheKey);
            return;
        }

        String vendorName = "Vendor";
        String vendorOwnerUserId = null;

        try {
            Optional<SourceRecord> sourceOpt = sourceRepository.findById(sourceId);
            if (sourceOpt.isPresent()) {
                Optional<VendorRecord> vendorOpt = vendorRepository.findById(sourceOpt.get().vendorId());
                if (vendorOpt.isPresent()) {
                    vendorName = vendorOpt.get().vendorName();
                    vendorOwnerUserId = vendorOpt.get().ownerUserId();
                }
            }
        } catch (Exception e) {
            log.warn("Could not resolve vendor info for source {}: {}", sourceId, e.getMessage());
        }

        int driftPercent = totalKeys > 0 ? (int) Math.round(((double) sortedKeys.size() / totalKeys) * 100) : 0;
        java.time.Instant driftInstant = java.time.Instant.now();

        String vendorTitle;
        String vendorMessage;
        String adminTitle;
        String adminMessage;

        if (isTotalDrift) {
            vendorTitle = "[CRITICAL] Total Schema Drift: " + sourceId;
            vendorMessage = String.format(
                    "All payload fields %s for source %s failed to match your approved schema mapping (detected at: %s). " +
                            "All analytical table columns are currently NULL. Logs are safely preserved in raw_unmapped JSON. " +
                            "Please contact your ULPF administrator or update your mapping schema. " +
                            "Once updated, administrators can trigger Replay via POST /v1/admin/sources/%s/replay to backfill.",
                    sortedKeys, sourceId, driftInstant, sourceId);

            adminTitle = "[CRITICAL ACTION REQUIRED] Total Schema Drift: " + sourceId;
            adminMessage = String.format(
                    "100%% of payload fields %s for source %s (Vendor: %s) failed to match the approved schema mapping (detected at: %s)! " +
                            "All analytical table columns are currently NULL. Logs are preserved in raw_unmapped JSON. " +
                            "Action: Update the mapping schema at /admin and trigger Replay via POST /v1/admin/sources/%s/replay to backfill.",
                    sortedKeys, sourceId, vendorName, driftInstant, sourceId);
        } else if (driftPercent > 30 || (totalKeys > 0 && ((double) sortedKeys.size() / totalKeys) > 0.30)) {
            vendorTitle = String.format("Schema Drift Alert (%d%%): %s", driftPercent, sourceId);
            vendorMessage = String.format(
                    "%d%% of vendor payload fields %s in source %s were unmapped (detected at: %s). " +
                            "The fields are safely preserved in raw_unmapped JSON storage without data loss. " +
                            "If you wish to promote these fields to canonical columns, please contact your ULPF Administrator.",
                    driftPercent, sortedKeys, sourceId, driftInstant);

            adminTitle = String.format("[ADMIN ACTION REQUIRED] High Schema Drift (%d%%): %s", driftPercent, sourceId);
            adminMessage = String.format(
                    "Source %s (Vendor: %s) exceeded the schema drift threshold with %d%% of fields unmapped (%s, detected at: %s). " +
                            "Fields are preserved in raw_unmapped JSON. Inspect candidate mappings at /admin, and trigger Replay via POST /v1/admin/sources/%s/replay to backfill once promoted.",
                    sourceId, vendorName, driftPercent, sortedKeys, driftInstant, sourceId);
        } else {
            vendorTitle = "Schema Drift Alert: " + sourceId;
            vendorMessage = String.format(
                    "New unmapped vendor payload fields %s were detected in your live log stream for source %s (detected at: %s). " +
                            "The fields are safely preserved in raw_unmapped JSON storage without data loss. " +
                            "If you wish to promote these fields to canonical columns, please contact your ULPF Administrator.",
                    sortedKeys, sourceId, driftInstant);

            adminTitle = "[ADMIN ACTION REQUIRED] Schema Drift: " + sourceId;
            adminMessage = String.format(
                    "Source %s (Vendor: %s) sent new unmapped fields %s in live ingestion (detected at: %s). " +
                            "Fields are preserved in raw_unmapped JSON. Open the Admin Control Panel (/admin) to inspect candidate mappings and promote fields.",
                    sourceId, vendorName, sortedKeys, driftInstant);
        }

        // 1. Dispatch to Vendor Owner (if found)
        if (vendorOwnerUserId != null) {
            saveNotificationSafely(vendorOwnerUserId, vendorTitle, vendorMessage);
        }

        // 2. Dispatch to System Admins
        List<String> adminUserIds = resolveAdminUserIds();
        for (String adminId : adminUserIds) {
            if (!adminId.equals(vendorOwnerUserId)) { // Avoid duplicate notification if owner is admin
                saveNotificationSafely(adminId, adminTitle, adminMessage);
            }
        }

        sendWebhookAlertSafely(adminTitle, adminMessage, sourceId, isTotalDrift ? "TOTAL_DRIFT" : "SCHEMA_DRIFT");
    }

    public boolean isDriftAlertThrottled(String sourceId, Set<String> unmappedKeys) {
        if (sourceId == null || unmappedKeys == null || unmappedKeys.isEmpty()) return false;
        List<String> sortedKeys = new ArrayList<>(unmappedKeys);
        Collections.sort(sortedKeys);
        String cacheKey = sourceId + ":" + String.join(",", sortedKeys);
        Long lastNotified = lastNotifiedCache.get(cacheKey);
        return lastNotified != null && (System.currentTimeMillis() - lastNotified) < cooldownMs;
    }

    /**
     * Dispatches a notification to the vendor owner when an incoming log stream falls back
     * to a historical mapping version.
     * Deduplicates alerts PERMANENTLY per sourceId, historical version, and fallback keys (sent exactly ONCE ever).
     */
    public void notifyHistoricalFallback(String sourceId, int historicalVersion, Set<String> fallbackKeys) {
        if (sourceId == null || fallbackKeys == null || fallbackKeys.isEmpty()) {
            return;
        }

        List<String> sortedKeys = new ArrayList<>(fallbackKeys);
        Collections.sort(sortedKeys);

        String cacheKey = sourceId + ":v" + historicalVersion + ":" + String.join(",", sortedKeys);

        // Permanently check & register notification state to ensure it is sent ONLY ONCE
        if (!notifiedHistoricalFallbacks.add(cacheKey)) {
            log.debug("Historical version fallback notification suppressed (already notified once) for key: {}", cacheKey);
            return;
        }

        String vendorOwnerUserId = null;

        try {
            Optional<SourceRecord> sourceOpt = sourceRepository.findById(sourceId);
            if (sourceOpt.isPresent()) {
                Optional<VendorRecord> vendorOpt = vendorRepository.findById(sourceOpt.get().vendorId());
                if (vendorOpt.isPresent()) {
                    vendorOwnerUserId = vendorOpt.get().ownerUserId();
                }
            }
        } catch (Exception e) {
            log.warn("Could not resolve vendor owner for historical version fallback alert (source {}): {}", sourceId, e.getMessage());
        }

        if (vendorOwnerUserId != null) {
            String title = "Schema Version Fallback Alert: " + sourceId;
            String message = String.format(
                    "Log payload fields %s for source %s fell back to historical mapping version v%d during live ingestion. " +
                    "To prevent schema version mismatch, please update your log stream format to match the active mapping version.",
                    sortedKeys, sourceId, historicalVersion
            );
            saveNotificationSafely(vendorOwnerUserId, title, message);
        }
    }

    public boolean hasFallbackAlert(String sourceId) {
        if (sourceId == null) return false;
        return notifiedHistoricalFallbacks.stream().anyMatch(key -> key.startsWith(sourceId + ":"));
    }

    /**
     * Resets historical fallback notification locks for a source when live logs resume 100% active version compliance,
     * and dispatches an informative upgrade notification to the vendor owner.
     */
    public void notifyVersionUpgrade(String sourceId, int activeVersion) {
        if (sourceId == null) {
            return;
        }

        boolean hadFallbackAlert = notifiedHistoricalFallbacks.removeIf(key -> key.startsWith(sourceId + ":"));

        if (hadFallbackAlert) {
            String vendorOwnerUserId = null;

            try {
                Optional<SourceRecord> sourceOpt = sourceRepository.findById(sourceId);
                if (sourceOpt.isPresent()) {
                    Optional<VendorRecord> vendorOpt = vendorRepository.findById(sourceOpt.get().vendorId());
                    if (vendorOpt.isPresent()) {
                        vendorOwnerUserId = vendorOpt.get().ownerUserId();
                    }
                }
            } catch (Exception e) {
                log.warn("Could not resolve vendor owner for version upgrade alert (source {}): {}", sourceId, e.getMessage());
            }

            if (vendorOwnerUserId != null) {
                String title = "Schema Version Resumed: " + sourceId;
                String message = String.format(
                        "Live log stream for source %s has successfully resumed using active mapping version v%d. " +
                        "Historical version fallback alerts have been reset.",
                        sourceId, activeVersion
                );
                saveNotificationSafely(vendorOwnerUserId, title, message);
            }
        }
    }

    private List<String> resolveAdminUserIds() {
        if (userRepository != null) {
            try {
                List<User> admins = userRepository.findUsersByRole(Role.ADMIN);
                if (!admins.isEmpty()) {
                    return admins.stream().map(User::userId).toList();
                }
            } catch (Exception e) {
                log.warn("Failed to lookup admin users: {}", e.getMessage());
            }
        }
        return List.of("admin"); // Default fallback admin user ID
    }

    private void saveNotificationSafely(String userId, String title, String message) {
        try {
            if (notificationService != null) {
                notificationService.createNotification(userId, title, message);
            } else if (onboardingRepository != null) {
                onboardingRepository.saveNotification(userId, title, message);
            }
            log.info("Dispatched Schema Drift Notification to user {}: {}", userId, title);
        } catch (Exception e) {
            log.warn("Failed to persist notification for user {}: {}", userId, e.getMessage());
        }
    }

    private void sendWebhookAlertSafely(String title, String message, String sourceId, String alertType) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            return;
        }
        try {
            log.info("Dispatching webhook drift alert to [{}]: type={}, sourceId={}, title={}", webhookUrl, alertType, sourceId, title);
            java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
            String payload = String.format("{\"alertType\":\"%s\",\"sourceId\":\"%s\",\"title\":\"%s\",\"timestamp\":\"%s\"}",
                    alertType, sourceId, title.replace("\"", "\\\""), java.time.Instant.now().toString());
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(webhookUrl))
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(payload))
                    .timeout(java.time.Duration.ofSeconds(3))
                    .build();
            client.sendAsync(request, java.net.http.HttpResponse.BodyHandlers.discarding())
                    .exceptionally(ex -> {
                        log.debug("Webhook drift alert delivery failed to {}: {}", webhookUrl, ex.getMessage());
                        return null;
                    });
        } catch (Exception e) {
            log.debug("Could not dispatch webhook drift alert: {}", e.getMessage());
        }
    }

    public void clearCache() {
        lastNotifiedCache.clear();
        notifiedHistoricalFallbacks.clear();
    }
}
