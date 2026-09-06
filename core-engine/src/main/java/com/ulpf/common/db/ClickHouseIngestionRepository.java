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
import java.util.Collections;
import java.util.List;
import java.util.Queue;
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

    public record RawEventRecord(
        String eventId,
        String lineageId,
        String vendorId,
        String sourceId,
        Integer mappingVersion,
        LocalDateTime receivedAt,
        String rawPayload
    ) {}

    public record CanonicalEventRecord(
        String eventId,
        String lineageId,
        String vendorId,
        String sourceId,
        Integer mappingVersion,
        LocalDateTime timestamp,
        Double numericValue,
        String canonicalPayload
    ) {}

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

        } catch (Exception e) {
            log.error("Failed to flush batch of {} raw log events to ClickHouse!", batch.size(), e);
            // Re-enqueue batch in case of transient network failure
            bufferQueue.addAll(batch);
        }
    }

    /**
     * Synchronously flushes queued canonical events to ClickHouse ulpf_events.canonical_events.
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

        if (batch.isEmpty()) {
            return;
        }

        try {
            log.info("Flushing batch of {} canonical events to ClickHouse (ulpf_events.canonical_events)...", batch.size());
            String sql = """
                INSERT INTO ulpf_events.canonical_events (event_id, lineage_id, vendor_id, source_id, mapping_version, timestamp, numeric_value, canonical_payload)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
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
                ps.setTimestamp(6, record.timestamp() != null ? Timestamp.valueOf(record.timestamp()) : Timestamp.valueOf(LocalDateTime.now()));
                if (record.numericValue() != null) {
                    ps.setDouble(7, record.numericValue());
                } else {
                    ps.setNull(7, java.sql.Types.DOUBLE);
                }
                ps.setString(8, record.canonicalPayload());
            });

            log.info("Successfully flushed {} canonical events to ClickHouse.", batch.size());

        } catch (Exception e) {
            log.debug("Canonical events table ulpf_events.canonical_events skipped: {}", e.getMessage());
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
}
