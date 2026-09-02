package com.ulpf.dataplane.controller;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ulpf.dataplane.service.EventIngestionService;

@RestController
@RequestMapping("/v1")
public class EventController {

    private final EventIngestionService eventIngestionService;

    EventController(EventIngestionService eventIngestionService) {
        this.eventIngestionService = eventIngestionService;
    }

    @PostMapping("/events")
    public ResponseEntity<?> ingest(
            @RequestHeader(value = "X-API-Key", required = false) String apiKey,
            @RequestBody(required = false) Map<String, Object> payload
    ) {
        if (apiKey == null || apiKey.isBlank()) {
            return ResponseEntity.status(401).body(Map.of("error", "missing API key"));
        }

        String vendorId = eventIngestionService.resolveVendorFromApiKey(apiKey);
        if (vendorId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "invalid API key"));
        }

        if (payload == null || payload.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "request body must be a valid JSON object"));
        }

        var result = eventIngestionService.ingest(vendorId, payload);

        return ResponseEntity.status(202).body(Map.of(
                "eventId", result.eventId(),
                "status", result.status()
        ));
    }
}