package com.ulpf.controlplane.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ulpf.common.GlobalExceptionHandler;
import com.ulpf.replay.model.ReplayJobStatus;
import com.ulpf.replay.model.ReplayRequest;
import com.ulpf.replay.service.ReplayBackfillService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class ReplayBackfillControllerTest {

    @Mock
    private ReplayBackfillService replayService;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        ReplayBackfillController controller = new ReplayBackfillController(replayService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void testStartReplayAccepted() throws Exception {
        String sourceId = "src_100";
        ReplayJobStatus status = ReplayJobStatus.initial("job_1", sourceId, "events_table", 500);

        when(replayService.triggerReplayAsync(eq(sourceId), any())).thenReturn(status);

        mockMvc.perform(post("/v1/admin/sources/{sourceId}/replay", sourceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"chunkSize\": 500}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value("job_1"))
                .andExpect(jsonPath("$.sourceId").value(sourceId))
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.totalEstimate").value(500));
    }

    @Test
    void testStartReplayConflict() throws Exception {
        String sourceId = "src_running";
        when(replayService.triggerReplayAsync(eq(sourceId), any()))
                .thenThrow(new IllegalStateException("Replay job job_existing is already running for source: " + sourceId));

        mockMvc.perform(post("/v1/admin/sources/{sourceId}/replay", sourceId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Replay job job_existing is already running for source: " + sourceId));
    }

    @Test
    void testStartReplayNotFound() throws Exception {
        String sourceId = "src_not_found";
        when(replayService.triggerReplayAsync(eq(sourceId), any()))
                .thenThrow(new IllegalArgumentException("Source not found: " + sourceId));

        mockMvc.perform(post("/v1/admin/sources/{sourceId}/replay", sourceId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Source not found: " + sourceId));
    }

    @Test
    void testGetStatusFound() throws Exception {
        String sourceId = "src_status";
        ReplayJobStatus status = new ReplayJobStatus(
                "job_status", sourceId, "events_table", "RUNNING",
                1000, 450, 400, 50, 0, 45,
                LocalDateTime.now(), null, null
        );

        when(replayService.getJobStatus(sourceId)).thenReturn(Optional.of(status));

        mockMvc.perform(get("/v1/admin/sources/{sourceId}/replay/status", sourceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value("job_status"))
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.processed").value(450))
                .andExpect(jsonPath("$.inserted").value(400))
                .andExpect(jsonPath("$.skipped").value(50))
                .andExpect(jsonPath("$.progressPercent").value(45));
    }

    @Test
    void testGetStatusNotFound() throws Exception {
        String sourceId = "src_unknown";
        when(replayService.getJobStatus(sourceId)).thenReturn(Optional.empty());

        mockMvc.perform(get("/v1/admin/sources/{sourceId}/replay/status", sourceId))
                .andExpect(status().isNotFound());
    }

    @Test
    void testCancelReplaySuccess() throws Exception {
        String sourceId = "src_cancel";
        when(replayService.cancelReplay(sourceId)).thenReturn(true);

        mockMvc.perform(delete("/v1/admin/sources/{sourceId}/replay", sourceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Cancellation requested for replay job on source " + sourceId));
    }

    @Test
    void testCancelReplayNotFound() throws Exception {
        String sourceId = "src_no_job";
        when(replayService.cancelReplay(sourceId)).thenReturn(false);

        mockMvc.perform(delete("/v1/admin/sources/{sourceId}/replay", sourceId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("No active replay job to cancel for source " + sourceId));
    }
}
