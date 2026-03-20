package com.flightplan.service;

import com.flightplan.model.FlightPlan;
import com.flightplan.model.FlightRoute;
import com.flightplan.model.GeoPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for FlightService.
 *
 * Uses LENIENT strictness globally because several @BeforeEach / helper stubs
 * pre-load cache data (flights, fixes, airways) that is only consumed by a
 * subset of tests in each nested class.  Unused stubs here are intentional
 * convenience setup, not mistakes, so we suppress UnnecessaryStubbingException
 * with Strictness.LENIENT.
 *
 * Key examples of intentional unused stubs:
 *  - GetFlightByCallsignTests.stubCache() — only getFlightPlans() is used by
 *    getFlightByCallsign(); getFixes/getAirways are NOT called by that method.
 *  - ResolveRouteTests.emptyForUnknown() — resolveRoute short-circuits after
 *    the callsign lookup fails, so getFixes/getAirways stubs go unused.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("FlightService")
class FlightServiceTest {

    @Mock private FlightDataCache flightDataCache;

    private FlightService service;

    @BeforeEach
    void setUp() {
        service = new FlightService(flightDataCache);
    }

    // ── getAllFlightPlans ──────────────────────────────────────────────

    @Nested @DisplayName("getAllFlightPlans()")
    class GetAllFlightPlansTests {

        @Test @DisplayName("delegates directly to FlightDataCache — no upstream API call")
        void delegatesToCache() {
            List<FlightPlan> cached = List.of(buildSia200());
            when(flightDataCache.getFlightPlans()).thenReturn(cached);

            assertThat(service.getAllFlightPlans()).isSameAs(cached);
            verify(flightDataCache).getFlightPlans();
            verifyNoMoreInteractions(flightDataCache);
        }

        @Test @DisplayName("returns empty list when cache is empty")
        void returnsEmptyWhenCacheEmpty() {
            when(flightDataCache.getFlightPlans()).thenReturn(List.of());
            assertThat(service.getAllFlightPlans()).isEmpty();
        }
    }

    // ── getFlightByCallsign ────────────────────────────────────────────

    @Nested @DisplayName("getFlightByCallsign()")
    class GetFlightByCallsignTests {

        // Only getFlightPlans() is called by getFlightByCallsign().
        // getFixes/getAirways are NOT used by this method — no need to stub them.
        @BeforeEach
        void stubCache() {
            when(flightDataCache.getFlightPlans()).thenReturn(List.of(buildSia200()));
        }

        @Test @DisplayName("finds exact callsign match from cache")
        void exactMatch() {
            Optional<FlightPlan> result = service.getFlightByCallsign("SIA200");
            assertThat(result).isPresent();
            assertThat(result.get().getAircraftIdentification()).isEqualTo("SIA200");
        }

        @Test @DisplayName("case-insensitive match")
        void caseInsensitive() {
            assertThat(service.getFlightByCallsign("sia200")).isPresent();
        }

        @Test @DisplayName("returns empty Optional for unknown callsign")
        void returnsEmptyForUnknown() {
            assertThat(service.getFlightByCallsign("XXXXXX")).isEmpty();
        }
    }

    // ── getAirways / getFixes ──────────────────────────────────────────

    @Nested @DisplayName("getAirways() and getFixes()")
    class GeoPointDelegationTests {

        @Test @DisplayName("getAirways delegates to cache")
        void airwaysDelegatesToCache() {
            List<GeoPoint> airways = List.of(new GeoPoint("A576", 1.5, 104.1, "airway"));
            when(flightDataCache.getAirways()).thenReturn(airways);

            assertThat(service.getAirways()).isSameAs(airways);
            verify(flightDataCache).getAirways();
        }

        @Test @DisplayName("getFixes delegates to cache")
        void fixesDelegatesToCache() {
            List<GeoPoint> fixes = List.of(new GeoPoint("WSSS", 1.36, 103.99, "fix"));
            when(flightDataCache.getFixes()).thenReturn(fixes);

            assertThat(service.getFixes()).isSameAs(fixes);
            verify(flightDataCache).getFixes();
        }
    }

    // ── getCacheLastRefreshed ──────────────────────────────────────────

    @Nested @DisplayName("getCacheLastRefreshed()")
    class GetCacheLastRefreshedTests {

        @Test @DisplayName("delegates to FlightDataCache.getLastRefreshed()")
        void delegatesToCache() {
            Instant ts = Instant.parse("2026-03-13T10:45:00Z");
            when(flightDataCache.getLastRefreshed()).thenReturn(ts);

            assertThat(service.getCacheLastRefreshed()).isEqualTo(ts);
            verify(flightDataCache).getLastRefreshed();
        }

        @Test @DisplayName("returns null when cache has never been refreshed")
        void returnsNullWhenNeverRefreshed() {
            when(flightDataCache.getLastRefreshed()).thenReturn(null);
            assertThat(service.getCacheLastRefreshed()).isNull();
        }
    }

    // ── resolveRoute ───────────────────────────────────────────────────

    @Nested @DisplayName("resolveRoute()")
    class ResolveRouteTests {

        /**
         * Stubs all three cache reads used by resolveRoute().
         * emptyForUnknown() short-circuits before getFixes/getAirways are reached —
         * LENIENT mode suppresses the unused-stubbing error for those two in that test.
         */
        private void stubCache(List<FlightPlan> plans) {
            when(flightDataCache.getFlightPlans()).thenReturn(plans);
            when(flightDataCache.getFixes()).thenReturn(fullFixList());
            when(flightDataCache.getAirways()).thenReturn(List.of());
        }

