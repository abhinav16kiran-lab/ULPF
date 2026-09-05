package com.ulpf.analytics.service;

import com.ulpf.common.db.ClickHouseIngestionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AnalyticsServiceTest {

    private JdbcTemplate clickhouseJdbcTemplate;
    private ClickHouseIngestionRepository clickHouseIngestionRepository;
    private AnalyticsService analyticsService;

    @BeforeEach
    void setUp() {
        clickhouseJdbcTemplate = mock(JdbcTemplate.class);
        clickHouseIngestionRepository = mock(ClickHouseIngestionRepository.class);
        analyticsService = new AnalyticsService(clickhouseJdbcTemplate, clickHouseIngestionRepository);
    }

    @Test
    void testValidAggregationQueries() {
        when(clickhouseJdbcTemplate.queryForObject(eq("SELECT COUNT(src_ip) FROM ulpf_raw.raw_events"),
                eq(Double.class)))
                .thenReturn(42.0);

        var result = analyticsService.runQuery("raw_events", "src_ip", "COUNT");

        assertEquals("raw_events", result.table());
        assertEquals("src_ip", result.column());
        assertEquals("COUNT", result.aggregation());
        assertEquals(42.0, result.result());
        verify(clickhouseJdbcTemplate, times(1)).queryForObject("SELECT COUNT(src_ip) FROM ulpf_raw.raw_events",
                Double.class);
    }

    @Test
    void testExplicitDatabasePrefixTable() {
        when(clickhouseJdbcTemplate.queryForObject(eq("SELECT AVG(bytes) FROM ulpf_events.firewall_logs"),
                eq(Double.class)))
                .thenReturn(1024.5);

        var result = analyticsService.runQuery("ulpf_events.firewall_logs", "bytes", "AVG");

        assertEquals(1024.5, result.result());
        verify(clickhouseJdbcTemplate, times(1)).queryForObject("SELECT AVG(bytes) FROM ulpf_events.firewall_logs",
                Double.class);
    }

    @Test
    void testInvalidAggregationThrowsException() {
        assertThrows(IllegalArgumentException.class, () -> analyticsService.runQuery("raw_events", "src_ip", "DROP"));
        assertThrows(IllegalArgumentException.class, () -> analyticsService.runQuery("raw_events", "src_ip", "SELECT"));
        verifyNoInteractions(clickhouseJdbcTemplate);
    }

    @Test
    void testSQLInjectionIdentifierRejection() {
        assertThrows(IllegalArgumentException.class,
                () -> analyticsService.runQuery("raw_events; DROP TABLE users", "src_ip", "COUNT"));
        assertThrows(IllegalArgumentException.class,
                () -> analyticsService.runQuery("raw_events", "src_ip; --", "COUNT"));
        verifyNoInteractions(clickhouseJdbcTemplate);
    }
}
