package com.flightplan.service;

import com.flightplan.model.FlightPlan;
import com.flightplan.model.FlightRoute;
import com.flightplan.model.GeoPoint;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.Locale;
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

                // Include airway points when available. The upstream geopoints feed provides a single
                // representative coordinate per airway name (not a full segment graph), so we insert
                // it as an intermediate "airway" point to reflect airway usage in the filed route.
                String airwayName = el.getAirway();
                if (airwayName != null && !airwayName.isBlank()) {
                    String aw = airwayName.trim().toUpperCase(Locale.ROOT);
                    if (!"DCT".equals(aw)) {
                        GeoPoint airwayGp = fixMap.get(aw);
                        if (airwayGp != null) {
                            routePoints.add(new FlightRoute.RoutePoint(
                                    aw, airwayGp.getLat(), airwayGp.getLon(), "airway", el.getSeqNum()
                            ));
                            polyline.add(new double[]{airwayGp.getLat(), airwayGp.getLon()});
                        }
                    }
                }
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

    /**
     * Optional extension: returns an alternate route based on the resolved route.
     * Because the upstream data does not provide airway segment graphs, this generates a
     * safe, deterministic "alternate" polyline by slightly offsetting intermediate points.
     */
    public Optional<FlightRoute> resolveAlternateRoute(String callsign) {
        Optional<FlightRoute> primaryOpt = resolveRoute(callsign);
        if (primaryOpt.isEmpty()) return Optional.empty();

        FlightRoute primary = primaryOpt.get();
        List<double[]> primaryLine = primary.getPolyline();
        if (primaryLine == null || primaryLine.size() < 2) return Optional.of(primary);

        double magnitude = 0.6 + (Math.abs(Objects.hashCode(callsign)) % 60) / 100.0; // 0.6..1.19 deg
        double sign = (Math.abs(Objects.hashCode(callsign + ":alt")) % 2 == 0) ? 1.0 : -1.0;

        List<double[]> altLine = new ArrayList<>(primaryLine.size());
        for (int i = 0; i < primaryLine.size(); i++) {
            double[] p = primaryLine.get(i);
            if (p == null || p.length < 2) continue;
            double lat = p[0];
            double lon = p[1];

            // Keep endpoints identical; nudge intermediate points.
            if (i > 0 && i < primaryLine.size() - 1) {
                double f = Math.sin(i * 1.7) * magnitude * sign;
                lat = clampLat(lat + (f * 0.25));
                lon = wrapLon(lon + (f * 0.35));
            }
            altLine.add(new double[]{lat, lon});
        }

        // Route points: keep as-is; only polyline changes for alternate visualisation.
        return Optional.of(new FlightRoute(
                primary.getCallsign(),
                primary.getDepartureAerodrome(),
                primary.getDestinationAerodrome(),
                primary.getAircraftType(),
                primary.getFlightType(),
                primary.getRoutePoints(),
                altLine
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

    private double clampLat(double lat) {
        return Math.max(-85.0, Math.min(85.0, lat));
    }

    private double wrapLon(double lon) {
        double x = lon;
        while (x > 180.0) x -= 360.0;
        while (x < -180.0) x += 360.0;
        return x;
    }
}