        @Test @DisplayName("returns empty for unknown callsign")
        void emptyForUnknown() {
            stubCache(List.of(buildSia200()));
            assertThat(service.resolveRoute("XXXXXX")).isEmpty();
        }

        @Test @DisplayName("returns resolved FlightRoute for SIA200")
        void resolvesValidRoute() {
            stubCache(List.of(buildSia200()));
            Optional<FlightRoute> result = service.resolveRoute("SIA200");

            assertThat(result).isPresent();
            FlightRoute r = result.get();
            assertThat(r.getCallsign()).isEqualTo("SIA200");
            assertThat(r.getDepartureAerodrome()).isEqualTo("WSSS");
            assertThat(r.getDestinationAerodrome()).isEqualTo("YSSY");
            assertThat(r.getAircraftType()).isEqualTo("A359");
            assertThat(r.getFlightType()).isEqualTo("M");
        }

        @Test @DisplayName("first route point is the departure airport")
        void firstPointIsDeparture() {
            stubCache(List.of(buildSia200()));
            FlightRoute r = service.resolveRoute("SIA200").orElseThrow();
            assertThat(r.getRoutePoints().get(0).getName()).isEqualTo("WSSS");
            assertThat(r.getRoutePoints().get(0).getType()).isEqualTo("airport");
        }

        @Test @DisplayName("last route point is the destination airport")
        void lastPointIsDestination() {
            stubCache(List.of(buildSia200()));
            List<FlightRoute.RoutePoint> pts = service.resolveRoute("SIA200").orElseThrow().getRoutePoints();
            assertThat(pts.get(pts.size() - 1).getName()).isEqualTo("YSSY");
            assertThat(pts.get(pts.size() - 1).getType()).isEqualTo("airport");
        }

        @Test @DisplayName("polyline has same count as routePoints")
        void polylineSameCount() {
            stubCache(List.of(buildSia200()));
            FlightRoute r = service.resolveRoute("SIA200").orElseThrow();
            assertThat(r.getPolyline()).hasSameSizeAs(r.getRoutePoints());
        }

        @Test @DisplayName("all route points have valid lat/lon")
        void routePointsValidCoords() {
            stubCache(List.of(buildSia200()));
            service.resolveRoute("SIA200").orElseThrow().getRoutePoints().forEach(rp -> {
                assertThat(rp.getLat()).isBetween(-90.0, 90.0);
                assertThat(rp.getLon()).isBetween(-180.0, 180.0);
            });
        }

        @Test @DisplayName("skips route element with null position")
        void skipsNullPosition() {
            FlightPlan plan = buildSia200();
            FlightPlan.RouteElement nullPos = new FlightPlan.RouteElement();
            nullPos.setSeqNum(99);
            nullPos.setPosition(null);
            plan.getFiledRoute().getRouteElement().add(nullPos);
            stubCache(List.of(plan));
            assertThat(service.resolveRoute("SIA200")).isPresent();
        }

        @Test @DisplayName("uses fix map when inline lat/lon are zero")
        void usesFixMapForZeroCoords() {
            FlightPlan plan = buildSia200();
            plan.getFiledRoute().getRouteElement().forEach(el -> {
                el.getPosition().setLat(0.0);
                el.getPosition().setLon(0.0);
            });
            stubCache(List.of(plan));
            boolean hasWsss = service.resolveRoute("SIA200").orElseThrow()
                    .getRoutePoints().stream().anyMatch(p -> "WSSS".equals(p.getName()));
            assertThat(hasWsss).isTrue();
        }

        @Test @DisplayName("skips element with zero coords not in fix map")
        void skipsUnresolvableElement() {
            FlightPlan plan = buildSia200();
            FlightPlan.RouteElement unknown = new FlightPlan.RouteElement();
            unknown.setSeqNum(50);
            FlightPlan.Position pos = new FlightPlan.Position();
            pos.setDesignatedPoint("XXXXXX");
            pos.setLat(0.0);
            pos.setLon(0.0);
            unknown.setPosition(pos);
            plan.getFiledRoute().getRouteElement().add(unknown);
            stubCache(List.of(plan));
            assertThat(service.resolveRoute("SIA200")).isPresent();
        }

