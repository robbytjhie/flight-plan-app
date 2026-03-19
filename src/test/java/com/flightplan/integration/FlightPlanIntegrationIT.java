package com.flightplan.integration;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for the full Spring Boot context — dev profile only.
 *
 * ── Profile strategy ────────────────────────────────────────────────────────
 * This class runs with @ActiveProfiles("dev"):
 *   MockDataFetchStrategy is active — no real API calls, no WebClient needed.
 *   MockRedisConfig provides a mock RedisConnectionFactory so LeaderElectionConfig
 *   can probe Redis (it will "fail" the ping gracefully, falling back to
 *   DefaultLockRegistry — which is the correct dev behaviour).
 *
 * ── Prod-profile / WireMock tests ───────────────────────────────────────────
 * Live-mode (prod profile) tests live in {@link LiveModeProdIT}.
 * Spring Boot does NOT support @SpringBootTest on @Nested inner classes —
 * attempting it silently ignores the inner-class annotations and runs the
 * nested tests in the outer class's context, causing wrong-profile failures.
 *
 * ── Why MockRedisConfig instead of excluding Redis auto-config? ─────────────
 * LeaderElectionConfig always takes a RedisConnectionFactory parameter
 * (both dev and prod beans do a connection probe or construct a
 * RedisLockRegistry). We need the bean to exist in the application context,
 * but we don't want CI to require a live Redis server.
 * A @TestConfiguration @Primary mock satisfies the dependency without any
 * real connection.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "security.cors.allowed-origins=http://localhost:3000",
                "spring.main.allow-bean-definition-overriding=true"
        }
)
@AutoConfigureMockMvc
@ActiveProfiles("mock")
@DisplayName("FlightPlan Integration Tests")
class FlightPlanIntegrationIT {

    @Autowired MockMvc mockMvc;

