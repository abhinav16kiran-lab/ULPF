package com.ulpf.common.db;

import com.ulpf.common.db.ClickHouseIngestionRepository.CanonicalEventRecord;
import com.ulpf.common.db.ClickHouseIngestionRepository.RawEventRecord;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * The ClickHouse pipeline meters (backlog item 13, Phase 0). Names, tags and buckets follow the metric table in
 * docs/plans/2026-10-28-prometheus-grafana-observability.md, so the Grafana work can use them as they are.
 */
class ClickHousePipelineMetricsTest {

    private JdbcTemplate jdbc;
    private ClickHouseIngestionRepository repo;
    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        jdbc = Mockito.mock(JdbcTemplate.class);
        repo = new ClickHouseIngestionRepository(jdbc);
        registry = new SimpleMeterRegistry();
        repo.setMeterRegistry(registry);
    }

    private static RawEventRecord raw(String id) {
        return new RawEventRecord(id, "lin-" + id, "vendor-1", "source-1", 1, LocalDateTime.now(), "{\"a\":1}");
    }

    private static CanonicalEventRecord canonical(String table, String id) {
        return new CanonicalEventRecord(table, id, "lin-" + id, "vendor-1", "source-1", 1,
                LocalDateTime.now(), 1.0, "{}", "{}", Collections.emptyMap());
    }

    @SuppressWarnings("unchecked")
    private void tableHasColumns(String table) {
        Mockito.when(jdbc.query(Mockito.contains("system.columns"), any(RowMapper.class), Mockito.eq(table)))
                .thenReturn(List.of("event_id", "timestamp"));
    }

    private double count(String name, String tag, String value) {
        var counter = registry.find(name).tag(tag, value).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void aRawFlushRecordsItsDurationAndBatchSize() {
        repo.enqueue(raw("e1"));
        repo.enqueue(raw("e2"));
        repo.flush();

        Timer timer = registry.find("ulpf.clickhouse.flush.duration").tag("stage", "raw").timer();
        assertNotNull(timer, "flush timer with stage=raw");
        assertEquals(1, timer.count());
        DistributionSummary batch = registry.find("ulpf.clickhouse.batch.size").tag("stage", "raw").summary();
        assertNotNull(batch, "batch size summary with stage=raw");
        assertEquals(1, batch.count());
        assertEquals(2.0, batch.totalAmount());
    }

    @Test
    void aCanonicalFlushRecordsItsDurationAndBatchSize() {
        tableHasColumns("events_t");
        repo.enqueueCanonical(canonical("events_t", "c1"));
        repo.enqueueCanonical(canonical("events_t", "c2"));
        repo.enqueueCanonical(canonical("events_t", "c3"));
        repo.flushCanonical();

        assertEquals(1, registry.find("ulpf.clickhouse.flush.duration").tag("stage", "canonical").timer().count());
        assertEquals(3.0, registry.find("ulpf.clickhouse.batch.size").tag("stage", "canonical").summary().totalAmount());
    }

    @Test
    void anEmptyFlushRecordsNothing() {
        repo.flush();
        repo.flushCanonical();

        assertTrue(registry.find("ulpf.clickhouse.flush.duration").timers().stream().allMatch(t -> t.count() == 0));
    }

    @Test
    void aFailedRawInsertCountsAnErrorAndTheReenqueuedRows() {
        Mockito.doThrow(new DataRetrievalFailureException("connection reset"))
                .when(jdbc).batchUpdate(anyString(), any(), anyInt(), any());
        Mockito.when(jdbc.queryForObject(anyString(), Mockito.eq(Integer.class), any(Object[].class))).thenReturn(0);
        repo.enqueue(raw("e1"));
        repo.enqueue(raw("e2"));

        repo.flush();

        assertEquals(1, count("ulpf.clickhouse.insert.errors", "table", "raw_events"));
        assertEquals(2, count("ulpf.records.reenqueued", "table", "raw_events"));
        assertEquals(2, repo.getQueueSize(), "the batch is back on the queue");
    }

    @Test
    void aCanonicalRowThatFailsTwiceIsCountedAsDropped() {
        tableHasColumns("events_bad");
        Mockito.doThrow(new DataRetrievalFailureException("write timeout"))
                .when(jdbc).batchUpdate(anyString(), any(), anyInt(), any());
        repo.enqueueCanonical(canonical("events_bad", "c1"));

        repo.flushCanonical();

        assertEquals(1, count("ulpf.clickhouse.insert.errors", "table", "events_bad"));
        assertEquals(1, count("ulpf.records.dropped", "table", "events_bad"));
        assertEquals(0, count("ulpf.records.degraded", "table", "events_bad"));
    }

    @Test
    void aCanonicalRowSavedWithoutItsMappedFieldsIsCountedAsDegraded() {
        tableHasColumns("events_deg");
        // The first INSERT (the batch) fails; the degraded retry succeeds.
        Mockito.doThrow(new DataRetrievalFailureException("bad value")).doReturn(new int[][]{{1}})
                .when(jdbc).batchUpdate(anyString(), any(), anyInt(), any());
        repo.enqueueCanonical(canonical("events_deg", "c1"));

        repo.flushCanonical();

        assertEquals(1, count("ulpf.records.degraded", "table", "events_deg"));
        assertEquals(0, count("ulpf.records.dropped", "table", "events_deg"));
    }

    @Test
    void bothHalvesFailingDropsTheWholeBatch() {
        tableHasColumns("events_sys");
        Mockito.doThrow(new DataRetrievalFailureException("cluster down"))
                .when(jdbc).batchUpdate(anyString(), any(), anyInt(), any());
        for (int i = 0; i < 4; i++) {
            repo.enqueueCanonical(canonical("events_sys", "c" + i));
        }

        repo.flushCanonical();

        assertEquals(4, count("ulpf.records.dropped", "table", "events_sys"));
    }

    /** The tag policy of the Grafana plan: per-table tags only on counters; timers and summaries stay small. */
    @Test
    void timersAndSummariesHaveNoPerTableTagsAndAtMost16Series() {
        tableHasColumns("events_t");
        repo.enqueue(raw("e1"));
        repo.flush();
        repo.enqueueCanonical(canonical("events_t", "c1"));
        repo.flushCanonical();

        Set<String> forbidden = Set.of("table", "source_id", "vendor_id");
        for (Meter meter : registry.getMeters()) {
            if (meter instanceof Timer || meter instanceof DistributionSummary) {
                for (var tag : meter.getId().getTags()) {
                    assertFalse(forbidden.contains(tag.getKey()), meter.getId() + " must not carry " + tag.getKey());
                }
                int buckets = meter instanceof Timer t ? t.takeSnapshot().histogramCounts().length
                        : ((DistributionSummary) meter).takeSnapshot().histogramCounts().length;
                assertTrue(buckets > 0, meter.getId() + " needs explicit buckets");
                assertTrue(buckets + 3 <= 16, meter.getId() + " exports " + (buckets + 3) + " series");
            }
        }
    }
}
