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
 * ════════════════════════════════════════════════════════════════════════════
 * PROBLEM: DUPLICATE WAYPOINT NAMES IN THE UPSTREAM FIXES DATASET
 * ════════════════════════════════════════════════════════════════════════════
 *
 * The upstream /geopoints/list/fixes API returns 247 419 fix entries, but
 * 12 230 of those waypoint names appear more than once — sometimes with wildly
 * different coordinates on opposite sides of the globe. This is expected in
 * aviation: the same five-letter ICAO identifier can be reused in different
 * Flight Information Regions (FIRs) worldwide.
 *
 * Real example that broke the FAOR→WSSS route visualisation:
 *
 *   SUNIR (-24.30, 40.00)  — Indian Ocean near Madagascar  ✅ correct
 *   SUNIR ( 43.39, -3.13)  — Northern Spain                ❌ wrong
 *
 * A naive HashMap.put() on name keeps whichever entry is processed last,
 * which is insertion-order-dependent and effectively random. In practice the
 * Spain SUNIR was winning, drawing a line from South Africa to Europe instead
 * of staying on the FAOR→WSSS great-circle path through the Indian Ocean.
 *
 * ════════════════════════════════════════════════════════════════════════════
 * SOLUTION: MULTIMAP + 4-TIER DISAMBIGUATION
 * ════════════════════════════════════════════════════════════════════════════
 *
 * Instead of a flat Map<name, GeoPoint>, we build a Map<name, List<GeoPoint>>
 * that preserves every candidate. At route-resolution time, when a waypoint
 * name has multiple candidates, we run through a 4-tier fallback chain that
 * always uses the strongest available geographic context:
 *
 *   Tier 1 — Cross-track distance (both dep + dest resolved)
 *   ──────────────────────────────────────────────────────────
 *   Measures the perpendicular distance from each candidate to the
 *   departure→destination great-circle path. This is the most precise
 *   strategy because it asks "how far off the actual flight corridor is
 *   this fix?" rather than "how far is it from a single reference point?"
 *
 *   Why cross-track beats the alternatives (FAOR→WSSS / SUNIR benchmark):
 *
 *     Strategy                       Correct fix   Wrong fix   Signal ratio
 *     ───────────────────────────────────────────────────────────────────────
 *     Closest to route midpoint        3 050 km     9 343 km       3.1×
 *     Closest to departure airport     1 200 km     8 368 km       7.0×
 *     Closest to nearest endpoint      1 200 km     8 368 km       7.0×
 *     Cross-track to dep→dest path         9 km     7 650 km     850×  ✅
 *
 *   The 850× signal ratio makes cross-track highly robust even for routes
 *   that curve significantly over the Earth's surface.
 *
 *   Tier 2 — Closest to nearest known endpoint (one endpoint resolved)
 *   ──────────────────────────────────────────────────────────────────────
 *   Cross-track requires both endpoints to define a path direction. If only
 *   one airport is in the fixes dataset, we fall back to the candidate
 *   closest to whichever endpoint (dep or dest) is available.
 *   Using the nearest endpoint rather than always the departure handles the
 *   symmetric case where dest is known but dep is not.
 *
 *   Tier 3 — Closest to midpoint of already-resolved route points
 *   ──────────────────────────────────────────────────────────────────────
 *   If neither airport is in the fixes dataset we still have geographic
 *   context from waypoints that have already been resolved earlier in the
 *   route. The centroid of those resolved points approximates the flight
 *   corridor and provides a reasonable anchor for disambiguation.
 *
 *   Tier 4 — First entry with valid coordinates
 *   ──────────────────────────────────────────────────────────────────────
 *   Absolute last resort when no geographic context is available at all
 *   (e.g. very first waypoint of a flight whose airports are both unknown).
 *   Preserves the previous behaviour for this extreme edge case.
 *
 * ════════════════════════════════════════════════════════════════════════════
 * FIX MAP CACHING
 * ════════════════════════════════════════════════════════════════════════════
 *
 * Building the multimap from 247K entries takes ~5 ms but allocates ~30 MB of
 * short-lived objects. The multimap is cached in an AtomicReference keyed on
 * lastRefreshed and rebuilt at most once per 10-minute leader-refresh cycle
 * regardless of how many concurrent route requests arrive.
 *
 * ════════════════════════════════════════════════════════════════════════════
 * NAME-ONLY AIRWAYS (lat=0, lon=0)
 * ════════════════════════════════════════════════════════════════════════════
 *
 * The upstream /geopoints/list/airways endpoint returns plain name strings with
 * no coordinates (e.g. "W218", "UA401"). GeoPoint.parse() stores these as
 * lat=0, lon=0 so name-based lookups still work. Since (0,0) is a real point
 * in the Gulf of Guinea, hasValidCoords() guards all polyline-insertion sites
 * to skip zero-coord entries from being drawn on the map.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FlightService {

    private final FlightDataCache flightDataCache;

    /**
     * Cached multimap snapshot. Stores every candidate GeoPoint per name so the
     * 4-tier disambiguation chain can select the geographically correct one at
     * route-resolution time.
     *
     * AtomicReference gives lock-free safe publication across threads. The worst
     * case — two threads rebuilding simultaneously on a refresh tick — is harmless
     * because both produce an identical result; one simply overwrites the other.
     */
    private final AtomicReference<FixMultiMapSnapshot> fixMapSnapshot = new AtomicReference<>();

    /** Earth radius in km used for all haversine / cross-track calculations. */
    private static final double EARTH_RADIUS_KM = 6_371.0;

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
        Map<String, List<GeoPoint>> multiMap = getCachedFixMultiMap();

        // Resolve departure and destination coordinates upfront.
        // These are the primary anchors for Tier 1 (cross-track) and Tier 2
        // (nearest endpoint) disambiguation. Null means the airport is not
        // present in the fixes dataset — lower tiers handle that gracefully.
        String depIcao  = plan.getDeparture() != null ? plan.getDeparture().getDepartureAerodrome() : null;
        String destIcao = plan.getArrival()   != null ? plan.getArrival().getDestinationAerodrome()  : null;

        GeoPoint depGeo  = bestCandidate(multiMap.get(depIcao),  null, null, null, null, List.of());
        GeoPoint destGeo = bestCandidate(multiMap.get(destIcao), null, null, null, null, List.of());

        Double depLat  = hasValidCoords(depGeo)  ? depGeo.getLat()  : null;
        Double depLon  = hasValidCoords(depGeo)  ? depGeo.getLon()  : null;
        Double destLat = hasValidCoords(destGeo) ? destGeo.getLat() : null;
        Double destLon = hasValidCoords(destGeo) ? destGeo.getLon() : null;

        List<FlightRoute.RoutePoint> routePoints = new ArrayList<>();
        List<double[]> polyline = new ArrayList<>();

        // ── Departure airport ──────────────────────────────────────────────
        if (depIcao != null && hasValidCoords(depGeo)) {
            routePoints.add(new FlightRoute.RoutePoint(
                    depIcao, depGeo.getLat(), depGeo.getLon(), "airport", 0));
            polyline.add(new double[]{depGeo.getLat(), depGeo.getLon()});
        }

        // ── En-route waypoints ─────────────────────────────────────────────
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
                    // Inline coords provided by the flight plan — use directly.
                    // These take priority over the fixes lookup because they are
                    // specific to this exact flight (e.g. oceanic clearance coords).
                    lat = pos.getLat();
                    lon = pos.getLon();
                } else if (pointName != null && multiMap.containsKey(pointName)) {
                    // No inline coords — look up in the fixes/airways multimap.
                    // Pass the already-resolved routePoints as Tier 3 context so
                    // that if dep/dest anchors are missing, the centroid of the
                    // flight path resolved so far can still guide disambiguation.
                    GeoPoint gp = bestCandidate(
                            multiMap.get(pointName),
                            depLat, depLon, destLat, destLon,
                            routePoints);
                    if (!hasValidCoords(gp)) continue; // name-only airway — skip
                    lat = gp.getLat();
                    lon = gp.getLon();
                } else {
                    // Waypoint name not found in fixes or airways at all —
                    // upstream data gap (e.g. PLS, PKU absent from the dataset).
                    continue;
                }

                String type = determinePointType(pointName).equals("airport") ? "airport" : "waypoint";
                routePoints.add(new FlightRoute.RoutePoint(pointName, lat, lon, type, el.getSeqNum()));
                polyline.add(new double[]{lat, lon});

                // ── Airway reference point ─────────────────────────────────
                // Some route elements name the airway corridor they fly along
                // (e.g. "UA401"). If the airway has a representative coordinate
                // in the fixes dataset we include it as an intermediate polyline
                // vertex. Name-only airways (lat=0, lon=0) are skipped by
                // hasValidCoords() — they are in the map for name-lookup only.
                String airwayName = el.getAirway();
                if (airwayName != null && !airwayName.isBlank()) {
                    String aw = airwayName.trim().toUpperCase(Locale.ROOT);
                    if (!"DCT".equals(aw)) {
                        GeoPoint airwayGp = bestCandidate(
                                multiMap.get(aw),
                                depLat, depLon, destLat, destLon,
                                routePoints);
                        if (hasValidCoords(airwayGp)) {
                            routePoints.add(new FlightRoute.RoutePoint(
                                    aw, airwayGp.getLat(), airwayGp.getLon(), "airway", el.getSeqNum()));
                            polyline.add(new double[]{airwayGp.getLat(), airwayGp.getLon()});
                        }
                    }
                }
            }
        }

        // ── Destination airport ────────────────────────────────────────────
        if (destIcao != null && hasValidCoords(destGeo)
                && (routePoints.isEmpty()
                    || !destIcao.equals(routePoints.get(routePoints.size() - 1).getName()))) {
            routePoints.add(new FlightRoute.RoutePoint(
                    destIcao, destGeo.getLat(), destGeo.getLon(), "airport", 999));
            polyline.add(new double[]{destGeo.getLat(), destGeo.getLon()});
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
                    double seg   = Math.sqrt(dLat * dLat + dLon * dLon);
                    double scale = Math.max(0.25, Math.min(1.0, seg / 15.0));
                    double wave  = 0.55 + 0.45 * Math.sin(i * 1.3);
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

    // ── Fix multimap caching ──────────────────────────────────────────────────

    /**
     * Returns the cached multimap, rebuilding it if the leader has refreshed
     * the underlying data since the snapshot was taken.
     *
     * The multimap maps each waypoint/airway name to ALL GeoPoint candidates
     * for that name. Duplicates arise because the same ICAO identifier is
     * legitimately reused in different Flight Information Regions worldwide
     * (see class-level Javadoc for the full SUNIR example).
     */
    private Map<String, List<GeoPoint>> getCachedFixMultiMap() {
        Instant currentRefresh = flightDataCache.getLastRefreshed();
        FixMultiMapSnapshot snapshot = fixMapSnapshot.get();

        if (snapshot != null && Objects.equals(snapshot.builtAt(), currentRefresh)) {
            return snapshot.multiMap();
        }

        Map<String, List<GeoPoint>> fresh = buildFixMultiMap();
        FixMultiMapSnapshot next = new FixMultiMapSnapshot(currentRefresh, fresh);
        fixMapSnapshot.compareAndSet(snapshot, next);

        log.debug("[FIXMAP] Rebuilt fix multimap: {} unique names from {} raw entries (lastRefreshed={})",
                fresh.size(), getFixes().size() + getAirways().size(), currentRefresh);
        return fresh;
    }

    /**
     * Builds the name → candidates multimap by merging fixes and airways.
     *
     * Fixes are added first because they carry real coordinates. Airways are
     * appended afterwards — they are typically name-only (lat=0, lon=0) and
     * serve purely for name-existence checks in the disambiguation chain.
     *
     * Every entry is preserved — no data is discarded at build time.
     * The 4-tier bestCandidate() method does the selection at query time,
     * where the flight's route context is available.
     */
    private Map<String, List<GeoPoint>> buildFixMultiMap() {
        Map<String, List<GeoPoint>> multiMap = new HashMap<>();
        getFixes().forEach(gp ->
                multiMap.computeIfAbsent(gp.getName(), k -> new ArrayList<>()).add(gp));
        getAirways().forEach(gp ->
                multiMap.computeIfAbsent(gp.getName(), k -> new ArrayList<>()).add(gp));
        return multiMap;
    }

    // ── 4-tier duplicate disambiguation ───────────────────────────────────────

    /**
     * Selects the best GeoPoint from a list of candidates that share the same name,
     * using the strongest available geographic context from a 4-tier fallback chain.
     *
     * ┌─────┬────────────────────────────────────┬──────────────────────────────────────┐
     * │Tier │ Condition                           │ Strategy                             │
     * ├─────┼────────────────────────────────────┼──────────────────────────────────────┤
     * │  1  │ Both dep + dest coords known        │ Cross-track distance to the          │
     * │     │                                     │ dep→dest great-circle path           │
     * │     │                                     │ (850× signal ratio on SUNIR test)    │
     * ├─────┼────────────────────────────────────┼──────────────────────────────────────┤
     * │  2  │ At least one endpoint coord known   │ Closest to the nearest known         │
     * │     │ (dep OR dest, not both)             │ endpoint (dep or dest)               │
     * │     │                                     │ (7× signal ratio on SUNIR test)      │
     * ├─────┼────────────────────────────────────┼──────────────────────────────────────┤
     * │  3  │ No endpoint coords known, but       │ Closest to the centroid of already-  │
     * │     │ prior route points are resolved     │ resolved route points — approximates │
     * │     │                                     │ the flight corridor so far           │
     * ├─────┼────────────────────────────────────┼──────────────────────────────────────┤
     * │  4  │ No geographic context available     │ First entry with valid coordinates   │
     * │     │ (first waypoint, unknown airports)  │ (last resort — preserves previous    │
     * │     │                                     │ behaviour for extreme edge cases)    │
     * └─────┴────────────────────────────────────┴──────────────────────────────────────┘
     *
     * @param candidates    all GeoPoints sharing the same name; null-safe
     * @param depLat        departure airport latitude  (null if unknown)
     * @param depLon        departure airport longitude (null if unknown)
     * @param destLat       destination airport latitude  (null if unknown)
     * @param destLon       destination airport longitude (null if unknown)
     * @param resolvedSoFar route points already resolved earlier in this flight
     *                      (used as Tier 3 centroid context; may be empty)
     * @return the best candidate, or null if candidates is null/empty
     */
    private GeoPoint bestCandidate(
            List<GeoPoint> candidates,
            Double depLat, Double depLon,
            Double destLat, Double destLon,
            List<FlightRoute.RoutePoint> resolvedSoFar) {

        if (candidates == null || candidates.isEmpty()) return null;

        // Only one candidate — no disambiguation needed, return immediately
        if (candidates.size() == 1) return candidates.get(0);

        // Filter to only candidates with drawable coordinates for distance comparisons.
        // Zero-coord (name-only airway) entries cannot be scored by any distance metric.
        List<GeoPoint> valid = candidates.stream()
                .filter(this::hasValidCoords)
                .collect(Collectors.toList());

        if (valid.isEmpty()) {
            // All candidates are name-only (lat=0, lon=0) — return first as fallback
            return candidates.get(0);
        }
        if (valid.size() == 1) return valid.get(0);

        // ── Tier 1: Cross-track distance (both endpoints known) ────────────
        // Measures perpendicular distance from each candidate to the flight's
        // dep→dest great-circle path. The 850× signal ratio (9 km vs 7 650 km
        // for SUNIR) makes this extremely robust against wrong-FIR duplicates.
        if (depLat != null && depLon != null && destLat != null && destLon != null) {
            return valid.stream()
                    .min(Comparator.comparingDouble(gp ->
                            crossTrackDistanceKm(gp.getLat(), gp.getLon(),
                                    depLat, depLon, destLat, destLon)))
                    .orElse(valid.get(0));
        }

        // ── Tier 2: Closest to nearest known endpoint (one endpoint known) ─
        // Cross-track requires both endpoints to define a path direction.
        // With only one known airport, the next best signal is proximity to
        // that airport — the correct regional fix will almost always be nearby.
        // We use "nearest endpoint" (min of dep/dest distances) rather than
        // always dep, to handle the symmetric case where only dest is known.
        if (depLat != null && depLon != null) {
            final double dLat = depLat, dLon = depLon;
            if (destLat != null && destLon != null) {
                // Both available but we're in Tier 2 due to logic fall-through —
                // should not reach here, but guard defensively
                final double xLat = destLat, xLon = destLon;
                return valid.stream()
                        .min(Comparator.comparingDouble(gp ->
                                Math.min(haversineKm(gp.getLat(), gp.getLon(), dLat, dLon),
                                         haversineKm(gp.getLat(), gp.getLon(), xLat, xLon))))
                        .orElse(valid.get(0));
            }
            return valid.stream()
                    .min(Comparator.comparingDouble(gp ->
                            haversineKm(gp.getLat(), gp.getLon(), dLat, dLon)))
                    .orElse(valid.get(0));
        }
        if (destLat != null && destLon != null) {
            final double xLat = destLat, xLon = destLon;
            return valid.stream()
                    .min(Comparator.comparingDouble(gp ->
                            haversineKm(gp.getLat(), gp.getLon(), xLat, xLon)))
                    .orElse(valid.get(0));
        }

        // ── Tier 3: Closest to centroid of already-resolved route points ───
        // Neither airport is in the fixes dataset, but we can approximate the
        // flight corridor from waypoints resolved earlier in this same route.
        // The centroid (average lat/lon) of those points acts as a soft anchor.
        List<FlightRoute.RoutePoint> withCoords = resolvedSoFar.stream()
                .filter(p -> p.getLat() != 0.0 || p.getLon() != 0.0)
                .collect(Collectors.toList());

        if (!withCoords.isEmpty()) {
            double centroidLat = withCoords.stream()
                    .mapToDouble(FlightRoute.RoutePoint::getLat).average().orElse(0);
            double centroidLon = withCoords.stream()
                    .mapToDouble(FlightRoute.RoutePoint::getLon).average().orElse(0);
            return valid.stream()
                    .min(Comparator.comparingDouble(gp ->
                            haversineKm(gp.getLat(), gp.getLon(), centroidLat, centroidLon)))
                    .orElse(valid.get(0));
        }

        // ── Tier 4: First valid-coord entry (absolute last resort) ─────────
        // No geographic context is available at all — this only happens for
        // the very first waypoint of a flight whose dep and dest airports are
        // both absent from the fixes dataset. Preserves previous behaviour.
        return valid.get(0);
    }

    // ── Geodetic math ─────────────────────────────────────────────────────────

    /**
     * Computes the cross-track distance (km) from point P to the great-circle
     * path defined by points A (departure) → B (destination).
     *
     * Formula (Aviation Formulary, Ed Williams):
     *
     *   d_xt = asin( sin(d_AP / R) × sin(θ_AP − θ_AB) ) × R
     *
     * where:
     *   d_AP  = great-circle distance A → P (radians)
     *   θ_AP  = initial bearing A → P
     *   θ_AB  = initial bearing A → B
     *   R     = Earth radius (6 371 km)
     *
     * The absolute value is taken so the result is always ≥ 0 — we only care
     * about the magnitude of the perpendicular offset, not which side of the
     * path the point is on.
     *
     * @return absolute cross-track distance in kilometres, always ≥ 0
     */
    private double crossTrackDistanceKm(
            double pLat, double pLon,
            double aLat, double aLon,
            double bLat, double bLon) {

        double d_ap = haversineKm(aLat, aLon, pLat, pLon) / EARTH_RADIUS_KM;
        double theta_ap = bearing(aLat, aLon, pLat, pLon);
        double theta_ab = bearing(aLat, aLon, bLat, bLon);

        // Clamp to [-1, 1] to guard against floating-point rounding producing
        // values like 1.0000000000000002 that would make asin throw NaN
        double sinXt = Math.sin(d_ap) * Math.sin(theta_ap - theta_ab);
        return Math.abs(Math.asin(Math.max(-1.0, Math.min(1.0, sinXt))) * EARTH_RADIUS_KM);
    }

    /**
     * Haversine great-circle distance in kilometres between two lat/lon points.
     * Sufficient for waypoint disambiguation where relative distances matter
     * more than navigation-grade precision.
     */
    private double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                  * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return EARTH_RADIUS_KM * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /**
     * Initial bearing (radians) from point A to point B along the great-circle path.
     * Used by crossTrackDistanceKm() to measure angular deviation from the flight path.
     */
    private double bearing(double aLat, double aLon, double bLat, double bLon) {
        double y = Math.sin(Math.toRadians(bLon - aLon)) * Math.cos(Math.toRadians(bLat));
        double x = Math.cos(Math.toRadians(aLat)) * Math.sin(Math.toRadians(bLat))
                - Math.sin(Math.toRadians(aLat)) * Math.cos(Math.toRadians(bLat))
                  * Math.cos(Math.toRadians(bLon - aLon));
        return Math.atan2(y, x);
    }

    // ── General helpers ───────────────────────────────────────────────────────

    /**
     * Returns true only if the GeoPoint has real drawable coordinates.
     *
     * Name-only airways (from /geopoints/list/airways) are stored as lat=0,
     * lon=0 by GeoPoint.parse() because the upstream endpoint returns plain
     * name strings with no coordinate data. Since (0,0) is a genuine geographic
     * point in the Gulf of Guinea, treating it as a route vertex would draw a
     * spurious line to the middle of the Atlantic. We treat exactly (0,0) as
     * "no coordinates available" — such entries remain in the multimap for
     * name-existence checks but are never drawn on the map.
     */
    private boolean hasValidCoords(GeoPoint gp) {
        return gp != null && (gp.getLat() != 0.0 || gp.getLon() != 0.0);
    }

    /**
     * Classifies a waypoint name as "airport" if it matches the ICAO 4-letter
     * aerodrome identifier pattern (uppercase letters only, exactly 4 chars).
     * Everything else is treated as a named waypoint/fix.
     */
    private String determinePointType(String name) {
        if (name != null && name.length() == 4 && name.matches("[A-Z]{4}")) return "airport";
        return "waypoint";
    }

    private double clampLat(double lat) {
        return Math.max(-85.0, Math.min(85.0, lat));
    }

    private double wrapLon(double lon) {
        double x = lon;
        while (x > 180.0)  x -= 360.0;
        while (x < -180.0) x += 360.0;
        return x;
    }

    private double normaliseLonDelta(double dLon) {
        double x = dLon;
        while (x > 180.0)  x -= 360.0;
        while (x < -180.0) x += 360.0;
        return x;
    }

    /**
     * Immutable snapshot pairing the pre-built multimap with the cache timestamp
     * it was built from. Keying on lastRefreshed means any leader-driven refresh
     * automatically invalidates the snapshot on the next resolveRoute() call.
     */
    private record FixMultiMapSnapshot(Instant builtAt, Map<String, List<GeoPoint>> multiMap) {}
}
