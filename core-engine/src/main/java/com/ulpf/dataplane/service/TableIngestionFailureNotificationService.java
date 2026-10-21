package com.ulpf.dataplane.service;

import com.ulpf.common.db.OnboardingRepository;
import com.ulpf.common.db.SourceRepository;
import com.ulpf.common.db.SourceRepository.SourceRecord;
import com.ulpf.common.db.UserRepository;
import com.ulpf.controlplane.model.Role;
import com.ulpf.controlplane.model.User;
import com.ulpf.common.db.VendorRepository;
import com.ulpf.common.db.VendorRepository.VendorRecord;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Service responsible for alerting administrators and data source owners (vendors)
 * when ClickHouse dynamic table operations fail during canonical log ingestion.
 *
 * Implements thread-safe atomic throttling with digest summaries.
 */
@Service
public class TableIngestionFailureNotificationService {

    private static final Logger log = LoggerFactory.getLogger(TableIngestionFailureNotificationService.class);

    private long cooldownMs = 1800000L; // Default: 30 Minutes

    @Value("${ulpf.alert.webhook-url:}")
    private String webhookUrl;

    private final SourceRepository sourceRepository;
    private final VendorRepository vendorRepository;
    private final OnboardingRepository onboardingRepository;
    private final UserRepository userRepository;
    private NotificationService notificationService;

    @Autowired(required = false)
    private AlertDispatcher alertDispatcher;

    public record ThrottleEntry(
            long firstAlertTime,
            Instant firstAlertInstant,
            int initialAlertCount,
            AtomicLong accumulatedCount,
            String lastDetails
    ) {}

    public record EvaluationResult(
            boolean shouldAlert,
            ThrottleEntry expiredPrevious,
            Instant alertInstant
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
        if (this.alertDispatcher != null) {
            this.alertDispatcher.setNotificationService(notificationService);
        }
    }

    public void setAlertDispatcher(AlertDispatcher alertDispatcher) {
        this.alertDispatcher = alertDispatcher;
    }

    public AlertDispatcher getAlertDispatcher() {
        if (this.alertDispatcher == null) {
            this.alertDispatcher = new AlertDispatcher(onboardingRepository, userRepository, notificationService, null);
            if (webhookUrl != null) {
                this.alertDispatcher.setWebhookUrl(webhookUrl);
            }
        }
        return this.alertDispatcher;
    }

    public void setCooldownMs(long cooldownMs) {
        this.cooldownMs = cooldownMs;
    }

    public long getCooldownMs() {
        return this.cooldownMs;
    }

    public void setWebhookUrl(String webhookUrl) {
        this.webhookUrl = webhookUrl;
        if (this.alertDispatcher != null) {
            this.alertDispatcher.setWebhookUrl(webhookUrl);
        }
    }

    /**
     * Atomic throttle evaluation helper. Returns whether to alert and any expired window entry.
     */
    private EvaluationResult evaluate(String cacheKey, int eventCount, String details) {
        long now = System.currentTimeMillis();
        Instant alertInstant = Instant.now();
        AtomicBoolean startNewWindow = new AtomicBoolean(false);
        AtomicReference<ThrottleEntry> expiredRef = new AtomicReference<>();

        throttleCache.compute(cacheKey, (k, existing) -> {
            if (existing != null && (now - existing.firstAlertTime()) < cooldownMs) {
                existing.accumulatedCount().addAndGet(eventCount);
                return existing;
            }
            startNewWindow.set(true);
            if (existing != null) {
                expiredRef.set(existing);
            }
            return new ThrottleEntry(now, alertInstant, eventCount, new AtomicLong(eventCount), details);
        });

        return new EvaluationResult(startNewWindow.get(), expiredRef.get(), alertInstant);
    }

