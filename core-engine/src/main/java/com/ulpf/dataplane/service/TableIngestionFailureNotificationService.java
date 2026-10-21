package com.ulpf.dataplane.service;

import com.ulpf.common.db.IngestionIncidentRepository;
import com.ulpf.common.db.OnboardingRepository;
import com.ulpf.common.db.SourceRepository;
import com.ulpf.common.db.SourceRepository.SourceRecord;
import com.ulpf.common.db.UserRepository;
import com.ulpf.common.db.VendorRepository;
import com.ulpf.common.db.VendorRepository.VendorRecord;
import com.ulpf.controlplane.service.NotificationService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Service responsible for alerting administrators and data source owners (vendors)
 * when ClickHouse dynamic table operations fail during canonical log ingestion.
 *
 * Implements thread-safe atomic throttling with digest summaries and incident lifecycle tracking.
 */
@Service
public class TableIngestionFailureNotificationService {

    private static final Logger log = LoggerFactory.getLogger(TableIngestionFailureNotificationService.class);

    @Value("${ulpf.table-failure.cooldown-ms:1800000}")
    private long cooldownMs = 1800000L; // Default: 30 Minutes

    @Value("${ulpf.table-failure.replay-margin-minutes:5}")
    private long replayMarginMinutes = 5;

    @Value("${ulpf.table-failure.recovery-quiet-ms:300000}")
    private long recoveryQuietMs = 300000L; // Default: 5 Minutes quiet period

    @Value("${ulpf.alert.webhook-url:}")
    private String webhookUrl;

    private final SourceRepository sourceRepository;
    private final VendorRepository vendorRepository;
    private final OnboardingRepository onboardingRepository;
    private final UserRepository userRepository;
    private NotificationService notificationService;

    @Autowired(required = false)
    private AlertDispatcher alertDispatcher;

    @Autowired(required = false)
    private IngestionIncidentRepository incidentRepository;

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

    public record ActiveIncident(
            String incidentId,
            String sourceId,
            String failureType,
            String tableName,
            LocalDateTime startedAt,
            AtomicLong affectedCount,
            AtomicLong lastFailureAt,
            AtomicLong lastSuccessAt,
            AtomicLong lastReminderAt
    ) {}

    // In-memory throttling cache: (sourceId + ":" + failureType) -> ThrottleEntry
    private final Map<String, ThrottleEntry> throttleCache = new ConcurrentHashMap<>();

    // In-memory active incident tracking: (sourceId + ":" + failureType) -> ActiveIncident
    private final Map<String, ActiveIncident> openIncidents = new ConcurrentHashMap<>();

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

