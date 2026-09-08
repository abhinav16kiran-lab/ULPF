package com.ulpf.common.tracing;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class W3cTraceContextParserTest {

    @Test
    void testParseValidW3cTraceparentHeader() {
        String header = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        Optional<TraceContext> result = W3cTraceContextParser.parseTraceparent(header);

        assertTrue(result.isPresent());
        TraceContext ctx = result.get();

        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", ctx.traceId());
        assertEquals("00f067aa0ba902b7", ctx.parentSpanId());
        assertNotNull(ctx.spanId());
        assertEquals(16, ctx.spanId().length());
        assertTrue(ctx.sampled());
    }

    @Test
    void testParseUnsampledTraceparentHeader() {
        String header = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-00";
        Optional<TraceContext> result = W3cTraceContextParser.parseTraceparent(header);

        assertTrue(result.isPresent());
        assertFalse(result.get().sampled());
    }

    @Test
    void testInvalidW3cTraceparentHeaderReturnsEmpty() {
        assertFalse(W3cTraceContextParser.parseTraceparent(null).isPresent());
        assertFalse(W3cTraceContextParser.parseTraceparent("").isPresent());
        assertFalse(W3cTraceContextParser.parseTraceparent("invalid-header-string").isPresent());
        // Invalid length or version 'ff'
        assertFalse(W3cTraceContextParser.parseTraceparent("ff-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01").isPresent());
    }

    @Test
    void testGenerateTraceIdAndSpanIdFormat() {
        String traceId = W3cTraceContextParser.generateTraceId();
        String spanId = W3cTraceContextParser.generateSpanId();

        assertNotNull(traceId);
        assertNotNull(spanId);
        assertEquals(32, traceId.length());
        assertEquals(16, spanId.length());
    }
}
