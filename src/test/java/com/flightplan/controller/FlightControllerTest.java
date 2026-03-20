package com.flightplan.controller;

import com.flightplan.config.InputSanitiser;
import com.flightplan.config.SecurityConfig;
import com.flightplan.model.FlightPlan;
import com.flightplan.model.FlightRoute;
import com.flightplan.model.GeoPoint;
import com.flightplan.service.FlightService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit tests for FlightController.
 *
 * Uses @WebMvcTest which loads the Spring Security filter chain, so CORS headers,
 * CSP, HSTS, and X-Frame-Options are all validated here too.
 *
 * Stubbing strategy:
 *  - Security header tests hit GET /api/health, which requires no service stubs,
 *    avoiding unnecessary stubbing errors entirely.
 *  - Each functional test stubs only the service methods that its endpoint actually calls.
 *  - InputSanitiser is imported via @Import(InputSanitiser.class) — the real bean
 *    is required so IM8 S6 validation tests get genuine 400 rejections.
 */
@WebMvcTest(value = FlightController.class, properties = "security.cors.allowed-origins=http://localhost:3000")
@Import({InputSanitiser.class, SecurityConfig.class})   // SecurityConfig loads our permitAll rules; InputSanitiser for IM8 S6 validation
@DisplayName("FlightController")
class FlightControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean FlightService flightService;
    // InputSanitiser loaded via @Import above — real bean needed for IM8 S6 validation tests

    private FlightPlan sia200;
    private FlightPlan mas370;
    private FlightRoute sia200Route;

    @BeforeEach
    void setUp() {
        sia200 = flight("SIA200", "WSSS", "YSSY", "A359", "M");
        mas370 = flight("MAS370", "WMKK", "ZBAA", "B772", "S");
        sia200Route = route("SIA200", "WSSS", "YSSY", "A359");
    }

    // ── Security headers (IM8 S5 / S6 / S8 / S9) ────────────────────
    // All tests use GET /api/health — no service stub needed, eliminating
    // any risk of UnnecessaryStubbingException in this block.

    @Nested @DisplayName("IM8 Security Headers")
    class SecurityHeaderTests {

        @Test @DisplayName("response includes X-Content-Type-Options: nosniff")
        void contentTypeOptions() throws Exception {
            mockMvc.perform(get("/api/health"))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        }

        @Test @DisplayName("response includes X-Frame-Options: DENY")
        void frameOptions() throws Exception {
            mockMvc.perform(get("/api/health"))
                    .andExpect(header().string("X-Frame-Options", "DENY"));
        }

        @Test @DisplayName("response includes Content-Security-Policy header")
        void cspHeader() throws Exception {
            mockMvc.perform(get("/api/health"))
                    .andExpect(header().exists("Content-Security-Policy"));
        }

        @Test @DisplayName("response includes Referrer-Policy header")
        void referrerPolicy() throws Exception {
            mockMvc.perform(get("/api/health"))
                    .andExpect(header().exists("Referrer-Policy"));
        }

        @Test @DisplayName("response includes X-Request-ID header")
        void requestIdHeader() throws Exception {
            mockMvc.perform(get("/api/health"))
                    .andExpect(header().exists("X-Request-ID"));
        }

        @Test @DisplayName("X-Request-ID from request is echoed back in response")
        void requestIdEchoed() throws Exception {
            mockMvc.perform(get("/api/health").header("X-Request-ID", "test-id-abc123"))
                    .andExpect(header().string("X-Request-ID", "test-id-abc123"));
        }
    }

    // ── GET /api/flights ─────────────────────────────────────────────

    @Nested @DisplayName("GET /api/flights")
    class GetAllFlightsTests {

        @Test @DisplayName("200 with list of all flights")
        void returns200WithList() throws Exception {
            when(flightService.getAllFlightPlans()).thenReturn(List.of(sia200, mas370));
            mockMvc.perform(get("/api/flights"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$", hasSize(2)))
                    .andExpect(jsonPath("$[0].aircraftIdentification").value("SIA200"))
                    .andExpect(jsonPath("$[1].aircraftIdentification").value("MAS370"));
        }

        @Test @DisplayName("200 with empty list when no flights cached")
        void returns200Empty() throws Exception {
            when(flightService.getAllFlightPlans()).thenReturn(List.of());
            mockMvc.perform(get("/api/flights"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(0)));
        }
    }

    // ── GET /api/flights/search ──────────────────────────────────────

    @Nested @DisplayName("GET /api/flights/search")
    class SearchFlightsTests {

        @Test @DisplayName("returns only SIA200 for callsign=SIA")
        void filtersBySiaPrefix() throws Exception {
            when(flightService.getAllFlightPlans()).thenReturn(List.of(sia200, mas370));
            mockMvc.perform(get("/api/flights/search").param("callsign", "SIA"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].aircraftIdentification").value("SIA200"));
        }

        @Test @DisplayName("search is case-insensitive (sia200 finds SIA200)")
        void caseInsensitive() throws Exception {
            when(flightService.getAllFlightPlans()).thenReturn(List.of(sia200, mas370));
            mockMvc.perform(get("/api/flights/search").param("callsign", "sia200"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)));
        }

        @Test @DisplayName("returns empty list for no match")
        void returnsEmptyForNoMatch() throws Exception {
            when(flightService.getAllFlightPlans()).thenReturn(List.of(sia200, mas370));
            mockMvc.perform(get("/api/flights/search").param("callsign", "ZZZZZZ"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(0)));
        }

        @Test @DisplayName("400 when callsign param is missing")
        void returns400WhenParamMissing() throws Exception {
            mockMvc.perform(get("/api/flights/search"))
                    .andExpect(status().isBadRequest());
        }

        // ── IM8 S6: Input validation / XSS prevention ────────────────

        @Test @DisplayName("IM8 S6: 400 for XSS payload in callsign param")
        void rejects400ForXssPayload() throws Exception {
            mockMvc.perform(get("/api/flights/search")
                            .param("callsign", "<script>alert(1)</script>"))
                    .andExpect(status().isBadRequest());
        }

        @Test @DisplayName("IM8 S6: 400 for SQL injection pattern in callsign param")
        void rejects400ForSqlInjection() throws Exception {
            mockMvc.perform(get("/api/flights/search")
                            .param("callsign", "'; DROP TABLE--"))
                    .andExpect(status().isBadRequest());
        }

        @Test @DisplayName("IM8 S6: 400 for callsign exceeding 8 characters")
        void rejects400ForOverlongCallsign() throws Exception {
            mockMvc.perform(get("/api/flights/search")
                            .param("callsign", "TOOLONGCALLSIGN"))
                    .andExpect(status().isBadRequest());
        }

        @Test @DisplayName("IM8 S6: error response body contains no stack trace")
        void errorResponseHasNoStackTrace() throws Exception {
            mockMvc.perform(get("/api/flights/search")
                            .param("callsign", "<evil>"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").exists())
                    .andExpect(content().string(not(containsString("java."))))
                    .andExpect(content().string(not(containsString("Exception"))));
        }

        @Test @DisplayName("skips flights with null aircraftIdentification")
        void skipsNullCallsignFlights() throws Exception {
            FlightPlan nullCallsign = new FlightPlan();
            nullCallsign.setAircraftIdentification(null);
            when(flightService.getAllFlightPlans()).thenReturn(List.of(sia200, nullCallsign));
            mockMvc.perform(get("/api/flights/search").param("callsign", "SIA"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)));
        }
    }

    // ── GET /api/flights/{callsign} ──────────────────────────────────

    @Nested @DisplayName("GET /api/flights/{callsign}")
    class GetFlightByCallsignTests {

        @Test @DisplayName("200 with flight when found")
        void returns200WhenFound() throws Exception {
            when(flightService.getFlightByCallsign("SIA200")).thenReturn(Optional.of(sia200));
            mockMvc.perform(get("/api/flights/SIA200"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.aircraftIdentification").value("SIA200"))
                    .andExpect(jsonPath("$.departure.departureAerodrome").value("WSSS"))
                    .andExpect(jsonPath("$.arrival.destinationAerodrome").value("YSSY"));
        }

        @Test @DisplayName("404 when callsign not found in cache")
        void returns404WhenMissing() throws Exception {
            when(flightService.getFlightByCallsign(anyString())).thenReturn(Optional.empty());
            mockMvc.perform(get("/api/flights/NOTFND"))
                    .andExpect(status().isNotFound());
        }

        // IM8 S6: path variable sanitisation
        @Test @DisplayName("IM8 S6: 400 for XSS payload in path variable")
        void rejects400ForXssInPath() throws Exception {
            mockMvc.perform(get("/api/flights/<script>alert(1)</script>"))
                    .andExpect(status().isBadRequest());
        }

        @Test @DisplayName("IM8 S6: 400 for path variable with special characters")
        void rejects400ForSpecialCharsInPath() throws Exception {
            mockMvc.perform(get("/api/flights/SIA%20200"))
                    .andExpect(status().isBadRequest());
        }

        @Test @DisplayName("IM8 S6: 400 for path variable exceeding 8 characters")
        void rejects400ForOverlongPath() throws Exception {
            mockMvc.perform(get("/api/flights/TOOLONGID"))
                    .andExpect(status().isBadRequest());
        }
    }

    // ── GET /api/route/{callsign} ────────────────────────────────────

    @Nested @DisplayName("GET /api/route/{callsign}")
    class GetFlightRouteTests {

        @Test @DisplayName("200 with full resolved route")
        void returns200WithRoute() throws Exception {
            when(flightService.resolveRoute("SIA200")).thenReturn(Optional.of(sia200Route));
            mockMvc.perform(get("/api/route/SIA200"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.callsign").value("SIA200"))
                    .andExpect(jsonPath("$.departureAerodrome").value("WSSS"))
                    .andExpect(jsonPath("$.destinationAerodrome").value("YSSY"))
                    .andExpect(jsonPath("$.routePoints", hasSize(2)))
                    .andExpect(jsonPath("$.polyline", hasSize(2)));
        }

        @Test @DisplayName("404 when route not found")
        void returns404WhenMissing() throws Exception {
            when(flightService.resolveRoute(anyString())).thenReturn(Optional.empty());
            mockMvc.perform(get("/api/route/ZZZZZ"))
                    .andExpect(status().isNotFound());
        }

        @Test @DisplayName("IM8 S6: 400 for XSS payload in route path variable")
        void rejects400ForXssInRoutePath() throws Exception {
            mockMvc.perform(get("/api/route/<img src=x>"))
                    .andExpect(status().isBadRequest());
        }
    }

    // ── GET /api/route/{callsign}/alternate (optional extension) ───────

    @Nested @DisplayName("GET /api/route/{callsign}/alternate")
    class GetAlternateFlightRouteTests {

        @Test @DisplayName("200 with alternate resolved route")
        void returns200WithAlternateRoute() throws Exception {
            when(flightService.resolveAlternateRoute("SIA200")).thenReturn(Optional.of(sia200Route));
            mockMvc.perform(get("/api/route/SIA200/alternate"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.callsign").value("SIA200"))
                    .andExpect(jsonPath("$.polyline", hasSize(2)));
        }

        @Test @DisplayName("404 when alternate route not found")
        void returns404WhenMissing() throws Exception {
            when(flightService.resolveAlternateRoute(anyString())).thenReturn(Optional.empty());
            mockMvc.perform(get("/api/route/ZZZZZ/alternate"))
                    .andExpect(status().isNotFound());
        }

        @Test @DisplayName("IM8 S6: 400 for XSS payload in alternate route path variable")
        void rejects400ForXssInAlternateRoutePath() throws Exception {
            mockMvc.perform(get("/api/route/<img src=x>/alternate"))
                    .andExpect(status().isBadRequest());
        }
    }

    // ── GET /api/geopoints/** (internal — blocked externally, hidden from Swagger) ──

    @Nested @DisplayName("GET /api/geopoints/** (internal endpoints)")

    class GeopointsTests {



        @Test @DisplayName("403 for /api/geopoints/airways — blocked by SecurityConfig")

        void airwaysBlockedExternally() throws Exception {

            // SecurityConfig .requestMatchers("/api/geopoints/**").denyAll() means

            // external callers always receive 403 regardless of service state.

            mockMvc.perform(get("/api/geopoints/airways"))

                    .andExpect(status().isForbidden());

        }



        @Test @DisplayName("403 for /api/geopoints/fixes — blocked by SecurityConfig")

        void fixesBlockedExternally() throws Exception {

            mockMvc.perform(get("/api/geopoints/fixes"))

                    .andExpect(status().isForbidden());

        }



        @Test @DisplayName("403 for any /api/geopoints/** sub-path")

        void anyGeopointsSubpathBlocked() throws Exception {

            mockMvc.perform(get("/api/geopoints/anything"))

                    .andExpect(status().isForbidden());

        }

    }



    // ── GET /api/health ──────────────────────────────────────────────

    @Nested @DisplayName("GET /api/health")
    class HealthTests {

        @Test @DisplayName("200 with status=UP and service name")
        void returns200Up() throws Exception {
            mockMvc.perform(get("/api/health"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UP"))
                    .andExpect(jsonPath("$.service").value("flight-plan-backend"));
        }

        @Test @DisplayName("IM8 S6: health endpoint does NOT expose version field")
        void healthDoesNotExposeVersion() throws Exception {
            mockMvc.perform(get("/api/health"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.version").doesNotExist());
        }
    }

    // ── GET /api/cache/status ────────────────────────────────────────

    @Nested @DisplayName("GET /api/cache/status")
    class CacheStatusTests {

        @Test @DisplayName("200 with all four cache metadata fields")
        void returns200WithAllFields() throws Exception {
            Instant now = Instant.parse("2026-03-13T10:45:00Z");
            when(flightService.getCacheLastRefreshed()).thenReturn(now);
            when(flightService.getAllFlightPlans()).thenReturn(List.of(sia200, mas370));
            when(flightService.getAirways()).thenReturn(List.of(
                    new GeoPoint("A576", 1.5, 104.1, "airway")));
            when(flightService.getFixes()).thenReturn(List.of(
                    new GeoPoint("WSSS", 1.36, 103.99, "fix"),
                    new GeoPoint("YSSY", -33.94, 151.17, "fix")));

            mockMvc.perform(get("/api/cache/status"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.lastRefreshed").value("2026-03-13T10:45:00Z"))
                    .andExpect(jsonPath("$.flightPlanCount").value(2))
                    .andExpect(jsonPath("$.airwaysCount").value(1))
                    .andExpect(jsonPath("$.fixesCount").value(2));
        }

        @Test @DisplayName("200 with 'not yet refreshed' when cache has never been populated")
        void returnsNotYetRefreshedWhenNull() throws Exception {
            when(flightService.getCacheLastRefreshed()).thenReturn(null);
            when(flightService.getAllFlightPlans()).thenReturn(List.of());
            when(flightService.getAirways()).thenReturn(List.of());
            when(flightService.getFixes()).thenReturn(List.of());

            mockMvc.perform(get("/api/cache/status"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.lastRefreshed").value("not yet refreshed"))
                    .andExpect(jsonPath("$.flightPlanCount").value(0))
                    .andExpect(jsonPath("$.airwaysCount").value(0))
                    .andExpect(jsonPath("$.fixesCount").value(0));
        }

        @Test @DisplayName("200 with zero counts when cache is empty but has been refreshed")
        void returnsZeroCountsWhenCacheEmpty() throws Exception {
            when(flightService.getCacheLastRefreshed()).thenReturn(Instant.now());
            when(flightService.getAllFlightPlans()).thenReturn(List.of());
            when(flightService.getAirways()).thenReturn(List.of());
            when(flightService.getFixes()).thenReturn(List.of());

            mockMvc.perform(get("/api/cache/status"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.flightPlanCount").value(0))
                    .andExpect(jsonPath("$.airwaysCount").value(0))
                    .andExpect(jsonPath("$.fixesCount").value(0));
        }

        // Security headers verified without redundant service stubs —
        // GET /api/cache/status stubs only what the endpoint actually calls.
        @Test @DisplayName("response includes security headers")
        void cacheStatusHasSecurityHeaders() throws Exception {
            when(flightService.getCacheLastRefreshed()).thenReturn(null);
            when(flightService.getAllFlightPlans()).thenReturn(List.of());
            when(flightService.getAirways()).thenReturn(List.of());
            when(flightService.getFixes()).thenReturn(List.of());

            mockMvc.perform(get("/api/cache/status"))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                    .andExpect(header().string("X-Frame-Options", "DENY"));
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private FlightPlan flight(String cs, String dep, String dest, String acType, String ft) {
        FlightPlan fp = new FlightPlan();
        fp.setId("id-" + cs);
        fp.setAircraftIdentification(cs);
        fp.setMessageType("FPL");
        fp.setFlightType(ft);
        FlightPlan.Departure d = new FlightPlan.Departure();
        d.setDepartureAerodrome(dep);
        fp.setDeparture(d);
        FlightPlan.Arrival a = new FlightPlan.Arrival();
        a.setDestinationAerodrome(dest);
        fp.setArrival(a);
        FlightPlan.Aircraft ac = new FlightPlan.Aircraft();
        ac.setAircraftType(acType);
        fp.setAircraft(ac);
        return fp;
    }

    private FlightRoute route(String cs, String dep, String dest, String acType) {
        return new FlightRoute(cs, dep, dest, acType, "M",
                List.of(
                        new FlightRoute.RoutePoint(dep, 1.36, 103.99, "airport", 0),
                        new FlightRoute.RoutePoint(dest, -33.94, 151.17, "airport", 999)
                ),
                List.of(new double[]{1.36, 103.99}, new double[]{-33.94, 151.17})
        );
    }
}