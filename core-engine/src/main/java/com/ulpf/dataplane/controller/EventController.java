package com.ulpf.dataplane.controller;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ulpf.dataplane.service.EventIngestionService;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The ingestion endpoint, {@code POST /v1/events}, authenticated by the {@code X-API-Key} header. One request
 * carries one event, in one of two forms:
 * <ul>
 *   <li>{@code application/json}: a JSON object, or a JSON string that holds one raw log line.</li>
 *   <li>Any other type ({@code text/plain}, {@code application/octet-stream}, {@code application/x-www-form-urlencoded},
 *       or none): the body is one raw log line, for example Syslog, CEF or LEEF. It is read as bytes and decoded
 *       with the charset that the request declares, or UTF-8 if it declares none.</li>
 * </ul>
 * The format detector decides what the event is; the content type only decides how the body is read.
 *
 * <p>Why two handlers: before 2026-11-15 one handler bound every body to {@code Object}. Spring has no converter
 * from text to {@code Object}, so every plain-text body got 415, although the API spec promised plain text.
 * The text handler reads the raw request stream, not an {@code @RequestBody}: for a form type, Spring rebuilds the
 * body from parsed form parameters and re-encodes it, so a line sent with {@code curl -d} would change; and Spring's
 * String converter falls back to ISO-8859-1, which garbles UTF-8 lines that declare no charset.
 */
@RestController
@RequestMapping("/v1")
public class EventController {

    private final EventIngestionService eventIngestionService;

    EventController(EventIngestionService eventIngestionService) {
        this.eventIngestionService = eventIngestionService;
    }

    /** A JSON body: an object (most vendors) or a string (a raw line wrapped in quotes). */
    @PostMapping(value = "/events", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> ingestJson(
            @RequestHeader(value = "X-API-Key", required = false) String apiKey,
            @RequestBody Object payload,
            HttpServletRequest request
    ) {
        return ingest(apiKey, payload, request);
    }

    /**
     * Every other body: one raw log line. {@code !application/json} also matches a request without a type. The two
     * mappings must not overlap: with an optional JSON body, both matched an empty request and Spring answered 409.
     */
    @PostMapping(value = "/events", consumes = "!" + MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> ingestText(
            @RequestHeader(value = "X-API-Key", required = false) String apiKey,
            HttpServletRequest request
    ) throws IOException {
        if (apiKey == null || apiKey.isBlank()) {  // checked before the body is read, so no work is done for it
            return ResponseEntity.status(401).body(Map.of("error", "missing API key"));
        }
        byte[] body = request.getInputStream().readAllBytes();
        return ingest(apiKey, new String(body, charsetOf(request)), request);
    }

    private static Charset charsetOf(HttpServletRequest request) {
        String declared = request.getCharacterEncoding();
        if (declared != null) {
            try {
                return Charset.forName(declared);
            } catch (IllegalArgumentException unknownCharset) {
                // An unknown name falls back to UTF-8, the common case for log shippers.
            }
        }
        return StandardCharsets.UTF_8;
    }

    private ResponseEntity<?> ingest(String apiKey, Object payload, HttpServletRequest request) {
        if (apiKey == null || apiKey.isBlank()) {
            return ResponseEntity.status(401).body(Map.of("error", "missing API key"));
        }

        String vendorId = eventIngestionService.resolveVendorFromApiKey(apiKey);
        if (vendorId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "invalid API key"));
        }

        if (payload == null || (payload instanceof String s && s.isBlank()) || (payload instanceof Map<?,?> m && m.isEmpty())) {
            return ResponseEntity.badRequest().body(Map.of("error", "request body must not be empty"));
        }

        com.ulpf.common.tracing.TraceContext traceCtx = null;
        if (request != null) {
            traceCtx = (com.ulpf.common.tracing.TraceContext) request.getAttribute(com.ulpf.common.tracing.TracingFilter.TRACE_CONTEXT_ATTRIBUTE);
        }

        var result = eventIngestionService.ingest(apiKey, payload, traceCtx);

        java.util.Map<String, Object> responseMap = new java.util.HashMap<>();
        responseMap.put("eventId", result.eventId());
        responseMap.put("status", result.status());
        if (result.detectedFormat() != null) {
            responseMap.put("detectedFormat", result.detectedFormat());
        }
        if (result.traceId() != null) {
            responseMap.put("traceId", result.traceId());
        }

        return ResponseEntity.status(202).body(responseMap);
    }
}
