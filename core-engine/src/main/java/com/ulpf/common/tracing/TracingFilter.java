package com.ulpf.common.tracing;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Servlet Filter that intercepts incoming HTTP requests, extracts/generates
 * distributed trace context (W3C traceparent, correlation headers), binds trace parameters to SLF4J MDC,
 * and sets trace headers on the HTTP response.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TracingFilter extends OncePerRequestFilter {

    public static final String TRACE_CONTEXT_ATTRIBUTE = "ULPF_TRACE_CONTEXT";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        Map<String, String> headerMap = new HashMap<>();
        var headerNames = request.getHeaderNames();
        if (headerNames != null) {
            while (headerNames.hasMoreElements()) {
                String headerName = headerNames.nextElement();
                headerMap.put(headerName.toLowerCase(), request.getHeader(headerName));
            }
        }

        TraceContext traceContext = TraceContextExtractor.extract(headerMap, null);
        request.setAttribute(TRACE_CONTEXT_ATTRIBUTE, traceContext);

        TraceMdcAdapter.put(traceContext);

        if (traceContext != null) {
            response.setHeader("X-Trace-Id", traceContext.traceId());
            response.setHeader("X-Span-Id", traceContext.spanId());
            response.setHeader("traceparent", W3cTraceContextParser.formatTraceparent(traceContext));
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            TraceMdcAdapter.clear();
        }
    }
}
