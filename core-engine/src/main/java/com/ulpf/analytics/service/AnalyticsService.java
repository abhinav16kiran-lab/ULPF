package com.ulpf.analytics.service;

import java.util.Random;
import java.util.Set;

import org.springframework.stereotype.Service;

@Service
public class AnalyticsService {

    private static final Set<String> VALID_AGGREGATIONS = Set.of("COUNT", "AVG", "MIN", "MAX", "SUM");
    private static final Random random = new Random();

    public record AnalyticsResult(String table, String column, String aggregation, double result) {}

    public boolean isValidAggregation(String aggregation) {
        return aggregation != null && VALID_AGGREGATIONS.contains(aggregation.toUpperCase());
    }

    public AnalyticsResult runQuery(String table, String column, String aggregation) {
        // TODO: replace with real read-only ClickHouse query once integration is ready
        // e.g. SELECT COUNT(column) FROM ulpf_events.table
        double fakeResult = random.nextInt(100);
        return new AnalyticsResult(table, column, aggregation.toUpperCase(), fakeResult);
    }
}