    @PostConstruct
    public void init() {
        if (incidentRepository != null) {
            try {
                List<IngestionIncidentRepository.IncidentRecord> open = incidentRepository.findAllOpen();
                for (var rec : open) {
                    long lastFailure = rec.lastSeenAt() != null
                            ? rec.lastSeenAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                            : (rec.startedAt() != null
                                ? rec.startedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                                : System.currentTimeMillis());
                    openIncidents.put(rec.sourceId() + ":" + rec.failureType(),
                            new ActiveIncident(
                                    rec.incidentId(), rec.sourceId(), rec.failureType(), rec.tableName(), rec.startedAt(),
                                    new AtomicLong(rec.affectedCount()),
                                    new AtomicLong(lastFailure),
                                    new AtomicLong(0),
                                    new AtomicLong(lastFailure)
                            ));
                }
                log.info("Initialized {} active ingestion incidents from database.", open.size());
            } catch (Exception e) {
                log.warn("Could not load open incidents on startup: {}", e.getMessage());
            }
        }
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

    public void setIncidentRepository(IngestionIncidentRepository incidentRepository) {
        this.incidentRepository = incidentRepository;
    }

    public void setCooldownMs(long cooldownMs) {
        this.cooldownMs = cooldownMs;
    }

    public long getCooldownMs() {
        return this.cooldownMs;
    }

    public void setReplayMarginMinutes(long replayMarginMinutes) {
        this.replayMarginMinutes = replayMarginMinutes;
    }

    public long getReplayMarginMinutes() {
        return this.replayMarginMinutes;
    }

    public void setRecoveryQuietMs(long recoveryQuietMs) {
        this.recoveryQuietMs = recoveryQuietMs;
    }

    public long getRecoveryQuietMs() {
        return this.recoveryQuietMs;
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

    private void recordFailure(String cacheKey, String sourceId, String failureType, String tableName, int eventCount, boolean shouldAlert) {
        long now = System.currentTimeMillis();
        if (shouldAlert) {
            recordIncidentOnAlert(cacheKey, sourceId, failureType, tableName, eventCount, now);
        } else {
            ActiveIncident inc = openIncidents.get(cacheKey);
            if (inc != null) {
                long failTime = Math.max(now, inc.lastSuccessAt().get() + 1);
                inc.lastFailureAt().set(failTime);
            }
        }
    }

    private void recordIncidentOnAlert(String cacheKey, String sourceId, String failureType, String tableName, int eventCount, long now) {
        openIncidents.compute(cacheKey, (k, existing) -> {
            if (existing != null) {
                existing.affectedCount().addAndGet(eventCount);
                long failTime = Math.max(now, existing.lastSuccessAt().get() + 1);
                existing.lastFailureAt().set(failTime);
                return existing;
            }
            String incidentId = "inc-" + UUID.randomUUID().toString().substring(0, 8);
            LocalDateTime startedAt = LocalDateTime.now();
            ActiveIncident newInc = new ActiveIncident(
                    incidentId, sourceId, failureType, tableName, startedAt,
                    new AtomicLong(eventCount),
                    new AtomicLong(now),
                    new AtomicLong(0),
                    new AtomicLong(now)
            );
            if (incidentRepository != null) {
                try {
                    incidentRepository.createIncident(new IngestionIncidentRepository.IncidentRecord(
                            incidentId, sourceId, failureType, tableName, startedAt, startedAt, null, eventCount, "OPEN", failureType
                    ));
                } catch (Exception e) {
                    log.warn("Failed to persist incident {} to database: {}", incidentId, e.getMessage());
                }
            }
            return newInc;
        });
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
            recordFailure(cacheKey, sourceId, failureType, tableName, eventCount, false);
            log.debug("Table missing alert suppressed (cooldown active) for source {}. Total accumulated: {}",
                    sourceId, throttleCache.get(cacheKey) != null ? throttleCache.get(cacheKey).accumulatedCount().get() : eventCount);
            return;
        }

        recordFailure(cacheKey, sourceId, failureType, tableName, eventCount, true);

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
            recordFailure(cacheKey, sourceId, failureType, tableName, eventCount, false);
            log.debug("Insert failure alert suppressed (cooldown active) for source {}. Total accumulated: {}",
                    sourceId, throttleCache.get(cacheKey) != null ? throttleCache.get(cacheKey).accumulatedCount().get() : eventCount);
            return;
        }

        recordFailure(cacheKey, sourceId, failureType, tableName, eventCount, true);

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
            recordFailure(cacheKey, sourceId, failureType, null, eventCount, false);
            return;
        }

