package com.flightplan.service;

import com.flightplan.model.FlightPlan;
import com.flightplan.model.GeoPoint;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * DEV profile implementation of {@link DataFetchStrategy}.
 *
 * Returns pre-built in-memory data from {@link MockDataService}.
 * No network calls are made — this implementation works fully offline
 * and requires no API key or Redis.
 *
 * Active when:  spring.profiles.active=dev  (the default)
 * Inactive when: spring.profiles.active=prod
 */
@Service
@Profile("mock")
@Slf4j
@RequiredArgsConstructor
public class MockDataFetchStrategy implements DataFetchStrategy {

    private final MockDataService mockDataService;

    @Override
    public List<FlightPlan> fetchFlightPlans() {
        log.debug("[FETCH][dev] Returning mock flight plans");
        return mockDataService.getMockFlightPlans();
    }

    @Override
    public List<GeoPoint> fetchAirways() {
        log.debug("[FETCH][dev] Returning mock airways");
        return mockDataService.getMockAirways();
    }

    @Override
    public List<GeoPoint> fetchFixes() {
        log.debug("[FETCH][dev] Returning mock fixes");
        return mockDataService.getMockFixes();
    }
}