        @Test @DisplayName("name-only airway in fix map (lat=0,lon=0) is skipped from polyline — no Gulf of Guinea lines")
        void nameOnlyAirwaySkippedFromPolyline() {
            // UA401 exists in the airways list as name-only (lat=0, lon=0).
            // It must NOT appear as a polyline vertex — that would draw a line to the Gulf of Guinea.
            FlightPlan plan = buildSia200();
            // Add a route element whose airway field is a name-only entry
            FlightPlan.RouteElement el = new FlightPlan.RouteElement();
            el.setSeqNum(5);
            FlightPlan.Position pos = new FlightPlan.Position();
            pos.setDesignatedPoint("PARDI");
            pos.setLat(1.10);
            pos.setLon(104.20);
            el.setPosition(pos);
            el.setAirway("UA401");
            plan.getFiledRoute().getRouteElement().add(el);

            when(flightDataCache.getFlightPlans()).thenReturn(List.of(plan));
            when(flightDataCache.getFixes()).thenReturn(fullFixList());
            // UA401 is a name-only airway — stored with lat=0, lon=0
            when(flightDataCache.getAirways()).thenReturn(List.of(
                    new GeoPoint("UA401", 0.0, 0.0, "airway")
            ));
            when(flightDataCache.getLastRefreshed()).thenReturn(Instant.now());

            FlightRoute route = service.resolveRoute("SIA200").orElseThrow();

            // UA401 must not appear in the polyline
            boolean zeroZeroInPolyline = route.getPolyline().stream()
                    .anyMatch(p -> p[0] == 0.0 && p[1] == 0.0);
            assertThat(zeroZeroInPolyline)
                    .as("(0,0) must not appear in polyline — name-only airway should be skipped")
                    .isFalse();

            // UA401 must not appear in routePoints either
            boolean ua401InPoints = route.getRoutePoints().stream()
                    .anyMatch(p -> "UA401".equals(p.getName()));
            assertThat(ua401InPoints)
                    .as("name-only airway UA401 must not appear as a route point")
                    .isFalse();
        }

        @Test @DisplayName("waypoint with (0,0) in fix map is skipped from polyline")
        void waypointWithZeroCoordsInFixMapSkipped() {
            // If a waypoint name resolves to a name-only entry (0,0) in the fix map,
            // it must be skipped — not drawn as a Gulf of Guinea point.
            FlightPlan plan = buildSia200();
            FlightPlan.RouteElement el = new FlightPlan.RouteElement();
            el.setSeqNum(5);
            FlightPlan.Position pos = new FlightPlan.Position();
            pos.setDesignatedPoint("NOCOORD");
            pos.setLat(0.0); // no inline coords
            pos.setLon(0.0);
            el.setPosition(pos);
            el.setAirway("DCT");
            plan.getFiledRoute().getRouteElement().add(el);

            when(flightDataCache.getFlightPlans()).thenReturn(List.of(plan));
            when(flightDataCache.getFixes()).thenReturn(fullFixList());
            // NOCOORD is in the fix map but with (0,0) — name-only
            when(flightDataCache.getAirways()).thenReturn(List.of(
                    new GeoPoint("NOCOORD", 0.0, 0.0, "airway")
            ));
            when(flightDataCache.getLastRefreshed()).thenReturn(Instant.now());

            FlightRoute route = service.resolveRoute("SIA200").orElseThrow();

            boolean zeroZeroInPolyline = route.getPolyline().stream()
                    .anyMatch(p -> p[0] == 0.0 && p[1] == 0.0);
            assertThat(zeroZeroInPolyline)
                    .as("(0,0) must not appear in polyline")
                    .isFalse();
        }

        @Test @DisplayName("airway with real coords is still added to polyline")
        void airwayWithRealCoordsAddedToPolyline() {
            // Confirm the guard does not accidentally skip airways that DO have coordinates
            FlightPlan plan = buildSia200();
            FlightPlan.RouteElement el = new FlightPlan.RouteElement();
            el.setSeqNum(5);
            FlightPlan.Position pos = new FlightPlan.Position();
            pos.setDesignatedPoint("PARDI");
            pos.setLat(1.10);
            pos.setLon(104.20);
            el.setPosition(pos);
            el.setAirway("M771"); // has real coords
            plan.getFiledRoute().getRouteElement().add(el);

            when(flightDataCache.getFlightPlans()).thenReturn(List.of(plan));
            when(flightDataCache.getFixes()).thenReturn(fullFixList());
            when(flightDataCache.getAirways()).thenReturn(List.of(
                    new GeoPoint("M771", 3.15, 101.70, "airway")
            ));
            when(flightDataCache.getLastRefreshed()).thenReturn(Instant.now());

            FlightRoute route = service.resolveRoute("SIA200").orElseThrow();

            boolean m771InPoints = route.getRoutePoints().stream()
                    .anyMatch(p -> "M771".equals(p.getName()));
            assertThat(m771InPoints)
                    .as("airway M771 with real coords should appear in route points")
                    .isTrue();
        }

        @Test @DisplayName("handles null filedRoute gracefully")
        void handlesNullFiledRoute() {
            FlightPlan plan = buildSia200();
            plan.setFiledRoute(null);
            stubCache(List.of(plan));
            assertThat(service.resolveRoute("SIA200")).isPresent();
        }

        @Test @DisplayName("handles null routeElement list gracefully")
        void handlesNullRouteElementList() {
            FlightPlan plan = buildSia200();
            plan.getFiledRoute().setRouteElement(null);
            stubCache(List.of(plan));
            assertThat(service.resolveRoute("SIA200")).isPresent();
        }

        @Test @DisplayName("handles null departure gracefully")
        void handlesNullDeparture() {
            FlightPlan plan = buildSia200();
            plan.setDeparture(null);
            stubCache(List.of(plan));
            assertThat(service.resolveRoute("SIA200").orElseThrow().getDepartureAerodrome()).isNull();
        }

        @Test @DisplayName("handles null arrival gracefully")
        void handlesNullArrival() {
            FlightPlan plan = buildSia200();
            plan.setArrival(null);
            stubCache(List.of(plan));
            assertThat(service.resolveRoute("SIA200").orElseThrow().getDestinationAerodrome()).isNull();
        }

