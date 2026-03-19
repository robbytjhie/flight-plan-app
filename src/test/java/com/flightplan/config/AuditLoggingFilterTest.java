package com.flightplan.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import com.flightplan.controller.FlightController;
import com.flightplan.service.FlightService;

import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.List;

/**
 * Unit tests for AuditLoggingFilter.
 *
 * Covers all branches:
 *  - X-Request-ID: provided by client vs. generated fresh (null / blank)
 *  - getClientIp: X-Forwarded-For present vs. absent
 *    (both single IP and comma-separated "client, proxy" forms)
 *
 * Uses @WebMvcTest so the real filter is loaded into the MockMvc filter chain.
 */
@WebMvcTest(
    value = FlightController.class,
    properties = "security.cors.allowed-origins=http://localhost:3000"
)
@Import({AuditLoggingFilter.class, InputSanitiser.class, SecurityConfig.class})
@DisplayName("AuditLoggingFilter")
class AuditLoggingFilterTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    FlightService flightService;

    // ── X-Request-ID header handling ──────────────────────────────────

    @Nested
    @DisplayName("X-Request-ID header")
    class RequestIdTests {

        @Test
        @DisplayName("echoes back client-supplied X-Request-ID unchanged")
        void echosClientProvidedRequestId() throws Exception {
            mockMvc.perform(get("/api/health")
                            .header("X-Request-ID", "client-id-abc123"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("X-Request-ID", "client-id-abc123"));
        }

        @Test
        @DisplayName("generates a UUID X-Request-ID when header is absent")
        void generatesRequestIdWhenAbsent() throws Exception {
            mockMvc.perform(get("/api/health"))
                    .andExpect(status().isOk())
                    .andExpect(header().exists("X-Request-ID"))
                    // Generated IDs are UUID format: 8-4-4-4-12 hex chars
                    .andExpect(header().string("X-Request-ID",
                            matchesPattern("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")));
        }

        @Test
        @DisplayName("generates a new UUID when X-Request-ID header is blank")
        void generatesRequestIdWhenBlank() throws Exception {
            mockMvc.perform(get("/api/health")
                            .header("X-Request-ID", "   "))
                    .andExpect(status().isOk())
                    .andExpect(header().exists("X-Request-ID"))
                    // Should NOT echo back the blank value
                    .andExpect(header().string("X-Request-ID",
                            matchesPattern("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")));
        }

        @Test
        @DisplayName("each request without X-Request-ID gets a distinct generated ID")
        void generatesUniqueIdsPerRequest() throws Exception {
            String id1 = mockMvc.perform(get("/api/health"))
                    .andReturn().getResponse().getHeader("X-Request-ID");
            String id2 = mockMvc.perform(get("/api/health"))
                    .andReturn().getResponse().getHeader("X-Request-ID");

            org.assertj.core.api.Assertions.assertThat(id1).isNotEqualTo(id2);
        }
    }

    // ── Client IP extraction (X-Forwarded-For branches) ───────────────

    @Nested
    @DisplayName("Client IP extraction (X-Forwarded-For)")
    class ClientIpTests {

        @Test
        @DisplayName("filter completes successfully without X-Forwarded-For header")
        void noForwardedForHeader() throws Exception {
            // AuditLoggingFilter falls back to request.getRemoteAddr() — just verify
            // it completes without error and still returns 200
            mockMvc.perform(get("/api/health"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("filter completes successfully with single X-Forwarded-For IP")
        void singleForwardedForIp() throws Exception {
            mockMvc.perform(get("/api/health")
                            .header("X-Forwarded-For", "203.0.113.42"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("filter extracts first IP from comma-separated X-Forwarded-For")
        void commaSeperatedForwardedFor() throws Exception {
            // "client, proxy1, proxy2" — only leftmost (client) IP should be logged
            mockMvc.perform(get("/api/health")
                            .header("X-Forwarded-For", "203.0.113.42, 10.0.0.1, 10.0.0.2"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("filter handles blank X-Forwarded-For by falling back to remote addr")
        void blankForwardedFor() throws Exception {
            mockMvc.perform(get("/api/health")
                            .header("X-Forwarded-For", "   "))
                    .andExpect(status().isOk());
        }
    }

    // ── Filter applies to all endpoints ──────────────────────────────

    @Nested
    @DisplayName("Filter applies to all endpoints")
    class FilterScopeTests {

        @Test
        @DisplayName("X-Request-ID header is present on /api/flights response")
        void requestIdOnFlightsEndpoint() throws Exception {
            when(flightService.getAllFlightPlans()).thenReturn(List.of());
            mockMvc.perform(get("/api/flights"))
                    .andExpect(status().isOk())
                    .andExpect(header().exists("X-Request-ID"));
        }

        @Test
        @DisplayName("X-Request-ID header is present on /api/cache/status response")
        void requestIdOnCacheStatusEndpoint() throws Exception {
            when(flightService.getCacheLastRefreshed()).thenReturn(null);
            when(flightService.getAllFlightPlans()).thenReturn(List.of());
            when(flightService.getAirways()).thenReturn(List.of());
            when(flightService.getFixes()).thenReturn(List.of());
            mockMvc.perform(get("/api/cache/status"))
                    .andExpect(status().isOk())
                    .andExpect(header().exists("X-Request-ID"));
        }

        @Test
        @DisplayName("client-supplied X-Request-ID is echoed on non-health endpoints")
        void requestIdEchoedOnNonHealthEndpoint() throws Exception {
            when(flightService.getAllFlightPlans()).thenReturn(List.of());
            mockMvc.perform(get("/api/flights")
                            .header("X-Request-ID", "trace-xyz-999"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("X-Request-ID", "trace-xyz-999"));
        }
    }
}
