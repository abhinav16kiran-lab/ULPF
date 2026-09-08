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
            @RequestBody(required = false) Map<String, Object> payload,
            jakarta.servlet.http.HttpServletRequest request
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

        com.ulpf.common.tracing.TraceContext traceCtx = null;
        if (request != null) {
            traceCtx = (com.ulpf.common.tracing.TraceContext) request.getAttribute(com.ulpf.common.tracing.TracingFilter.TRACE_CONTEXT_ATTRIBUTE);
        }

        var result = eventIngestionService.ingest(apiKey, payload, traceCtx);

        java.util.Map<String, Object> responseMap = new java.util.HashMap<>();
        responseMap.put("eventId", result.eventId());
        responseMap.put("status", result.status());
        if (result.traceId() != null) {
            responseMap.put("traceId", result.traceId());
        }

        return ResponseEntity.status(202).body(responseMap);
    }
}