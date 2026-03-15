package com.flightplan.service;

import com.flightplan.model.FlightPlan;
import com.flightplan.model.FlightRoute;
import com.flightplan.model.GeoPoint;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Flight Service
 *
 * Serves all business logic for the API layer.  Data is read exclusively from
 * {@link FlightDataCache} – this service NEVER calls the upstream API directly.
 *
 * Leader election and cache population are entirely the responsibility of
 * {@link FlightDataCache} + {@link FlightFetchService}.  This design ensures:
 *
 *  - Each inbound HTTP request is served instantly from in-memory cache (no
 *    blocking network call per request).
 *  - The upstream API is called at most once per refresh interval, regardless
 *    of how many pods are running or how many simultaneous requests arrive.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FlightService {

    private final FlightDataCache flightDataCache;

    // ── Flight Plans ─────────────────────────────────────────────────────

    public List<FlightPlan> getAllFlightPlans() {
        return flightDataCache.getFlightPlans();
    }

    public Optional<FlightPlan> getFlightByCallsign(String callsign) {
        return getAllFlightPlans().stream()
                .filter(fp -> callsign.equalsIgnoreCase(fp.getAircraftIdentification()))
                .findFirst();
    }

    // ── GeoPoints ────────────────────────────────────────────────────────

    public List<GeoPoint> getAirways() {
        return flightDataCache.getAirways();
    }

    public List<GeoPoint> getFixes() {
        return flightDataCache.getFixes();
    }

    public java.time.Instant getCacheLastRefreshed() {
        return flightDataCache.getLastRefreshed();
    }

    // ── Route Resolution ─────────────────────────────────────────────────

    public Optional<FlightRoute> resolveRoute(String callsign) {
        Optional<FlightPlan> planOpt = getFlightByCallsign(callsign);
        if (planOpt.isEmpty()) return Optional.empty();

        FlightPlan plan = planOpt.get();
        Map<String, GeoPoint> fixMap = buildFixMap();

        List<FlightRoute.RoutePoint> routePoints = new ArrayList<>();
        List<double[]> polyline = new ArrayList<>();

        // Departure airport
        String depIcao = plan.getDeparture() != null
                ? plan.getDeparture().getDepartureAerodrome() : null;
        if (depIcao != null) {
            GeoPoint depGeo = fixMap.get(depIcao);
            if (depGeo != null) {
                routePoints.add(new FlightRoute.RoutePoint(depIcao, depGeo.getLat(),
                        depGeo.getLon(), "airport", 0));
                polyline.add(new double[]{depGeo.getLat(), depGeo.getLon()});
            }
        }

        // En-route waypoints
        if (plan.getFiledRoute() != null && plan.getFiledRoute().getRouteElement() != null) {
            List<FlightPlan.RouteElement> elements = plan.getFiledRoute().getRouteElement();
            elements.sort(Comparator.comparingInt(e -> (e.getSeqNum() == null ? 0 : e.getSeqNum())));

            for (FlightPlan.RouteElement el : elements) {
                FlightPlan.Position pos = el.getPosition();
                if (pos == null) continue;

                double lat, lon;
                String pointName = pos.getDesignatedPoint();

                if (pos.getLat() != null && pos.getLon() != null
                        && (pos.getLat() != 0.0 || pos.getLon() != 0.0)) {
                    lat = pos.getLat();
                    lon = pos.getLon();
                } else if (pointName != null && fixMap.containsKey(pointName)) {
                    GeoPoint gp = fixMap.get(pointName);
                    lat = gp.getLat();
                    lon = gp.getLon();
                } else {
                    continue;
                }

                String type = determinePointType(pointName).equals("airport") ? "airport" : "waypoint";
                routePoints.add(new FlightRoute.RoutePoint(pointName, lat, lon, type, el.getSeqNum()));
                polyline.add(new double[]{lat, lon});
            }
        }

        // Destination airport
        String destIcao = plan.getArrival() != null
                ? plan.getArrival().getDestinationAerodrome() : null;
        if (destIcao != null && (routePoints.isEmpty() ||
                !destIcao.equals(routePoints.get(routePoints.size() - 1).getName()))) {
            GeoPoint destGeo = fixMap.get(destIcao);
            if (destGeo != null) {
                routePoints.add(new FlightRoute.RoutePoint(destIcao, destGeo.getLat(),
                        destGeo.getLon(), "airport", 999));
                polyline.add(new double[]{destGeo.getLat(), destGeo.getLon()});
            }
        }

        return Optional.of(new FlightRoute(
                callsign, depIcao, destIcao,
                plan.getAircraft() != null ? plan.getAircraft().getAircraftType() : null,
                plan.getFlightType(), routePoints, polyline
        ));
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private Map<String, GeoPoint> buildFixMap() {
        Map<String, GeoPoint> map = new HashMap<>();
        getFixes().forEach(gp -> map.put(gp.getName(), gp));
        getAirways().forEach(gp -> map.putIfAbsent(gp.getName(), gp));
        return map;
    }

    private String determinePointType(String name) {
        if (name != null && name.length() == 4 && name.matches("[A-Z]{4}")) return "airport";
        return "waypoint";
    }
}

