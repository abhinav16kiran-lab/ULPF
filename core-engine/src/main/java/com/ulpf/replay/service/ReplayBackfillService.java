package com.ulpf.replay.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ulpf.common.db.ClickHouseIngestionRepository;
import com.ulpf.common.db.ClickHouseIngestionRepository.CanonicalEventRecord;
import com.ulpf.common.db.ClickHouseIngestionRepository.RawEventRecord;
import com.ulpf.common.db.MappingRepository;
import com.ulpf.common.db.MappingRepository.MappingVersionRecord;
import com.ulpf.common.db.SourceRepository;
import com.ulpf.common.db.SourceRepository.SourceRecord;
import com.ulpf.common.db.VendorRepository;
import com.ulpf.common.db.VendorRepository.VendorRecord;
import com.ulpf.controlplane.service.NotificationService;
import com.ulpf.mapping.service.DynamicSchemaProvisioningService;
import com.ulpf.mapping.service.MappingTransformer;
import com.ulpf.replay.model.ReplayJobStatus;
import com.ulpf.replay.model.ReplayRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class ReplayBackfillService {

    private static final Logger log = LoggerFactory.getLogger(ReplayBackfillService.class);

    private final ClickHouseIngestionRepository clickHouseRepo;
    private final MappingRepository mappingRepo;
    private final SourceRepository sourceRepo;
    private final DynamicSchemaProvisioningService schemaService;
    private final MappingTransformer transformer;
    private final NotificationService notificationService;
    private final VendorRepository vendorRepo;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // In-memory job state tracking per sourceId
    private final ConcurrentHashMap<String, ReplayJobStatus> activeJobs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicBoolean> cancellationTokens = new ConcurrentHashMap<>();

    // Bounded executor pool to prevent ClickHouse resource saturation
    private final ExecutorService executorService;

    public ReplayBackfillService(
            ClickHouseIngestionRepository clickHouseRepo,
            MappingRepository mappingRepo,
            SourceRepository sourceRepo,
            DynamicSchemaProvisioningService schemaService,
            MappingTransformer transformer,
            @Autowired(required = false) NotificationService notificationService) {
        this(clickHouseRepo, mappingRepo, sourceRepo, schemaService, transformer, notificationService, null, Executors.newFixedThreadPool(2));
    }

    @Autowired
    public ReplayBackfillService(
            ClickHouseIngestionRepository clickHouseRepo,
            MappingRepository mappingRepo,
            SourceRepository sourceRepo,
            DynamicSchemaProvisioningService schemaService,
            MappingTransformer transformer,
            @Autowired(required = false) NotificationService notificationService,
            @Autowired(required = false) VendorRepository vendorRepo) {
        this(clickHouseRepo, mappingRepo, sourceRepo, schemaService, transformer, notificationService, vendorRepo, Executors.newFixedThreadPool(2));
    }

    public ReplayBackfillService(
            ClickHouseIngestionRepository clickHouseRepo,
            MappingRepository mappingRepo,
            SourceRepository sourceRepo,
            DynamicSchemaProvisioningService schemaService,
            MappingTransformer transformer,
            NotificationService notificationService,
            VendorRepository vendorRepo,
            ExecutorService executorService) {
        this.clickHouseRepo = clickHouseRepo;
        this.mappingRepo = mappingRepo;
        this.sourceRepo = sourceRepo;
        this.schemaService = schemaService;
        this.transformer = transformer != null ? transformer : new MappingTransformer();
        this.notificationService = notificationService;
        this.vendorRepo = vendorRepo;
        this.executorService = executorService != null ? executorService : Executors.newFixedThreadPool(2);
    }

    public synchronized ReplayJobStatus triggerReplayAsync(String sourceId, ReplayRequest request) {
        ReplayRequest req = request != null ? request : new ReplayRequest(null, null, 1000);

        // Guard: check if replay is already running for this source
        ReplayJobStatus existing = activeJobs.get(sourceId);
        if (existing != null && ("RUNNING".equals(existing.status()) || "QUEUED".equals(existing.status()))) {
            throw new IllegalStateException("Replay job " + existing.jobId() + " is already running for source: " + sourceId);
        }

        // 1. Validate source
        SourceRecord source = sourceRepo.findById(sourceId)
                .orElseThrow(() -> new IllegalArgumentException("Source not found: " + sourceId));

        // 2. Validate active mapping
        MappingVersionRecord activeMapping = mappingRepo.findActiveBySourceId(sourceId)
                .orElseThrow(() -> new IllegalStateException("No active mapping version found for source: " + sourceId));

        // 3. Resolve target table
        String targetTable = resolveTargetTable(activeMapping, source.sourceName());

        // 4. Verify or provision target table
        if (!schemaService.tableExists("ulpf_events", targetTable)) {
            log.info("Target table ulpf_events.{} does not exist. Provisioning dynamic table now...", targetTable);
            targetTable = schemaService.provisionSchemaForSource(sourceId, source.sourceName(), activeMapping.mappingJson());
        }

        // 5. Total count estimate
        long totalEstimate = clickHouseRepo.countRawEventsForSource(sourceId, req.fromTime(), req.toTime());
        String jobId = "replay_" + UUID.randomUUID().toString().substring(0, 8);

        if (totalEstimate == 0) {
            log.info("No raw events found to replay for sourceId {}", sourceId);
            ReplayJobStatus completed = ReplayJobStatus.initial(jobId, sourceId, targetTable, 0).completed(0, 0, 0, 0);
            activeJobs.put(sourceId, completed);
            return completed;
        }

        ReplayJobStatus initialStatus = ReplayJobStatus.initial(jobId, sourceId, targetTable, totalEstimate);
        activeJobs.put(sourceId, initialStatus);
        AtomicBoolean cancelToken = new AtomicBoolean(false);
        cancellationTokens.put(sourceId, cancelToken);

        final String finalTargetTable = targetTable;
        executorService.submit(() -> executeReplay(jobId, source, activeMapping, finalTargetTable, req, cancelToken));

        return initialStatus;
    }

    public void executeReplay(
            String jobId,
            SourceRecord source,
            MappingVersionRecord activeMapping,
            String targetTable,
            ReplayRequest req,
            AtomicBoolean cancelToken) {

        String sourceId = source.sourceId();
        log.info("Starting replay execution [{}] for source {} into table ulpf_events.{}", jobId, sourceId, targetTable);

        activeJobs.computeIfPresent(sourceId, (k, s) -> s.withProgress(0, 0, 0, 0));

        long processed = 0;
        long inserted = 0;
        long skipped = 0;
        long failed = 0;

        LocalDateTime cursorTime = null;
        String cursorEventId = null;
        int chunkSize = req.effectiveChunkSize();

        try {
            JsonNode mappingRoot = objectMapper.readTree(activeMapping.mappingJson());
            String sensorField = null;
            if (mappingRoot.has("metadata") && mappingRoot.get("metadata").has("sensor_field")) {
                sensorField = mappingRoot.get("metadata").get("sensor_field").asText();
            } else if (mappingRoot.has("sensor_field")) {
                sensorField = mappingRoot.get("sensor_field").asText();
            }

            // Load historical mappings for fallback resolution
            List<JsonNode> historicalRoots = new ArrayList<>();
            for (MappingVersionRecord hist : mappingRepo.findAllValidVersionsBySourceId(sourceId)) {
                if (!hist.mappingId().equals(activeMapping.mappingId()) && hist.mappingJson() != null) {
                    try {
                        historicalRoots.add(objectMapper.readTree(hist.mappingJson()));
                    } catch (Exception ignored) {}
                }
            }

            while (true) {
                if (cancelToken.get()) {
                    log.info("Replay job [{}] for source {} was cancelled.", jobId, sourceId);
                    activeJobs.compute(sourceId, (k, s) -> s != null ? s.cancelled() : ReplayJobStatus.initial(jobId, sourceId, targetTable, 0).cancelled());
                    return;
                }

                List<RawEventRecord> chunk = clickHouseRepo.readRawEventsChunk(
                        sourceId, req.fromTime(), req.toTime(), cursorTime, cursorEventId, chunkSize);

                if (chunk.isEmpty()) {
                    break; // Replay complete!
                }

                // 1. Check existing event_ids in target table to prevent duplicates
                List<String> eventIds = chunk.stream().map(RawEventRecord::eventId).toList();
                Set<String> existingIds = clickHouseRepo.findExistingEventIds(targetTable, eventIds);

                List<CanonicalEventRecord> toInsert = new ArrayList<>();

                for (RawEventRecord raw : chunk) {
                    processed++;

                    if (existingIds != null && existingIds.contains(raw.eventId())) {
                        skipped++;
                        continue;
                    }

                    try {
                        var transformRes = transformer.transform(
                                raw.rawPayload(), mappingRoot, historicalRoots, sensorField);

                        CanonicalEventRecord canonical = new CanonicalEventRecord(
                                targetTable,
                                raw.eventId(),
                                raw.lineageId() != null ? raw.lineageId() : UUID.randomUUID().toString(),
                                raw.vendorId(),
                                raw.sourceId(),
                                activeMapping.version(),
                                raw.receivedAt(),
                                transformRes.numericValue(),
                                transformRes.canonicalPayloadJson(),
                                transformRes.rawUnmappedJson(),
                                transformRes.mappedFields()
                        );
                        toInsert.add(canonical);
                    } catch (Exception e) {
                        log.warn("Failed to transform raw event {}: {}", raw.eventId(), e.getMessage());
                        failed++;
                    }
                }

                // 2. Batch insert into dynamic table
                if (!toInsert.isEmpty()) {
                    int count = clickHouseRepo.batchInsertDynamicRecords(targetTable, toInsert);
                    inserted += count;
                }

                // 3. Advance cursor
                RawEventRecord last = chunk.get(chunk.size() - 1);
                cursorTime = last.receivedAt();
                cursorEventId = last.eventId();

                // 4. Update status
                final long p = processed;
                final long ins = inserted;
                final long sk = skipped;
                final long f = failed;
                activeJobs.compute(sourceId, (k, s) -> s != null ? s.withProgress(p, ins, sk, f) : ReplayJobStatus.initial(jobId, sourceId, targetTable, p).withProgress(p, ins, sk, f));
            }

            final long finalP = processed;
            final long finalIns = inserted;
            final long finalSk = skipped;
            final long finalF = failed;
            activeJobs.compute(sourceId, (k, s) -> s != null ? s.completed(finalP, finalIns, finalSk, finalF) : ReplayJobStatus.initial(jobId, sourceId, targetTable, finalP).completed(finalP, finalIns, finalSk, finalF));
            log.info("Replay job [{}] COMPLETED: processed={}, inserted={}, skipped={}, failed={}",
                    jobId, processed, inserted, skipped, failed);

            if (notificationService != null) {
                String notifyUserId = null;
                if (vendorRepo != null && source.vendorId() != null) {
                    notifyUserId = vendorRepo.findById(source.vendorId())
                            .map(VendorRecord::ownerUserId)
                            .orElse(null);
                }
                if (notifyUserId != null) {
                    notificationService.createNotification(
                            notifyUserId,
                            "Replay Completed",
                            String.format("Historical logs replayed for '%s'. Total: %d, Inserted: %d, Skipped: %d",
                                    source.sourceName(), processed, inserted, skipped)
                    );
                }
            }

        } catch (Exception e) {
            log.error("Replay job [{}] FAILED for source {}: {}", jobId, sourceId, e.getMessage(), e);
            activeJobs.compute(sourceId, (k, s) -> s != null ? s.failed(e.getMessage()) : ReplayJobStatus.initial(jobId, sourceId, targetTable, 0).failed(e.getMessage()));
        } finally {
            cancellationTokens.remove(sourceId);
        }
    }

    public Optional<ReplayJobStatus> getJobStatus(String sourceId) {
        return Optional.ofNullable(activeJobs.get(sourceId));
    }

    public boolean cancelReplay(String sourceId) {
        AtomicBoolean token = cancellationTokens.get(sourceId);
        if (token != null) {
            token.set(true);
            return true;
        }
        return false;
    }

    private String resolveTargetTable(MappingVersionRecord mapping, String sourceName) {
        try {
            JsonNode root = objectMapper.readTree(mapping.mappingJson());
            if (root.has("metadata") && root.get("metadata").has("target_table")) {
                String tbl = root.get("metadata").get("target_table").asText();
                if (tbl != null && !tbl.isBlank()) return tbl;
            }
            if (root.has("target_table")) {
                String tbl = root.get("target_table").asText();
                if (tbl != null && !tbl.isBlank()) return tbl;
            }
        } catch (Exception ignored) {}
        return schemaService.constructTableName(sourceName);
    }
}
