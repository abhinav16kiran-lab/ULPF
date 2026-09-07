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
}