        @Test @DisplayName("handles null aircraft gracefully")
        void handlesNullAircraft() {
            FlightPlan plan = buildSia200();
            plan.setAircraft(null);
            stubCache(List.of(plan));
            assertThat(service.resolveRoute("SIA200").orElseThrow().getAircraftType()).isNull();
        }

        @Test @DisplayName("does not duplicate destination when last element matches it")
        void doesNotDuplicateDestination() {
            FlightPlan plan = buildSia200();
            FlightPlan.RouteElement lastEl = new FlightPlan.RouteElement();
            lastEl.setSeqNum(100);
            FlightPlan.Position pos = new FlightPlan.Position();
            pos.setDesignatedPoint("YSSY");
            pos.setLat(-33.9461);
            pos.setLon(151.1772);
            lastEl.setPosition(pos);
            plan.getFiledRoute().getRouteElement().add(lastEl);
            stubCache(List.of(plan));

            long yssyCount = service.resolveRoute("SIA200").orElseThrow()
                    .getRoutePoints().stream().filter(p -> "YSSY".equals(p.getName())).count();
            assertThat(yssyCount).isEqualTo(1);
        }

        @Test @DisplayName("classifies 4-letter uppercase names as airports")
        void fourLetterNamesAreAirports() {
            stubCache(List.of(buildSia200()));
            service.resolveRoute("SIA200").orElseThrow().getRoutePoints().stream()
                    .filter(rp -> rp.getName() != null && rp.getName().matches("[A-Z]{4}"))
                    .forEach(rp -> assertThat(rp.getType()).isEqualTo("airport"));
        }

        @Test @DisplayName("airway geopoints used as fallback when fix map lacks entry")
        void airwayFallback() {
            FlightPlan plan = buildSia200();
            FlightPlan.RouteElement el = new FlightPlan.RouteElement();
            el.setSeqNum(50);
            FlightPlan.Position pos = new FlightPlan.Position();
            pos.setDesignatedPoint("A576");
            pos.setLat(0.0);
            pos.setLon(0.0);
            el.setPosition(pos);
            plan.getFiledRoute().getRouteElement().add(el);

            when(flightDataCache.getFlightPlans()).thenReturn(List.of(plan));
            when(flightDataCache.getFixes()).thenReturn(List.of(
                    new GeoPoint("WSSS", 1.3644, 103.9915, "fix"),
                    new GeoPoint("YSSY", -33.9461, 151.1772, "fix"),
                    new GeoPoint("PARDI", 1.10, 104.20, "fix")
            ));
            when(flightDataCache.getAirways()).thenReturn(List.of(
                    new GeoPoint("A576", 1.50, 104.10, "airway")
            ));

            boolean hasA576 = service.resolveRoute("SIA200").orElseThrow()
                    .getRoutePoints().stream().anyMatch(p -> "A576".equals(p.getName()));
            assertThat(hasA576).isTrue();
        }

        @Test @DisplayName("inserts airway RoutePoint when route element specifies a non-DCT airway that exists in cache")
        void insertsAirwayPointWhenAvailable() {
            FlightPlan plan = buildSia200();
            // Add airway name to the en-route element
            plan.getFiledRoute().getRouteElement().get(1).setAirway("A576");

            when(flightDataCache.getFlightPlans()).thenReturn(List.of(plan));
            when(flightDataCache.getFixes()).thenReturn(fullFixList());
            when(flightDataCache.getAirways()).thenReturn(List.of(
                    new GeoPoint("A576", 1.50, 104.10, "airway")
            ));

            FlightRoute r = service.resolveRoute("SIA200").orElseThrow();
            // Expect an airway point named A576 with type "airway"
            boolean hasAirway = r.getRoutePoints().stream()
                    .anyMatch(p -> "A576".equals(p.getName()) && "airway".equals(p.getType()));
            assertThat(hasAirway).isTrue();
            // polyline and routePoints must stay aligned
            assertThat(r.getPolyline()).hasSameSizeAs(r.getRoutePoints());
        }

        @Test @DisplayName("does not insert airway RoutePoint for DCT")
        void doesNotInsertAirwayForDct() {
            FlightPlan plan = buildSia200();
            plan.getFiledRoute().getRouteElement().get(1).setAirway("DCT");

            when(flightDataCache.getFlightPlans()).thenReturn(List.of(plan));
            when(flightDataCache.getFixes()).thenReturn(fullFixList());
            when(flightDataCache.getAirways()).thenReturn(List.of(
                    new GeoPoint("DCT", 0.0, 0.0, "airway")
            ));

            FlightRoute r = service.resolveRoute("SIA200").orElseThrow();
            assertThat(r.getRoutePoints().stream().anyMatch(p -> "airway".equals(p.getType()))).isFalse();
        }

        @Test @DisplayName("does not insert airway RoutePoint when airway name is unknown")
        void doesNotInsertAirwayWhenUnknown() {
            FlightPlan plan = buildSia200();
            plan.getFiledRoute().getRouteElement().get(1).setAirway("ZZ99");

            when(flightDataCache.getFlightPlans()).thenReturn(List.of(plan));
            when(flightDataCache.getFixes()).thenReturn(fullFixList());
            when(flightDataCache.getAirways()).thenReturn(List.of());

            FlightRoute r = service.resolveRoute("SIA200").orElseThrow();
            assertThat(r.getRoutePoints().stream().anyMatch(p -> "ZZ99".equals(p.getName()))).isFalse();
        }
    }