        recordFailure(cacheKey, sourceId, failureType, null, eventCount, true);

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
            recordFailure(cacheKey, sourceId, failureType, null, eventCount, false);
            return;
        }

        recordFailure(cacheKey, sourceId, failureType, null, eventCount, true);

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
            recordFailure(cacheKey, sourceId, failureType, tableName, eventCount, false);
            return;
        }

        recordFailure(cacheKey, sourceId, failureType, tableName, eventCount, true);

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
     * Notifies admin and vendor when a single record fails dynamic insertion and is degraded into raw storage.
     * Uses its own throttle key (DEGRADED_RECORDS) and creates no incident, so it never affects recovery.
     */
    public void notifyDegradedRecords(String sourceId, String tableName, int count, String sampleEventId, String error) {
        if (sourceId == null || count <= 0) return;
        String failureType = "DEGRADED_RECORDS";
        String cacheKey = sourceId + ":" + failureType;
        EvaluationResult eval = evaluate(cacheKey, count, "Sample ID: " + sampleEventId);

        if (eval.expiredPrevious() != null && eval.expiredPrevious().accumulatedCount().get() > eval.expiredPrevious().initialAlertCount()) {
            ThrottleEntry prev = eval.expiredPrevious();
            sendDigestNotification(sourceId, failureType, prev.lastDetails(), prev.accumulatedCount().get(), prev.accumulatedCount().get() - prev.initialAlertCount());
        }

        if (!eval.shouldAlert()) {
            log.debug("Degraded records alert suppressed for source {}. Total accumulated: {}", sourceId,
                    throttleCache.get(cacheKey) != null ? throttleCache.get(cacheKey).accumulatedCount().get() : count);
            return;
        }

        SourceMeta meta = resolveSourceMeta(sourceId);
        String adminTitle = String.format("[DEGRADED] Malformed Record(s) Degraded for %s", meta.sourceName());
        String adminMessage = String.format(
                "Source '%s' (table '%s') had %d malformed record(s) degraded into raw storage due to error: %s. " +
                        "Sample event ID: %s. Mapped columns were stored as NULL; raw payload is preserved in raw_unmapped.",
                meta.sourceName(), tableName != null ? tableName : "N/A", count, error != null ? error : "Type coercion failure", sampleEventId);

        String vendorTitle = String.format("Data Notice: Partial Log Format Mismatch for %s", meta.sourceName());
        String vendorMessage = String.format(
                "Some records for source '%s' could not be fully mapped into analytics columns due to a value type mismatch. " +
                        "Raw logs are safely preserved. Sample event ID: %s.",
                meta.sourceName(), sampleEventId);

        AlertPayload payload = new AlertPayload(
                "DEGRADED_RECORDS", "WARN", sourceId, meta.sourceName(), meta.vendorId(), meta.vendorName(),
                tableName, "Record degraded to NULL mapped fields: " + error, (long) count, Collections.emptySet(),
                Instant.now(), adminMessage, "Inspect raw_unmapped and update mapping type definitions."
        );

        getAlertDispatcher().dispatchAlert(payload, adminTitle, adminMessage, meta.ownerUserId(), vendorTitle, vendorMessage);
    }

    /**
     * Periodic scheduled check running every 60 seconds to dispatch pending summary digests,
     * resolve quiet incidents after the 5-minute recovery quiet window, and send 24h reminders.
     */
    @Scheduled(fixedRate = 60000)
    public void scheduledMaintenance() {
        flushExpiredDigests();
        resolveQuietIncidents();
        sendStaleIncidentReminders();
    }

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

        ActiveIncident inc = openIncidents.get(sourceId + ":" + failureType);
        if (inc != null) {
            inc.affectedCount().addAndGet(suppressedCount);
            if (incidentRepository != null) {
                try {
                    incidentRepository.updateCountsAndLastSeen(inc.incidentId(), inc.affectedCount().get(), LocalDateTime.now());
                } catch (Exception e) {
                    log.warn("Failed to update incident {} in database: {}", inc.incidentId(), e.getMessage());
                }
            }
        }

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

    /**
     * Cheap recovery marker: sets lastSuccessAt = now on matching open incidents.
     * Does not resolve the incident immediately or touch the throttle cache.
     * Actual resolution occurs in resolveQuietIncidents() after recoveryQuietMs has elapsed.
     */
    public void markRecovered(String sourceId, String tableName) {
        if (openIncidents.isEmpty() || sourceId == null) {
            return;
        }

        long now = System.currentTimeMillis();
        for (ActiveIncident inc : openIncidents.values()) {
            if (inc.sourceId().equals(sourceId)) {
                if (tableName == null || inc.tableName() == null || inc.tableName().equals(tableName)) {
                    long successTime = Math.max(now, inc.lastFailureAt().get() + 1);
                    inc.lastSuccessAt().set(successTime);
                }
            }
        }
    }

    /**
     * Resolves incidents that have experienced success and have remained quiet for >= recoveryQuietMs.
     */
    public void resolveQuietIncidents() {
        if (openIncidents.isEmpty()) {
            return;
        }

        long now = System.currentTimeMillis();
        List<String> toResolve = new ArrayList<>();

        for (Map.Entry<String, ActiveIncident> entry : openIncidents.entrySet()) {
            ActiveIncident inc = entry.getValue();
            long lastSuccess = inc.lastSuccessAt().get();
            long lastFailure = inc.lastFailureAt().get();
            long effectiveNow = Math.max(now, lastSuccess);
            if (lastSuccess > lastFailure && (effectiveNow - lastFailure) >= recoveryQuietMs) {
                toResolve.add(entry.getKey());
            }
        }

        if (toResolve.isEmpty()) {
            return;
        }

        LocalDateTime resolvedAt = LocalDateTime.now();

        for (String key : toResolve) {
            ActiveIncident inc = openIncidents.remove(key);
            if (inc != null) {
                // 1. Read throttle entry for that key: add un-digested events to incident total
                ThrottleEntry throttle = throttleCache.get(key);
                if (throttle != null) {
                    long unDigested = throttle.accumulatedCount().get() - throttle.initialAlertCount();
                    if (unDigested > 0) {
                        inc.affectedCount().addAndGet(unDigested);
                    }
                }

                // 2. Persist total and resolve incident in database
                if (incidentRepository != null) {
                    try {
                        incidentRepository.updateCountsAndLastSeen(inc.incidentId(), inc.affectedCount().get(), resolvedAt);
                        incidentRepository.resolveIncident(inc.incidentId(), resolvedAt);
                    } catch (Exception e) {
                        log.warn("Failed to mark incident {} resolved in database: {}", inc.incidentId(), e.getMessage());
                    }
                }

                // 3. Clear throttleCache for this key so subsequent failures start a fresh incident
                throttleCache.remove(key);

                // 4. Compute fromTime = startedAt - margin, toTime = min(now, lastFailureAt + margin)
                LocalDateTime fromTime = inc.startedAt().minusMinutes(replayMarginMinutes);
                long lastFailure = inc.lastFailureAt().get();
                LocalDateTime lastFailureTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(lastFailure), ZoneId.systemDefault());
                LocalDateTime failurePlusMargin = lastFailureTime.plusMinutes(replayMarginMinutes);
                LocalDateTime toTime = resolvedAt.isBefore(failurePlusMargin) ? resolvedAt : failurePlusMargin;

                // 5. Send recovery notice
                try {
                    SourceMeta meta = resolveSourceMeta(inc.sourceId());
                    String adminTitle = String.format("[RECOVERED] Ingestion Recovered for %s (%s)", meta.sourceName(), inc.failureType());
                    String adminMessage = String.format(
                            "Ingestion for source '%s' (table '%s') has recovered successfully at %s. " +
                                    "Total affected events during incident: %d (from %s to %s). " +
                                    "To backfill omitted events, trigger Replay via: " +
                                    "POST /v1/admin/sources/%s/replay { \"fromTime\": \"%s\", \"toTime\": \"%s\" }",
                            meta.sourceName(), inc.tableName() != null ? inc.tableName() : "N/A", resolvedAt,
                            inc.affectedCount().get(), fromTime, toTime,
                            inc.sourceId(), fromTime, toTime);

                    String vendorTitle = String.format("Ingestion Resumed: %s", meta.sourceName());
                    String vendorMessage = String.format(
                            "Live log ingestion for source '%s' has recovered and is now streaming successfully. " +
                                    "Platform administrators will backfill any unmapped records from raw storage.",
                            meta.sourceName());

                    AlertPayload payload = new AlertPayload(
                            "RECOVERY_" + inc.failureType(), "INFO", inc.sourceId(), meta.sourceName(), meta.vendorId(), meta.vendorName(),
                            inc.tableName(), "Incident resolved", inc.affectedCount().get(), Collections.emptySet(),
                            Instant.now(), adminMessage,
                            String.format("POST /v1/admin/sources/%s/replay { \"fromTime\": \"%s\", \"toTime\": \"%s\" }",
                                    inc.sourceId(), fromTime, toTime)
                    );

                    getAlertDispatcher().dispatchAlert(payload, adminTitle, adminMessage, meta.ownerUserId(), vendorTitle, vendorMessage);
                } catch (Exception e) {
                    log.warn("Failed to dispatch recovery alert for source {} (key {}): {}", inc.sourceId(), key, e.getMessage());
                }
            }
        }
    }

    /**
     * Stale incidents: sends a 24-hour reminder for incidents that remain open without any recovery.
     */
    public void sendStaleIncidentReminders() {
        if (openIncidents.isEmpty()) {
            return;
        }

        long now = System.currentTimeMillis();
        long reminderIntervalMs = 24 * 60 * 60 * 1000L; // 24 hours

        for (ActiveIncident inc : openIncidents.values()) {
            if (inc.lastSuccessAt().get() <= inc.lastFailureAt().get()
                    && (now - inc.lastReminderAt().get()) >= reminderIntervalMs) {
                inc.lastReminderAt().set(now);
                try {
                    SourceMeta meta = resolveSourceMeta(inc.sourceId());
                    String adminTitle = String.format("[UNRESOLVED] Ingestion Failure Persisting for %s (%s)", meta.sourceName(), inc.failureType());
                    String adminMessage = String.format(
                            "Ingestion failure for source '%s' (table '%s', failure '%s') has remained unresolved for >24 hours. " +
                                    "Total affected events so far: %d (started at: %s). " +
                                    "Action required: Check database and source configuration, then trigger Replay via POST /v1/admin/sources/%s/replay.",
                            meta.sourceName(), inc.tableName() != null ? inc.tableName() : "N/A", inc.failureType(),
                            inc.affectedCount().get(), inc.startedAt(), inc.sourceId());

                    AlertPayload payload = new AlertPayload(
                            "UNRESOLVED_" + inc.failureType(), "WARN", inc.sourceId(), meta.sourceName(), meta.vendorId(), meta.vendorName(),
                            inc.tableName(), "Incident unresolved after 24h", inc.affectedCount().get(), Collections.emptySet(),
                            Instant.now(), adminMessage,
                            String.format("POST /v1/admin/sources/%s/replay", inc.sourceId())
                    );

                    getAlertDispatcher().dispatchAlert(payload, adminTitle, adminMessage, null, null, null);
                } catch (Exception e) {
                    log.warn("Failed to dispatch reminder for incident {}: {}", inc.incidentId(), e.getMessage());
                }
            }
        }
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
        openIncidents.clear();
    }

    public Map<String, ThrottleEntry> getThrottleCache() {
        return throttleCache;
    }

    public Map<String, ActiveIncident> getOpenIncidents() {
        return openIncidents;
    }
}
