package com.flightplan.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * IM8 S2 – Audit Logging
 *
 * Logs every inbound HTTP request with:
 *  - A unique X-Request-ID for end-to-end trace correlation
 *  - Method, URI (no query string – may contain sensitive params), remote IP
 *  - HTTP response status and elapsed time
 *
 * Intentionally does NOT log:
 *  - Request or response bodies (may contain PII / classified flight data)
 *  - Authorization headers
 *  - Query parameters (may contain callsigns or sensitive identifiers)
 *
 * Log format is structured (key=value) to facilitate ingestion by a SIEM
 * (e.g. Splunk, ELK) as required by IM8 S2.
 */
@Component
@Slf4j
public class AuditLoggingFilter extends OncePerRequestFilter {

    private static final String REQUEST_ID_HEADER = "X-Request-ID";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String requestId = request.getHeader(REQUEST_ID_HEADER);
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }

        // Echo the request ID back so callers can correlate logs
        response.setHeader(REQUEST_ID_HEADER, requestId);

        long start = System.currentTimeMillis();

        try {
            filterChain.doFilter(request, response);
        } finally {
            long elapsed = System.currentTimeMillis() - start;
            // Strip query string from URI to avoid logging potentially sensitive params
            String uri = request.getRequestURI();

            log.info("[AUDIT] requestId={} method={} uri={} status={} elapsed={}ms ip={}",
                    requestId,
                    request.getMethod(),
                    uri,
                    response.getStatus(),
                    elapsed,
                    getClientIp(request));
        }
    }

    /**
     * Safely extract the client IP, handling X-Forwarded-For from the Ingress.
     * Takes only the leftmost (original client) address.
     */
    private String getClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