    // ── resolveAlternateRoute (optional extension) ──────────────────────

    @Nested @DisplayName("resolveAlternateRoute()")
    class ResolveAlternateRouteTests {

        @BeforeEach
        void stubCache() {
            when(flightDataCache.getFlightPlans()).thenReturn(List.of(buildSia200()));
            when(flightDataCache.getFixes()).thenReturn(fullFixList());
            when(flightDataCache.getAirways()).thenReturn(List.of());
        }

        @Test @DisplayName("returns empty for unknown callsign")
        void emptyForUnknown() {
            when(flightDataCache.getFlightPlans()).thenReturn(List.of(buildSia200()));
            assertThat(service.resolveAlternateRoute("XXXXXX")).isEmpty();
        }

        @Test @DisplayName("alternate route keeps endpoints but changes intermediate points")
        void keepsEndpointsChangesIntermediate() {
            FlightRoute primary = service.resolveRoute("SIA200").orElseThrow();
            FlightRoute alt = service.resolveAlternateRoute("SIA200").orElseThrow();

            assertThat(alt.getPolyline()).hasSize(primary.getPolyline().size());
            // Endpoints unchanged
            assertThat(alt.getPolyline().get(0)).containsExactly(primary.getPolyline().get(0));
            assertThat(alt.getPolyline().get(primary.getPolyline().size() - 1))
                    .containsExactly(primary.getPolyline().get(primary.getPolyline().size() - 1));

            // If there are intermediate points, at least one should differ
            if (primary.getPolyline().size() > 2) {
                boolean anyDifferent = false;
                for (int i = 1; i < primary.getPolyline().size() - 1; i++) {
                    double[] p = primary.getPolyline().get(i);
                    double[] a = alt.getPolyline().get(i);
                    if (p[0] != a[0] || p[1] != a[1]) { anyDifferent = true; break; }
                }
                assertThat(anyDifferent).isTrue();
            }
        }

        @Test @DisplayName("returns primary route unchanged when polyline has fewer than 2 points")
        void returnsPrimaryWhenPolylineTooShort() {
            FlightPlan plan = new FlightPlan();
            plan.setId("alt-001");
            plan.setAircraftIdentification("ALT1");
            plan.setMessageType("FPL");
            plan.setFlightType("M");
            plan.setFiledRoute(null);
            plan.setDeparture(null);
            plan.setArrival(null);

            when(flightDataCache.getFlightPlans()).thenReturn(List.of(plan));
            when(flightDataCache.getFixes()).thenReturn(List.of());
            when(flightDataCache.getAirways()).thenReturn(List.of());

            FlightRoute primary = service.resolveRoute("ALT1").orElseThrow();
            FlightRoute alt = service.resolveAlternateRoute("ALT1").orElseThrow();

            assertThat(primary.getPolyline()).isEmpty();
            assertThat(alt.getPolyline()).isEmpty();
        }

        @Test @DisplayName("clamps latitude and wraps longitude for alternate polyline")
        void clampsAndWrapsAltPolyline() {
            FlightPlan plan = extremePlan("WRP1");

            when(flightDataCache.getFlightPlans()).thenReturn(List.of(plan));
            when(flightDataCache.getFixes()).thenReturn(List.of(
                    new GeoPoint("DEPA", 10.0, 170.0, "fix"),
                    new GeoPoint("DSTA", -10.0, -170.0, "fix")
            ));
            when(flightDataCache.getAirways()).thenReturn(List.of());

            FlightRoute alt = service.resolveAlternateRoute("WRP1").orElseThrow();
            assertThat(alt.getPolyline().size()).isGreaterThanOrEqualTo(3);

            // Only intermediate points are modified, but all points must remain in valid bounds.
            alt.getPolyline().forEach(p -> {
                assertThat(p[0]).isBetween(-85.0, 85.0);
                assertThat(p[1]).isBetween(-180.0, 180.0);
            });
        }

        @Test @DisplayName("normalises longitude delta across dateline (covers +/-180 wrap branches)")
        void normalisesLonDeltaAcrossDateline() {
            // Two cases:
            //  1) prev=-179, next=+179  => delta=+358  -> normalise to -2 (x>180 branch)
            //  2) prev=+179, next=-179  => delta=-358 -> normalise to +2 (x<-180 branch)
            FlightPlan plan = datelinePlan("DLN01");

            when(flightDataCache.getFlightPlans()).thenReturn(List.of(plan));
            when(flightDataCache.getFixes()).thenReturn(List.of(
                    new GeoPoint("DEPA", 0.0, 0.0, "fix"),
                    new GeoPoint("DSTA", 1.0, 1.0, "fix")
            ));
            when(flightDataCache.getAirways()).thenReturn(List.of());

            FlightRoute primary = service.resolveRoute("DLN01").orElseThrow();
            FlightRoute alt = service.resolveAlternateRoute("DLN01").orElseThrow();

            // Endpoints unchanged, but at least one intermediate point should differ.
            assertThat(alt.getPolyline().get(0)).containsExactly(primary.getPolyline().get(0));
            assertThat(alt.getPolyline().get(primary.getPolyline().size() - 1))
                    .containsExactly(primary.getPolyline().get(primary.getPolyline().size() - 1));

            boolean anyDifferent = false;
            for (int i = 1; i < primary.getPolyline().size() - 1; i++) {
                double[] p = primary.getPolyline().get(i);
                double[] a = alt.getPolyline().get(i);
                if (p[0] != a[0] || p[1] != a[1]) { anyDifferent = true; break; }
            }
            assertThat(anyDifferent).isTrue();
        }

