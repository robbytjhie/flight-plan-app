package com.flightplan.controller;

import com.flightplan.config.InputSanitiser;
import com.flightplan.model.FlightPlan;
import com.flightplan.model.FlightRoute;
import com.flightplan.model.GeoPoint;
import com.flightplan.service.FlightService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Flight Plan REST Controller
 *
 * IM8 S6 – Input Validation:
 *  - All path variables and query params pass through {@link InputSanitiser}
 *    before reaching the service layer.
 *  - Bean Validation annotations (@NotBlank, @Size) provide a first-pass
 *    constraint before sanitiser runs.
 *  - Responses are JSON only – no HTML rendering, eliminating reflected-XSS risk.
 *
 * IM8 S2 – Audit Logging:
 *  - Request-level audit is handled by {@link com.flightplan.config.AuditLoggingFilter}.
 *  - Controller logs at DEBUG only; no PII or sensitive identifiers at INFO+.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Validated
@Slf4j
@Tag(name = "Flight Plans", description = "Flight plan data, route resolution, and geopoints — all served from the 60-second leader-refreshed cache")
public class FlightController {

    private final FlightService flightService;
    private final InputSanitiser inputSanitiser;

    /**
     * GET /api/flights
     * Returns all cached flight plans. Data is served from the leader-populated cache.
     */
    @Operation(summary = "List all cached flight plans",
               description = "Returns all flight plans currently held in the in-memory cache. Cache is refreshed every 60 seconds by the leader pod.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "List of flight plans (may be empty if cache has not yet been populated)",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = FlightPlan.class))))
    })
    @GetMapping("/flights")
    public ResponseEntity<List<FlightPlan>> getAllFlights() {
        log.debug("GET /api/flights");
        return ResponseEntity.ok(flightService.getAllFlightPlans());
    }

    /**
     * GET /api/flights/search?callsign=SIA200
     * Case-insensitive partial-match search.
     *
     * IM8 S6: callsign query param is sanitised before use.
     */
    @Operation(summary = "Search flights by callsign",
               description = "Case-insensitive partial match against aircraftIdentification. Searches the cache — no upstream API call.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Matching flight plans"),
        @ApiResponse(responseCode = "400", description = "Invalid or missing callsign parameter", content = @Content)
    })
    @GetMapping("/flights/search")
    public ResponseEntity<List<FlightPlan>> searchFlights(
            @Parameter(description = "Callsign to search (partial, case-insensitive, max 8 chars)", example = "SIA", required = true)
            @RequestParam
            @NotBlank(message = "callsign must not be blank")
            @Size(max = 8, message = "callsign must not exceed 8 characters")
            String callsign) {

        String safe = inputSanitiser.sanitiseSearchQuery(callsign);
        log.debug("GET /api/flights/search (sanitised callsign length={})", safe.length());

        List<FlightPlan> results = flightService.getAllFlightPlans().stream()
                .filter(fp -> fp.getAircraftIdentification() != null
                        && fp.getAircraftIdentification().toUpperCase().contains(safe))
                .toList();

        return ResponseEntity.ok(results);
    }

    /**
     * GET /api/flights/{callsign}
     * Returns a single flight plan by exact callsign match.
     *
     * IM8 S6: path variable is sanitised; not echoed in error responses.
     */
    @Operation(summary = "Get a single flight by exact callsign",
               description = "Returns the full FlightPlan object for the given callsign. Match is case-insensitive.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Flight plan found",
            content = @Content(schema = @Schema(implementation = FlightPlan.class))),
        @ApiResponse(responseCode = "404", description = "No flight with that callsign in the cache", content = @Content),
        @ApiResponse(responseCode = "400", description = "Invalid callsign format", content = @Content)
    })
    @GetMapping("/flights/{callsign}")
    public ResponseEntity<FlightPlan> getFlightByCallsign(
            @PathVariable
            @NotBlank
            @Size(max = 8)
            String callsign) {

        String safe = inputSanitiser.sanitiseCallsign(callsign);
        log.debug("GET /api/flights/{callsign} (sanitised)");

        return flightService.getFlightByCallsign(safe)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * GET /api/route/{callsign}
     * Returns the resolved geo-coordinate route for map rendering.
     *
     * IM8 S6: path variable sanitised before service call.
     */
    @Operation(summary = "Resolve a flight route for map rendering",
               description = "Returns the fully resolved route with lat/lon coordinates for each waypoint and airport. Ready for direct use by Leaflet.js polyline.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Resolved route with polyline",
            content = @Content(schema = @Schema(implementation = FlightRoute.class))),
        @ApiResponse(responseCode = "404", description = "Callsign not found in cache", content = @Content),
        @ApiResponse(responseCode = "400", description = "Invalid callsign format", content = @Content)
    })
    @GetMapping("/route/{callsign}")
    public ResponseEntity<FlightRoute> getFlightRoute(
            @PathVariable
            @NotBlank
            @Size(max = 8)
            String callsign) {

        String safe = inputSanitiser.sanitiseCallsign(callsign);
        log.debug("GET /api/route/{callsign} (sanitised)");

        return flightService.resolveRoute(safe)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * GET /api/route/{callsign}/alternate
     * Optional extension: returns an alternate route polyline for the same flight.
     */
    @Operation(summary = "Resolve an alternate flight route (optional extension)",
               description = "Returns an alternate route polyline for the same callsign. This is a best-effort alternate suitable for demo visualisation.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Alternate resolved route with polyline",
            content = @Content(schema = @Schema(implementation = FlightRoute.class))),
        @ApiResponse(responseCode = "404", description = "Callsign not found in cache", content = @Content),
        @ApiResponse(responseCode = "400", description = "Invalid callsign format", content = @Content)
    })
    @GetMapping("/route/{callsign}/alternate")
    public ResponseEntity<FlightRoute> getAlternateFlightRoute(
            @PathVariable
            @NotBlank
            @Size(max = 8)
            String callsign) {

        String safe = inputSanitiser.sanitiseCallsign(callsign);
        log.debug("GET /api/route/{callsign}/alternate (sanitised)");

        return flightService.resolveAlternateRoute(safe)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * GET /api/route/{callsign}/alternate/waypoints
     * Returns up to N waypoint-based A->C->B alternates (heuristic, corridor-based).
     */
    @Operation(
            summary = "Resolve waypoint-based city-to-city alternates",
            description = "Returns up to N alternate routes built as A -> C -> B using fixes/waypoints near the A->B corridor.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "List of waypoint alternate routes",
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = FlightRoute.class)))),
            @ApiResponse(responseCode = "400", description = "Invalid callsign or limit", content = @Content)
    })
    @GetMapping("/route/{callsign}/alternate/waypoints")
    public ResponseEntity<List<FlightRoute>> getWaypointAlternateRoutes(
            @PathVariable
            @NotBlank
            @Size(max = 8)
            String callsign,
            @RequestParam(defaultValue = "5")
            @Min(1)
            @Max(5)
            int limit
    ) {
        String safe = inputSanitiser.sanitiseCallsign(callsign);
        log.debug("GET /api/route/{callsign}/alternate/waypoints (sanitised) limit={}", limit);

        return ResponseEntity.ok(flightService.resolveWaypointAlternateRoutes(safe, limit));
    }

    @Operation(summary = "List all airways geopoints", description = "Returns all airway geopoints from the cache.")
    @ApiResponse(responseCode = "200", description = "Airways list")
    @GetMapping("/geopoints/airways")
    public ResponseEntity<List<GeoPoint>> getAirways() {
        return ResponseEntity.ok(flightService.getAirways());
    }

    @Operation(summary = "List all fix/waypoint geopoints", description = "Returns all fix geopoints from the cache.")
    @ApiResponse(responseCode = "200", description = "Fixes list")
    @GetMapping("/geopoints/fixes")
    public ResponseEntity<List<GeoPoint>> getFixes() {
        return ResponseEntity.ok(flightService.getFixes());
    }

    @Operation(summary = "Application health check", description = "Lightweight liveness probe. Does not expose version or environment details.")
    @ApiResponse(responseCode = "200", description = "Service is up")
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "service", "flight-plan-backend"
                // version intentionally omitted – reduces fingerprinting surface
        ));
    }

    /**
     * GET /api/cache/status
     * Returns metadata about the in-memory data cache: last refresh time and item counts.
     * Useful for dashboard operators to confirm the scheduler is running.
     */
    @Operation(summary = "Cache status",
               description = "Returns the last refresh timestamp and current item counts for the in-memory cache. Allows operators and the frontend dashboard to confirm data freshness without triggering an upstream API call.")
    @ApiResponse(responseCode = "200", description = "Cache metadata")
    @GetMapping("/cache/status")
    public ResponseEntity<Map<String, Object>> cacheStatus() {
        java.time.Instant lastRefreshed = flightService.getCacheLastRefreshed();
        return ResponseEntity.ok(Map.of(
                "lastRefreshed",    lastRefreshed != null ? lastRefreshed.toString() : "not yet refreshed",
                "flightPlanCount",  flightService.getAllFlightPlans().size(),
                "airwaysCount",     flightService.getAirways().size(),
                "fixesCount",       flightService.getFixes().size()
        ));
    }
}