    /**
     * Notifies admin and vendor when an analytical dynamic table does not exist or has no columns in ClickHouse.
     */
    public void notifyTableMissing(String sourceId, String tableName, int eventCount) {
        if (sourceId == null || eventCount <= 0) return;

        String failureType = "TABLE_MISSING";
        String cacheKey = sourceId + ":" + failureType;
        EvaluationResult eval = evaluate(cacheKey, eventCount, tableName);

        if (eval.expiredPrevious() != null && eval.expiredPrevious().accumulatedCount().get() > eval.expiredPrevious().initialAlertCount()) {
            ThrottleEntry prev = eval.expiredPrevious();
            sendDigestNotification(sourceId, failureType, prev.lastDetails(), prev.accumulatedCount().get(), prev.accumulatedCount().get() - prev.initialAlertCount());
        }

        if (!eval.shouldAlert()) {
            log.debug("Table missing alert suppressed (cooldown active) for source {}. Total accumulated: {}",
                    sourceId, throttleCache.get(cacheKey) != null ? throttleCache.get(cacheKey).accumulatedCount().get() : eventCount);
            return;
        }

        Instant alertInstant = eval.alertInstant();
        SourceMeta meta = resolveSourceMeta(sourceId);

        String adminTitle = "[CRITICAL] Dynamic Table Missing: " + meta.sourceName();
        String adminMessage = String.format(
                "Dynamic table 'ulpf_events.%s' for source '%s' (ID: %s, Vendor: %s) does not exist in ClickHouse. " +
                        "%d event(s) could not be loaded into analytical storage. " +
                        "Events are safely preserved in 'ulpf_raw.raw_events' (started at: %s). " +
                        "Action: Verify table schema or provision table, then trigger Replay via POST /v1/admin/sources/%s/replay to backfill.",
                tableName, meta.sourceName(), sourceId, meta.vendorName(), eventCount, alertInstant, sourceId);

        String vendorTitle = "Ingestion Alert: Dynamic Table Missing for " + meta.sourceName();
        String vendorMessage = String.format(
                "Logs for source '%s' could not be loaded into dynamic analytics because table 'ulpf_events.%s' is missing. " +
                        "Your data is safely preserved in raw storage. Platform administrators have been notified to re-provision the table.",
                meta.sourceName(), tableName);

        AlertPayload payload = new AlertPayload(
                failureType, "CRITICAL", sourceId, meta.sourceName(), meta.vendorId(), meta.vendorName(),
                tableName, "Dynamic table missing in ClickHouse", eventCount, Collections.emptySet(), alertInstant,
                adminMessage, "POST /v1/admin/sources/" + sourceId + "/replay");

        getAlertDispatcher().dispatchAlert(payload, adminTitle, adminMessage, meta.ownerUserId(), vendorTitle, vendorMessage);
    }

    /**
     * Notifies admin and vendor when a ClickHouse dynamic table batch insert fails due to an exception.
     */
    public void notifyInsertFailure(String sourceId, String tableName, int eventCount, String errorMessage) {
        if (sourceId == null || eventCount <= 0) return;

        String failureType = "INSERT_FAILURE";
        String cacheKey = sourceId + ":" + failureType;
        EvaluationResult eval = evaluate(cacheKey, eventCount, tableName + ":" + errorMessage);

        if (eval.expiredPrevious() != null && eval.expiredPrevious().accumulatedCount().get() > eval.expiredPrevious().initialAlertCount()) {
            ThrottleEntry prev = eval.expiredPrevious();
            sendDigestNotification(sourceId, failureType, prev.lastDetails(), prev.accumulatedCount().get(), prev.accumulatedCount().get() - prev.initialAlertCount());
        }

        if (!eval.shouldAlert()) {
            log.debug("Insert failure alert suppressed (cooldown active) for source {}. Total accumulated: {}",
                    sourceId, throttleCache.get(cacheKey) != null ? throttleCache.get(cacheKey).accumulatedCount().get() : eventCount);
            return;
        }

        Instant alertInstant = eval.alertInstant();
        SourceMeta meta = resolveSourceMeta(sourceId);

        String adminTitle = "[INGESTION FAILURE] Dynamic Table Insert Failed for " + meta.sourceName();
        String adminMessage = String.format(
                "Batch insert into dynamic table 'ulpf_events.%s' failed for source '%s' (ID: %s, Vendor: %s). " +
                        "%d event(s) could not be written to analytical storage (started at: %s). " +
                        "Events are safely preserved in 'ulpf_raw.raw_events'. Error: %s. " +
                        "Action: Review database errors and execute Replay via POST /v1/admin/sources/%s/replay after resolution.",
                tableName, meta.sourceName(), sourceId, meta.vendorName(), eventCount, alertInstant, errorMessage, sourceId);

        String vendorTitle = "Ingestion Warning: Analytical Insert Failed for " + meta.sourceName();
        String vendorMessage = String.format(
                "Dynamic analytical writes for source '%s' (table 'ulpf_events.%s') experienced an insertion failure. " +
                        "All incoming events are safely preserved in raw storage.",
                meta.sourceName(), tableName);

        AlertPayload payload = new AlertPayload(
                failureType, "CRITICAL", sourceId, meta.sourceName(), meta.vendorId(), meta.vendorName(),
                tableName, errorMessage, eventCount, Collections.emptySet(), alertInstant,
                adminMessage, "POST /v1/admin/sources/" + sourceId + "/replay");

        getAlertDispatcher().dispatchAlert(payload, adminTitle, adminMessage, meta.ownerUserId(), vendorTitle, vendorMessage);
    }

