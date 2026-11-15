package com.ulpf.common;

import com.ulpf.common.db.ClickHouseIngestionRepository;
import com.ulpf.common.db.ClickHouseIngestionRepository.CanonicalEventRecord;
import com.ulpf.common.db.ClickHouseIngestionRepository.RawEventRecord;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QueueMetricsTest {

    @Test
    void theQueueGaugesFollowTheBufferDepth() {
        ClickHouseIngestionRepository repo = new ClickHouseIngestionRepository(Mockito.mock(JdbcTemplate.class));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new MetricsConfig().queueMetrics(repo).bindTo(registry);

        for (int i = 0; i < 3; i++) {
            repo.enqueue(new RawEventRecord("e" + i, "l", "v", "s", 1, LocalDateTime.now(), "{}"));
        }
        repo.enqueueCanonical(new CanonicalEventRecord("events_t", "c", "l", "v", "s", 1, LocalDateTime.now(), 1.0,
                "{}", "{}", Collections.emptyMap()));

        assertEquals(3.0, registry.get("ulpf.queue.raw.size").gauge().value());
        assertEquals(1.0, registry.get("ulpf.queue.canonical.size").gauge().value());

        repo.flush();

        assertEquals(0.0, registry.get("ulpf.queue.raw.size").gauge().value());
    }
}
