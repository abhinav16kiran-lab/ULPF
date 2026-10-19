package com.ulpf.common.tracing;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.Map;
import java.util.Optional;

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
        assertTrue(ctx.sampled());
    }

    @Test
    void testExtractFromGenericCorrelationHeaderValidUuid() {
        Map<String, String> headers = Map.of(
                "X-Correlation-ID", "4bf92f35-77b3-4da6-a3ce-929d0e0e4736"
        );

        TraceContext ctx = TraceContextExtractor.extract(headers, null);

        assertNotNull(ctx);
        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", ctx.traceId());
        assertEquals(32, ctx.traceId().length());
        assertEquals("x-correlation-id", ctx.attributes().get("extracted_header"));
    }

    @Test
    void testExtractFromGenericCorrelationHeaderValid32Hex() {
        Map<String, String> headers = Map.of(
                "X-Trace-Id", "4BF92F3577B34DA6A3CE929D0E0E4736"
        );

        TraceContext ctx = TraceContextExtractor.extract(headers, null);

        assertNotNull(ctx);
        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", ctx.traceId());
        assertEquals("x-trace-id", ctx.attributes().get("extracted_header"));
    }

    @Test
    void testExtractFromGenericCorrelationHeaderInvalidRejected() {
        // Short string "abc" must not be zero-padded to 32 chars
        assertNull(TraceContextExtractor.extract(Map.of("X-Correlation-ID", "abc"), null));

        // Non-hex string must not be accepted
        assertNull(TraceContextExtractor.extract(Map.of("X-Correlation-ID", "my-custom-corr-123"), null));

        // Over-length string must not be truncated
        assertNull(TraceContextExtractor.extract(Map.of("X-Trace-Id", "4bf92f3577b34da6a3ce929d0e0e473600000"), null));

        // All-zeros must be rejected
        assertNull(TraceContextExtractor.extract(Map.of("X-Trace-Id", "00000000000000000000000000000000"), null));
    }

    @Test
    void testExtractFromPayloadKeyValid() {
        Map<String, Object> payload = Map.of(
                "trace_id", "4bf92f3577b34da6a3ce929d0e0e4736",
                "message", "Order processed"
        );

        TraceContext ctx = TraceContextExtractor.extract(null, payload);

        assertNotNull(ctx);
        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", ctx.traceId());
        assertEquals("payload", ctx.attributes().get("extracted_from"));
    }

    @Test
    void testExtractFromPayloadKeyInvalidRejected() {
        // Invalid short string
        assertNull(TraceContextExtractor.extract(null, Map.of("trace_id", "abc")));

        // Non-hex characters
        assertNull(TraceContextExtractor.extract(null, Map.of("trace_id", "abc123trace456789012345678901234")));

        // All-zeros
        assertNull(TraceContextExtractor.extract(null, Map.of("trace_id", "00000000-0000-0000-0000-000000000000")));
    }

    @Test
    void testExtractReturnsNullWhenMissing() {
        assertNull(TraceContextExtractor.extract(null, null));
        assertNull(TraceContextExtractor.extract(Map.of(), Map.of()));
    }

    @Test
    void testGenerateFreshTraceContext() {
        TraceContext ctx = TraceContextExtractor.generateFresh();

        assertNotNull(ctx);
        assertNotNull(ctx.traceId());
        assertEquals(32, ctx.traceId().length());
        assertNotNull(ctx.spanId());
        assertEquals(16, ctx.spanId().length());
        assertEquals("true", ctx.attributes().get("generated"));
    }

    @Test
    void testValidateAndNormalizeTraceIdDirect() {
        // Valid 32-hex
        Optional<String> hex32 = TraceContextExtractor.validateAndNormalizeTraceId("4bf92f3577b34da6a3ce929d0e0e4736");
        assertTrue(hex32.isPresent());
        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", hex32.get());

        // Valid UUID
        Optional<String> uuid = TraceContextExtractor.validateAndNormalizeTraceId("4bf92f35-77b3-4da6-a3ce-929d0e0e4736");
        assertTrue(uuid.isPresent());
        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", uuid.get());

        // Uppercase normalized to lowercase
        Optional<String> upper = TraceContextExtractor.validateAndNormalizeTraceId("4BF92F3577B34DA6A3CE929D0E0E4736");
        assertTrue(upper.isPresent());
        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", upper.get());

        // Invalid: short, padded, non-hex, zeros
        assertTrue(TraceContextExtractor.validateAndNormalizeTraceId("abc").isEmpty());
        assertTrue(TraceContextExtractor.validateAndNormalizeTraceId("my-custom-corr-123").isEmpty());
        assertTrue(TraceContextExtractor.validateAndNormalizeTraceId("00000000000000000000000000000000").isEmpty());
        assertTrue(TraceContextExtractor.validateAndNormalizeTraceId("").isEmpty());
        assertTrue(TraceContextExtractor.validateAndNormalizeTraceId(null).isEmpty());
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