        @Test @DisplayName("handles degenerate local path where prev==next (covers norm<=1e-9 branch)")
        void handlesDegeneratePrevNextSame() {
            FlightPlan plan = new FlightPlan();
            plan.setId("deg-001");
            plan.setAircraftIdentification("DEG01");
            plan.setMessageType("FPL");
            plan.setFlightType("M");

            FlightPlan.Departure dep = new FlightPlan.Departure();
            dep.setDepartureAerodrome("DEPA");
            plan.setDeparture(dep);

            FlightPlan.Arrival arr = new FlightPlan.Arrival();
            arr.setDestinationAerodrome("DSTA");
            plan.setArrival(arr);

            // Route: three identical intermediate points so prev==next around the middle.
            FlightPlan.FiledRoute fr = new FlightPlan.FiledRoute();
            List<FlightPlan.RouteElement> els = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                FlightPlan.RouteElement el = new FlightPlan.RouteElement();
                el.setSeqNum(i + 1);
                FlightPlan.Position pos = new FlightPlan.Position();
                pos.setDesignatedPoint("MIDP");
                pos.setLat(10.0);
                pos.setLon(20.0);
                el.setPosition(pos);
                el.setAirway("DCT");
                els.add(el);
            }
            fr.setRouteElement(els);
            plan.setFiledRoute(fr);

            when(flightDataCache.getFlightPlans()).thenReturn(List.of(plan));
            when(flightDataCache.getFixes()).thenReturn(List.of(
                    new GeoPoint("DEPA", 0.0, 0.0, "fix"),
                    new GeoPoint("DSTA", 1.0, 1.0, "fix"),
                    new GeoPoint("MIDP", 10.0, 20.0, "fix")
            ));
            when(flightDataCache.getAirways()).thenReturn(List.of());

            FlightRoute primary = service.resolveRoute("DEG01").orElseThrow();
            FlightRoute alt = service.resolveAlternateRoute("DEG01").orElseThrow();

            // Should not throw; endpoints must match.
            assertThat(alt.getPolyline().get(0)).containsExactly(primary.getPolyline().get(0));
            assertThat(alt.getPolyline().get(primary.getPolyline().size() - 1))
                    .containsExactly(primary.getPolyline().get(primary.getPolyline().size() - 1));
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private FlightPlan buildSia200() {
        FlightPlan fp = new FlightPlan();
        fp.setId("test-001");
        fp.setAircraftIdentification("SIA200");
        fp.setMessageType("FPL");
        fp.setFlightType("M");
        fp.setAircraftOperating("SIA");

        FlightPlan.Departure dep = new FlightPlan.Departure();
        dep.setDepartureAerodrome("WSSS");
        fp.setDeparture(dep);

        FlightPlan.Arrival arr = new FlightPlan.Arrival();
        arr.setDestinationAerodrome("YSSY");
        fp.setArrival(arr);

        FlightPlan.Aircraft ac = new FlightPlan.Aircraft();
        ac.setAircraftType("A359");
        fp.setAircraft(ac);

        FlightPlan.FiledRoute route = new FlightPlan.FiledRoute();
        FlightPlan.RouteElement e1 = new FlightPlan.RouteElement();
        e1.setSeqNum(1);
        FlightPlan.Position p1 = new FlightPlan.Position();
        p1.setDesignatedPoint("WSSS");
        p1.setLat(1.3644); p1.setLon(103.9915);
        e1.setPosition(p1);
        e1.setAirway("DCT");

        FlightPlan.RouteElement e2 = new FlightPlan.RouteElement();
        e2.setSeqNum(2);
        FlightPlan.Position p2 = new FlightPlan.Position();
        p2.setDesignatedPoint("PARDI");
        p2.setLat(1.10); p2.setLon(104.20);
        e2.setPosition(p2);
        e2.setAirway("DCT");

        route.setRouteElement(new ArrayList<>(List.of(e1, e2)));
        fp.setFiledRoute(route);
        return fp;
    }

    private List<GeoPoint> fullFixList() {
        return List.of(
                new GeoPoint("WSSS", 1.3644, 103.9915, "fix"),
                new GeoPoint("YSSY", -33.9461, 151.1772, "fix"),
                new GeoPoint("PARDI", 1.10, 104.20, "fix")
        );
    }

    private FlightPlan extremePlan(String callsign) {
        FlightPlan fp = new FlightPlan();
        fp.setId("ext-001");
        fp.setAircraftIdentification(callsign);
        fp.setMessageType("FPL");
        fp.setFlightType("M");

        FlightPlan.Departure dep = new FlightPlan.Departure();
        dep.setDepartureAerodrome("DEPA");
        fp.setDeparture(dep);

        FlightPlan.Arrival arr = new FlightPlan.Arrival();
        arr.setDestinationAerodrome("DSTA");
        fp.setArrival(arr);

        FlightPlan.FiledRoute route = new FlightPlan.FiledRoute();
        FlightPlan.RouteElement mid = new FlightPlan.RouteElement();
        mid.setSeqNum(1);
        FlightPlan.Position pos = new FlightPlan.Position();
        pos.setDesignatedPoint("MIDP");
        pos.setLat(90.0);     // out of bounds, will be clamped in alternate
        pos.setLon(190.0);    // out of bounds, will be wrapped in alternate
        mid.setPosition(pos);
        mid.setAirway("DCT");
        route.setRouteElement(new ArrayList<>(List.of(mid)));
        fp.setFiledRoute(route);
        return fp;
    }

