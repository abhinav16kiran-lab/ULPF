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
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AnalyticsControllerTest {

    private MockMvc mockMvc;

    @Mock
    private AnalyticsService analyticsService;

    @BeforeEach
    void setUp() {
        AnalyticsController analyticsController = new AnalyticsController(analyticsService);
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
        when(analyticsService.runQuery(anyString(), anyString(), anyString()))
                .thenReturn(new AnalyticsResult("logs", "latency", "AVG", 42.5));

        mockMvc.perform(get("/v1/analytics")
                        .param("table", "logs")
                        .param("column", "latency")
                        .param("aggregation", "AVG"))
                .andExpect(status().isOk());
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
                .andExpect(status().isOk());
    }
}
