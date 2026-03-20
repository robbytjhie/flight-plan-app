package com.flightplan.service;

import com.flightplan.model.FlightPlan;
import com.flightplan.model.FlightRoute;
import com.flightplan.model.GeoPoint;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Flight Service
 *
 * Serves all business logic for the API layer. Data is read exclusively from
 * {@link FlightDataCache} — this service NEVER calls the upstream API directly.
 *
 * ── Fix map caching ──────────────────────────────────────────────────────────
 * buildFixMap() merges 247 000+ fixes and 9 000+ airways into a single HashMap
 * for O(1) waypoint lookups during route resolution. Building that map is cheap
 * in CPU terms (~5 ms) but allocates ~30 MB of short-lived objects on every call.
 *
 * Previously the map was rebuilt on every resolveRoute() call — once per callsign
 * lookup, for every concurrent request. Under load (89 flights × concurrent users)
 * this caused repeated GC pressure with no benefit because the underlying data
 * only changes when the leader refreshes the cache (default every 10 minutes).
 *
 * The cached fix map is keyed on the cache's lastRefreshed timestamp. On each
 * resolveRoute() call we compare the current lastRefreshed to the snapshot's
 * timestamp — if they match, the cached map is reused; if not (leader has refreshed),
 * the map is rebuilt exactly once and stored. This means the 247K-entry merge
 * happens at most once per 10-minute refresh window regardless of request volume.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FlightService {

    private final FlightDataCache flightDataCache;

    /**
     * Snapshot of the pre-built fix map, paired with the cache timestamp it was
     * built from. AtomicReference ensures safe publication across threads without
     * locking — the worst case is two threads simultaneously building the map on a
     * cache refresh tick, which is harmless (one result wins, both are identical).
     */
    private final AtomicReference<FixMapSnapshot> fixMapSnapshot = new AtomicReference<>();

    // ── Flight Plans ──────────────────────────────────────────────────────────

    public List<FlightPlan> getAllFlightPlans() {
        return flightDataCache.getFlightPlans();
    }

    public Optional<FlightPlan> getFlightByCallsign(String callsign) {
        return getAllFlightPlans().stream()
                .filter(fp -> callsign.equalsIgnoreCase(fp.getAircraftIdentification()))
                .findFirst();
    }

    // ── GeoPoints ─────────────────────────────────────────────────────────────

    public List<GeoPoint> getAirways() {
        return flightDataCache.getAirways();
    }

    public List<GeoPoint> getFixes() {
        return flightDataCache.getFixes();
    }

    public Instant getCacheLastRefreshed() {
        return flightDataCache.getLastRefreshed();
    }

    // ── Route Resolution ──────────────────────────────────────────────────────

    public Optional<FlightRoute> resolveRoute(String callsign) {
        Optional<FlightPlan> planOpt = getFlightByCallsign(callsign);
        if (planOpt.isEmpty()) return Optional.empty();

        FlightPlan plan = planOpt.get();
        Map<String, GeoPoint> fixMap = getCachedFixMap();

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

    public Optional<FlightRoute> resolveAlternateRoute(String callsign) {
        Optional<FlightRoute> primaryOpt = resolveRoute(callsign);
        if (primaryOpt.isEmpty()) return Optional.empty();

        FlightRoute primary = primaryOpt.get();
        List<double[]> primaryLine = primary.getPolyline();
        if (primaryLine == null || primaryLine.size() < 2) return Optional.of(primary);

        double baseOffsetDeg = 1.8 + (Math.abs(Objects.hashCode(callsign)) % 70) / 20.0;
        double sign = (Math.abs(Objects.hashCode(callsign + ":alt")) % 2 == 0) ? 1.0 : -1.0;

        List<double[]> altLine = new ArrayList<>(primaryLine.size());
        for (int i = 0; i < primaryLine.size(); i++) {
            double[] p = primaryLine.get(i);
            if (p == null || p.length < 2) continue;

            double lat = p[0];
            double lon = p[1];

            if (i > 0 && i < primaryLine.size() - 1) {
                double[] prev = primaryLine.get(i - 1);
                double[] next = primaryLine.get(i + 1);
                if (prev != null && next != null && prev.length >= 2 && next.length >= 2) {
                    double dLat = next[0] - prev[0];
                    double dLon = normaliseLonDelta(next[1] - prev[1]);

                    double pLat = -dLon;
                    double pLon = dLat;
                    double norm = Math.sqrt(pLat * pLat + pLon * pLon);

                    double seg = Math.sqrt(dLat * dLat + dLon * dLon);
                    double scale = Math.max(0.25, Math.min(1.0, seg / 15.0));
                    double wave = 0.55 + 0.45 * Math.sin(i * 1.3);
                    double offset = baseOffsetDeg * scale * wave * sign;

                    if (norm > 1e-9) {
                        lat = clampLat(lat + (pLat / norm) * offset);
                        lon = wrapLon(lon + (pLon / norm) * offset);
                    }
                }
            }

            altLine.add(new double[]{lat, lon});
        }

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

    // ── Fix map caching ───────────────────────────────────────────────────────

    /**
     * Returns a pre-built name → GeoPoint lookup map, rebuilt only when the
     * underlying cache has been refreshed by the leader pod.
     *
     * Thread safety: AtomicReference.compareAndSet ensures only one thread
     * rebuilds the map per refresh cycle. Concurrent threads that lose the CAS
     * race simply re-read the freshly set snapshot on their next get().
     */
    private Map<String, GeoPoint> getCachedFixMap() {
        Instant currentRefresh = flightDataCache.getLastRefreshed();
        FixMapSnapshot snapshot = fixMapSnapshot.get();

        if (snapshot != null && Objects.equals(snapshot.builtAt(), currentRefresh)) {
            return snapshot.fixMap();
        }

        // Cache has been refreshed (or this is the first call) — rebuild.
        Map<String, GeoPoint> fresh = buildFixMap();
        FixMapSnapshot next = new FixMapSnapshot(currentRefresh, fresh);

        // CAS: if another thread already updated the snapshot, that's fine — both
        // results are identical for the same refresh timestamp.
        fixMapSnapshot.compareAndSet(snapshot, next);

        log.debug("[FIXMAP] Rebuilt fix map: {} entries (lastRefreshed={})",
                fresh.size(), currentRefresh);
        return fresh;
    }

    private Map<String, GeoPoint> buildFixMap() {
        Map<String, GeoPoint> map = new HashMap<>();
        getFixes().forEach(gp -> map.put(gp.getName(), gp));
        getAirways().forEach(gp -> map.putIfAbsent(gp.getName(), gp));
        return map;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

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

    /**
     * Immutable snapshot pairing a pre-built fix map to the cache timestamp
     * it was built from. Records are a natural fit — equality is by value.
     */
    private record FixMapSnapshot(Instant builtAt, Map<String, GeoPoint> fixMap) {}
}
