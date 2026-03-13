package com.flightplan.service;

import com.flightplan.model.FlightPlan;
import com.flightplan.model.GeoPoint;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Thin orchestration layer between {@link FlightDataCache} and the
 * active {@link DataFetchStrategy}.
 *
 * This class contains zero mock-vs-live logic.  The active Spring profile
 * determines which strategy implementation is injected at startup:
 *
 *   dev   →  {@link MockDataFetchStrategy}  (mock data, no network)
 *   prod  →  {@link LiveDataFetchStrategy}  (real API at api.swimapisg.info)
 *
 * Adding a new data source in the future means implementing DataFetchStrategy
 * and annotating it with the appropriate @Profile — no changes needed here.
 *
 * IM8 S5 (Data in Transit): TLS enforcement is handled at the WebClient layer
 * inside LiveDataFetchStrategy / AppConfig; this class is transport-agnostic.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FlightFetchService {

    /** Profile-selected strategy: MockDataFetchStrategy (dev) or LiveDataFetchStrategy (prod) */
    private final DataFetchStrategy dataFetchStrategy;

    public List<FlightPlan> fetchFlightPlans() {
        return dataFetchStrategy.fetchFlightPlans();
    }

    public List<GeoPoint> fetchAirways() {
        return dataFetchStrategy.fetchAirways();
    }

    public List<GeoPoint> fetchFixes() {
        return dataFetchStrategy.fetchFixes();
    }
}
