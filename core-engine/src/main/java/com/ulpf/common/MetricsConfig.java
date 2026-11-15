package com.ulpf.common;

import com.ulpf.common.db.ClickHouseIngestionRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Application meters that are bound from outside the measured class (backlog item 13, Phase 0). The names follow
 * the metric table of the Grafana plan (docs/plans/2026-10-28-prometheus-grafana-observability.md), which adds the
 * Prometheus export later. The flush, batch and failure meters live in {@link ClickHouseIngestionRepository}.
 *
 * <p>The queue gauges call {@code ConcurrentLinkedQueue.size()}, which walks the queue. That is acceptable here,
 * because a gauge is read only when someone reads the metrics, not per event.
 */
@Configuration
public class MetricsConfig {

    @Bean
    public MeterBinder queueMetrics(ClickHouseIngestionRepository repository) {
        return registry -> {
            Gauge.builder("ulpf.queue.raw.size", repository, ClickHouseIngestionRepository::getQueueSize)
                    .description("Raw events waiting to be written to ClickHouse")
                    .register(registry);
            Gauge.builder("ulpf.queue.canonical.size", repository, ClickHouseIngestionRepository::getCanonicalQueueSize)
                    .description("Canonical events waiting to be written to ClickHouse")
                    .register(registry);
        };
    }
}
