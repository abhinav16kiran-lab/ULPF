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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Service for detecting dynamic table ingestion failures (missing dynamic tables,
 * ClickHouse write exceptions, missing target_table metadata) and dispatching
 * actionable notifications to system administrators and vendor owners.
 *
 * Implements sliding-window throttling (default: 30 minutes) to prevent notification
 * storms during high-throughput log ingestion, with an atomic accumulator to track
 * dropped/affected event counts.
 */
@Service
public class TableIngestionFailureNotificationService {

    private static final Logger log = LoggerFactory.getLogger(TableIngestionFailureNotificationService.class);

    @Value("${ulpf.table-failure.cooldown-ms:1800000}")
    private long cooldownMs = 1800000L; // Default: 30 Minutes

    private final SourceRepository sourceRepository;
    private final VendorRepository vendorRepository;
    private final OnboardingRepository onboardingRepository;
    private final UserRepository userRepository;

    public record ThrottleEntry(
            long firstAlertTime,
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

    public void setCooldownMs(long cooldownMs) {
        this.cooldownMs = cooldownMs;
    }

    public long getCooldownMs() {
        return this.cooldownMs;
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
            log.debug("Table missing alert suppressed (cooldown active) for source {}. Total suppressed: {}",
                    sourceId, existing.accumulatedCount().get());
            return;
        }

        // Window expired or first occurrence: check if we should send a digest for previous window
        if (existing != null && existing.accumulatedCount().get() > 0) {
            sendDigestNotification(sourceId, failureType, tableName, existing.accumulatedCount().get());
        }

        // Register new window
        throttleCache.put(cacheKey, new ThrottleEntry(now, new AtomicLong(eventCount), tableName));

        SourceMeta meta = resolveSourceMeta(sourceId);

        String adminTitle = "[CRITICAL] Dynamic Table Missing: " + meta.sourceName();
        String adminMessage = String.format(
                "Dynamic table 'ulpf_events.%s' for source '%s' (ID: %s, Vendor: %s) does not exist in ClickHouse. " +
                        "%d event(s) could not be loaded into analytical storage. " +
                        "Events are safely preserved in 'ulpf_raw.raw_events'. " +
                        "Action: Verify table schema or provision table, then trigger Replay via POST /v1/admin/sources/%s/replay to backfill.",
                tableName, meta.sourceName(), sourceId, meta.vendorName(), eventCount, sourceId);

        dispatchToAdminAndVendor(meta, adminTitle, adminMessage,
                "Ingestion Alert: Dynamic Table Missing for " + meta.sourceName(),
                String.format("Logs for source '%s' could not be loaded into dynamic analytics because table 'ulpf_events.%s' is missing. " +
                        "Your data is safely preserved in raw storage. Platform administrators have been notified to re-provision the table.",
                        meta.sourceName(), tableName));
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
            log.debug("Dynamic insert failure alert suppressed (cooldown active) for source {}. Total suppressed: {}",
                    sourceId, existing.accumulatedCount().get());
            return;
        }

        if (existing != null && existing.accumulatedCount().get() > 0) {
            sendDigestNotification(sourceId, failureType, tableName, existing.accumulatedCount().get());
        }

        throttleCache.put(cacheKey, new ThrottleEntry(now, new AtomicLong(eventCount), errorMessage));

        SourceMeta meta = resolveSourceMeta(sourceId);

        String adminTitle = "[INGESTION FAILURE] Dynamic Table Insert Failed: " + meta.sourceName();
        String adminMessage = String.format(
                "Failed to insert batch of %d event(s) into dynamic table 'ulpf_events.%s' for source '%s' (ID: %s). " +
                        "Error: %s. Events are safely preserved in 'ulpf_raw.raw_events'. " +
                        "Action: Once database availability is restored, trigger Replay via POST /v1/admin/sources/%s/replay to recover.",
                eventCount, tableName, meta.sourceName(), sourceId, errorMessage != null ? errorMessage : "Unknown error", sourceId);

        dispatchToAdminAndVendor(meta, adminTitle, adminMessage,
                "Ingestion Alert: Database Write Issue for " + meta.sourceName(),
                String.format("A temporary database write issue occurred while inserting %d event(s) for source '%s'. " +
                        "Your events are safely recorded in raw storage and will be backfilled once database stability is confirmed.",
                        eventCount, meta.sourceName()));
    }

    /**
     * Notifies admin when an active source emits events but has no target_table defined in mapping metadata.
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

        throttleCache.put(cacheKey, new ThrottleEntry(now, new AtomicLong(eventCount), "NO_TARGET_TABLE"));

        SourceMeta meta = resolveSourceMeta(sourceId);

        String adminTitle = "[CONFIGURATION REQUIRED] No Target Table Defined: " + meta.sourceName();
        String adminMessage = String.format(
                "Source '%s' (ID: %s, Vendor: %s) has no 'target_table' defined in its active mapping metadata. " +
                        "%d event(s) bypassed dynamic table insertion. " +
                        "Events are safely stored in 'ulpf_raw.raw_events'. " +
                        "Action: Update the active mapping metadata with a valid target_table and trigger Replay via POST /v1/admin/sources/%s/replay.",
                meta.sourceName(), sourceId, meta.vendorName(), eventCount, sourceId);

        dispatchToAdminAndVendor(meta, adminTitle, adminMessage,
                "Configuration Notice for " + meta.sourceName(),
                String.format("Log events for source '%s' are currently not routed to an analytical table because no target table is configured in your mapping metadata. " +
                        "Events are safely preserved in raw storage.", meta.sourceName()));
    }

    private void sendDigestNotification(String sourceId, String failureType, String details, long count) {
        SourceMeta meta = resolveSourceMeta(sourceId);
        String title = String.format("[DIGEST] Ingestion Failures Summary for %s (%s)", meta.sourceName(), failureType);
        String message = String.format(
                "During the last 30-minute window, source '%s' (ID: %s) experienced %d affected event(s) due to %s (%s). " +
                        "All raw events remain safely preserved in 'ulpf_raw.raw_events'.",
                meta.sourceName(), sourceId, count, failureType, details != null ? details : "N/A");

        List<String> adminUserIds = resolveAdminUserIds();
        for (String adminId : adminUserIds) {
            saveNotificationSafely(adminId, title, message);
        }
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
            onboardingRepository.saveNotification(userId, title, message);
            log.info("Dispatched Table Ingestion Failure Notification to user {}: {}", userId, title);
        } catch (Exception e) {
            log.warn("Failed to persist notification for user {}: {}", userId, e.getMessage());
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
