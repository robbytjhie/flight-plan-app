package com.flightplan.service;

import com.flightplan.model.FlightPlan;
import com.flightplan.model.GeoPoint;

import java.util.List;

/**
 * Strategy interface for fetching flight data.
 *
 * Two implementations are provided — Spring selects exactly one at startup
 * based on the active profile:
 *
 *   @Profile("dev")   →  {@link MockDataFetchStrategy}
 *                         Returns pre-built in-memory data.
 *                         No network calls.  No API key needed.
 *
 *   @Profile("prod")  →  {@link LiveDataFetchStrategy}
 *                         Calls the real upstream API at api.swimapisg.info.
 *                         Requires FLIGHT_API_KEY environment variable.
 *
 * {@link FlightFetchService} injects this interface and is therefore completely
 * decoupled from the mock-vs-live decision — it has zero if/else logic.
 */
public interface DataFetchStrategy {

    /**
     * Fetch all current flight plans.
     * @return list of flight plans; never null
     */
    List<FlightPlan> fetchFlightPlans();

    /**
     * Fetch all airway geopoints.
     * @return list of airway geopoints; never null
     */
    List<GeoPoint> fetchAirways();

    /**
     * Fetch all fix / waypoint geopoints.
     * @return list of fix geopoints; never null
     */
    List<GeoPoint> fetchFixes();
}
