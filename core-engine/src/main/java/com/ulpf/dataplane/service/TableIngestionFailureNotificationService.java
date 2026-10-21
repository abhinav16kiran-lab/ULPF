package com.ulpf.dataplane.service;

import com.ulpf.common.db.OnboardingRepository;
import com.ulpf.common.db.SourceRepository;
import com.ulpf.common.db.SourceRepository.SourceRecord;
import com.ulpf.common.db.UserRepository;
import com.ulpf.common.db.VendorRepository;
import com.ulpf.common.db.VendorRepository.VendorRecord;
import com.ulpf.controlplane.model.Role;
import com.ulpf.controlplane.model.User;
import com.ulpf.controlplane.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Service for detecting dynamic table ingestion failures (missing dynamic tables,
 * ClickHouse write exceptions, missing target_table metadata, missing mappings,
 * and missing table columns) and dispatching actionable notifications to system
 * administrators and vendor owners via NotificationService and optional Webhooks.
 *
 * Implements sliding-window throttling (default: 30 minutes) to prevent notification
 * storms during high-throughput log ingestion, with atomic accumulators to track
 * dropped/affected event counts and periodic summary digest dispatches.
 */
@Service
public class TableIngestionFailureNotificationService {

    private static final Logger log = LoggerFactory.getLogger(TableIngestionFailureNotificationService.class);

    @Value("${ulpf.table-failure.cooldown-ms:1800000}")
    private long cooldownMs = 1800000L; // Default: 30 Minutes

    @Value("${ulpf.alert.webhook-url:}")
    private String webhookUrl;

    private final SourceRepository sourceRepository;
    private final VendorRepository vendorRepository;
    private final OnboardingRepository onboardingRepository;
    private final UserRepository userRepository;
    private NotificationService notificationService;

    public record ThrottleEntry(
            long firstAlertTime,
            Instant firstAlertInstant,
            int initialAlertCount,
            AtomicLong accumulatedCount,
            String lastDetails
    ) {}

    // In-memory throttling cache: (sourceId + ":" + failureType) -> ThrottleEntry
    private final Map<String, ThrottleEntry> throttleCache = new ConcurrentHashMap<>();

    public TableIngestionFailureNotificationService(
            SourceRepository sourceRepository,
            VendorRepository vendorRepository,
            OnboardingRepository onboardingRepository,
            @Autowired(required = false) UserRepository userRepository) {
        this.sourceRepository = sourceRepository;
        this.vendorRepository = vendorRepository;
        this.onboardingRepository = onboardingRepository;
        this.userRepository = userRepository;
    }

