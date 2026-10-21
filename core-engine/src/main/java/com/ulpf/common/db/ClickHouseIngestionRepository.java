package com.ulpf.common.db;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

/**
 * High-throughput ClickHouse repository for raw and canonical log event ingestion.
 * Features in-memory queue buffers with batch size flush (500), 1s scheduled timer,
 * lineage backtracking lookup, and @PreDestroy container shutdown flush.
 */
@Repository
public class ClickHouseIngestionRepository {

    private static final Logger log = LoggerFactory.getLogger(ClickHouseIngestionRepository.class);

    private final JdbcTemplate clickhouseJdbcTemplate;
    private com.ulpf.integrity.service.BatchIntegrityService batchIntegrityService;
    private com.ulpf.dataplane.service.TableIngestionFailureNotificationService tableFailureNotificationService;
    private final Queue<RawEventRecord> bufferQueue = new ConcurrentLinkedQueue<>();
    private final Queue<CanonicalEventRecord> canonicalQueue = new ConcurrentLinkedQueue<>();
    
    // In-memory ring buffer of recent raw events for fast lineage backtracking
    private final Queue<RawEventRecord> recentRawHistory = new ConcurrentLinkedQueue<>();
    private static final int MAX_HISTORY_SIZE = 5000;

    @Value("${clickhouse.ingestion.batch-size:500}")
    private int batchSize = 500;