    private FlightPlan datelinePlan(String callsign) {
        FlightPlan fp = new FlightPlan();
        fp.setId("dateline-001");
        fp.setAircraftIdentification(callsign);
        fp.setMessageType("FPL");
        fp.setFlightType("M");

        FlightPlan.Departure dep = new FlightPlan.Departure();
        dep.setDepartureAerodrome("DEPA");
        fp.setDeparture(dep);

        FlightPlan.Arrival arr = new FlightPlan.Arrival();
        arr.setDestinationAerodrome("DSTA");
        fp.setArrival(arr);

        FlightPlan.FiledRoute route = new FlightPlan.FiledRoute();
        // 5 intermediate points to allow the prev/next delta checks to exercise both wrap directions.
        FlightPlan.RouteElement e1 = new FlightPlan.RouteElement();
        e1.setSeqNum(1);
        FlightPlan.Position p1 = new FlightPlan.Position();
        p1.setDesignatedPoint("P1");
        p1.setLat(5.0);
        p1.setLon(-179.0);
        e1.setPosition(p1);
        e1.setAirway("DCT");

        FlightPlan.RouteElement e2 = new FlightPlan.RouteElement();
        e2.setSeqNum(2);
        FlightPlan.Position p2 = new FlightPlan.Position();
        p2.setDesignatedPoint("P2");
        p2.setLat(6.0);
        p2.setLon(179.0);
        e2.setPosition(p2);
        e2.setAirway("DCT");

        FlightPlan.RouteElement e3 = new FlightPlan.RouteElement();
        e3.setSeqNum(3);
        FlightPlan.Position p3 = new FlightPlan.Position();
        p3.setDesignatedPoint("P3");
        p3.setLat(7.0);
        p3.setLon(179.0);
        e3.setPosition(p3);
        e3.setAirway("DCT");

        FlightPlan.RouteElement e4 = new FlightPlan.RouteElement();
        e4.setSeqNum(4);
        FlightPlan.Position p4 = new FlightPlan.Position();
        p4.setDesignatedPoint("P4");
        p4.setLat(8.0);
        p4.setLon(-179.0);
        e4.setPosition(p4);
        e4.setAirway("DCT");

        route.setRouteElement(new ArrayList<>(List.of(e1, e2, e3, e4)));
        fp.setFiledRoute(route);
        return fp;
    }

    // ── Fix map caching ───────────────────────────────────────────────────────

    /**
     * Tests for getCachedFixMap() — verifies that the 247K-entry fix map is built
     * once per cache refresh cycle and not rebuilt on every resolveRoute() call.
     *
     * Strategy: stub getLastRefreshed() to return a fixed Instant, call resolveRoute()
     * multiple times, and assert that getFixes() / getAirways() are only called once
     * (the first build). Changing the Instant simulates a leader cache refresh and
     * verifies the map is rebuilt exactly once more.
     */
    @Nested @DisplayName("fix map caching")
    class FixMapCachingTests {

        private final Instant T1 = Instant.parse("2026-03-20T10:00:00Z");
        private final Instant T2 = Instant.parse("2026-03-20T10:10:00Z");

        private FlightPlan simplePlan(String callsign) {
            FlightPlan fp = new FlightPlan();
            fp.setAircraftIdentification(callsign);
            FlightPlan.Departure dep = new FlightPlan.Departure();
            dep.setDepartureAerodrome("WSSS");
            fp.setDeparture(dep);
            FlightPlan.Arrival arr = new FlightPlan.Arrival();
            arr.setDestinationAerodrome("YSSY");
            fp.setArrival(arr);
            FlightPlan.FiledRoute route = new FlightPlan.FiledRoute();
            route.setRouteElement(new ArrayList<>());
            fp.setFiledRoute(route);
            return fp;
        }

        @Test
        @DisplayName("getFixes() and getAirways() are called once for multiple resolveRoute() calls with same lastRefreshed")
        void fixMapBuiltOnlyOncePerRefreshCycle() {
            FlightPlan plan = simplePlan("SIA200");
            when(flightDataCache.getFlightPlans()).thenReturn(List.of(plan));
            when(flightDataCache.getFixes()).thenReturn(List.of(
                    new GeoPoint("WSSS", 1.36, 103.99, "fix"),
                    new GeoPoint("YSSY", -33.94, 151.17, "fix")));
            when(flightDataCache.getAirways()).thenReturn(List.of());
            when(flightDataCache.getLastRefreshed()).thenReturn(T1);

            // Three calls with the same lastRefreshed timestamp
            service.resolveRoute("SIA200");
            service.resolveRoute("SIA200");
            service.resolveRoute("SIA200");

            // Fix map should only have been built once — getFixes/getAirways called once each
            verify(flightDataCache, times(1)).getFixes();
            verify(flightDataCache, times(1)).getAirways();
        }

