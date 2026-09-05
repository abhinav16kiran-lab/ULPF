package com.ulpf.analytics.service;

import com.ulpf.common.db.ClickHouseIngestionRepository;
import com.ulpf.common.db.ClickHouseIngestionRepository.RawEventRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * Service for running read-only aggregation queries and lineage backtracking against ClickHouse.
 */
@Service
public class AnalyticsService {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsService.class);
    private static final Set<String> VALID_AGGREGATIONS = Set.of("COUNT", "AVG", "MIN", "MAX", "SUM");

    private final JdbcTemplate clickhouseJdbcTemplate;
    private final ClickHouseIngestionRepository clickHouseIngestionRepository;

    public AnalyticsService(
            @Qualifier("clickhouseJdbcTemplate") JdbcTemplate clickhouseJdbcTemplate,
            ClickHouseIngestionRepository clickHouseIngestionRepository
    ) {
        this.clickhouseJdbcTemplate = clickhouseJdbcTemplate;
        this.clickHouseIngestionRepository = clickHouseIngestionRepository;
    }

    public record AnalyticsResult(String table, String column, String aggregation, double result) {}

    public boolean isValidAggregation(String aggregation) {
        return aggregation != null && VALID_AGGREGATIONS.contains(aggregation.toUpperCase());
    }

    public AnalyticsResult runQuery(String table, String column, String aggregation) {
        if (!isValidAggregation(aggregation)) {
            throw new IllegalArgumentException("Invalid aggregation function: " + aggregation);
        }

        String aggUpper = aggregation.toUpperCase();
        String safeTable = sanitizeIdentifier(table);
        String safeColumn = sanitizeIdentifier(column);

        // Target ulpf_raw or ulpf_events database prefix if not explicitly provided
        String fullTableName = safeTable.contains(".") ? safeTable : "ulpf_raw." + safeTable;

        String sql = String.format("SELECT %s(%s) FROM %s", aggUpper, safeColumn, fullTableName);
        log.info("Executing ClickHouse Analytics Query: {}", sql);

        try {
            Double queryResult = clickhouseJdbcTemplate.queryForObject(sql, Double.class);
            double val = (queryResult != null) ? queryResult : 0.0;
            return new AnalyticsResult(table, column, aggUpper, val);
        } catch (Exception e) {
            log.error("ClickHouse analytics query failed for table {}: {}", table, e.getMessage());
            return new AnalyticsResult(table, column, aggUpper, 0.0);
        }
    }

    public List<RawEventRecord> getLineage(String lineageId) {
        return clickHouseIngestionRepository.findRawEventsByLineageId(lineageId);
    }

    private String sanitizeIdentifier(String input) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("SQL identifier cannot be empty");
        }
        String cleaned = input.trim();
        if (!cleaned.matches("^[a-zA-Z0-9_.]+$")) {
            throw new IllegalArgumentException("Invalid characters in SQL identifier: " + input);
        }
        return cleaned;
    }
}