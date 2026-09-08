package com.ulpf.common.tracing;

import java.util.Map;

/**
 * Immutable record representing a W3C & OpenTelemetry compliant distributed trace context.
 */
public record TraceContext(
        String traceId,
        String spanId,
        String parentSpanId,
        boolean sampled,
        Map<String, String> attributes
) {
    public TraceContext {
        attributes = attributes != null ? Map.copyOf(attributes) : Map.of();
    }

    public static TraceContext createNew(String traceId, String spanId) {
        return new TraceContext(traceId, spanId, null, true, Map.of());
    }
}
