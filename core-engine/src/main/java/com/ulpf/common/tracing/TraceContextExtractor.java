package com.ulpf.common.tracing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Extractor utility that inspects HTTP headers and raw event payloads for correlation IDs / Trace IDs.
 * Multi-candidate hierarchy:
 * 1. W3C traceparent header
 * 2. Common HTTP headers (X-Trace-Id, X-Request-Id, X-Correlation-Id)
 * 3. Raw log payload fields (trace_id, traceId, correlation_id, x-request-id, w3c_traceparent)
 * If missing or non-compliant with W3C / UUID hex standards, returns null (no silent zero-padding or truncation).
 */
public class TraceContextExtractor {

    private static final Logger log = LoggerFactory.getLogger(TraceContextExtractor.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static final Pattern HEX_32_PATTERN = Pattern.compile("^[0-9a-fA-F]{32}$");
    private static final Pattern UUID_PATTERN = Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final String ALL_ZEROS_TRACE_ID = "00000000000000000000000000000000";

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
     * Extracts a TraceContext from HTTP headers or log payload if a valid W3C trace ID / UUID is present.
     * Returns null if no valid trace ID is found.
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
                        String rawVal = entry.getValue().trim();
                        // Check if header contains a full W3C traceparent
                        Optional<TraceContext> w3cCtx = W3cTraceContextParser.parseTraceparent(rawVal);
                        if (w3cCtx.isPresent()) {
                            return w3cCtx.get();
                        }
                        Optional<String> validTraceId = validateAndNormalizeTraceId(rawVal);
                        if (validTraceId.isPresent()) {
                            String spanId = W3cTraceContextParser.generateSpanId();
                            return new TraceContext(validTraceId.get(), spanId, null, true, Map.of("extracted_header", candidate));
                        }
                    }
                }
            }
        }

        // 3. Try raw log payload JSON keys
        if (payload != null) {
            Optional<TraceContext> payloadCtx = extractFromPayload(payload);
            if (payloadCtx.isPresent()) {
                return payloadCtx.get();
            }
        }

        // Return null if no valid trace ID was provided
        return null;
    }

    /**
     * Generates a fresh W3C Trace Context when explicit generation is desired.
     */
    public static TraceContext generateFresh() {
        String freshTraceId = W3cTraceContextParser.generateTraceId();
        String freshSpanId = W3cTraceContextParser.generateSpanId();
        return new TraceContext(freshTraceId, freshSpanId, null, true, Map.of("generated", "true"));
    }

    private static Optional<TraceContext> extractFromPayload(Object payload) {
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
                            String trimmed = val.trim();
                            Optional<TraceContext> w3cCtx = W3cTraceContextParser.parseTraceparent(trimmed);
                            if (w3cCtx.isPresent()) {
                                return w3cCtx;
                            }
                            Optional<String> validTraceId = validateAndNormalizeTraceId(trimmed);
                            if (validTraceId.isPresent()) {
                                String spanId = W3cTraceContextParser.generateSpanId();
                                return Optional.of(new TraceContext(validTraceId.get(), spanId, null, true, Map.of("extracted_from", "payload", "payload_key", key)));
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Could not inspect payload for trace ID: {}", e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Validates and normalizes candidate trace ID strings into 32-character hexadecimal format.
     * Accepts:
     * - Standard 32-character hex strings ([0-9a-fA-F]{32})
     * - Standard 36-character canonical UUID strings (converted by stripping hyphens)
     * Rejects:
     * - Non-hexadecimal characters
     * - Strings of invalid lengths (does NOT truncate or pad)
     * - All-zero identifiers (forbidden by W3C specification)
     *
     * @param input the raw candidate trace ID string
     * @return Optional containing the lowercase 32-hex trace ID, or empty if invalid
     */
    public static Optional<String> validateAndNormalizeTraceId(String input) {
        if (input == null || input.isBlank()) {
            return Optional.empty();
        }
        String trimmed = input.trim();
        if (UUID_PATTERN.matcher(trimmed).matches()) {
            trimmed = trimmed.replace("-", "");
        }
        if (!HEX_32_PATTERN.matcher(trimmed).matches() || ALL_ZEROS_TRACE_ID.equals(trimmed)) {
            log.debug("Rejected non-compliant trace ID candidate: '{}'", input);
            return Optional.empty();
        }
        return Optional.of(trimmed.toLowerCase());
    }
}

