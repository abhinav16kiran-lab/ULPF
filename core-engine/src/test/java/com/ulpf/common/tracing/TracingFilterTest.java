package com.ulpf.common.tracing;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class TracingFilterTest {

    private TracingFilter tracingFilter;

    @BeforeEach
    void setUp() {
        tracingFilter = new TracingFilter();
        TraceMdcAdapter.clear();
    }

    @Test
    void testExtractW3cTraceparentHeader() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        String w3cHeader = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        request.addHeader("traceparent", w3cHeader);

        FilterChain filterChain = (req, res) -> {
            TraceContext ctx = (TraceContext) req.getAttribute(TracingFilter.TRACE_CONTEXT_ATTRIBUTE);
            assertNotNull(ctx);
            assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", ctx.traceId());
            assertEquals("00f067aa0ba902b7", ctx.parentSpanId());
            assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", MDC.get(TraceMdcAdapter.MDC_TRACE_ID));
        };

        tracingFilter.doFilterInternal(request, response, filterChain);

        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", response.getHeader("X-Trace-Id"));
        assertNotNull(response.getHeader("X-Span-Id"));
        assertTrue(response.getHeader("traceparent").startsWith("00-4bf92f3577b34da6a3ce929d0e0e4736-"));

        // MDC should be cleared after filter completion
        assertNull(MDC.get(TraceMdcAdapter.MDC_TRACE_ID));
    }

    @Test
    void testExtractGenericCorrelationHeader() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        request.addHeader("X-Correlation-Id", "my-custom-correlation-12345");

        FilterChain filterChain = (req, res) -> {
            TraceContext ctx = (TraceContext) req.getAttribute(TracingFilter.TRACE_CONTEXT_ATTRIBUTE);
            assertNotNull(ctx);
            assertNotNull(ctx.traceId());
            assertEquals(32, ctx.traceId().length());
            assertEquals(ctx.traceId(), MDC.get(TraceMdcAdapter.MDC_TRACE_ID));
        };

        tracingFilter.doFilterInternal(request, response, filterChain);

        assertNotNull(response.getHeader("X-Trace-Id"));
        assertNull(MDC.get(TraceMdcAdapter.MDC_TRACE_ID));
    }

    @Test
    void testFallbackFreshTraceIdGeneration() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain filterChain = (req, res) -> {
            TraceContext ctx = (TraceContext) req.getAttribute(TracingFilter.TRACE_CONTEXT_ATTRIBUTE);
            assertNotNull(ctx);
            assertNotNull(ctx.traceId());
            assertEquals(32, ctx.traceId().length());
            assertEquals(ctx.traceId(), MDC.get(TraceMdcAdapter.MDC_TRACE_ID));
        };

        tracingFilter.doFilterInternal(request, response, filterChain);

        assertNotNull(response.getHeader("X-Trace-Id"));
        assertNotNull(response.getHeader("traceparent"));
        assertNull(MDC.get(TraceMdcAdapter.MDC_TRACE_ID));
    }
}