    public ClickHouseIngestionRepository(@Qualifier("clickhouseJdbcTemplate") JdbcTemplate clickhouseJdbcTemplate) {
        this.clickhouseJdbcTemplate = clickhouseJdbcTemplate;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public void setBatchIntegrityService(com.ulpf.integrity.service.BatchIntegrityService batchIntegrityService) {
        this.batchIntegrityService = batchIntegrityService;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setTableFailureNotificationService(com.ulpf.dataplane.service.TableIngestionFailureNotificationService tableFailureNotificationService) {
        this.tableFailureNotificationService = tableFailureNotificationService;
    }

    public record RawEventRecord(
        String eventId,
        String lineageId,
        String vendorId,
        String sourceId,
        Integer mappingVersion,
        LocalDateTime receivedAt,
        String rawPayload
    ) {
        public RawEventRecord {
            if (receivedAt == null) {
                receivedAt = LocalDateTime.now();
            }
        }
    }

    public record CanonicalEventRecord(
        String targetTable,
        String eventId,
        String lineageId,
        String vendorId,
        String sourceId,
        Integer mappingVersion,
        LocalDateTime timestamp,
        Double numericValue,
        String canonicalPayload,
        String rawUnmapped,
        java.util.Map<String, Object> mappedFields
    ) {
        public CanonicalEventRecord(
            String eventId,
            String lineageId,
            String vendorId,
            String sourceId,
            Integer mappingVersion,
            LocalDateTime timestamp,
            Double numericValue,
            String canonicalPayload,
            String rawUnmapped
        ) {
            this(null, eventId, lineageId, vendorId, sourceId, mappingVersion, timestamp, numericValue, canonicalPayload, rawUnmapped, java.util.Collections.emptyMap());
        }

        public CanonicalEventRecord(
            String eventId,
            String lineageId,
            String vendorId,
            String sourceId,
            Integer mappingVersion,
            LocalDateTime timestamp,
            Double numericValue,
            String canonicalPayload
        ) {
            this(null, eventId, lineageId, vendorId, sourceId, mappingVersion, timestamp, numericValue, canonicalPayload, "{}", java.util.Collections.emptyMap());
        }
    }

    private final java.util.Map<String, List<String>> tableColumnsCache = new java.util.concurrent.ConcurrentHashMap<>();

    public void invalidateColumnsCache(String tableName) {
        if (tableName != null) {
            tableColumnsCache.remove(tableName);
        }
    }

    private List<String> getColumnsForTable(String tableName) {
        if (tableName == null || tableName.isBlank() || clickhouseJdbcTemplate == null) {
            return java.util.Collections.emptyList();
        }
        return tableColumnsCache.computeIfAbsent(tableName, tbl -> {
            try {
                return clickhouseJdbcTemplate.query(
                        "SELECT name FROM system.columns WHERE database = 'ulpf_events' AND table = ? ORDER BY position ASC",
                        (rs, rowNum) -> rs.getString("name"),
                        tbl
                );
            } catch (Exception e) {
                log.warn("Could not query columns for table ulpf_events.{}: {}", tbl, e.getMessage());
                return java.util.Collections.emptyList();
            }
        });
    }

    /**
     * Enqueues a raw event record into the in-memory buffer and recent history tracker.
     */
    public void enqueue(RawEventRecord event) {
        bufferQueue.add(event);
        recentRawHistory.add(event);
        while (recentRawHistory.size() > MAX_HISTORY_SIZE) {
            recentRawHistory.poll();
        }
        if (bufferQueue.size() >= batchSize) {
            flush();
        }
    }

    /**
     * Enqueues an emitted canonical event record into the canonical buffer.
     */
    public void enqueueCanonical(CanonicalEventRecord event) {
        canonicalQueue.add(event);
        if (canonicalQueue.size() >= batchSize) {
            flushCanonical();
        }
    }

    /**
     * Background scheduled timer running every 1000 ms (1 sec) to flush pending events to ClickHouse.
     */
    @Scheduled(fixedDelay = 1000)
    public void scheduledFlush() {
        if (!bufferQueue.isEmpty()) {
            flush();
        }
        if (!canonicalQueue.isEmpty()) {
            flushCanonical();
        }
    }

    /**
     * Synchronously flushes queued raw events to ClickHouse ulpf_raw.raw_events.
     */
    public synchronized void flush() {
        if (bufferQueue.isEmpty()) {
            return;
        }

        List<RawEventRecord> batch = new ArrayList<>();
        RawEventRecord item;
        while ((item = bufferQueue.poll()) != null) {
            batch.add(item);
        }

        if (batch.isEmpty()) {
            return;
        }

        try {
            log.info("Flushing batch of {} raw log events to ClickHouse (ulpf_raw.raw_events)...", batch.size());
            String sql = """
                INSERT INTO ulpf_raw.raw_events (event_id, lineage_id, vendor_id, source_id, mapping_version, received_at, raw_payload)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;

            clickhouseJdbcTemplate.batchUpdate(sql, batch, batch.size(), (ps, record) -> {
                ps.setString(1, record.eventId());
                ps.setString(2, record.lineageId());
                ps.setString(3, record.vendorId());
                ps.setString(4, record.sourceId());
                if (record.mappingVersion() != null) {
                    ps.setInt(5, record.mappingVersion());
                } else {
                    ps.setNull(5, java.sql.Types.INTEGER);
                }
                ps.setTimestamp(6, record.receivedAt() != null ? Timestamp.valueOf(record.receivedAt()) : Timestamp.valueOf(LocalDateTime.now()));
                ps.setString(7, record.rawPayload());
            });

            log.info("Successfully flushed {} raw log events to ClickHouse.", batch.size());

            // Generate Merkle Tree tamper-evident integrity block for batch
            if (batchIntegrityService != null) {
                try {
                    batchIntegrityService.processRawBatch(batch);
                } catch (Exception e) {
                    log.warn("Failed to process batch integrity block: {}", e.getMessage());
                }
            }

        } catch (Exception e) {
            log.warn("Flush exception encountered for batch of {} events: {}", batch.size(), e.getMessage());
            boolean alreadyCommitted = false;
            try {
                if (!batch.isEmpty() && batch.get(0).eventId() != null) {
                    String probeSql = "SELECT count() FROM ulpf_raw.raw_events WHERE event_id = ?";
                    Integer count = clickhouseJdbcTemplate.queryForObject(probeSql, Integer.class, batch.get(0).eventId());
                    if (count != null && count > 0) {
                        alreadyCommitted = true;
                    }
                }
            } catch (Exception probeEx) {
                log.debug("Probe query to check if batch was committed failed: {}", probeEx.getMessage());
            }

            if (alreadyCommitted) {
                log.info("Batch of {} events was already committed in ClickHouse before timeout. Skipping duplicate insert.", batch.size());
                if (batchIntegrityService != null) {
                    try {
                        batchIntegrityService.processRawBatch(batch);
                    } catch (Exception ex) {
                        log.warn("Failed to process batch integrity block on recovery: {}", ex.getMessage());
                    }
                }
            } else {
                log.error("Failed to flush batch of {} raw log events to ClickHouse! Re-enqueuing batch.", batch.size(), e);
                // Re-enqueue batch in case of genuine transient network failure
                bufferQueue.addAll(batch);
            }
        }
    }

    /**
     * Synchronously flushes queued canonical events dynamically to their respective ClickHouse tables.
     */
    public synchronized void flushCanonical() {
        if (canonicalQueue.isEmpty()) {
            return;
        }

        List<CanonicalEventRecord> batch = new ArrayList<>();
        CanonicalEventRecord item;
        while ((item = canonicalQueue.poll()) != null) {
            batch.add(item);
        }

        if (batch.isEmpty() || clickhouseJdbcTemplate == null) {
            return;
        }

        // Group batch by dynamic targetTable
        java.util.Map<String, List<CanonicalEventRecord>> byTable = new java.util.LinkedHashMap<>();
        java.util.Map<String, Integer> missingTargetTableCounts = new java.util.HashMap<>();

        for (CanonicalEventRecord rec : batch) {
            if (rec.targetTable() != null && !rec.targetTable().isBlank()) {
                byTable.computeIfAbsent(rec.targetTable(), k -> new ArrayList<>()).add(rec);
            } else {
                if (rec.sourceId() != null) {
                    missingTargetTableCounts.merge(rec.sourceId(), 1, Integer::sum);
                }
                log.debug("Skipping dynamic table insert for event {} (no targetTable defined)", rec.eventId());
            }
        }

        if (tableFailureNotificationService != null && !missingTargetTableCounts.isEmpty()) {
            missingTargetTableCounts.forEach((sourceId, count) ->
                    tableFailureNotificationService.notifyNoTargetTable(sourceId, count));
        }

        for (java.util.Map.Entry<String, List<CanonicalEventRecord>> entry : byTable.entrySet()) {
            String tableName = entry.getKey().trim();
            List<CanonicalEventRecord> tableBatch = entry.getValue();
            if (!tableName.matches("^[a-zA-Z0-9_]+$")) {
                log.warn("Invalid dynamic table name: '{}', skipping batch", tableName);
                continue;
            }

            try {
                List<String> cols = getColumnsForTable(tableName);
                if (cols.isEmpty()) {
                    log.debug("Table ulpf_events.{} has no known columns or does not exist yet. Skipping flush of {} events.", tableName, tableBatch.size());
                    if (tableFailureNotificationService != null) {
                        java.util.Map<String, Integer> countsBySource = new java.util.HashMap<>();
                        for (CanonicalEventRecord r : tableBatch) {
                            if (r.sourceId() != null) {
                                countsBySource.merge(r.sourceId(), 1, Integer::sum);
                            }
                        }
                        countsBySource.forEach((sourceId, count) ->
                                tableFailureNotificationService.notifyTableMissing(sourceId, tableName, count));
                    }
                    continue;
                }

                String colsSql = String.join(", ", cols);
                String placeholders = String.join(", ", java.util.Collections.nCopies(cols.size(), "?"));
                String sql = "INSERT INTO ulpf_events." + tableName + " (" + colsSql + ") VALUES (" + placeholders + ")";

                clickhouseJdbcTemplate.batchUpdate(sql, tableBatch, tableBatch.size(), (ps, record) -> {
                    for (int i = 0; i < cols.size(); i++) {
                        int paramIndex = i + 1;
                        String col = cols.get(i).toLowerCase();
                        switch (col) {
                            case "event_id" -> ps.setString(paramIndex, record.eventId());
                            case "lineage_id" -> ps.setString(paramIndex, record.lineageId());
                            case "vendor_id" -> ps.setString(paramIndex, record.vendorId());
                            case "source_id" -> ps.setString(paramIndex, record.sourceId());
                            case "mapping_version" -> {
                                if (record.mappingVersion() != null) {
                                    ps.setInt(paramIndex, record.mappingVersion());
                                } else {
                                    ps.setNull(paramIndex, java.sql.Types.INTEGER);
                                }
                            }
                            case "timestamp" -> ps.setTimestamp(paramIndex, record.timestamp() != null ? Timestamp.valueOf(record.timestamp()) : Timestamp.valueOf(LocalDateTime.now()));
                            case "numeric_value" -> {
                                if (record.numericValue() != null) {
                                    ps.setDouble(paramIndex, record.numericValue());
                                } else {
                                    ps.setNull(paramIndex, java.sql.Types.DOUBLE);
                                }
                            }
                            case "raw_unmapped" -> ps.setString(paramIndex, record.rawUnmapped() != null ? record.rawUnmapped() : "{}");
                            case "canonical_payload" -> ps.setString(paramIndex, record.canonicalPayload() != null ? record.canonicalPayload() : "{}");
                            default -> {
                                Object val = record.mappedFields() != null ? record.mappedFields().get(col) : null;
                                if (val != null) {
                                    ps.setString(paramIndex, String.valueOf(val));
                                } else {
                                    ps.setNull(paramIndex, java.sql.Types.VARCHAR);
                                }
                            }
                        }
                    }
                });

                log.info("Successfully flushed {} dynamic events into ulpf_events.{}", tableBatch.size(), tableName);
            } catch (Exception e) {
                log.warn("Failed to flush dynamic events to ulpf_events.{}: {}", tableName, e.getMessage());
                if (tableFailureNotificationService != null) {
                    java.util.Map<String, Integer> countsBySource = new java.util.HashMap<>();
                    for (CanonicalEventRecord r : tableBatch) {
                        if (r.sourceId() != null) {
                            countsBySource.merge(r.sourceId(), 1, Integer::sum);
                        }
                    }
                    countsBySource.forEach((sourceId, count) ->
                            tableFailureNotificationService.notifyInsertFailure(sourceId, tableName, count, e.getMessage()));
                }
            }
        }

    }

    /**
     * Looks up raw event records associated with a specific lineage ID for raw-to-aggregate backtracking.
     */
    public List<RawEventRecord> findRawEventsByLineageId(String lineageId) {
        if (lineageId == null || lineageId.isBlank()) {
            return Collections.emptyList();
        }

        // First check ClickHouse if available
        try {
            String sql = """
                SELECT event_id, lineage_id, vendor_id, source_id, mapping_version, received_at, raw_payload
                FROM ulpf_raw.raw_events
                WHERE lineage_id = ?
                ORDER BY received_at ASC
                """;
            List<RawEventRecord> dbResults = clickhouseJdbcTemplate.query(sql, (rs, rowNum) -> new RawEventRecord(
                rs.getString("event_id"),
                rs.getString("lineage_id"),
                rs.getString("vendor_id"),
                rs.getString("source_id"),
                rs.getObject("mapping_version") != null ? rs.getInt("mapping_version") : null,
                rs.getTimestamp("received_at") != null ? rs.getTimestamp("received_at").toLocalDateTime() : null,
                rs.getString("raw_payload")
            ), lineageId);

            if (!dbResults.isEmpty()) {
                return dbResults;
            }
        } catch (Exception e) {
            log.warn("ClickHouse query for lineage_id {} failed or offline, falling back to in-memory history: {}", lineageId, e.getMessage());
        }

        // Fallback to in-memory recent raw history tracker
        return recentRawHistory.stream()
                .filter(r -> lineageId.equals(r.lineageId()))
                .collect(Collectors.toList());
    }

    @PreDestroy
    public void shutdownFlush() {
        log.info("Container shutdown signal received. Triggering final synchronous flush of raw and canonical buffers...");
        flush();
        flushCanonical();
    }

    public int getQueueSize() {
        return bufferQueue.size();
    }

    public int getCanonicalQueueSize() {
        return canonicalQueue.size();
    }

    /**
     * Read raw events for a source with cursor pagination (received_at, event_id).
     */
    public List<RawEventRecord> readRawEventsChunk(
            String sourceId,
            LocalDateTime fromTime,
            LocalDateTime toTime,
            LocalDateTime cursorTime,
            String cursorEventId,
            int limit) {
        if (clickhouseJdbcTemplate == null || sourceId == null) {
            return Collections.emptyList();
        }

        StringBuilder sql = new StringBuilder();
        List<Object> params = new ArrayList<>();

        sql.append("SELECT event_id, lineage_id, vendor_id, source_id, mapping_version, received_at, raw_payload ");
        sql.append("FROM ulpf_raw.raw_events WHERE source_id = ? ");
        params.add(sourceId);

        if (fromTime != null) {
            sql.append("AND received_at >= ? ");
            params.add(Timestamp.valueOf(fromTime));
        }
        if (toTime != null) {
            sql.append("AND received_at <= ? ");
            params.add(Timestamp.valueOf(toTime));
        }

        // Cursor pagination (received_at, event_id)
        if (cursorTime != null && cursorEventId != null) {
            sql.append("AND (received_at > ? OR (received_at = ? AND event_id > ?)) ");
            params.add(Timestamp.valueOf(cursorTime));
            params.add(Timestamp.valueOf(cursorTime));
            params.add(cursorEventId);
        }

        sql.append("ORDER BY received_at ASC, event_id ASC LIMIT ?");
        params.add(limit);

        try {
            return clickhouseJdbcTemplate.query(sql.toString(), (rs, rowNum) -> new RawEventRecord(
                    rs.getString("event_id"),
                    rs.getString("lineage_id"),
                    rs.getString("vendor_id"),
                    rs.getString("source_id"),
                    rs.getObject("mapping_version") != null ? rs.getInt("mapping_version") : null,
                    rs.getTimestamp("received_at") != null ? rs.getTimestamp("received_at").toLocalDateTime() : null,
                    rs.getString("raw_payload")
            ), params.toArray());
        } catch (Exception e) {
            log.error("Failed to read raw events chunk for source {}: {}", sourceId, e.getMessage());
            throw new RuntimeException("Failed to read raw events from ClickHouse for source " + sourceId + ": " + e.getMessage(), e);
        }
    }

    /**
     * Total event count estimate for progress tracking.
     */
    public long countRawEventsForSource(String sourceId, LocalDateTime fromTime, LocalDateTime toTime) {
        if (clickhouseJdbcTemplate == null || sourceId == null) {
            return 0L;
        }
        StringBuilder sql = new StringBuilder("SELECT count() FROM ulpf_raw.raw_events WHERE source_id = ? ");
        List<Object> params = new ArrayList<>();
        params.add(sourceId);

        if (fromTime != null) {
            sql.append("AND received_at >= ? ");
            params.add(Timestamp.valueOf(fromTime));
        }
        if (toTime != null) {
            sql.append("AND received_at <= ? ");
            params.add(Timestamp.valueOf(toTime));
        }

        try {
            Long count = clickhouseJdbcTemplate.queryForObject(sql.toString(), Long.class, params.toArray());
            return count != null ? count : 0L;
        } catch (Exception e) {
            log.warn("Failed to count raw events for source {}: {}", sourceId, e.getMessage());
            throw new RuntimeException("Failed to count raw events from ClickHouse for source " + sourceId + ": " + e.getMessage(), e);
        }
    }

    /**
     * Find event_ids that already exist in the target dynamic table (for deduplication).
     */
    public Set<String> findExistingEventIds(String tableName, Collection<String> eventIds) {
        if (clickhouseJdbcTemplate == null || tableName == null || eventIds == null || eventIds.isEmpty()) {
            return Collections.emptySet();
        }
        try {
            String inClause = eventIds.stream()
                    .map(id -> "'" + id.replace("'", "''") + "'")
                    .collect(Collectors.joining(","));
            String sql = "SELECT event_id FROM ulpf_events." + tableName + " WHERE event_id IN (" + inClause + ")";
            List<String> found = clickhouseJdbcTemplate.query(sql, (rs, rowNum) -> rs.getString("event_id"));
            return new HashSet<>(found);
        } catch (Exception e) {
            log.warn("Failed to check existing event_ids in ulpf_events.{}: {}", tableName, e.getMessage());
            throw new RuntimeException("Failed to check existing event_ids in ulpf_events." + tableName + ": " + e.getMessage(), e);
        }
    }

    /**
     * Checks if ClickHouse memory usage exceeds 80% threshold for adaptive throttling.
     */
    public boolean isMemoryPressureHigh() {
        if (clickhouseJdbcTemplate == null) return false;
        try {
            Long memoryUsage = clickhouseJdbcTemplate.queryForObject(
                "SELECT value FROM system.metrics WHERE metric = 'MemoryTracking'", Long.class);
            Long maxMemory = clickhouseJdbcTemplate.queryForObject(
                "SELECT value FROM system.settings WHERE name = 'max_memory_usage'", Long.class);
            if (memoryUsage != null && maxMemory != null && maxMemory > 0) {
                return (double) memoryUsage / maxMemory > 0.80;
            }
        } catch (Exception ignored) {}
        return false;
    }

    /**
     * Synchronous batch insert into dynamic table during replay.
     */
    public int batchInsertDynamicRecords(String tableName, List<CanonicalEventRecord> records) {
        if (clickhouseJdbcTemplate == null || tableName == null || records == null || records.isEmpty()) {
            return 0;
        }

        List<String> cols = getColumnsForTable(tableName);
        if (cols.isEmpty()) {
            invalidateColumnsCache(tableName);
            cols = getColumnsForTable(tableName);
        }
        if (cols.isEmpty()) {
            throw new IllegalStateException("Dynamic table ulpf_events." + tableName + " has no columns or does not exist");
        }

        String colNames = String.join(", ", cols);
        String placeholders = String.join(", ", Collections.nCopies(cols.size(), "?"));
        String insertSql = "INSERT INTO ulpf_events." + tableName + " (" + colNames + ") VALUES (" + placeholders + ")";

        final List<String> finalCols = cols;
        clickhouseJdbcTemplate.batchUpdate(insertSql, records, records.size(), (ps, record) -> {
            for (int i = 0; i < finalCols.size(); i++) {
                int paramIndex = i + 1;
                String col = finalCols.get(i).toLowerCase();
                switch (col) {
                    case "event_id" -> ps.setString(paramIndex, record.eventId());
                    case "lineage_id" -> ps.setString(paramIndex, record.lineageId());
                    case "vendor_id" -> ps.setString(paramIndex, record.vendorId());
                    case "source_id" -> ps.setString(paramIndex, record.sourceId());
                    case "mapping_version" -> {
                        if (record.mappingVersion() != null) {
                            ps.setInt(paramIndex, record.mappingVersion());
                        } else {
                            ps.setNull(paramIndex, java.sql.Types.INTEGER);
                        }
                    }
                    case "timestamp" -> ps.setTimestamp(paramIndex, record.timestamp() != null
                            ? Timestamp.valueOf(record.timestamp())
                            : Timestamp.valueOf(LocalDateTime.now()));
                    case "numeric_value" -> {
                        if (record.numericValue() != null) {
                            ps.setDouble(paramIndex, record.numericValue());
                        } else {
                            ps.setNull(paramIndex, java.sql.Types.DOUBLE);
                        }
                    }
                    case "raw_unmapped" -> ps.setString(paramIndex, record.rawUnmapped() != null ? record.rawUnmapped() : "{}");
                    case "canonical_payload" -> ps.setString(paramIndex, record.canonicalPayload() != null ? record.canonicalPayload() : "{}");
                    default -> {
                        Object val = record.mappedFields() != null ? record.mappedFields().get(col) : null;
                        if (val != null) {
                            ps.setString(paramIndex, String.valueOf(val));
                        } else {
                            ps.setNull(paramIndex, java.sql.Types.VARCHAR);
                        }
                    }
                }
            }
        });

        return records.size();
    }
}
