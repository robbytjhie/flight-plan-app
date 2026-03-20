package com.flightplan.integration;

import com.flightplan.service.FlightDataCache;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.integration.support.locks.DefaultLockRegistry;
import org.springframework.integration.support.locks.LockRegistry;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for the prod profile — LiveDataFetchStrategy active,
 * upstream API stubbed with WireMock.
 *
 * ── Why a separate top-level class? ─────────────────────────────────────────
 * Spring Boot does NOT support @SpringBootTest on @Nested inner classes.
 * Extracting to a top-level class is the correct fix.
 *
 * ── Cache architecture ───────────────────────────────────────────────────────
 * FlightController serves data exclusively from FlightDataCache (in-memory).
 * The cache is populated by FlightDataCache.refreshIfLeader() on a @Scheduled
 * tick — NOT on each HTTP request. Calling the endpoint directly without first
 * populating the cache will always return an empty list.
 *
 * Test strategy: register WireMock stubs FIRST, then call refreshIfLeader()
 * directly to populate the cache, then assert on the HTTP endpoint.
 *
 * ── Lock Registry ────────────────────────────────────────────────────────────
 * MockInfraConfig replaces the prod RedisLockRegistry with a DefaultLockRegistry
 * (always grants tryLock()=true) so every refreshIfLeader() call in tests
 * performs the fetch — no Redis needed in CI.
 *
 * ── Dev-profile / mock-data tests ────────────────────────────────────────────
 * Those live in {@link FlightPlanIntegrationIT}.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "flight.api.key=test-api-key",
                "security.cors.allowed-origins=http://localhost:3000",
                "spring.main.allow-bean-definition-overriding=true"
        }
)
@AutoConfigureMockMvc
@ActiveProfiles("prod")
@DisplayName("Prod Profile Integration Tests (live API via WireMock)")
class LiveModeProdIT {

    @Autowired MockMvc mockMvc;
    @Autowired FlightDataCache flightDataCache;