    /**
     * Notifies admin and vendor when an event has an active mapping, but no 'target_table' is configured.
     */
    public void notifyNoTargetTable(String sourceId, int eventCount) {
        if (sourceId == null || eventCount <= 0) return;

        String failureType = "NO_TARGET_TABLE";
        String cacheKey = sourceId + ":" + failureType;
        EvaluationResult eval = evaluate(cacheKey, eventCount, "NO_TARGET_TABLE");

        if (eval.expiredPrevious() != null && eval.expiredPrevious().accumulatedCount().get() > eval.expiredPrevious().initialAlertCount()) {
            ThrottleEntry prev = eval.expiredPrevious();
            sendDigestNotification(sourceId, failureType, prev.lastDetails(), prev.accumulatedCount().get(), prev.accumulatedCount().get() - prev.initialAlertCount());
        }

        if (!eval.shouldAlert()) {
            return;
        }

        Instant alertInstant = eval.alertInstant();
        SourceMeta meta = resolveSourceMeta(sourceId);

        String adminTitle = "[CONFIGURATION REQUIRED] No Target Table Defined: " + meta.sourceName();
        String adminMessage = String.format(
                "Source '%s' (ID: %s, Vendor: %s) has an active mapping without 'target_table' in its metadata. " +
                        "%d event(s) bypassed dynamic table insertion (detected at: %s). " +
                        "Events are safely stored in 'ulpf_raw.raw_events'. " +
                        "Action: Update the active mapping metadata with a valid target_table and trigger Replay via POST /v1/admin/sources/%s/replay.",
                meta.sourceName(), sourceId, meta.vendorName(), eventCount, alertInstant, sourceId);

        String vendorTitle = "Configuration Notice for " + meta.sourceName();
        String vendorMessage = String.format(
                "Log events for source '%s' are currently not routed to an analytical table because no target table is configured in your mapping metadata. " +
                        "Events are safely preserved in raw storage.", meta.sourceName());

        AlertPayload payload = new AlertPayload(
                failureType, "WARN", sourceId, meta.sourceName(), meta.vendorId(), meta.vendorName(),
                null, "No target_table in active mapping metadata", eventCount, Collections.emptySet(), alertInstant,
                adminMessage, "POST /v1/admin/sources/" + sourceId + "/replay");

        getAlertDispatcher().dispatchAlert(payload, adminTitle, adminMessage, meta.ownerUserId(), vendorTitle, vendorMessage);
    }

    /**
     * Notifies admin and vendor when an incoming stream is emitting events but has no approved active mapping.
     */
    public void notifyMissingMapping(String sourceId, int eventCount) {
        if (sourceId == null || eventCount <= 0) return;

        String failureType = "MAPPING_MISSING";
        String cacheKey = sourceId + ":" + failureType;
        EvaluationResult eval = evaluate(cacheKey, eventCount, "MAPPING_MISSING");

        if (eval.expiredPrevious() != null && eval.expiredPrevious().accumulatedCount().get() > eval.expiredPrevious().initialAlertCount()) {
            ThrottleEntry prev = eval.expiredPrevious();
            sendDigestNotification(sourceId, failureType, prev.lastDetails(), prev.accumulatedCount().get(), prev.accumulatedCount().get() - prev.initialAlertCount());
        }

        if (!eval.shouldAlert()) {
            return;
        }

        Instant alertInstant = eval.alertInstant();
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

        AlertPayload payload = new AlertPayload(
                failureType, "WARN", sourceId, meta.sourceName(), meta.vendorId(), meta.vendorName(),
                null, "No approved schema mapping", eventCount, Collections.emptySet(), alertInstant,
                adminMessage, "POST /v1/admin/sources/" + sourceId + "/replay");

        getAlertDispatcher().dispatchAlert(payload, adminTitle, adminMessage, meta.ownerUserId(), vendorTitle, vendorMessage);
    }

