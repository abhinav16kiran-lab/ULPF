package com.ulpf.analytics.service;

import com.ulpf.common.db.ClickHouseIngestionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

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

    @Test
    void testExecuteDirectSql_RejectsExternalTableFunctions() {
        assertThrows(IllegalArgumentException.class,
                () -> analyticsService.executeDirectSql("SELECT * FROM file('/etc/passwd', 'LineAsString')"));
        assertThrows(IllegalArgumentException.class,
                () -> analyticsService.executeDirectSql("SELECT * FROM url('http://example.com/data', 'CSV')"));
        assertThrows(IllegalArgumentException.class,
                () -> analyticsService.executeDirectSql("SELECT * FROM remote('127.0.0.1', default)"));
        assertThrows(IllegalArgumentException.class,
                () -> analyticsService.executeDirectSql("SELECT * FROM s3('https://s3.amazonaws.com/bucket/file.csv')"));
        verifyNoInteractions(clickhouseJdbcTemplate);
    }

    @Test
    @SuppressWarnings("unchecked")
    void testExportParquetReturnsBinaryDataWhenPresent() {
        byte[] expectedParquet = new byte[] {0x50, 0x41, 0x52, 0x31, 0x15, 0x00, 0x00, 0x50, 0x41, 0x52, 0x31};
        when(clickhouseJdbcTemplate.query(anyString(), any(org.springframework.jdbc.core.ResultSetExtractor.class), any(Object[].class)))
                .thenReturn(expectedParquet);

        byte[] parquet = analyticsService.exportParquet("raw_events", "vendor-123", "src-1", null, null, 100);

        assertNotNull(parquet);
        assertEquals(expectedParquet.length, parquet.length);
        assertEquals(0x50, parquet[0]); // 'P'
        assertEquals(0x41, parquet[1]); // 'A'
        assertEquals(0x52, parquet[2]); // 'R'
        assertEquals(0x31, parquet[3]); // '1'
    }

    @Test
    @SuppressWarnings("unchecked")
    void testExportParquetReturnsEmptyArrayWhenNoDataFound() {
        when(clickhouseJdbcTemplate.query(anyString(), any(org.springframework.jdbc.core.ResultSetExtractor.class), any(Object[].class)))
                .thenReturn(new byte[0]);

        byte[] parquet = analyticsService.exportParquet("raw_events", "vendor-123", "src-1", null, null, 100);

        assertNotNull(parquet);
        assertEquals(0, parquet.length, "Should return empty byte array instead of fake PAR1 fallback when no data exists");
    }

    @Test
    @SuppressWarnings("unchecked")
    void testExportParquetPropagatesExceptionOnDatabaseFailure() {
        when(clickhouseJdbcTemplate.query(anyString(), any(org.springframework.jdbc.core.ResultSetExtractor.class), any(Object[].class)))
                .thenThrow(new org.springframework.dao.DataAccessException("Connection refused") {});

        assertThrows(RuntimeException.class, () ->
                analyticsService.exportParquet("raw_events", "vendor-123", "src-1", null, null, 100)
        );
    }

    @Test
    void testSearchLogs_ReturnsResult() {
        var searchResult = analyticsService.searchLogs("error", "CONTAINS", "vendor-1", null, null, null, 50);
        assertNotNull(searchResult);
        assertEquals("error", searchResult.query());
        assertNotNull(searchResult.events());
    }

    @Test
    void testGetTimeSeries_ReturnsBuckets() {
        when(clickhouseJdbcTemplate.query(anyString(), ArgumentMatchers.<RowMapper<AnalyticsService.TimeSeriesBucket>>any(), any(Object[].class)))
                .thenReturn(java.util.List.of(new AnalyticsService.TimeSeriesBucket("2026-10-20T10:00:00Z", 150L, 12L)));

        var buckets = analyticsService.getTimeSeries("error", "5m", null, null);
        assertNotNull(buckets);
        assertEquals(1, buckets.size());
        assertEquals("2026-10-20T10:00:00Z", buckets.get(0).timestamp());
        assertEquals(150L, buckets.get(0).totalCount());
        assertEquals(12L, buckets.get(0).errorCount());
    }

    @Test
    void testGetTimeSeries_BuildsDynamicParameterizedSql() {
        org.mockito.ArgumentCaptor<String> sqlCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<Object[]> paramsCaptor = org.mockito.ArgumentCaptor.forClass(Object[].class);

        when(clickhouseJdbcTemplate.query(sqlCaptor.capture(), ArgumentMatchers.<RowMapper<AnalyticsService.TimeSeriesBucket>>any(), paramsCaptor.capture()))
                .thenReturn(java.util.Collections.emptyList());

        analyticsService.getTimeSeries("timeout", "1h", "2026-10-20T00:00:00Z", "2026-10-20T23:59:59Z");

        String capturedSql = sqlCaptor.getValue();
        Object[] capturedParams = paramsCaptor.getValue();

        assertTrue(capturedSql.contains("toStartOfInterval(received_at, toIntervalSecond(?))"), "SQL must use toIntervalSecond(?)");
        assertTrue(capturedSql.contains("positionCaseInsensitive(raw_payload, ?) > 0"), "SQL must filter by query");
        assertTrue(capturedSql.contains("received_at >= parseDateTime64BestEffortOrNull(?)"), "SQL must filter by from timestamp");
        assertTrue(capturedSql.contains("received_at <= parseDateTime64BestEffortOrNull(?)"), "SQL must filter by to timestamp");

        assertEquals(4, capturedParams.length);
        assertEquals(3600L, capturedParams[0]); // 1h in seconds
        assertEquals("timeout", capturedParams[1]);
        assertEquals("2026-10-20T00:00:00Z", capturedParams[2]);
        assertEquals("2026-10-20T23:59:59Z", capturedParams[3]);
    }

    @Test
    void testGetTimeSeries_Exception_ReturnsFallbackMockBuckets() {
        when(clickhouseJdbcTemplate.query(anyString(), ArgumentMatchers.<RowMapper<AnalyticsService.TimeSeriesBucket>>any(), any(Object[].class)))
                .thenThrow(new RuntimeException("ClickHouse connection refused"));

        var buckets = analyticsService.getTimeSeries("error", "5m", null, null);
        assertNotNull(buckets);
        assertFalse(buckets.isEmpty(), "Must fall back to synthetic mock data when ClickHouse is offline");
    }

    @Test
    void testResolveIntervalSeconds() {
        assertEquals(10L, analyticsService.resolveIntervalSeconds("10s"));
        assertEquals(30L, analyticsService.resolveIntervalSeconds("30s"));
        assertEquals(60L, analyticsService.resolveIntervalSeconds("1m"));
        assertEquals(300L, analyticsService.resolveIntervalSeconds("5m"));
        assertEquals(600L, analyticsService.resolveIntervalSeconds("10m"));
        assertEquals(900L, analyticsService.resolveIntervalSeconds("15m"));
        assertEquals(1800L, analyticsService.resolveIntervalSeconds("30m"));
        assertEquals(3600L, analyticsService.resolveIntervalSeconds("1h"));
        assertEquals(43200L, analyticsService.resolveIntervalSeconds("12h"));
        assertEquals(86400L, analyticsService.resolveIntervalSeconds("1d"));
        assertEquals(259200L, analyticsService.resolveIntervalSeconds("3d"));

        // Defaults when null or blank
        assertEquals(300L, analyticsService.resolveIntervalSeconds(null));
        assertEquals(300L, analyticsService.resolveIntervalSeconds(""));
        assertEquals(300L, analyticsService.resolveIntervalSeconds("   "));

        // Strict rejection on invalid formats, non-numeric values, or SQL injection attempts
        assertThrows(IllegalArgumentException.class, () -> analyticsService.resolveIntervalSeconds("5m; DROP TABLE raw_events"));
        assertThrows(IllegalArgumentException.class, () -> analyticsService.resolveIntervalSeconds("10x"));
        assertThrows(IllegalArgumentException.class, () -> analyticsService.resolveIntervalSeconds("0m"));
        assertThrows(IllegalArgumentException.class, () -> analyticsService.resolveIntervalSeconds("-5m"));
        assertThrows(IllegalArgumentException.class, () -> analyticsService.resolveIntervalSeconds("invalid"));
        assertThrows(IllegalArgumentException.class, () -> analyticsService.resolveIntervalSeconds("m"));
    }

    @Test
    void testImportLogFile_Success() {
        org.springframework.mock.web.MockMultipartFile mockFile = new org.springframework.mock.web.MockMultipartFile(
                "file",
                "test_logs.json",
                "application/json",
                "{\"event\":\"login\",\"user\":\"admin\"}\n{\"event\":\"logout\",\"user\":\"admin\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );

        var result = analyticsService.importLogFile(mockFile, "cisco", "fw1");
        assertNotNull(result);
        assertEquals("SUCCESS", result.status());
        assertEquals("test_logs.json", result.fileName());
        assertEquals("cisco", result.vendorId());
        assertEquals("fw1", result.sourceId());
        assertTrue(result.importedCount() > 0);
    }

    @Test
    void testImportLogFile_EmptyFileThrowsException() {
        org.springframework.mock.web.MockMultipartFile emptyFile = new org.springframework.mock.web.MockMultipartFile(
                "file",
                "empty.log",
                "text/plain",
                new byte[0]
        );

        assertThrows(IllegalArgumentException.class, () -> analyticsService.importLogFile(emptyFile, "v1", "s1"));
    }
}
