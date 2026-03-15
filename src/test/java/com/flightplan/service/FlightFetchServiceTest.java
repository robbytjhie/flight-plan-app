package com.flightplan.service;

import com.flightplan.model.FlightPlan;
import com.flightplan.model.GeoPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for FlightFetchService.
 *
 * FlightFetchService is now a pure delegation layer — it holds zero mock/live
 * logic.  These tests verify that it correctly forwards all three fetch calls
 * to whatever DataFetchStrategy is injected, and that it returns the strategy's
 * result unmodified.
 *
 * Profile selection (dev → Mock, prod → Live) is tested at the integration
 * level in FlightPlanIntegrationIT.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FlightFetchService")
class FlightFetchServiceTest {

    @Mock
    private DataFetchStrategy strategy;

    private FlightFetchService service;

    @BeforeEach
    void setUp() {
        service = new FlightFetchService(strategy);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private FlightPlan mockPlan() {
        FlightPlan fp = new FlightPlan();
        fp.setAircraftIdentification("SIA200");
        return fp;
    }

    private GeoPoint mockPoint(String name, String type) {
        return new GeoPoint(name, 1.0, 104.0, type);
    }

    // ── tests ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("fetchFlightPlans")
    class FetchFlightPlans {

        @Test
        @DisplayName("delegates to strategy and returns result")
        void delegatesToStrategy() {
            List<FlightPlan> expected = List.of(mockPlan());
            when(strategy.fetchFlightPlans()).thenReturn(expected);

            List<FlightPlan> result = service.fetchFlightPlans();

            assertThat(result).isSameAs(expected);
            verify(strategy, times(1)).fetchFlightPlans();
        }

        @Test
        @DisplayName("returns empty list when strategy returns empty")
        void returnsEmptyList() {
            when(strategy.fetchFlightPlans()).thenReturn(List.of());

            assertThat(service.fetchFlightPlans()).isEmpty();
        }
    }

    @Nested
    @DisplayName("fetchAirways")
    class FetchAirways {

        @Test
        @DisplayName("delegates to strategy and returns result")
        void delegatesToStrategy() {
            List<GeoPoint> expected = List.of(mockPoint("A576", "airway"));
            when(strategy.fetchAirways()).thenReturn(expected);

            List<GeoPoint> result = service.fetchAirways();

            assertThat(result).isSameAs(expected);
            verify(strategy, times(1)).fetchAirways();
        }
    }

    @Nested
    @DisplayName("fetchFixes")
    class FetchFixes {

        @Test
        @DisplayName("delegates to strategy and returns result")
        void delegatesToStrategy() {
            List<GeoPoint> expected = List.of(mockPoint("WSSS", "fix"));
            when(strategy.fetchFixes()).thenReturn(expected);

            List<GeoPoint> result = service.fetchFixes();

            assertThat(result).isSameAs(expected);
            verify(strategy, times(1)).fetchFixes();
        }
    }

    @Nested
    @DisplayName("strategy isolation")
    class StrategyIsolation {

        @Test
        @DisplayName("fetchFlightPlans does not call fetchAirways or fetchFixes")
        void noSideCalls_forFlightPlans() {
            when(strategy.fetchFlightPlans()).thenReturn(List.of());

            service.fetchFlightPlans();

            verify(strategy, never()).fetchAirways();
            verify(strategy, never()).fetchFixes();
        }

        @Test
        @DisplayName("fetchAirways does not call fetchFlightPlans or fetchFixes")
        void noSideCalls_forAirways() {
            when(strategy.fetchAirways()).thenReturn(List.of());

            service.fetchAirways();

            verify(strategy, never()).fetchFlightPlans();
            verify(strategy, never()).fetchFixes();
        }

        @Test
        @DisplayName("fetchFixes does not call fetchFlightPlans or fetchAirways")
        void noSideCalls_forFixes() {
            when(strategy.fetchFixes()).thenReturn(List.of());

            service.fetchFixes();

            verify(strategy, never()).fetchFlightPlans();
            verify(strategy, never()).fetchAirways();
        }
    }
}
