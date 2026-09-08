package com.ulpf.common.tracing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Extractor utility that inspects HTTP headers and raw event payloads for correlation IDs / Trace IDs.
 * Multi-candidate fallback hierarchy:
 * 1. W3C traceparent header
 * 2. Common HTTP headers (X-Trace-Id, X-Request-Id, X-Correlation-Id)
 * 3. Raw log payload fields (trace_id, traceId, correlation_id, x-request-id)
 * 4. Fallback generation of a fresh W3C Trace ID if missing.
 */
public class TraceContextExtractor {

    private static final Logger log = LoggerFactory.getLogger(TraceContextExtractor.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static final List<String> HEADER_CANDIDATES = List.of(
            "traceparent",
            "x-trace-id",
            "x-request-id",
            "x-correlation-id",
            "correlation-id",
            "request-id"
    );

    private static final List<String> PAYLOAD_KEYS = List.of(
            "trace_id",
            "traceId",
            "correlation_id",
            "correlationId",
            "x_request_id",
            "xRequestId",
            "request_id",
            "requestId",
            "w3c_traceparent"
    );

    /**
     * Extracts or generates a TraceContext from HTTP headers and log payload.
     */
    public static TraceContext extract(Map<String, String> headers, Object payload) {
        // 1. Try W3C traceparent header
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if ("traceparent".equalsIgnoreCase(entry.getKey())) {
                    Optional<TraceContext> w3cCtx = W3cTraceContextParser.parseTraceparent(entry.getValue());
                    if (w3cCtx.isPresent()) {
                        return w3cCtx.get();
                    }
                }
            }

            // 2. Try generic correlation headers
            for (String candidate : HEADER_CANDIDATES) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    if (candidate.equalsIgnoreCase(entry.getKey()) && entry.getValue() != null && !entry.getValue().isBlank()) {
                        String val = entry.getValue().trim();
                        String traceId = normalizeTo32Hex(val);
                        String spanId = W3cTraceContextParser.generateSpanId();
                        return new TraceContext(traceId, spanId, null, true, Map.of("extracted_header", candidate));
                    }
                }
            }
        }

        // 3. Try raw log payload JSON keys
        if (payload != null) {
            Optional<String> payloadTraceId = extractFromPayload(payload);
            if (payloadTraceId.isPresent()) {
                String traceId = normalizeTo32Hex(payloadTraceId.get());
                String spanId = W3cTraceContextParser.generateSpanId();
                return new TraceContext(traceId, spanId, null, true, Map.of("extracted_from", "payload"));
            }
        }

        // 4. Fallback generation of fresh W3C Trace Context
        String freshTraceId = W3cTraceContextParser.generateTraceId();
        String freshSpanId = W3cTraceContextParser.generateSpanId();
        return new TraceContext(freshTraceId, freshSpanId, null, true, Map.of("generated", "true"));
    }

    private static Optional<String> extractFromPayload(Object payload) {
        try {
            JsonNode rootNode;
            if (payload instanceof JsonNode jn) {
                rootNode = jn;
            } else if (payload instanceof String str) {
                rootNode = objectMapper.readTree(str);
            } else {
                rootNode = objectMapper.valueToTree(payload);
            }

            if (rootNode != null && rootNode.isObject()) {
                for (String key : PAYLOAD_KEYS) {
                    if (rootNode.has(key) && !rootNode.get(key).isNull()) {
                        String val = rootNode.get(key).asText();
                        if (val != null && !val.isBlank()) {
                            return Optional.of(val.trim());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Could not inspect payload for trace ID: {}", e.getMessage());
        }
        return Optional.empty();
    }

    private static String normalizeTo32Hex(String input) {
        String clean = input.replaceAll("[^a-zA-Z0-9]", "");
        if (clean.length() == 32) {
            return clean.toLowerCase();
        } else if (clean.length() > 32) {
            return clean.substring(0, 32).toLowerCase();
        } else {
            StringBuilder sb = new StringBuilder(clean.toLowerCase());
            while (sb.length() < 32) {
                sb.append("0");
            }
            return sb.toString();
        }
    }
}
