package com.flightplan.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flightplan.model.FlightPlan;
import com.flightplan.model.GeoPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers {@link LiveDataFetchStrategy} reactive WebClient paths (success, empty, parse errors, upstream errors).
 * This class is not exercised by the default Spring test slice because {@code dev} tests use mock fetch stubs.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("LiveDataFetchStrategy")
class LiveDataFetchStrategyTest {

    /** Deep stubs avoid referencing package-private WebClient nested types in signatures. */
    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private WebClient webClient;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private LiveDataFetchStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new LiveDataFetchStrategy(webClient);
        ReflectionTestUtils.setField(strategy, "objectMapper", objectMapper);
    }

    @Nested
    @DisplayName("fetchFlightPlans()")
    class FetchFlightPlansTests {

        @Test
        @DisplayName("returns plans from upstream flux")
        void success() {
            FlightPlan fp = new FlightPlan();
            fp.setId("1");
            when(webClient.get().uri("/flight-manager/displayAll").retrieve().bodyToFlux(FlightPlan.class))
                    .thenReturn(Flux.just(fp));

            List<FlightPlan> out = strategy.fetchFlightPlans();

            assertThat(out).hasSize(1);
            assertThat(out.get(0).getId()).isEqualTo("1");
            verify(webClient, atLeastOnce()).get();
        }

        @Test
        @DisplayName("on upstream error returns empty list")
        void errorReturnsEmpty() {
            when(webClient.get().uri("/flight-manager/displayAll").retrieve().bodyToFlux(FlightPlan.class))
                    .thenReturn(Flux.error(new RuntimeException("upstream down")));

            List<FlightPlan> out = strategy.fetchFlightPlans();

            assertThat(out).isEmpty();
        }
    }

    @Nested
    @DisplayName("fetchAirways() / fetchFixes()")
    class FetchGeoPointsTests {

        @Test
        @DisplayName("parses JSON string array into GeoPoints")
        void parsesAirways() throws Exception {
            String json = "[\"A576 (1.5,104.1)\", \"W218\"]";
            when(webClient.get().uri("/geopoints/list/airways").retrieve().bodyToMono(String.class))
                    .thenReturn(Mono.just(json));

            List<GeoPoint> out = strategy.fetchAirways();

            assertThat(out).hasSize(2);
            assertThat(out.get(0).getName()).isEqualTo("A576");
            assertThat(out.get(1).getLat()).isZero();
            verify(webClient, atLeastOnce()).get();
        }

        @Test
        @DisplayName("filters null GeoPoints when upstream strings do not parse")
        void filtersUnparseableStrings() {
            String json = "[\"X (not,numbers)\", \"FIX1 (1.0,2.0)\"]";
            when(webClient.get().uri("/geopoints/list/fixes").retrieve().bodyToMono(String.class))
                    .thenReturn(Mono.just(json));

            List<GeoPoint> out = strategy.fetchFixes();

            assertThat(out).hasSize(1);
            assertThat(out.get(0).getName()).isEqualTo("FIX1");
        }

        @Test
        @DisplayName("empty JSON array yields empty list")
        void emptyArray() {
            when(webClient.get().uri("/geopoints/list/fixes").retrieve().bodyToMono(String.class))
                    .thenReturn(Mono.just("[]"));

            assertThat(strategy.fetchFixes()).isEmpty();
        }

        @Test
        @DisplayName("Mono empty (null body) yields empty list")
        void nullBody() {
            when(webClient.get().uri("/geopoints/list/airways").retrieve().bodyToMono(String.class))
                    .thenReturn(Mono.empty());

            assertThat(strategy.fetchAirways()).isEmpty();
        }

        @Test
        @DisplayName("JSON parse failure in mapper yields empty array path then empty list")
        void mapperThrows() throws Exception {
            when(webClient.get().uri("/geopoints/list/airways").retrieve().bodyToMono(String.class))
                    .thenReturn(Mono.just("[\"bad\"]"));

            ObjectMapper spyMapper = spy(objectMapper);
            JsonProcessingException parseErr = mock(JsonProcessingException.class);
            doThrow(parseErr).when(spyMapper).readValue(anyString(), eq(String[].class));
            ReflectionTestUtils.setField(strategy, "objectMapper", spyMapper);

            assertThat(strategy.fetchAirways()).isEmpty();
        }

        @Test
        @DisplayName("upstream error before parse returns empty list")
        void networkError() {
            when(webClient.get().uri("/geopoints/list/fixes").retrieve().bodyToMono(String.class))
                    .thenReturn(Mono.error(new RuntimeException("timeout")));

            assertThat(strategy.fetchFixes()).isEmpty();
        }
    }
}
