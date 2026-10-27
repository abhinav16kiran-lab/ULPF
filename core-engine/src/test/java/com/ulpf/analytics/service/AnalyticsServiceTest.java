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
