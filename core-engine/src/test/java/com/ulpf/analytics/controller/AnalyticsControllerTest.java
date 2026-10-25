package com.ulpf.analytics.controller;

import com.ulpf.analytics.service.AnalyticsService;
import com.ulpf.analytics.service.AnalyticsService.AnalyticsResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AnalyticsControllerTest {

    private MockMvc mockMvc;

    @Mock
    private AnalyticsService analyticsService;

    @Mock
    private com.ulpf.mapping.service.DynamicSchemaProvisioningService dynamicSchemaProvisioningService;

    @BeforeEach
    void setUp() {
        AnalyticsController analyticsController = new AnalyticsController(analyticsService, dynamicSchemaProvisioningService);
        mockMvc = MockMvcBuilders.standaloneSetup(analyticsController).build();
    }

    @Test
    void testGetAnalytics_MissingParams_ReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/v1/analytics"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testGetAnalytics_ValidParams_ReturnsOk() throws Exception {
        when(analyticsService.isValidAggregation("AVG")).thenReturn(true);
        // timeRange/from/to are optional request params and arrive as null here
        when(analyticsService.runQuery(anyString(), anyString(), anyString(),
                nullable(String.class), nullable(String.class), nullable(String.class)))
                .thenReturn(new AnalyticsResult("logs", "latency", "AVG", 42.5));

        mockMvc.perform(get("/v1/analytics")
                        .param("table", "logs")
                        .param("column", "latency")
                        .param("aggregation", "AVG"))
                .andExpect(status().isOk());
    }

    @Test
    void testGetAnalytics_InvalidTableThrowsIllegalArgumentException_ReturnsBadRequest() throws Exception {
        when(analyticsService.isValidAggregation("COUNT")).thenReturn(true);
        when(analyticsService.runQuery(anyString(), anyString(), anyString(),
                nullable(String.class), nullable(String.class), nullable(String.class)))
                .thenThrow(new IllegalArgumentException("Invalid characters in SQL identifier: bad;table"));

        mockMvc.perform(get("/v1/analytics")
                        .param("table", "bad;table")
                        .param("column", "src_ip")
                        .param("aggregation", "COUNT"))
                .andExpect(status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.error")
                        .value("Invalid characters in SQL identifier: bad;table"));
    }

    @Test
    void testExportParquet_ReturnsBinaryContent() throws Exception {
        byte[] mockParquet = new byte[] {0x50, 0x41, 0x52, 0x31, 0x00, 0x00, 0x00, 0x00, 0x50, 0x41, 0x52, 0x31};
        when(analyticsService.exportParquet(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(mockParquet);

        mockMvc.perform(get("/v1/analytics/export/parquet")
                        .param("vendorId", "vendor-1"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string(org.springframework.http.HttpHeaders.CONTENT_TYPE, "application/vnd.apache.parquet"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, org.hamcrest.Matchers.containsString("attachment; filename=\"ulpf_export_vendor-1_")));
    }

    @Test
    void testExportParquet_ReturnsNoContentWhenEmpty() throws Exception {
        when(analyticsService.exportParquet(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new byte[0]);

        mockMvc.perform(get("/v1/analytics/export/parquet")
                        .param("vendorId", "vendor-empty"))
                .andExpect(status().isNoContent());
    }

    @Test
    void testExportParquet_ReturnsBadGatewayOnStorageFailure() throws Exception {
        when(analyticsService.exportParquet(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenThrow(new RuntimeException("ClickHouse connection failed"));

        mockMvc.perform(get("/v1/analytics/export/parquet")
                        .param("vendorId", "vendor-1"))
                .andExpect(status().isBadGateway())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.error")
                        .value(org.hamcrest.Matchers.containsString("Failed to export Parquet data from storage")));
    }

    @Test
    void testSearchLogs_ReturnsOk() throws Exception {
        when(analyticsService.searchLogs(anyString(), anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new AnalyticsService.LogSearchResult(java.util.Collections.emptyList(), 0, 5, "error"));

        mockMvc.perform(get("/v1/analytics/search")
                        .param("query", "error"))
                .andExpect(status().isOk());
    }

    @Test
    void testGetTimeSeries_ReturnsOk() throws Exception {
        when(analyticsService.getTimeSeries(org.mockito.ArgumentMatchers.any(), anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.Collections.emptyList());

        mockMvc.perform(get("/v1/analytics/timeseries")
                        .param("interval", "5m"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.interval").value("5m"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.totalBuckets").value(0));
    }

    @Test
    void testGetTimeSeries_WithAllParameters_ReturnsOk() throws Exception {
        when(analyticsService.getTimeSeries(org.mockito.ArgumentMatchers.eq("error"), org.mockito.ArgumentMatchers.eq("1h"), org.mockito.ArgumentMatchers.eq("2026-10-20T00:00:00Z"), org.mockito.ArgumentMatchers.eq("2026-10-20T23:59:59Z")))
                .thenReturn(java.util.List.of(new AnalyticsService.TimeSeriesBucket("2026-10-20T10:00:00Z", 200L, 5L)));

        mockMvc.perform(get("/v1/analytics/timeseries")
                        .param("query", "error")
                        .param("interval", "1h")
                        .param("from", "2026-10-20T00:00:00Z")
                        .param("to", "2026-10-20T23:59:59Z"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.interval").value("1h"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.totalBuckets").value(1))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.buckets[0].timestamp").value("2026-10-20T10:00:00Z"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.buckets[0].totalCount").value(200))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.buckets[0].errorCount").value(5));
    }

    @Test
    void testGetTimeSeries_InvalidInterval_ReturnsBadRequest() throws Exception {
        when(analyticsService.getTimeSeries(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("bad_interval"), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalArgumentException("Invalid interval 'bad_interval'. Format must be <positive_integer>[s|m|h|d], e.g. '30s', '5m', '1h', '1d'."));

        mockMvc.perform(get("/v1/analytics/timeseries")
                        .param("interval", "bad_interval"))
                .andExpect(status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.error")
                        .value(org.hamcrest.Matchers.containsString("Format must be <positive_integer>[s|m|h|d]")));
    }

    @Test
    void testImportLogFile_ReturnsOk() throws Exception {
        org.springframework.mock.web.MockMultipartFile mockFile = new org.springframework.mock.web.MockMultipartFile(
                "file", "logs.json", "application/json", "{\"test\":true}".getBytes()
        );
        when(analyticsService.importLogFile(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new AnalyticsService.BulkImportResult("SUCCESS", "logs.json", 1, 10, "cisco", "fw1"));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/v1/analytics/import/file")
                        .file(mockFile)
                        .param("vendorId", "cisco")
                        .param("sourceId", "fw1"))
                .andExpect(status().isOk());
    }
}