        @Test
        @DisplayName("fix map is rebuilt exactly once when lastRefreshed changes (leader refresh)")
        void fixMapRebuiltAfterCacheRefresh() {
            FlightPlan plan = simplePlan("SIA200");
            when(flightDataCache.getFlightPlans()).thenReturn(List.of(plan));
            when(flightDataCache.getFixes()).thenReturn(List.of(
                    new GeoPoint("WSSS", 1.36, 103.99, "fix"),
                    new GeoPoint("YSSY", -33.94, 151.17, "fix")));
            when(flightDataCache.getAirways()).thenReturn(List.of());

            // First refresh cycle — two calls, map built once
            when(flightDataCache.getLastRefreshed()).thenReturn(T1);
            service.resolveRoute("SIA200");
            service.resolveRoute("SIA200");
            verify(flightDataCache, times(1)).getFixes();

            // Leader refreshes the cache — timestamp changes
            when(flightDataCache.getLastRefreshed()).thenReturn(T2);
            service.resolveRoute("SIA200");
            service.resolveRoute("SIA200");

            // getFixes should now have been called exactly twice total (once per refresh)
            verify(flightDataCache, times(2)).getFixes();
            verify(flightDataCache, times(2)).getAirways();
        }

        @Test
        @DisplayName("fix map is built on first call when snapshot is null (cold start)")
        void fixMapBuiltOnColdStart() {
            FlightPlan plan = simplePlan("SIA200");
            when(flightDataCache.getFlightPlans()).thenReturn(List.of(plan));
            when(flightDataCache.getFixes()).thenReturn(List.of(
                    new GeoPoint("WSSS", 1.36, 103.99, "fix")));
            when(flightDataCache.getAirways()).thenReturn(List.of());
            when(flightDataCache.getLastRefreshed()).thenReturn(T1);

            // Fresh service instance — snapshot is null
            FlightService freshService = new FlightService(flightDataCache);
            freshService.resolveRoute("SIA200");

            verify(flightDataCache, times(1)).getFixes();
            verify(flightDataCache, times(1)).getAirways();
        }

        @Test
        @DisplayName("fix map built from null lastRefreshed (cache not yet refreshed) and reused")
        void fixMapWorksWithNullLastRefreshed() {
            FlightPlan plan = simplePlan("SIA200");
            when(flightDataCache.getFlightPlans()).thenReturn(List.of(plan));
            when(flightDataCache.getFixes()).thenReturn(List.of(
                    new GeoPoint("WSSS", 1.36, 103.99, "fix")));
            when(flightDataCache.getAirways()).thenReturn(List.of());
            when(flightDataCache.getLastRefreshed()).thenReturn(null);

            service.resolveRoute("SIA200");
            service.resolveRoute("SIA200");

            // Null timestamp is a valid key — map still built only once
            verify(flightDataCache, times(1)).getFixes();
        }

        @Test
        @DisplayName("routes are correctly resolved using the cached fix map")
        void cachedFixMapProducesCorrectRoute() {
            FlightPlan plan = simplePlan("SIA200");
            when(flightDataCache.getFlightPlans()).thenReturn(List.of(plan));
            when(flightDataCache.getFixes()).thenReturn(List.of(
                    new GeoPoint("WSSS", 1.3644, 103.9915, "fix"),
                    new GeoPoint("YSSY", -33.9461, 151.1772, "fix")));
            when(flightDataCache.getAirways()).thenReturn(List.of());
            when(flightDataCache.getLastRefreshed()).thenReturn(T1);

            Optional<FlightRoute> route = service.resolveRoute("SIA200");

            assertThat(route).isPresent();
            assertThat(route.get().getDepartureAerodrome()).isEqualTo("WSSS");
            assertThat(route.get().getDestinationAerodrome()).isEqualTo("YSSY");
            assertThat(route.get().getPolyline()).hasSize(2);

            // Call again — same result, still from cache
            Optional<FlightRoute> route2 = service.resolveRoute("SIA200");
            assertThat(route2).isPresent();
            assertThat(route2.get().getPolyline()).hasSize(2);

            // Map only built once
            verify(flightDataCache, times(1)).getFixes();
        }

        @Test
        @DisplayName("each N-minute refresh cycle triggers exactly one map rebuild regardless of call volume")
        void exactlyOneRebuildPerRefreshCycle() {
            FlightPlan plan = simplePlan("SIA200");
            when(flightDataCache.getFlightPlans()).thenReturn(List.of(plan));
            when(flightDataCache.getFixes()).thenReturn(List.of(
                    new GeoPoint("WSSS", 1.36, 103.99, "fix")));
            when(flightDataCache.getAirways()).thenReturn(List.of());

            // Simulate three 10-minute refresh cycles with 5 requests each
            Instant[] cycles = {
                Instant.parse("2026-03-20T10:00:00Z"),
                Instant.parse("2026-03-20T10:10:00Z"),
                Instant.parse("2026-03-20T10:20:00Z")
            };

            for (Instant cycle : cycles) {
                when(flightDataCache.getLastRefreshed()).thenReturn(cycle);
                for (int i = 0; i < 5; i++) {
                    service.resolveRoute("SIA200");
                }
            }

            // 3 cycles × 1 rebuild each = 3 total getFixes calls
            verify(flightDataCache, times(3)).getFixes();
            verify(flightDataCache, times(3)).getAirways();
        }
    }
}
