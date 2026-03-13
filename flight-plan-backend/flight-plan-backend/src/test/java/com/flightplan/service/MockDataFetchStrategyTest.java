package com.flightplan.service;

import com.flightplan.model.FlightPlan;
import com.flightplan.model.GeoPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for MockDataFetchStrategy (active on dev profile).
 *
 * Verifies that the strategy correctly delegates to MockDataService
 * and returns its results without modification.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MockDataFetchStrategy")
class MockDataFetchStrategyTest {

    @Mock
    private MockDataService mockDataService;

    private MockDataFetchStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new MockDataFetchStrategy(mockDataService);
    }

    @Test
    @DisplayName("fetchFlightPlans delegates to MockDataService")
    void fetchFlightPlans_delegatesToMockDataService() {
        FlightPlan fp = new FlightPlan();
        fp.setAircraftIdentification("SIA200");
        when(mockDataService.getMockFlightPlans()).thenReturn(List.of(fp));

        List<FlightPlan> result = strategy.fetchFlightPlans();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getAircraftIdentification()).isEqualTo("SIA200");
        verify(mockDataService).getMockFlightPlans();
    }

    @Test
    @DisplayName("fetchAirways delegates to MockDataService")
    void fetchAirways_delegatesToMockDataService() {
        GeoPoint point = new GeoPoint("A576", 1.5, 104.1, "airway");
        when(mockDataService.getMockAirways()).thenReturn(List.of(point));

        List<GeoPoint> result = strategy.fetchAirways();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("A576");
        verify(mockDataService).getMockAirways();
    }

    @Test
    @DisplayName("fetchFixes delegates to MockDataService")
    void fetchFixes_delegatesToMockDataService() {
        GeoPoint point = new GeoPoint("WSSS", 1.3644, 103.9915, "fix");
        when(mockDataService.getMockFixes()).thenReturn(List.of(point));

        List<GeoPoint> result = strategy.fetchFixes();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("WSSS");
        verify(mockDataService).getMockFixes();
    }

    @Test
    @DisplayName("returns empty list when MockDataService returns empty")
    void returnsEmptyList_whenMockDataServiceReturnsEmpty() {
        when(mockDataService.getMockFlightPlans()).thenReturn(List.of());
        when(mockDataService.getMockAirways()).thenReturn(List.of());
        when(mockDataService.getMockFixes()).thenReturn(List.of());

        assertThat(strategy.fetchFlightPlans()).isEmpty();
        assertThat(strategy.fetchAirways()).isEmpty();
        assertThat(strategy.fetchFixes()).isEmpty();
    }
}
