package com.ulpf.common.tracing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility for parsing W3C Trace Context headers (`traceparent` and `tracestate`).
 * Standard format: 00-{4bf92f3577b34da6a3ce929d0e0e4736}-{00f067aa0ba902b7}-{01}
 */
public class W3cTraceContextParser {

    private static final Logger log = LoggerFactory.getLogger(W3cTraceContextParser.class);

    private static final Pattern TRACEPARENT_PATTERN = Pattern.compile(
            "^([0-9a-fA-F]{2})-([0-9a-fA-F]{32})-([0-9a-fA-F]{16})-([0-9a-fA-F]{2})$"
    );

    public static Optional<TraceContext> parseTraceparent(String traceparent) {
        if (traceparent == null || traceparent.isBlank()) {
            return Optional.empty();
        }

        Matcher matcher = TRACEPARENT_PATTERN.matcher(traceparent.trim());
        if (!matcher.matches()) {
            log.debug("Invalid W3C traceparent format: {}", traceparent);
            return Optional.empty();
        }

        String version = matcher.group(1);
        if ("ff".equalsIgnoreCase(version)) {
            return Optional.empty();
        }

        String traceId = matcher.group(2).toLowerCase();
        String parentSpanId = matcher.group(3).toLowerCase();
        String traceFlags = matcher.group(4);

        boolean sampled = (Integer.parseInt(traceFlags, 16) & 0x01) == 1;
        String childSpanId = generateSpanId();

        return Optional.of(new TraceContext(traceId, childSpanId, parentSpanId, sampled, null));
    }

    public static String generateTraceId() {
        return java.util.UUID.randomUUID().toString().replace("-", "");
    }

    public static String generateSpanId() {
        return java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    public static String formatTraceparent(TraceContext ctx) {
        if (ctx == null) return null;
        String flags = ctx.sampled() ? "01" : "00";
        return String.format("00-%s-%s-%s", ctx.traceId(), ctx.spanId(), flags);
    }
}