    @Autowired(required = false)
    public void setNotificationService(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    public void setCooldownMs(long cooldownMs) {
        this.cooldownMs = cooldownMs;
    }

    public long getCooldownMs() {
        return this.cooldownMs;
    }

    public void setWebhookUrl(String webhookUrl) {
        this.webhookUrl = webhookUrl;
    }

    /**
     * Notifies admin and vendor when an analytical dynamic table does not exist or has no columns in ClickHouse.
     */
    public void notifyTableMissing(String sourceId, String tableName, int eventCount) {
        if (sourceId == null || eventCount <= 0) return;

        String failureType = "TABLE_MISSING";
        String cacheKey = sourceId + ":" + failureType;
        long now = System.currentTimeMillis();

        ThrottleEntry existing = throttleCache.get(cacheKey);
        if (existing != null && (now - existing.firstAlertTime()) < cooldownMs) {
            existing.accumulatedCount().addAndGet(eventCount);
            log.debug("Table missing alert suppressed (cooldown active) for source {}. Total accumulated: {}",
                    sourceId, existing.accumulatedCount().get());
            return;
        }

        if (existing != null && existing.accumulatedCount().get() > existing.initialAlertCount()) {
            sendDigestNotification(sourceId, failureType, tableName, existing.accumulatedCount().get(), existing.accumulatedCount().get() - existing.initialAlertCount());
        }

        Instant alertInstant = Instant.now();
        throttleCache.put(cacheKey, new ThrottleEntry(now, alertInstant, eventCount, new AtomicLong(eventCount), tableName));

        SourceMeta meta = resolveSourceMeta(sourceId);

        String adminTitle = "[CRITICAL] Dynamic Table Missing: " + meta.sourceName();
        String adminMessage = String.format(
                "Dynamic table 'ulpf_events.%s' for source '%s' (ID: %s, Vendor: %s) does not exist in ClickHouse. " +
                        "%d event(s) could not be loaded into analytical storage. " +
                        "Events are safely preserved in 'ulpf_raw.raw_events' (started at: %s). " +
                        "Action: Verify table schema or provision table, then trigger Replay via POST /v1/admin/sources/%s/replay to backfill.",
                tableName, meta.sourceName(), sourceId, meta.vendorName(), eventCount, alertInstant, sourceId);

        dispatchToAdminAndVendor(meta, adminTitle, adminMessage,
                "Ingestion Alert: Dynamic Table Missing for " + meta.sourceName(),
                String.format("Logs for source '%s' could not be loaded into dynamic analytics because table 'ulpf_events.%s' is missing. " +
                        "Your data is safely preserved in raw storage. Platform administrators have been notified to re-provision the table.",
                        meta.sourceName(), tableName));
        sendWebhookAlertSafely(adminTitle, adminMessage, sourceId, failureType);
    }

    /**
     * Notifies admin and vendor when a ClickHouse dynamic table batch insert fails due to an exception.
     */
    public void notifyInsertFailure(String sourceId, String tableName, int eventCount, String errorMessage) {
        if (sourceId == null || eventCount <= 0) return;

        String failureType = "INSERT_FAILURE";
        String cacheKey = sourceId + ":" + failureType;
        long now = System.currentTimeMillis();

        ThrottleEntry existing = throttleCache.get(cacheKey);
        if (existing != null && (now - existing.firstAlertTime()) < cooldownMs) {
            existing.accumulatedCount().addAndGet(eventCount);
            log.debug("Dynamic insert failure alert suppressed (cooldown active) for source {}. Total accumulated: {}",
                    sourceId, existing.accumulatedCount().get());
            return;
        }

        if (existing != null && existing.accumulatedCount().get() > existing.initialAlertCount()) {
            sendDigestNotification(sourceId, failureType, tableName, existing.accumulatedCount().get(), existing.accumulatedCount().get() - existing.initialAlertCount());
        }

        Instant alertInstant = Instant.now();
        throttleCache.put(cacheKey, new ThrottleEntry(now, alertInstant, eventCount, new AtomicLong(eventCount), errorMessage));

        SourceMeta meta = resolveSourceMeta(sourceId);

        String adminTitle = "[INGESTION FAILURE] Dynamic Table Insert Failed: " + meta.sourceName();
        String adminMessage = String.format(
                "Failed to insert batch of %d event(s) into dynamic table 'ulpf_events.%s' for source '%s' (ID: %s). " +
                        "Error: %s. Events are safely preserved in 'ulpf_raw.raw_events' (started at: %s). " +
                        "Action: Once database availability is restored, trigger Replay via POST /v1/admin/sources/%s/replay to recover.",
                eventCount, tableName, meta.sourceName(), sourceId, errorMessage != null ? errorMessage : "Unknown error", alertInstant, sourceId);

        dispatchToAdminAndVendor(meta, adminTitle, adminMessage,
                "Ingestion Alert: Database Write Issue for " + meta.sourceName(),
                String.format("A temporary database write issue occurred while inserting %d event(s) for source '%s'. " +
                        "Your events are safely recorded in raw storage and will be backfilled once database stability is confirmed.",
                        eventCount, meta.sourceName()));
        sendWebhookAlertSafely(adminTitle, adminMessage, sourceId, failureType);
    }

    /**
     * Notifies admin and vendor when an active source emits events but has no target_table defined in mapping metadata.
     */
    public void notifyNoTargetTable(String sourceId, int eventCount) {
        if (sourceId == null || eventCount <= 0) return;

        String failureType = "NO_TARGET_TABLE";
        String cacheKey = sourceId + ":" + failureType;
        long now = System.currentTimeMillis();

        ThrottleEntry existing = throttleCache.get(cacheKey);
        if (existing != null && (now - existing.firstAlertTime()) < cooldownMs) {
            existing.accumulatedCount().addAndGet(eventCount);
            return;
        }

        if (existing != null && existing.accumulatedCount().get() > existing.initialAlertCount()) {
            sendDigestNotification(sourceId, failureType, "NO_TARGET_TABLE", existing.accumulatedCount().get(), existing.accumulatedCount().get() - existing.initialAlertCount());
        }

        Instant alertInstant = Instant.now();
        throttleCache.put(cacheKey, new ThrottleEntry(now, alertInstant, eventCount, new AtomicLong(eventCount), "NO_TARGET_TABLE"));

        SourceMeta meta = resolveSourceMeta(sourceId);

        String adminTitle = "[CONFIGURATION REQUIRED] No Target Table Defined: " + meta.sourceName();
        String adminMessage = String.format(
                "Source '%s' (ID: %s, Vendor: %s) has an active mapping without 'target_table' in its metadata. " +
                        "%d event(s) bypassed dynamic table insertion (detected at: %s). " +
                        "Events are safely stored in 'ulpf_raw.raw_events'. " +
                        "Action: Update the active mapping metadata with a valid target_table and trigger Replay via POST /v1/admin/sources/%s/replay.",
                meta.sourceName(), sourceId, meta.vendorName(), eventCount, alertInstant, sourceId);

        dispatchToAdminAndVendor(meta, adminTitle, adminMessage,
                "Configuration Notice for " + meta.sourceName(),
                String.format("Log events for source '%s' are currently not routed to an analytical table because no target table is configured in your mapping metadata. " +
                        "Events are safely preserved in raw storage.", meta.sourceName()));
        sendWebhookAlertSafely(adminTitle, adminMessage, sourceId, failureType);
    }

    /**
     * Notifies admin and vendor when an incoming stream is emitting events but has no approved active mapping.
     */
    public void notifyMissingMapping(String sourceId, int eventCount) {
        if (sourceId == null || eventCount <= 0) return;

        String failureType = "MAPPING_MISSING";
        String cacheKey = sourceId + ":" + failureType;
        long now = System.currentTimeMillis();

        ThrottleEntry existing = throttleCache.get(cacheKey);
        if (existing != null && (now - existing.firstAlertTime()) < cooldownMs) {
            existing.accumulatedCount().addAndGet(eventCount);
            return;
        }

        if (existing != null && existing.accumulatedCount().get() > existing.initialAlertCount()) {
            sendDigestNotification(sourceId, failureType, "MAPPING_MISSING", existing.accumulatedCount().get(), existing.accumulatedCount().get() - existing.initialAlertCount());
        }

        Instant alertInstant = Instant.now();
        throttleCache.put(cacheKey, new ThrottleEntry(now, alertInstant, eventCount, new AtomicLong(eventCount), "MAPPING_MISSING"));

        SourceMeta meta = resolveSourceMeta(sourceId);

        String adminTitle = "[ONBOARDING REQUIRED] Missing Schema Mapping: " + meta.sourceName();
        String adminMessage = String.format(
                "Source '%s' (ID: %s, Vendor: %s) is emitting live logs but has no approved schema mapping in ULPF. " +
                        "%d event(s) bypassed dynamic analytics tables and are safely archived in 'ulpf_raw.raw_events' (detected at: %s). " +
                        "Action: Review candidate mapping in Admin Portal (/admin) and approve schema. Once approved, trigger Replay via POST /v1/admin/sources/%s/replay.",
                meta.sourceName(), sourceId, meta.vendorName(), eventCount, alertInstant, sourceId);

        String vendorTitle = "Onboarding Notice: Schema Pending Approval for " + meta.sourceName();
        String vendorMessage = String.format(
                "Log events for source '%s' have arrived, but your schema mapping is awaiting platform administrator approval. " +
                        "All logs are safely preserved in raw storage. Once approved, historical logs will be backfilled.",
                meta.sourceName());

        dispatchToAdminAndVendor(meta, adminTitle, adminMessage, vendorTitle, vendorMessage);
        sendWebhookAlertSafely(adminTitle, adminMessage, sourceId, failureType);
    }

    /**
     * Notifies admin and vendor when mapped fields cannot be written because the analytical table lacks columns.
     */
    public void notifyMissingColumns(String sourceId, String tableName, Set<String> missingColumns, int eventCount) {
        if (sourceId == null || eventCount <= 0 || missingColumns == null || missingColumns.isEmpty()) return;

        String failureType = "MISSING_COLUMNS";
        String cacheKey = sourceId + ":" + failureType;
        long now = System.currentTimeMillis();

        ThrottleEntry existing = throttleCache.get(cacheKey);
        if (existing != null && (now - existing.firstAlertTime()) < cooldownMs) {
            existing.accumulatedCount().addAndGet(eventCount);
            return;
        }

        if (existing != null && existing.accumulatedCount().get() > existing.initialAlertCount()) {
            sendDigestNotification(sourceId, failureType, String.join(",", missingColumns), existing.accumulatedCount().get(), existing.accumulatedCount().get() - existing.initialAlertCount());
        }

        Instant alertInstant = Instant.now();
        throttleCache.put(cacheKey, new ThrottleEntry(now, alertInstant, eventCount, new AtomicLong(eventCount), String.join(",", missingColumns)));

        SourceMeta meta = resolveSourceMeta(sourceId);

        String adminTitle = String.format("[SCHEMA MISMATCH] Table %s Missing Columns for %s", tableName, meta.sourceName());
        String adminMessage = String.format(
                "Dynamic table 'ulpf_events.%s' is missing columns %s mapped for source '%s' (ID: %s). " +
                        "%d event(s) had mapped values omitted from dynamic analytical columns (detected at: %s). " +
                        "Values are safely preserved in raw_unmapped JSON. " +
                        "Action: Add missing columns via ALTER TABLE or use DynamicSchemaProvisioningService, then trigger Replay via POST /v1/admin/sources/%s/replay.",
                tableName, missingColumns, meta.sourceName(), sourceId, eventCount, alertInstant, sourceId);

        String vendorTitle = "Schema Notice: Missing Table Columns for " + meta.sourceName();
        String vendorMessage = String.format(
                "Columns %s are mapped for source '%s' but are not yet provisioned in the target analytical table. " +
                        "Data is preserved in raw storage. Platform administrators have been notified to add the columns.",
                missingColumns, meta.sourceName());

        dispatchToAdminAndVendor(meta, adminTitle, adminMessage, vendorTitle, vendorMessage);
        sendWebhookAlertSafely(adminTitle, adminMessage, sourceId, failureType);
    }

    /**
     * Periodic scheduled check running every 60 seconds to dispatch pending summary digests
     * when the throttling window expires, even if subsequent failures stop.
     */
    @Scheduled(fixedRate = 60000)
    public void flushExpiredDigests() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, ThrottleEntry> entry : throttleCache.entrySet()) {
            String key = entry.getKey();
            ThrottleEntry state = entry.getValue();
            if ((now - state.firstAlertTime()) >= cooldownMs) {
                if (state.accumulatedCount().get() > state.initialAlertCount()) {
                    String[] parts = key.split(":", 2);
                    String sourceId = parts[0];
                    String failureType = parts.length > 1 ? parts[1] : "INGESTION_FAILURE";
                    sendDigestNotification(sourceId, failureType, state.lastDetails(), state.accumulatedCount().get(), state.accumulatedCount().get() - state.initialAlertCount());
                }
                throttleCache.remove(key);
            }
        }
    }

    private void sendDigestNotification(String sourceId, String failureType, String details, long totalCount, long suppressedCount) {
        if (suppressedCount <= 0) return;
        SourceMeta meta = resolveSourceMeta(sourceId);
        String adminTitle = String.format("[DIGEST] Ingestion Failures Summary for %s (%s)", meta.sourceName(), failureType);
        String adminMessage = String.format(
                "During the last 30-minute window, source '%s' (ID: %s, Vendor: %s) experienced %d total affected event(s) (%d suppressed during window) due to %s (%s). " +
                        "All raw events remain safely preserved in 'ulpf_raw.raw_events'. Replay available via POST /v1/admin/sources/%s/replay.",
                meta.sourceName(), sourceId, meta.vendorName(), totalCount, suppressedCount, failureType, details != null ? details : "N/A", sourceId);

        String vendorTitle = String.format("Summary Digest: Ingestion Failures for %s", meta.sourceName());
        String vendorMessage = String.format(
                "During the last 30 minutes, %d event(s) for your source '%s' could not be loaded into analytical storage due to %s. " +
                        "Events are safely preserved in raw storage. Platform administrators have been notified.",
                suppressedCount, meta.sourceName(), failureType);

        dispatchToAdminAndVendor(meta, adminTitle, adminMessage, vendorTitle, vendorMessage);
        sendWebhookAlertSafely(adminTitle, adminMessage, sourceId, failureType);
    }

    private record SourceMeta(String sourceName, String vendorId, String vendorName, String vendorOwnerUserId) {}

    private SourceMeta resolveSourceMeta(String sourceId) {
        String sourceName = sourceId;
        String vendorId = null;
        String vendorName = "Vendor";
        String vendorOwnerUserId = null;

        try {
            Optional<SourceRecord> srcOpt = sourceRepository.findById(sourceId);
            if (srcOpt.isPresent()) {
                sourceName = srcOpt.get().sourceName();
                vendorId = srcOpt.get().vendorId();
                if (vendorId != null) {
                    Optional<VendorRecord> vOpt = vendorRepository.findById(vendorId);
                    if (vOpt.isPresent()) {
                        vendorName = vOpt.get().vendorName();
                        vendorOwnerUserId = vOpt.get().ownerUserId();
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Could not resolve source metadata for source {}: {}", sourceId, e.getMessage());
        }

        return new SourceMeta(sourceName, vendorId, vendorName, vendorOwnerUserId);
    }

    private void dispatchToAdminAndVendor(SourceMeta meta, String adminTitle, String adminMessage, String vendorTitle, String vendorMessage) {
        // Dispatch to all system admins
        List<String> adminUserIds = resolveAdminUserIds();
        for (String adminId : adminUserIds) {
            if (!adminId.equals(meta.vendorOwnerUserId())) {
                saveNotificationSafely(adminId, adminTitle, adminMessage);
            }
        }

        // Dispatch to vendor owner
        if (meta.vendorOwnerUserId() != null) {
            saveNotificationSafely(meta.vendorOwnerUserId(), vendorTitle, vendorMessage);
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
        return List.of("admin");
    }

    private void saveNotificationSafely(String userId, String title, String message) {
        try {
            if (notificationService != null) {
                notificationService.createNotification(userId, title, message);
            } else if (onboardingRepository != null) {
                onboardingRepository.saveNotification(userId, title, message);
            }
            log.info("Dispatched Table Ingestion Failure Notification to user {}: {}", userId, title);
        } catch (Exception e) {
            log.warn("Failed to persist notification for user {}: {}", userId, e.getMessage());
        }
    }

    private void sendWebhookAlertSafely(String title, String message, String sourceId, String alertType) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            return;
        }
        try {
            log.info("Dispatching webhook alert to [{}]: type={}, sourceId={}, title={}", webhookUrl, alertType, sourceId, title);
            java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
            String payload = String.format("{\"alertType\":\"%s\",\"sourceId\":\"%s\",\"title\":\"%s\",\"timestamp\":\"%s\"}",
                    alertType, sourceId, title.replace("\"", "\\\""), Instant.now().toString());
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(webhookUrl))
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(payload))
                    .timeout(java.time.Duration.ofSeconds(3))
                    .build();
            client.sendAsync(request, java.net.http.HttpResponse.BodyHandlers.discarding())
                    .exceptionally(ex -> {
                        log.debug("Webhook alert delivery failed to {}: {}", webhookUrl, ex.getMessage());
                        return null;
                    });
        } catch (Exception e) {
            log.debug("Could not dispatch webhook alert: {}", e.getMessage());
        }
    }

    public long getSuppressedCount(String sourceId, String failureType) {
        ThrottleEntry entry = throttleCache.get(sourceId + ":" + failureType);
        return entry != null ? entry.accumulatedCount().get() : 0L;
    }

    public boolean isThrottled(String sourceId, String failureType) {
        ThrottleEntry entry = throttleCache.get(sourceId + ":" + failureType);
        if (entry == null) return false;
        return (System.currentTimeMillis() - entry.firstAlertTime()) < cooldownMs;
    }

    public void clearCache() {
        throttleCache.clear();
    }
}