    /**
     * Provides a no-op mock RedisConnectionFactory for all integration tests.
     *
     * LeaderElectionConfig.devLockRegistry() probes this factory with a PING —
     * the mock throws an exception, the catch block logs a WARN, and the bean
     * falls back to DefaultLockRegistry.  This is the expected dev behaviour and
     * confirms that the fallback path works correctly in tests.
     */
    @TestConfiguration
    static class MockRedisConfig {
        @Bean
        @Primary
        public RedisConnectionFactory redisConnectionFactory() {
            // Returns a mock — PING will throw, triggering the dev fallback to
            // DefaultLockRegistry.  This is intentional and tested behaviour.
            return mock(RedisConnectionFactory.class);
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  IM8 Security Headers (applied to all endpoints)
    // ══════════════════════════════════════════════════════════════════

    @Nested @DisplayName("IM8 Security Headers (integration)")
    class SecurityHeaderIntegrationTests {

        @Test @DisplayName("X-Content-Type-Options: nosniff on /api/flights")
        void contentTypeOptionsHeader() throws Exception {
            mockMvc.perform(get("/api/flights"))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        }

        @Test @DisplayName("X-Frame-Options: DENY on /api/flights")
        void frameOptionsHeader() throws Exception {
            mockMvc.perform(get("/api/flights"))
                    .andExpect(header().string("X-Frame-Options", "DENY"));
        }

        @Test @DisplayName("Content-Security-Policy header present on /api/flights")
        void cspHeader() throws Exception {
            mockMvc.perform(get("/api/flights"))
                    .andExpect(header().exists("Content-Security-Policy"));
        }

        @Test @DisplayName("Strict-Transport-Security (HSTS) header present")
        void hstsHeader() throws Exception {
            mockMvc.perform(get("/api/flights"))
                    .andExpect(header().exists("Strict-Transport-Security"));
        }

        @Test @DisplayName("Referrer-Policy header present")
        void referrerPolicyHeader() throws Exception {
            mockMvc.perform(get("/api/flights"))
                    .andExpect(header().exists("Referrer-Policy"));
        }

        @Test @DisplayName("X-Request-ID generated and returned in response")
        void requestIdGeneratedForResponse() throws Exception {
            mockMvc.perform(get("/api/health"))
                    .andExpect(header().exists("X-Request-ID"));
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  DEV PROFILE — MockDataFetchStrategy active
    // ══════════════════════════════════════════════════════════════════

    @Nested @DisplayName("Dev Profile (mock data) — /api/flights")
    class MockModeFlightsTests {

        @Test @DisplayName("GET /api/flights → 200, 8 flights from mock cache")
        void returnsEightFlights() throws Exception {
            mockMvc.perform(get("/api/flights"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$", hasSize(8)));
        }

        @Test @DisplayName("every flight has required top-level fields")
        void allFlightsHaveRequiredFields() throws Exception {
            mockMvc.perform(get("/api/flights"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].aircraftIdentification").isNotEmpty())
                    .andExpect(jsonPath("$[0].departure.departureAerodrome").isNotEmpty())
                    .andExpect(jsonPath("$[0].arrival.destinationAerodrome").isNotEmpty())
                    .andExpect(jsonPath("$[0].aircraft.aircraftType").isNotEmpty())
                    .andExpect(jsonPath("$[0].messageType").value("FPL"));
        }

        @Test @DisplayName("response contains all 8 known callsigns")
        void containsAllCallsigns() throws Exception {
            mockMvc.perform(get("/api/flights"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[*].aircraftIdentification",
                            containsInAnyOrder("SIA200","MAS370","CPA101","UAL837",
                                    "QFA002","EK432","THA669","GIA723")));
        }
    }

    @Nested @DisplayName("Dev Profile (mock data) — /api/flights/search")
    class MockModeSearchTests {

        @Test @DisplayName("search SIA → returns SIA200 only")
        void searchSiaReturnsSia200() throws Exception {
            mockMvc.perform(get("/api/flights/search").param("callsign", "SIA"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].aircraftIdentification").value("SIA200"));
        }

        @Test @DisplayName("search is case-insensitive")
        void caseInsensitiveSearch() throws Exception {
            mockMvc.perform(get("/api/flights/search").param("callsign", "sia200"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)));
        }

        @Test @DisplayName("missing callsign param → 400")
        void missingParamReturns400() throws Exception {
            mockMvc.perform(get("/api/flights/search"))
                    .andExpect(status().isBadRequest());
        }

        @Test @DisplayName("IM8 S6: XSS payload in search param → 400")
        void xssPayloadReturns400() throws Exception {
            mockMvc.perform(get("/api/flights/search")
                            .param("callsign", "<script>alert(1)</script>"))
                    .andExpect(status().isBadRequest());
        }

        @Test @DisplayName("IM8 S6: SQL injection in search param → 400")
        void sqlInjectionReturns400() throws Exception {
            mockMvc.perform(get("/api/flights/search")
                            .param("callsign", "'; DROP TABLE--"))
                    .andExpect(status().isBadRequest());
        }

        @Test @DisplayName("IM8 S6: overlong callsign → 400")
        void overlongCallsignReturns400() throws Exception {
            mockMvc.perform(get("/api/flights/search")
                            .param("callsign", "TOOLONGCALLSIGN"))
                    .andExpect(status().isBadRequest());
        }

        @Test @DisplayName("IM8 S6: error response contains no Java stack trace")
        void errorResponseHasNoStackTrace() throws Exception {
            mockMvc.perform(get("/api/flights/search").param("callsign", "<evil>"))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().string(not(containsString("java."))))
                    .andExpect(content().string(not(containsString("Exception"))));
        }
    }

    @Nested @DisplayName("Dev Profile (mock data) — /api/flights/{callsign}")
    class MockModeGetByCallsignTests {

        @Test @DisplayName("GET /api/flights/SIA200 → 200 with correct data")
        void returnsSia200() throws Exception {
            mockMvc.perform(get("/api/flights/SIA200"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.aircraftIdentification").value("SIA200"))
                    .andExpect(jsonPath("$.departure.departureAerodrome").value("WSSS"))
                    .andExpect(jsonPath("$.arrival.destinationAerodrome").value("YSSY"))
                    .andExpect(jsonPath("$.aircraft.aircraftType").value("A359"));
        }

        @Test @DisplayName("GET /api/flights/EK432 → 200 with correct data")
        void returnsEk432() throws Exception {
            mockMvc.perform(get("/api/flights/EK432"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.departure.departureAerodrome").value("OMDB"))
                    .andExpect(jsonPath("$.arrival.destinationAerodrome").value("WSSS"));
        }

        @Test @DisplayName("GET /api/flights/NOTFND → 404 (valid format, not in cache)")
        void returns404ForValidButUnknown() throws Exception {
            mockMvc.perform(get("/api/flights/NOTFND"))
                    .andExpect(status().isNotFound());
        }

        @Test @DisplayName("IM8 S6: overlong path variable → 400")
        void xssInPathReturns400() throws Exception {
            mockMvc.perform(get("/api/flights/TOOLONGID"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested @DisplayName("Dev Profile (mock data) — /api/route/{callsign}")
    class MockModeRouteTests {

        @Test @DisplayName("GET /api/route/SIA200 → 200 with polyline and routePoints")
        void resolvesSia200Route() throws Exception {
            mockMvc.perform(get("/api/route/SIA200"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.callsign").value("SIA200"))
                    .andExpect(jsonPath("$.departureAerodrome").value("WSSS"))
                    .andExpect(jsonPath("$.destinationAerodrome").value("YSSY"))
                    .andExpect(jsonPath("$.routePoints", not(empty())))
                    .andExpect(jsonPath("$.polyline", not(empty())));
        }

        @Test @DisplayName("first routePoint is departure airport WSSS")
        void firstPointIsWSSS() throws Exception {
            mockMvc.perform(get("/api/route/SIA200"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.routePoints[0].name").value("WSSS"))
                    .andExpect(jsonPath("$.routePoints[0].type").value("airport"));
        }

        @Test @DisplayName("routePoints have lat/lon numbers")
        void routePointsHaveCoords() throws Exception {
            mockMvc.perform(get("/api/route/SIA200"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.routePoints[0].lat").isNumber())
                    .andExpect(jsonPath("$.routePoints[0].lon").isNumber());
        }

        @Test @DisplayName("each polyline entry is a 2-element array")
        void polylineEntriesArePairs() throws Exception {
            mockMvc.perform(get("/api/route/SIA200"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.polyline[0]", hasSize(2)));
        }

        @Test @DisplayName("GET /api/route/NOTFND → 404 (valid format, not in cache)")
        void returns404ForValidButUnknown() throws Exception {
            mockMvc.perform(get("/api/route/NOTFND"))
                    .andExpect(status().isNotFound());
        }

        @Test @DisplayName("all 8 mock flights resolve successfully")
        void allFlightsResolve() throws Exception {
            for (String cs : new String[]{"SIA200","MAS370","CPA101","UAL837",
                    "QFA002","EK432","THA669","GIA723"}) {
                mockMvc.perform(get("/api/route/" + cs))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.callsign").value(cs));
            }
        }
    }

    @Nested @DisplayName("Dev Profile (mock data) — /api/geopoints")
    class MockModeGeopointTests {

        @Test @DisplayName("GET /api/geopoints/airways → 200, non-empty, type=airway")
        void airwaysNonEmpty() throws Exception {
            mockMvc.perform(get("/api/geopoints/airways"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", not(empty())))
                    .andExpect(jsonPath("$[0].type").value("airway"))
                    .andExpect(jsonPath("$[0].name").isNotEmpty());
        }

        @Test @DisplayName("GET /api/geopoints/fixes → 200, non-empty, type=fix")
        void fixesNonEmpty() throws Exception {
            mockMvc.perform(get("/api/geopoints/fixes"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", not(empty())))
                    .andExpect(jsonPath("$[0].type").value("fix"));
        }

        @Test @DisplayName("WSSS has exact coordinates in fixes")
        void wsssExactCoords() throws Exception {
            mockMvc.perform(get("/api/geopoints/fixes"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[?(@.name=='WSSS')].lat", hasItem(1.3644)))
                    .andExpect(jsonPath("$[?(@.name=='WSSS')].lon", hasItem(103.9915)));
        }
    }

    @Nested @DisplayName("GET /api/health")
    class HealthTests {

        @Test @DisplayName("200 with status=UP and service name")
        void healthReturnsUp() throws Exception {
            mockMvc.perform(get("/api/health"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UP"))
                    .andExpect(jsonPath("$.service").value("flight-plan-backend"));
        }

        @Test @DisplayName("IM8 S6: health endpoint does NOT expose version (reduces fingerprinting)")
        void healthDoesNotExposeVersion() throws Exception {
            mockMvc.perform(get("/api/health"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.version").doesNotExist());
        }
    }
}