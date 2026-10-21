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
 * Service responsible for detecting and dispatching schema drift notifications
 * when live payload fields deviate from active schema mappings.
 *
 * Implements 30-minute window throttling per source with digest summaries for suppressed events.
 */
@Service
public class SchemaDriftNotificationService {

    private static final Logger log = LoggerFactory.getLogger(SchemaDriftNotificationService.class);

    @Value("${ulpf.schema-drift.cooldown-ms:1800000}")
    private long cooldownMs = 1800000L; // Default: 30 minutes

    private final SourceRepository sourceRepository;
    private final VendorRepository vendorRepository;
    private final OnboardingRepository onboardingRepository;
    private final UserRepository userRepository;

    private NotificationService notificationService;

    @Value("${ulpf.alert.webhook-url:}")
    private String webhookUrl;

    @Autowired(required = false)
    private AlertDispatcher alertDispatcher;

    public record DriftState(
            long firstSeen,
            AtomicLong lastSeen,
            int initialEventCount,
            AtomicLong eventCount,
            Set<String> accumulatedKeys,
            String vendorName,
            String vendorOwnerUserId
    ) {}

    // Throttle state per sourceId:SCHEMA_DRIFT or sourceId:TOTAL_DRIFT
    private final Map<String, DriftState> driftStateCache = new ConcurrentHashMap<>();
    private final Set<String> notifiedHistoricalFallbacks = ConcurrentHashMap.newKeySet();

