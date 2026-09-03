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
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * High-throughput ClickHouse repository for raw log event ingestion.
 * Features an in-memory queue buffer with batch size flush (500), 1s scheduled timer, and @PreDestroy container shutdown flush.
 */
@Repository
public class ClickHouseIngestionRepository {

    private static final Logger log = LoggerFactory.getLogger(ClickHouseIngestionRepository.class);

    private final JdbcTemplate clickhouseJdbcTemplate;
    private final Queue<RawEventRecord> bufferQueue = new ConcurrentLinkedQueue<>();

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

    /**
     * Enqueues a raw event record into the in-memory buffer.
     * Triggers batch flush if buffer size reaches batchSize (500).
     */
    public void enqueue(RawEventRecord event) {
        bufferQueue.add(event);
        if (bufferQueue.size() >= batchSize) {
            flush();
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
    }

    /**
     * Synchronously flushes all queued events across the bridge network to ClickHouse ulpf_raw.raw_events.
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
     * Container / JVM shutdown hook. Ensures all pending events in buffer are flushed to ClickHouse before application exits.
     */
    @PreDestroy
    public void shutdownFlush() {
        log.info("Container shutdown signal received. Triggering final synchronous flush of raw log event buffer...");
        flush();
    }

    public int getQueueSize() {
        return bufferQueue.size();
    }
}
