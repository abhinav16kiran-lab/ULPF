package com.ulpf.common.tracing;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TraceContextExtractorTest {

    @Test
    void testExtractFromW3cHeader() {
        Map<String, String> headers = Map.of(
                "traceparent", "00-7f91a34b92c019a8421b89104fa28101-e51b89104fa28101-01"
        );

        TraceContext ctx = TraceContextExtractor.extract(headers, null);

        assertNotNull(ctx);
        assertEquals("7f91a34b92c019a8421b89104fa28101", ctx.traceId());
        assertEquals("e51b89104fa28101", ctx.parentSpanId());
    }

    @Test
    void testExtractFromGenericCorrelationHeader() {
        Map<String, String> headers = Map.of(
                "X-Correlation-ID", "my-custom-corr-123"
        );

        TraceContext ctx = TraceContextExtractor.extract(headers, null);

        assertNotNull(ctx);
        assertNotNull(ctx.traceId());
        assertEquals(32, ctx.traceId().length());
        assertEquals("x-correlation-id", ctx.attributes().get("extracted_header"));
    }

    @Test
    void testExtractFromPayloadKey() {
        Map<String, Object> payload = Map.of(
                "trace_id", "abc123trace456789012345678901234",
                "message", "Order processed"
        );

        TraceContext ctx = TraceContextExtractor.extract(null, payload);

        assertNotNull(ctx);
        assertNotNull(ctx.traceId());
        assertEquals(32, ctx.traceId().length());
        assertEquals("payload", ctx.attributes().get("extracted_from"));
    }

    @Test
    void testFallbackGeneratesFreshW3cTraceContextWhenMissing() {
        TraceContext ctx = TraceContextExtractor.extract(null, null);

        assertNotNull(ctx);
        assertNotNull(ctx.traceId());
        assertEquals(32, ctx.traceId().length());
        assertNotNull(ctx.spanId());
        assertEquals(16, ctx.spanId().length());
        assertEquals("true", ctx.attributes().get("generated"));
    }

    @Test
    void testTraceMdcAdapterBindingAndClear() {
        TraceContext ctx = TraceContext.createNew("32charstraceid000000000000000001", "16charspanid0001");

        TraceMdcAdapter.put(ctx);
        assertEquals("32charstraceid000000000000000001", MDC.get(TraceMdcAdapter.MDC_TRACE_ID));
        assertEquals("16charspanid0001", MDC.get(TraceMdcAdapter.MDC_SPAN_ID));

        TraceMdcAdapter.clear();
        assertNull(MDC.get(TraceMdcAdapter.MDC_TRACE_ID));
        assertNull(MDC.get(TraceMdcAdapter.MDC_SPAN_ID));
    }
}