    /**
     * Notifies admin and vendor when mapped fields cannot be written because the analytical table lacks columns.
     */
    public void notifyMissingColumns(String sourceId, String tableName, Set<String> missingColumns, int eventCount) {
        if (sourceId == null || eventCount <= 0 || missingColumns == null || missingColumns.isEmpty()) return;

        String failureType = "MISSING_COLUMNS";
        String cacheKey = sourceId + ":" + failureType;
        EvaluationResult eval = evaluate(cacheKey, eventCount, String.join(",", missingColumns));

        if (eval.expiredPrevious() != null && eval.expiredPrevious().accumulatedCount().get() > eval.expiredPrevious().initialAlertCount()) {
            ThrottleEntry prev = eval.expiredPrevious();
            sendDigestNotification(sourceId, failureType, prev.lastDetails(), prev.accumulatedCount().get(), prev.accumulatedCount().get() - prev.initialAlertCount());
        }

        if (!eval.shouldAlert()) {
            return;
        }

        Instant alertInstant = eval.alertInstant();
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

        AlertPayload payload = new AlertPayload(
                failureType, "WARN", sourceId, meta.sourceName(), meta.vendorId(), meta.vendorName(),
                tableName, "Table missing mapped columns: " + missingColumns, eventCount, missingColumns, alertInstant,
                adminMessage, "POST /v1/admin/sources/" + sourceId + "/replay");

        getAlertDispatcher().dispatchAlert(payload, adminTitle, adminMessage, meta.ownerUserId(), vendorTitle, vendorMessage);
    }

    /**
     * Periodic scheduled check running every 60 seconds to dispatch pending summary digests
     * when the throttling window expires, even if subsequent failures stop.
     * Uses atomic computeIfPresent to eliminate race conditions between flush and incoming counts.
     */
    @Scheduled(fixedRate = 60000)
    public void flushExpiredDigests() {
        long now = System.currentTimeMillis();
        for (String key : throttleCache.keySet()) {
            AtomicReference<ThrottleEntry> removedRef = new AtomicReference<>();
            throttleCache.computeIfPresent(key, (k, state) -> {
                if ((now - state.firstAlertTime()) >= cooldownMs) {
                    removedRef.set(state);
                    return null;
                }
                return state;
            });

            ThrottleEntry removed = removedRef.get();
            if (removed != null && removed.accumulatedCount().get() > removed.initialAlertCount()) {
                String[] parts = key.split(":", 2);
                String sourceId = parts[0];
                String failureType = parts.length > 1 ? parts[1] : "INGESTION_FAILURE";
                sendDigestNotification(sourceId, failureType, removed.lastDetails(), removed.accumulatedCount().get(), removed.accumulatedCount().get() - removed.initialAlertCount());
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
                "During the last 30-minute window, %d total event(s) encountered %s. All events are safely preserved in raw storage.",
                totalCount, failureType);

        AlertPayload payload = new AlertPayload(
                "DIGEST_" + failureType, "INFO", sourceId, meta.sourceName(), meta.vendorId(), meta.vendorName(),
                details, "Digest summary", totalCount, Collections.emptySet(), Instant.now(),
                adminMessage, "POST /v1/admin/sources/" + sourceId + "/replay");

        getAlertDispatcher().dispatchAlert(payload, adminTitle, adminMessage, meta.ownerUserId(), vendorTitle, vendorMessage);
    }

    public record SourceMeta(String sourceName, String vendorId, String vendorName, String ownerUserId) {}

    public SourceMeta resolveSourceMeta(String sourceId) {
        String sourceName = sourceId;
        String vendorId = "unknown";
        String vendorName = "Unknown Vendor";
        String ownerUserId = null;

        try {
            Optional<SourceRecord> srcOpt = sourceRepository.findById(sourceId);
            if (srcOpt.isPresent()) {
                sourceName = srcOpt.get().sourceName();
                vendorId = srcOpt.get().vendorId();
                Optional<VendorRecord> venOpt = vendorRepository.findById(vendorId);
                if (venOpt.isPresent()) {
                    vendorName = venOpt.get().vendorName();
                    ownerUserId = venOpt.get().ownerUserId();
                }
            }
        } catch (Exception e) {
            log.warn("Failed to resolve metadata for sourceId {}: {}", sourceId, e.getMessage());
        }

        return new SourceMeta(sourceName, vendorId, vendorName, ownerUserId);
    }

    public long getSuppressedCount(String sourceId, String failureType) {
        ThrottleEntry entry = throttleCache.get(sourceId + ":" + failureType);
        return entry != null ? entry.accumulatedCount().get() : 0;
    }

    public boolean isThrottled(String sourceId, String failureType) {
        ThrottleEntry entry = throttleCache.get(sourceId + ":" + failureType);
        return entry != null && (System.currentTimeMillis() - entry.firstAlertTime()) < cooldownMs;
    }

    public void clearCache() {
        throttleCache.clear();
    }

    public Map<String, ThrottleEntry> getThrottleCache() {
        return throttleCache;
    }
}
