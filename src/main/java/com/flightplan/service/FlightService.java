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

        // Deterministic per-callsign variation so ALT route is stable across refreshes.
        double baseOffsetDeg = 1.8 + (Math.abs(Objects.hashCode(callsign)) % 70) / 20.0; // ~1.8..5.25 deg
        double sign = (Math.abs(Objects.hashCode(callsign + ":alt")) % 2 == 0) ? 1.0 : -1.0;

        List<double[]> altLine = new ArrayList<>(primaryLine.size());
        for (int i = 0; i < primaryLine.size(); i++) {
            double[] p = primaryLine.get(i);
            if (p == null || p.length < 2) continue;

            double lat = p[0];
            double lon = p[1];

            // Keep endpoints identical; offset intermediate points perpendicular to the local path.
            if (i > 0 && i < primaryLine.size() - 1) {
                double[] prev = primaryLine.get(i - 1);
                double[] next = primaryLine.get(i + 1);
                if (prev != null && next != null && prev.length >= 2 && next.length >= 2) {
                    double dLat = next[0] - prev[0];
                    double dLon = normaliseLonDelta(next[1] - prev[1]);

                    // Perpendicular vector in lat/lon space: (-dLon, dLat)
                    double pLat = -dLon;
                    double pLon = dLat;
                    double norm = Math.sqrt(pLat * pLat + pLon * pLon);

                    // Scale offset by segment "size" so short segments aren't wildly displaced.
                    double seg = Math.sqrt(dLat * dLat + dLon * dLon);
                    double scale = Math.max(0.25, Math.min(1.0, seg / 15.0)); // 0.25..1.0
                    double wave = 0.55 + 0.45 * Math.sin(i * 1.3);            // 0.10..1.0-ish
                    double offset = baseOffsetDeg * scale * wave * sign;

                    if (norm > 1e-9) {
                        lat = clampLat(lat + (pLat / norm) * offset);
                        lon = wrapLon(lon + (pLon / norm) * offset);
                    }
                }
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

    /**
     * City-to-city waypoint alternates:
     * - Take the primary route A->B endpoints
     * - Find up to {@code limit} fixes/waypoints closest to the A->B corridor
     * - Return routes of the form A -> C -> B
     *
     * This is a best-effort heuristic (no graph routing) intended for demo/interview use.
     */
    public List<FlightRoute> resolveWaypointAlternateRoutes(String callsign, int limit) {
        Optional<FlightRoute> primaryOpt = resolveRoute(callsign);
        if (primaryOpt.isEmpty()) return Collections.emptyList();

        FlightRoute primary = primaryOpt.get();
        List<double[]> primaryLine = primary.getPolyline();
        if (primaryLine == null || primaryLine.size() < 2) return Collections.emptyList();

        double[] a = primaryLine.get(0);
        double[] b = primaryLine.get(primaryLine.size() - 1);
        if (a == null || b == null || a.length < 2 || b.length < 2) return Collections.emptyList();

        // Exclude points already used by the primary route to keep alternates distinct.
        Set<String> primaryPointNames = primary.getRoutePoints() == null
                ? Set.of()
                : primary.getRoutePoints().stream()
                    .map(FlightRoute.RoutePoint::getName)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());

        // Heuristic threshold expansion until we have enough options.
        double thresholdKm = 250.0;
        double maxThresholdKm = 2000.0;
        List<Candidate> candidates = Collections.emptyList();
        while (thresholdKm <= maxThresholdKm) {
            candidates = findWaypointCandidates(primary, a, b, primaryPointNames, thresholdKm, limit);
            if (candidates.size() >= limit) break;
            thresholdKm *= 1.5;
        }

        if (candidates.isEmpty()) return Collections.emptyList();

        return candidates.stream()
                .sorted(Comparator.comparingDouble((Candidate c) -> c.distanceKm)
                        .thenComparingDouble(c -> c.tAlong))
                .limit(limit)
                .map(c -> buildWaypointAlternate(primary, c.fix))
                .collect(Collectors.toList());
    }

    private static class Candidate {
        private final GeoPoint fix;
        private final double distanceKm;
        private final double tAlong; // 0..1 projection along A->B

        private Candidate(GeoPoint fix, double distanceKm, double tAlong) {
            this.fix = fix;
            this.distanceKm = distanceKm;
            this.tAlong = tAlong;
        }
    }

    private List<Candidate> findWaypointCandidates(
            FlightRoute primary,
            double[] a,
            double[] b,
            Set<String> primaryPointNames,
            double thresholdKm,
            int limit
    ) {
        List<GeoPoint> fixes = getFixes();
        if (fixes == null || fixes.isEmpty()) return Collections.emptyList();

        List<Candidate> out = new ArrayList<>();
        for (GeoPoint fix : fixes) {
            if (fix == null || fix.getName() == null) continue;
            if (primaryPointNames.contains(fix.getName())) continue; // avoid primary duplicate

            double lat = fix.getLat();
            double lon = fix.getLon();
            if (Double.isNaN(lat) || Double.isNaN(lon)) continue;
            if (Math.abs(lat) > 85.0 || Math.abs(lon) > 180.0) continue;

            PointToSegmentKm d = distancePointToSegmentKm(lat, lon, a[0], a[1], b[0], b[1]);
            if (d.distanceKm <= thresholdKm) {
                out.add(new Candidate(fix, d.distanceKm, d.tAlong));
            }
            // Small early exit: if we already have plenty, don't scan full cache endlessly.
            if (out.size() > limit * 6) break;
        }

        out.sort(Comparator
                .comparingDouble((Candidate c) -> c.distanceKm)
                .thenComparingDouble(c -> c.tAlong));

        return out.stream().limit(limit).collect(Collectors.toList());
    }

    private static class PointToSegmentKm {
        private final double distanceKm;
        private final double tAlong;

        private PointToSegmentKm(double distanceKm, double tAlong) {
            this.distanceKm = distanceKm;
            this.tAlong = tAlong;
        }
    }

    /**
     * Compute planar approximation distance from point P to segment AB.
     * Returns both distanceKm and tAlong in [0,1].
     */
    private PointToSegmentKm distancePointToSegmentKm(
            double pLat,
            double pLon,
            double aLat,
            double aLon,
            double bLat,
            double bLon
    ) {
        // Convert degrees -> km using local scaling at the segment's mid latitude.
        double midLatRad = Math.toRadians((aLat + bLat) / 2.0);
        double kmPerDegLat = 111.32;
        double kmPerDegLon = 111.32 * Math.cos(midLatRad);

        // 2D vectors in km
        double ax = aLon * kmPerDegLon;
        double ay = aLat * kmPerDegLat;
        double bx = bLon * kmPerDegLon;
        double by = bLat * kmPerDegLat;
        double px = pLon * kmPerDegLon;
        double py = pLat * kmPerDegLat;

        double abx = bx - ax;
        double aby = by - ay;
        double apx = px - ax;
        double apy = py - ay;

        double ab2 = abx * abx + aby * aby;
        double t = ab2 < 1e-12 ? 0.0 : (apx * abx + apy * aby) / ab2;
        t = Math.max(0.0, Math.min(1.0, t));

        double cx = ax + t * abx;
        double cy = ay + t * aby;

        double dx = px - cx;
        double dy = py - cy;
        double dist = Math.sqrt(dx * dx + dy * dy);
        return new PointToSegmentKm(dist, t);
    }

    private FlightRoute buildWaypointAlternate(FlightRoute primary, GeoPoint waypointFix) {
        double[] a = primary.getPolyline().get(0);
        double[] b = primary.getPolyline().get(primary.getPolyline().size() - 1);

        String dep = primary.getDepartureAerodrome();
        String dest = primary.getDestinationAerodrome();
        String wpName = waypointFix.getName();

        String wpType = determinePointType(wpName);

        List<FlightRoute.RoutePoint> routePoints = List.of(
                new FlightRoute.RoutePoint(dep, a[0], a[1], "airport", 0),
                new FlightRoute.RoutePoint(wpName, waypointFix.getLat(), waypointFix.getLon(), wpType, 1),
                new FlightRoute.RoutePoint(dest, b[0], b[1], "airport", 999)
        );

        List<double[]> polyline = List.of(
                new double[]{a[0], a[1]},
                new double[]{waypointFix.getLat(), waypointFix.getLon()},
                new double[]{b[0], b[1]}
        );

        return new FlightRoute(
                primary.getCallsign(),
                primary.getDepartureAerodrome(),
                primary.getDestinationAerodrome(),
                primary.getAircraftType(),
                primary.getFlightType(),
                routePoints,
                polyline
        );
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

    private double normaliseLonDelta(double dLon) {
        double x = dLon;
        while (x > 180.0) x -= 360.0;
        while (x < -180.0) x += 360.0;
        return x;
    }
}