    static WireMockServer wireMock;

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMock != null) wireMock.stop();
    }

    @AfterEach
    void resetWireMock() {
        wireMock.resetAll();
    }

    @DynamicPropertySource
    static void configureWireMock(DynamicPropertyRegistry reg) {
        reg.add("flight.api.base-url", () -> "http://localhost:" + wireMock.port());
    }

    /**
     * Replaces both the RedisConnectionFactory and the prod LockRegistry bean
     * so no real Redis is required in CI.
     *
     * DefaultLockRegistry is an in-memory lock that always grants tryLock()=true,
     * ensuring refreshIfLeader() always executes the fetch in every test.
     */
    @TestConfiguration
    static class MockInfraConfig {

        @Bean
        @Primary
        public RedisConnectionFactory redisConnectionFactory() {
            return mock(RedisConnectionFactory.class);
        }

        /**
         * Overrides LeaderElectionConfig.redisLockRegistry() (prod profile).
         * DefaultLockRegistry always grants the lock so every refreshIfLeader()
         * call in tests performs the actual upstream fetch.
         */
        @Bean
        @Primary
        public LockRegistry flightDataLockRegistry() {
            return new DefaultLockRegistry();
        }
    }

    // ── Helper: stub all three endpoints then trigger a cache refresh ──────

    /**
     * Registers WireMock stubs for all three upstream endpoints, then calls
     * refreshIfLeader() directly to populate the cache before the test asserts
     * on an HTTP endpoint. All three endpoints must be stubbed together because
     * fetchAndCache() always calls all three in one shot.
     */
    private void stubAllAndRefresh(String flightBody, String airwayBody, String fixBody) {
        wireMock.stubFor(WireMock.get(urlEqualTo("/flight-manager/displayAll"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(flightBody)));
        wireMock.stubFor(WireMock.get(urlEqualTo("/geopoints/list/airways"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(airwayBody)));
        wireMock.stubFor(WireMock.get(urlEqualTo("/geopoints/list/fixes"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(fixBody)));
        flightDataCache.refreshIfLeader();
    }

    // ── Tests ─────────────────────────────────────────────────────────────

    @Test @DisplayName("fetches flights from stubbed live API")
    void fetchesFromLiveApi() throws Exception {
        stubAllAndRefresh(
                flightPlanArrayJson("EK500", "OMDB", "EGLL", "A388"),
                "[]",
                "[]"
        );

        mockMvc.perform(get("/api/flights"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].aircraftIdentification").value("EK500"));
    }

    @Test @DisplayName("falls back to empty list when live flights API returns 500")
    void emptyListOnUpstream500() throws Exception {
        wireMock.stubFor(WireMock.get(urlEqualTo("/flight-manager/displayAll"))
                .willReturn(aResponse().withStatus(500)));
        wireMock.stubFor(WireMock.get(urlEqualTo("/geopoints/list/airways"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("[]")));
        wireMock.stubFor(WireMock.get(urlEqualTo("/geopoints/list/fixes"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("[]")));
        flightDataCache.refreshIfLeader();

        mockMvc.perform(get("/api/flights"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", empty()));
    }

    @Test @DisplayName("fetches airways from stubbed live API — verified via cache/status count")
    void fetchesAirwaysFromLiveApi() throws Exception {
        // /api/geopoints/** is blocked externally (internal-only).
        // Verify the live fetch strategy correctly parsed and stored the airways
        // by checking airwaysCount in /api/cache/status.
        stubAllAndRefresh(
                "[]",
                "[\"A576 (1.50,104.10)\",\"M635 (-1.20,106.50)\"]",
                "[]"
        );

        mockMvc.perform(get("/api/cache/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.airwaysCount").value(2));
    }

    @Test @DisplayName("fetches fixes from stubbed live API — verified via cache/status and route resolution")
    void fetchesFixesFromLiveApi() throws Exception {
        // Verify fixes were correctly fetched and parsed by checking fixesCount,
        // then confirm coordinates are resolvable by requesting a route whose
        // departure airport (WSSS) is in the fixes list.
        stubAllAndRefresh(
                flightPlanArrayJson("EK500", "WSSS", "YSSY", "A388"),
                "[]",
                "[\"WSSS (1.3644,103.9915)\",\"YSSY (-33.9461,151.1772)\"]"
        );

        mockMvc.perform(get("/api/cache/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fixesCount").value(2));

        // Route resolution uses the fix map internally — WSSS coords prove fixes loaded correctly.
        mockMvc.perform(get("/api/route/EK500"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.routePoints[0].name").value("WSSS"))
                .andExpect(jsonPath("$.routePoints[0].lat").value(1.3644))
                .andExpect(jsonPath("$.routePoints[0].lon").value(103.9915));
    }

    @Test @DisplayName("airwaysCount is 0 in cache/status when upstream airways API is down (503)")
    void airwaysEmptyWhenApiDown() throws Exception {
        wireMock.stubFor(WireMock.get(urlEqualTo("/flight-manager/displayAll"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("[]")));
        wireMock.stubFor(WireMock.get(urlEqualTo("/geopoints/list/airways"))
                .willReturn(aResponse().withStatus(503)));
        wireMock.stubFor(WireMock.get(urlEqualTo("/geopoints/list/fixes"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("[]")));
        flightDataCache.refreshIfLeader();

        mockMvc.perform(get("/api/cache/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.airwaysCount").value(0));
    }

    @Test @DisplayName("fixesCount is 0 in cache/status when upstream fixes API is down (503)")
    void fixesEmptyWhenApiDown() throws Exception {
        wireMock.stubFor(WireMock.get(urlEqualTo("/flight-manager/displayAll"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("[]")));
        wireMock.stubFor(WireMock.get(urlEqualTo("/geopoints/list/airways"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("[]")));
        wireMock.stubFor(WireMock.get(urlEqualTo("/geopoints/list/fixes"))
                .willReturn(aResponse().withStatus(503)));
        flightDataCache.refreshIfLeader();

        mockMvc.perform(get("/api/cache/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fixesCount").value(0));
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private String flightPlanArrayJson(String cs, String dep, String dest, String acType) {
        return String.format("""
            [{
              "_id": "live-test-001",
              "messageType": "FPL",
              "aircraftIdentification": "%s",
              "flightType": "M",
              "aircraftOperating": "EK",
              "departure": { "departureAerodrome": "%s" },
              "arrival": { "destinationAerodrome": "%s" },
              "aircraft": { "aircraftType": "%s", "wakeTurbulence": "H" },
              "filedRoute": { "routeElement": [{
                "seqNum": 1,
                "position": { "lat": 25.25, "lon": 55.36, "designatedPoint": "OMDB" },
                "airway": "DCT"
              }]},
              "gufi": "live-gufi-001"
            }]
            """, cs, dep, dest, acType);
    }
}