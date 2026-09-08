package com.ulpf.common.tracing;

import org.slf4j.MDC;

/**
 * Adapter utility for binding and unbinding TraceContext to SLF4J / Logback MDC (Mapped Diagnostic Context).
 * Enables zero-boilerplate logging across threads.
 */
public class TraceMdcAdapter {

    public static final String MDC_TRACE_ID = "trace_id";
    public static final String MDC_SPAN_ID = "span_id";

    public static void put(TraceContext traceContext) {
        if (traceContext != null) {
            MDC.put(MDC_TRACE_ID, traceContext.traceId());
            MDC.put(MDC_SPAN_ID, traceContext.spanId());
        }
    }

    public static void clear() {
        MDC.remove(MDC_TRACE_ID);
        MDC.remove(MDC_SPAN_ID);
    }
}