    public SchemaDriftNotificationService(
            SourceRepository sourceRepository,
            VendorRepository vendorRepository,
            OnboardingRepository onboardingRepository,
            @Autowired(required = false) UserRepository userRepository) {
        this.sourceRepository = sourceRepository;
        this.vendorRepository = vendorRepository;
        this.onboardingRepository = onboardingRepository;
        this.userRepository = userRepository;
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

    @Autowired(required = false)
    public void setNotificationService(NotificationService notificationService) {
        this.notificationService = notificationService;
        if (this.alertDispatcher != null) {
            this.alertDispatcher.setNotificationService(notificationService);
        }
    }

    public void setWebhookUrl(String webhookUrl) {
        this.webhookUrl = webhookUrl;
        if (this.alertDispatcher != null) {
            this.alertDispatcher.setWebhookUrl(webhookUrl);
        }
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
     */
    public void checkAndNotifyDrift(String sourceId, Set<String> unmappedKeys) {
        checkAndNotifyDrift(sourceId, unmappedKeys, unmappedKeys != null ? unmappedKeys.size() : 0, false);
    }

    /**
     * Overloaded method with total drift detection and unmapped ratio threshold support.
     * Uses 30-minute window per sourceId with atomic compute and digest support.
     */
    public void checkAndNotifyDrift(String sourceId, Set<String> unmappedKeys, int totalKeys, boolean isTotalDrift) {
        if (sourceId == null || unmappedKeys == null || unmappedKeys.isEmpty()) {
            return;
        }

        List<String> sortedKeys = new ArrayList<>(unmappedKeys);
        Collections.sort(sortedKeys);

        String cacheKey = sourceId + ":" + (isTotalDrift ? "TOTAL_DRIFT" : "SCHEMA_DRIFT");
        long now = System.currentTimeMillis();
        Instant driftInstant = Instant.now();

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

        final String finalVendorName = vendorName;
        final String finalVendorOwnerUserId = vendorOwnerUserId;

        AtomicBoolean shouldNotify = new AtomicBoolean(false);
        AtomicReference<DriftState> expiredRef = new AtomicReference<>();

        driftStateCache.compute(cacheKey, (k, state) -> {
            if (state != null && (now - state.firstSeen()) < cooldownMs) {
                // Inside window: accumulate counts and unmapped keys (cap at 50)
                state.eventCount().incrementAndGet();
                state.lastSeen().set(now);
                for (String key : sortedKeys) {
                    if (state.accumulatedKeys().size() < 50) {
                        state.accumulatedKeys().add(key);
                    }
                }
                return state;
            }

            // Window expired or new entry: start new window
            shouldNotify.set(true);
            if (state != null) {
                expiredRef.set(state);
            }
            Set<String> initialKeys = ConcurrentHashMap.newKeySet();
            for (String key : sortedKeys) {
                if (initialKeys.size() < 50) {
                    initialKeys.add(key);
                }
            }
            return new DriftState(now, new AtomicLong(now), 1, new AtomicLong(1), initialKeys, finalVendorName, finalVendorOwnerUserId);
        });

        // If an expired window was closed, send its summary digest
        DriftState expired = expiredRef.get();
        if (expired != null && expired.eventCount().get() > expired.initialEventCount()) {
            sendDriftDigest(sourceId, cacheKey.endsWith("TOTAL_DRIFT"), expired);
        }

        if (!shouldNotify.get()) {
            log.debug("Schema drift notification suppressed (cooldown active) for key: {}", cacheKey);
            return;
        }

        double driftRatio = totalKeys > 0 ? ((double) sortedKeys.size() / totalKeys) : 0.0;
        int driftPercent = totalKeys > 0 ? (int) Math.round(driftRatio * 100) : 0;

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
        } else if (driftRatio > 0.30) {
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

        AlertPayload payload = new AlertPayload(
                isTotalDrift ? "TOTAL_DRIFT" : "SCHEMA_DRIFT",
                isTotalDrift ? "CRITICAL" : (driftRatio > 0.30 ? "WARN" : "INFO"),
                sourceId,
                sourceId,
                null,
                vendorName,
                null,
                isTotalDrift ? "100% of payload fields unmapped" : driftPercent + "% unmapped fields",
                sortedKeys.size(),
                unmappedKeys,
                driftInstant,
                adminMessage,
                "POST /v1/admin/sources/" + sourceId + "/replay"
        );

        getAlertDispatcher().dispatchAlert(payload, adminTitle, adminMessage, vendorOwnerUserId, vendorTitle, vendorMessage, true);
    }

    /**
     * Periodic scheduled check running every 60 seconds to dispatch pending drift digests
     * when the throttling window expires.
     */
    @Scheduled(fixedRate = 60000)
    public void flushExpiredDigests() {
        long now = System.currentTimeMillis();
        for (String key : driftStateCache.keySet()) {
            AtomicReference<DriftState> removedRef = new AtomicReference<>();
            driftStateCache.computeIfPresent(key, (k, state) -> {
                if ((now - state.firstSeen()) >= cooldownMs) {
                    removedRef.set(state);
                    return null;
                }
                return state;
            });

            DriftState removed = removedRef.get();
            if (removed != null && removed.eventCount().get() > removed.initialEventCount()) {
                String[] parts = key.split(":", 2);
                String sourceId = parts[0];
                boolean isTotal = parts.length > 1 && parts[1].equals("TOTAL_DRIFT");
                sendDriftDigest(sourceId, isTotal, removed);
            }
        }
    }

    private void sendDriftDigest(String sourceId, boolean isTotalDrift, DriftState state) {
        long totalCount = state.eventCount().get();
        long suppressedCount = totalCount - state.initialEventCount();
        if (suppressedCount <= 0) return;

        List<String> sortedKeys = new ArrayList<>(state.accumulatedKeys());
        Collections.sort(sortedKeys);

        String adminTitle = String.format("[DIGEST] Schema Drift Summary for %s (%s)", sourceId, isTotalDrift ? "TOTAL_DRIFT" : "SCHEMA_DRIFT");
        String adminMessage = String.format(
                "During the last 30-minute window, source '%s' (Vendor: %s) had %d drifted events (%d suppressed during window). Accumulated unmapped keys (%d): %s. " +
                        "Logs are preserved in raw_unmapped JSON. Review candidate mappings at /admin, and trigger Replay via POST /v1/admin/sources/%s/replay to backfill.",
                sourceId, state.vendorName(), totalCount, suppressedCount, sortedKeys.size(), sortedKeys, sourceId);

        String vendorTitle = String.format("Summary Digest: Schema Drift for %s", sourceId);
        String vendorMessage = String.format(
                "During the last 30-minute window, your stream for source %s had %d drifted events with unmapped fields %s. " +
                        "All fields are safely preserved in raw storage.",
                sourceId, totalCount, sortedKeys);

        AlertPayload payload = new AlertPayload(
                isTotalDrift ? "DIGEST_TOTAL_DRIFT" : "DIGEST_SCHEMA_DRIFT",
                "INFO",
                sourceId,
                sourceId,
                null,
                state.vendorName(),
                null,
                "Drift digest summary",
                totalCount,
                state.accumulatedKeys(),
                Instant.ofEpochMilli(state.firstSeen()),
                adminMessage,
                "POST /v1/admin/sources/" + sourceId + "/replay"
        );

        getAlertDispatcher().dispatchAlert(payload, adminTitle, adminMessage, state.vendorOwnerUserId(), vendorTitle, vendorMessage, true);
    }

    public boolean isDriftAlertThrottled(String sourceId, Set<String> unmappedKeys) {
        if (sourceId == null) return false;
        String cacheKey = sourceId + ":SCHEMA_DRIFT";
        DriftState state = driftStateCache.get(cacheKey);
        return state != null && (System.currentTimeMillis() - state.firstSeen()) < cooldownMs;
    }

    public Map<String, DriftState> getDriftStateCache() {
        return driftStateCache;
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
            log.warn("Could not resolve vendor owner for historical fallback alert (source {}): {}", sourceId, e.getMessage());
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

    private void saveNotificationSafely(String userId, String title, String message) {
        getAlertDispatcher().saveNotificationSafely(userId, title, message);
    }

    public void clearCache() {
        driftStateCache.clear();
        notifiedHistoricalFallbacks.clear();
    }
}
