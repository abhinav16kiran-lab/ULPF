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
    private static final long COOLDOWN_MS = 15 * 60 * 1000L; // 15 Minutes

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

    /**
     * Inspects unmapped keys from a live log event and dispatches role-tailored
     * notifications to vendor and admin.
     * Deduplicates alerts per sourceId and key set within a 15-minute window.
     */
    public void checkAndNotifyDrift(String sourceId, Set<String> unmappedKeys) {
        if (sourceId == null || unmappedKeys == null || unmappedKeys.isEmpty()) {
            return;
        }

        List<String> sortedKeys = new ArrayList<>(unmappedKeys);
        Collections.sort(sortedKeys);

        String cacheKey = sourceId + ":" + String.join(",", sortedKeys);
        long now = System.currentTimeMillis();

        Long lastNotified = lastNotifiedCache.get(cacheKey);
        if (lastNotified != null && (now - lastNotified) < COOLDOWN_MS) {
            log.debug("Schema drift notification suppressed (cooldown active) for key: {}", cacheKey);
            return;
        }

        lastNotifiedCache.put(cacheKey, now);

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

        // 1. Dispatch Informative Alert to Vendor Owner (Read-Only)
        if (vendorOwnerUserId != null) {
            String vendorTitle = "Schema Drift Alert: " + sourceId;
            String vendorMessage = String.format(
                    "New unmapped vendor payload fields %s were detected in your live log stream for source %s. " +
                            "The fields are safely preserved in raw_unmapped JSON storage without data loss. " +
                            "If you wish to promote these fields to canonical columns, please contact your ULPF Administrator.",
                    sortedKeys, sourceId);
            saveNotificationSafely(vendorOwnerUserId, vendorTitle, vendorMessage);
        }

        // 2. Dispatch Actionable Governance Alert to System Admins
        String adminTitle = "[ADMIN ACTION REQUIRED] Schema Drift: " + sourceId;
        String adminMessage = String.format(
                "Source %s (Vendor: %s) sent new unmapped fields %s in live ingestion. " +
                        "Fields are preserved in raw_unmapped JSON. Open the Admin Control Panel (/admin) to inspect candidate mappings and promote fields.",
                sourceId, vendorName, sortedKeys);

        List<String> adminUserIds = resolveAdminUserIds();
        for (String adminId : adminUserIds) {
            if (!adminId.equals(vendorOwnerUserId)) { // Avoid duplicate notification if owner is admin
                saveNotificationSafely(adminId, adminTitle, adminMessage);
            }
        }
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
            onboardingRepository.saveNotification(userId, title, message);
            log.info("Dispatched Schema Drift Notification to user {}: {}", userId, title);
        } catch (Exception e) {
            log.warn("Failed to persist notification for user {}: {}", userId, e.getMessage());
        }
    }

    public void clearCache() {
        lastNotifiedCache.clear();
        notifiedHistoricalFallbacks.clear();
    }
}
