package com.ulpf.controlplane.controller;

import com.ulpf.replay.model.ReplayJobStatus;
import com.ulpf.replay.model.ReplayRequest;
import com.ulpf.replay.service.ReplayBackfillService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/v1/admin/sources/{sourceId}/replay")
@PreAuthorize("hasRole('ADMIN')")
public class ReplayBackfillController {

    private final ReplayBackfillService replayService;

    public ReplayBackfillController(ReplayBackfillService replayService) {
        this.replayService = replayService;
    }

    @PostMapping
    public ResponseEntity<?> startReplay(
            @PathVariable String sourceId,
            @RequestBody(required = false) ReplayRequest request) {
        try {
            ReplayJobStatus status = replayService.triggerReplayAsync(sourceId, request);
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(status);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/status")
    public ResponseEntity<?> getStatus(@PathVariable String sourceId) {
        return replayService.getJobStatus(sourceId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping
    public ResponseEntity<?> cancelReplay(@PathVariable String sourceId) {
        boolean cancelled = replayService.cancelReplay(sourceId);
        if (cancelled) {
            return ResponseEntity.ok(Map.of("message", "Cancellation requested for replay job on source " + sourceId));
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "No active replay job to cancel for source " + sourceId));
    }
}
