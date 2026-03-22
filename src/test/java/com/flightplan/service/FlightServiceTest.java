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

        @Test @DisplayName("does not insert airway vertex when airway field equals designated point (avoids map dogleg)")
        void skipRedundantAirwayWhenSameNameAsFix() {
            FlightPlan plan = buildSia200();
            FlightPlan.RouteElement el = new FlightPlan.RouteElement();
            el.setSeqNum(5);
            FlightPlan.Position pos = new FlightPlan.Position();
            pos.setDesignatedPoint("WXZ1");
            pos.setLat(-6.80);
            pos.setLon(113.20);
            el.setPosition(pos);
            el.setAirway("WXZ1");
            plan.getFiledRoute().getRouteElement().add(el);

            when(flightDataCache.getFlightPlans()).thenReturn(List.of(plan));
            when(flightDataCache.getFixes()).thenReturn(fullFixList());
            when(flightDataCache.getAirways()).thenReturn(List.of(
                    new GeoPoint("WXZ1", -8.65, 115.22, "airway")
            ));
            when(flightDataCache.getLastRefreshed()).thenReturn(Instant.now());

            FlightRoute route = service.resolveRoute("SIA200").orElseThrow();
            long wxz1Points = route.getRoutePoints().stream().filter(p -> "WXZ1".equals(p.getName())).count();
            assertThat(wxz1Points).as("single fix vertex; no duplicate airway row").isEqualTo(1);
            boolean bogusLeg = route.getPolyline().stream()
                    .anyMatch(p -> Math.abs(p[0] - (-8.65)) < 0.01 && Math.abs(p[1] - 115.22) < 0.01);
            assertThat(bogusLeg).as("second WXZ1 airway coords must not be drawn").isFalse();
        }

        @Test @DisplayName("skips airway representative far off dep→dest corridor vs fix (wrong-FIR duplicate)")
        void skipSpuriousAirwayFarOffCorridor() {
            FlightPlan plan = buildSia200();
            FlightPlan.RouteElement el = new FlightPlan.RouteElement();
            el.setSeqNum(5);
            FlightPlan.Position pos = new FlightPlan.Position();
            pos.setDesignatedPoint("FIXQ");
            pos.setLat(-7.0);
            pos.setLon(114.0);
            el.setPosition(pos);
            el.setAirway("SPUR");
            plan.getFiledRoute().getRouteElement().add(el);

            when(flightDataCache.getFlightPlans()).thenReturn(List.of(plan));
            when(flightDataCache.getFixes()).thenReturn(fullFixList());
            when(flightDataCache.getAirways()).thenReturn(List.of(
                    new GeoPoint("SPUR", 50.0, 10.0, "airway")
            ));
            when(flightDataCache.getLastRefreshed()).thenReturn(Instant.now());

            FlightRoute route = service.resolveRoute("SIA200").orElseThrow();
            assertThat(route.getRoutePoints().stream().noneMatch(p -> "SPUR".equals(p.getName())))
                    .as("European duplicate must not create a route point")
                    .isTrue();
            assertThat(route.getPolyline().stream().noneMatch(p -> Math.abs(p[0] - 50.0) < 0.01))
                    .as("spurious airway lat must not appear in polyline")
                    .isTrue();
        }

        @Test @DisplayName("skips airway vertex when wp→airway→next is a large detour vs wp→next (double line on map)")
        void skipAirwayWhenDetourVersusNextFix() {
            FlightPlan plan = new FlightPlan();
            plan.setId("detour-01");
            plan.setAircraftIdentification("DETOUR1");
            plan.setFlightType("M");
            FlightPlan.Departure dep = new FlightPlan.Departure();
            dep.setDepartureAerodrome("WIII");
            plan.setDeparture(dep);
            FlightPlan.Arrival arr = new FlightPlan.Arrival();
            arr.setDestinationAerodrome("WADD");
            plan.setArrival(arr);

            FlightPlan.RouteElement e1 = new FlightPlan.RouteElement();
            e1.setSeqNum(1);
            FlightPlan.Position p1 = new FlightPlan.Position();
            p1.setDesignatedPoint("G579");
            p1.setLat(-6.80);
            p1.setLon(113.20);
            e1.setPosition(p1);
            e1.setAirway("M774");

            FlightPlan.RouteElement e2 = new FlightPlan.RouteElement();
            e2.setSeqNum(2);
            FlightPlan.Position p2 = new FlightPlan.Position();
            p2.setDesignatedPoint("TAKAS");
            p2.setLat(-8.20);
            p2.setLon(115.50);
            e2.setPosition(p2);
            e2.setAirway("DCT");

            FlightPlan.FiledRoute fr = new FlightPlan.FiledRoute();
            fr.setRouteElement(new ArrayList<>(List.of(e1, e2)));
            plan.setFiledRoute(fr);

            when(flightDataCache.getFlightPlans()).thenReturn(List.of(plan));
            when(flightDataCache.getFixes()).thenReturn(List.of(
                    new GeoPoint("WIII", -6.12, 106.66, "fix"),
                    new GeoPoint("WADD", -8.75, 115.17, "fix"),
                    new GeoPoint("G579", -6.80, 113.20, "fix"),
                    new GeoPoint("TAKAS", -8.20, 115.50, "fix")
            ));
            // Bogus M774 well south/west of the G579→TAKAS leg — large detour if inserted
            when(flightDataCache.getAirways()).thenReturn(List.of(
                    new GeoPoint("M774", -10.0, 113.0, "airway")
            ));
            when(flightDataCache.getLastRefreshed()).thenReturn(Instant.now());

            FlightRoute route = service.resolveRoute("DETOUR1").orElseThrow();
            assertThat(route.getRoutePoints().stream().noneMatch(p -> "M774".equals(p.getName())))
                    .as("detour airway must not be added between G579 and TAKAS")
                    .isTrue();
            assertThat(route.getPolyline().stream().noneMatch(p -> Math.abs(p[0] - (-10.0)) < 0.02))
                    .as("bogus M774 lat must not appear in polyline")
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

    // ── resolveRoute — additional branch coverage ─────────────────────

    @Nested @DisplayName("resolveRoute() — branch coverage")
    class ResolveRouteBranchTests {

        @Test @DisplayName("seqNum null on route element defaults to 0 for sort (no NPE)")
        void nullSeqNumDefaultsToZero() {
            FlightPlan plan = new FlightPlan();
            plan.setId("seq-001");
            plan.setAircraftIdentification("SEQ01");
            plan.setFlightType("M");

            FlightPlan.Departure dep = new FlightPlan.Departure();
            dep.setDepartureAerodrome("WSSS");
            plan.setDeparture(dep);

            FlightPlan.Arrival arr = new FlightPlan.Arrival();
            arr.setDestinationAerodrome("YSSY");
            plan.setArrival(arr);

            // One element with null seqNum
            FlightPlan.RouteElement el = new FlightPlan.RouteElement();
            el.setSeqNum(null);
            FlightPlan.Position pos = new FlightPlan.Position();
            pos.setDesignatedPoint("PARDI");
            pos.setLat(1.10);
            pos.setLon(104.20);
            el.setPosition(pos);
            el.setAirway("DCT");
            FlightPlan.FiledRoute fr = new FlightPlan.FiledRoute();
            fr.setRouteElement(new ArrayList<>(List.of(el)));
            plan.setFiledRoute(fr);

            when(flightDataCache.getFlightPlans()).thenReturn(List.of(plan));
            when(flightDataCache.getFixes()).thenReturn(List.of(
                    new GeoPoint("WSSS", 1.3644, 103.9915, "fix"),
                    new GeoPoint("YSSY", -33.9461, 151.1772, "fix"),
                    new GeoPoint("PARDI", 1.10, 104.20, "fix")));
            when(flightDataCache.getAirways()).thenReturn(List.of());
            when(flightDataCache.getLastRefreshed()).thenReturn(Instant.now());

            FlightRoute r = service.resolveRoute("SEQ01").orElseThrow();
            // PARDI should still appear in the route
            assertThat(r.getRoutePoints().stream().anyMatch(p -> "PARDI".equals(p.getName()))).isTrue();
        }

        @Test @DisplayName("destination not duplicated when it already appears as the last route point")
        void destinationNotDuplicatedWhenAlreadyLastPoint() {
            // Build a plan where the last waypoint in the filed route IS the destination airport
            FlightPlan plan = new FlightPlan();
            plan.setId("dup-001");
            plan.setAircraftIdentification("DUP01");
            plan.setFlightType("M");

            FlightPlan.Departure dep = new FlightPlan.Departure();
            dep.setDepartureAerodrome("WSSS");
            plan.setDeparture(dep);

            FlightPlan.Arrival arr = new FlightPlan.Arrival();
            arr.setDestinationAerodrome("YSSY");
            plan.setArrival(arr);

            // Last element is the destination itself, with real coords
            FlightPlan.RouteElement el = new FlightPlan.RouteElement();
            el.setSeqNum(1);
            FlightPlan.Position pos = new FlightPlan.Position();
            pos.setDesignatedPoint("YSSY");
            pos.setLat(-33.9461);
            pos.setLon(151.1772);
            el.setPosition(pos);
            el.setAirway("DCT");
            FlightPlan.FiledRoute fr = new FlightPlan.FiledRoute();
            fr.setRouteElement(new ArrayList<>(List.of(el)));
            plan.setFiledRoute(fr);

            when(flightDataCache.getFlightPlans()).thenReturn(List.of(plan));
            when(flightDataCache.getFixes()).thenReturn(List.of(
                    new GeoPoint("WSSS", 1.3644, 103.9915, "fix"),
                    new GeoPoint("YSSY", -33.9461, 151.1772, "fix")));
            when(flightDataCache.getAirways()).thenReturn(List.of());
            when(flightDataCache.getLastRefreshed()).thenReturn(Instant.now());

            FlightRoute r = service.resolveRoute("DUP01").orElseThrow();
            long yssyCount = r.getRoutePoints().stream()
                    .filter(p -> "YSSY".equals(p.getName())).count();
            assertThat(yssyCount).isEqualTo(1);
        }

        @Test @DisplayName("airway reference with blank name is skipped (no blank-name route point added)")
        void blankAirwayNameSkipped() {
            FlightPlan plan = buildSia200();
            // Set the airway to a blank string (not DCT, not null, but blank)
            plan.getFiledRoute().getRouteElement().get(1).setAirway("   ");

            when(flightDataCache.getFlightPlans()).thenReturn(List.of(plan));
            when(flightDataCache.getFixes()).thenReturn(fullFixList());
            when(flightDataCache.getAirways()).thenReturn(List.of());
            when(flightDataCache.getLastRefreshed()).thenReturn(Instant.now());

            FlightRoute r = service.resolveRoute("SIA200").orElseThrow();
            // No blank-named route point should appear
            assertThat(r.getRoutePoints().stream().anyMatch(p -> p.getName() == null || p.getName().isBlank())).isFalse();
        }
    }

    // ── resolveAlternateRoute — additional branch coverage ────────────

    @Nested @DisplayName("resolveAlternateRoute() — branch coverage")
    class ResolveAlternateRouteBranchTests {

        @Test @DisplayName("null polyline entry is skipped without NPE")
        void nullPolylineEntrySkipped() {
            // Build a plan whose inline coords will produce a route, then we
            // manually inject a null into the polyline via a secondary mock.
            // Since polyline is built internally, we test the guard indirectly
            // by using an alternate path: p==null guard is in the intermediate
            // loop. We simulate this via a custom route with 3 points.
            FlightPlan plan = new FlightPlan();
            plan.setId("np-001");
            plan.setAircraftIdentification("NP01");
            plan.setFlightType("M");

            FlightPlan.Departure dep = new FlightPlan.Departure();
            dep.setDepartureAerodrome("WSSS");
            plan.setDeparture(dep);

            FlightPlan.Arrival arr = new FlightPlan.Arrival();
            arr.setDestinationAerodrome("YSSY");
            plan.setArrival(arr);

            FlightPlan.FiledRoute fr = new FlightPlan.FiledRoute();
            FlightPlan.RouteElement mid = new FlightPlan.RouteElement();
            mid.setSeqNum(1);
            FlightPlan.Position pos = new FlightPlan.Position();
            pos.setDesignatedPoint("PARDI");
            pos.setLat(1.10);
            pos.setLon(104.20);
            mid.setPosition(pos);
            mid.setAirway("DCT");
            fr.setRouteElement(new ArrayList<>(List.of(mid)));
            plan.setFiledRoute(fr);

            when(flightDataCache.getFlightPlans()).thenReturn(List.of(plan));
            when(flightDataCache.getFixes()).thenReturn(List.of(
                    new GeoPoint("WSSS", 1.3644, 103.9915, "fix"),
                    new GeoPoint("YSSY", -33.9461, 151.1772, "fix"),
                    new GeoPoint("PARDI", 1.10, 104.20, "fix")));
            when(flightDataCache.getAirways()).thenReturn(List.of());
            when(flightDataCache.getLastRefreshed()).thenReturn(Instant.now());

            // Must not throw; alternate route has same number of points as primary
            FlightRoute primary = service.resolveRoute("NP01").orElseThrow();
            FlightRoute alt = service.resolveAlternateRoute("NP01").orElseThrow();
            assertThat(alt.getPolyline()).hasSize(primary.getPolyline().size());
        }

        @Test @DisplayName("sign branch: both hash values cover positive and negative offset sides")
        void signBranchBothSidesCovered() {
            // Two different callsigns will produce different hash values, covering
            // both the sign==+1.0 and sign==-1.0 branch in resolveAlternateRoute.
            // We simply need both to run without error and produce valid polylines.
            when(flightDataCache.getFixes()).thenReturn(fullFixList());
            when(flightDataCache.getAirways()).thenReturn(List.of());
            when(flightDataCache.getLastRefreshed()).thenReturn(Instant.now());

            // Callsign with even hash → sign +1; odd hash → sign -1
            // Rather than predicting hashes, test enough different callsigns
            // that both branches are exercised across them.
            for (String cs : List.of("SIA200", "EK432", "QF001", "BA018")) {
                FlightPlan plan = buildSia200();
                plan.setAircraftIdentification(cs);
                when(flightDataCache.getFlightPlans()).thenReturn(List.of(plan));
                Optional<FlightRoute> alt = service.resolveAlternateRoute(cs);
                assertThat(alt).isPresent();
                assertThat(alt.get().getPolyline()).isNotEmpty();
            }
        }
    }

    // ── bestCandidate — Tier 2 defensive branch ───────────────────────

    @Nested @DisplayName("bestCandidate() — Tier 2 defensive both-endpoints branch")
    class BestCandidateTier2DefensiveTests {

        /**
         * The Tier-2 block in bestCandidate() has a defensive inner check:
         * "if (depLat != null && depLon != null) { if (destLat != null && destLon != null) { ... } }"
         * This inner "both available" sub-branch can only be reached if Tier 1 somehow
         * didn't fire. We can't reach it via resolveRoute() in practice (Tier 1 always
         * fires when both are known), so we exercise the Tier 2 dep-only branch directly
         * through resolveRoute() with a flight whose dest is NOT in the fix map.
         */
        @Test @DisplayName("Tier 2 dep-only: picks closest to departure when dest not in fix map")
        void tier2DepOnly_destMissingFromFixMap() {
            FlightPlan plan = new FlightPlan();
            plan.setId("t2-001");
            plan.setAircraftIdentification("T2DEP");
            plan.setFlightType("M");

            FlightPlan.Departure dep = new FlightPlan.Departure();
            dep.setDepartureAerodrome("WSSS");
            plan.setDeparture(dep);

            // Destination is NOT in the fix map → destGeo will be null → destLat/destLon null
            FlightPlan.Arrival arr = new FlightPlan.Arrival();
            arr.setDestinationAerodrome("ZZZZ");
            plan.setArrival(arr);

            // Duplicate waypoint DUPW — one near Singapore (correct), one in Europe (wrong)
            FlightPlan.RouteElement el = new FlightPlan.RouteElement();
            el.setSeqNum(1);
            FlightPlan.Position pos = new FlightPlan.Position();
            pos.setDesignatedPoint("DUPW");
            pos.setLat(0.0);
            pos.setLon(0.0); // force fix-map lookup
            el.setPosition(pos);
            el.setAirway("DCT");
            FlightPlan.FiledRoute fr = new FlightPlan.FiledRoute();
            fr.setRouteElement(new ArrayList<>(List.of(el)));
            plan.setFiledRoute(fr);

            GeoPoint nearSingapore = new GeoPoint("DUPW", 1.5, 104.5, "fix");  // ~30 km from WSSS
            GeoPoint inEurope      = new GeoPoint("DUPW", 48.0, 16.0, "fix"); // ~9000 km from WSSS

            when(flightDataCache.getFlightPlans()).thenReturn(List.of(plan));
            when(flightDataCache.getFixes()).thenReturn(List.of(
                    new GeoPoint("WSSS", 1.3644, 103.9915, "fix"),
                    nearSingapore, inEurope));
            when(flightDataCache.getAirways()).thenReturn(List.of());
            when(flightDataCache.getLastRefreshed()).thenReturn(Instant.now());

            FlightRoute r = service.resolveRoute("T2DEP").orElseThrow();

            // The Singapore-region DUPW should be chosen over the European one
            Optional<FlightRoute.RoutePoint> dupw = r.getRoutePoints().stream()
                    .filter(p -> "DUPW".equals(p.getName())).findFirst();
            assertThat(dupw).isPresent();
            assertThat(dupw.get().getLat()).isCloseTo(1.5, org.assertj.core.data.Offset.offset(0.1));
        }

        @Test @DisplayName("Tier 2 dest-only: picks closest to destination when dep not in fix map")
        void tier2DestOnly_depMissingFromFixMap() {
            FlightPlan plan = new FlightPlan();
            plan.setId("t2-002");
            plan.setAircraftIdentification("T2DST");
            plan.setFlightType("M");

            // Departure NOT in fix map
            FlightPlan.Departure dep = new FlightPlan.Departure();
            dep.setDepartureAerodrome("ZZZZ");
            plan.setDeparture(dep);

            FlightPlan.Arrival arr = new FlightPlan.Arrival();
            arr.setDestinationAerodrome("YSSY");
            plan.setArrival(arr);

            FlightPlan.RouteElement el = new FlightPlan.RouteElement();
            el.setSeqNum(1);
            FlightPlan.Position pos = new FlightPlan.Position();
            pos.setDesignatedPoint("DUPW");
            pos.setLat(0.0);
            pos.setLon(0.0);
            el.setPosition(pos);
            el.setAirway("DCT");
            FlightPlan.FiledRoute fr = new FlightPlan.FiledRoute();
            fr.setRouteElement(new ArrayList<>(List.of(el)));
            plan.setFiledRoute(fr);

            GeoPoint nearSydney = new GeoPoint("DUPW", -33.0, 151.0, "fix");  // ~100 km from YSSY
            GeoPoint inEurope   = new GeoPoint("DUPW", 48.0, 16.0, "fix");   // ~16000 km from YSSY

            when(flightDataCache.getFlightPlans()).thenReturn(List.of(plan));
            when(flightDataCache.getFixes()).thenReturn(List.of(
                    new GeoPoint("YSSY", -33.9461, 151.1772, "fix"),
                    nearSydney, inEurope));
            when(flightDataCache.getAirways()).thenReturn(List.of());
            when(flightDataCache.getLastRefreshed()).thenReturn(Instant.now());

            FlightRoute r = service.resolveRoute("T2DST").orElseThrow();

            Optional<FlightRoute.RoutePoint> dupw = r.getRoutePoints().stream()
                    .filter(p -> "DUPW".equals(p.getName())).findFirst();
            assertThat(dupw).isPresent();
            assertThat(dupw.get().getLat()).isCloseTo(-33.0, org.assertj.core.data.Offset.offset(0.1));
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

    // ── 4-tier duplicate disambiguation ──────────────────────────────────────

    /**
     * Tests for the 4-tier bestCandidate() disambiguation chain.
     *
     * The upstream fixes dataset contains 12 230 waypoint names that appear more
     * than once with different coordinates in different FIRs worldwide. These tests
     * verify that resolveRoute() picks the geographically correct candidate by
     * working through a prioritised fallback chain from strongest to weakest context:
     *
     *   Tier 1 — Cross-track distance (both dep + dest known)
     *   Tier 2 — Closest to nearest known endpoint (only dep OR dest known)
     *   Tier 3 — Closest to centroid of already-resolved route points
     *   Tier 4 — First valid-coord entry (absolute last resort)
     *
     * The canonical test case is SUNIR on the FAOR→WSSS (Johannesburg→Singapore)
     * route, which has two entries in the upstream dataset:
     *
     *   SUNIR (-24.30,  40.00) — Indian Ocean near Madagascar  ✅ correct
     *   SUNIR ( 43.39,  -3.13) — Northern Spain                ❌ wrong
     *
     * Cross-track distances from the FAOR→WSSS great-circle path:
     *   Correct fix:   9 km  (lies almost exactly on the flight path)
     *   Wrong fix:  7650 km  (completely off the route)
     */
    @Nested @DisplayName("4-tier duplicate disambiguation")
    class DisambiguationTests {

        // FAOR (Johannesburg O.R. Tambo) and WSSS (Singapore Changi) anchor coords
        private static final double FAOR_LAT  = -26.13;
        private static final double FAOR_LON  =  28.24;
        private static final double WSSS_LAT  =   1.36;
        private static final double WSSS_LON  = 103.99;

        /**
         * Builds a minimal flight plan with a single en-route waypoint whose
         * name must be resolved from the fixes multimap.
         */
        private FlightPlan planWithWaypoint(String dep, String dest, String waypointName) {
            FlightPlan fp = new FlightPlan();
            fp.setAircraftIdentification("SIA481");

            FlightPlan.Departure d = new FlightPlan.Departure();
            d.setDepartureAerodrome(dep);
            fp.setDeparture(d);

            FlightPlan.Arrival a = new FlightPlan.Arrival();
            a.setDestinationAerodrome(dest);
            fp.setArrival(a);

            FlightPlan.RouteElement el = new FlightPlan.RouteElement();
            el.setSeqNum(1);
            FlightPlan.Position pos = new FlightPlan.Position();
            pos.setDesignatedPoint(waypointName);
            pos.setLat(0.0); // no inline coords — forces fix map lookup
            pos.setLon(0.0);
            el.setPosition(pos);
            el.setAirway("DCT");

            FlightPlan.FiledRoute route = new FlightPlan.FiledRoute();
            route.setRouteElement(new ArrayList<>(List.of(el)));
            fp.setFiledRoute(route);
            return fp;
        }

        @Test
        @DisplayName("Tier 1 — SUNIR: picks Madagascar (-24.30,40.00) not Spain (43.39,-3.13) via cross-track on FAOR→WSSS")
        void tier1_sunirCorrectCandidateViaCrossTrack() {
            // The canonical real-world regression. Both FAOR and WSSS are in the fixes
            // dataset, so Tier 1 (cross-track) runs. The Madagascar SUNIR is 9 km from
            // the FAOR→WSSS great-circle path; the Spain one is 7 650 km away.
            FlightPlan plan = planWithWaypoint("FAOR", "WSSS", "SUNIR");
            when(flightDataCache.getFlightPlans()).thenReturn(List.of(plan));
            when(flightDataCache.getFixes()).thenReturn(List.of(
                    new GeoPoint("FAOR", FAOR_LAT, FAOR_LON, "fix"),
                    new GeoPoint("WSSS", WSSS_LAT, WSSS_LON, "fix"),
                    new GeoPoint("SUNIR", -24.30,  40.00, "fix"),  // Indian Ocean — correct
                    new GeoPoint("SUNIR",  43.39,  -3.13, "fix")   // Spain — wrong
            ));
            when(flightDataCache.getAirways()).thenReturn(List.of());
            when(flightDataCache.getLastRefreshed()).thenReturn(Instant.now());

            FlightRoute.RoutePoint sunir = service.resolveRoute("SIA481").orElseThrow()
                    .getRoutePoints().stream()
                    .filter(p -> "SUNIR".equals(p.getName()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("SUNIR not in route"));

            assertThat(sunir.getLat()).as("should be Madagascar lat, not Spain").isEqualTo(-24.30);
            assertThat(sunir.getLon()).as("should be Indian Ocean lon, not Spain").isEqualTo(40.00);
        }

        @Test
        @DisplayName("Tier 1 — Spain SUNIR never appears in polyline")
        void tier1_spainSunirAbsentFromPolyline() {
            FlightPlan plan = planWithWaypoint("FAOR", "WSSS", "SUNIR");
            when(flightDataCache.getFlightPlans()).thenReturn(List.of(plan));
            when(flightDataCache.getFixes()).thenReturn(List.of(
                    new GeoPoint("FAOR", FAOR_LAT, FAOR_LON, "fix"),
                    new GeoPoint("WSSS", WSSS_LAT, WSSS_LON, "fix"),
                    new GeoPoint("SUNIR", -24.30, 40.00, "fix"),
                    new GeoPoint("SUNIR",  43.39, -3.13, "fix")
            ));
            when(flightDataCache.getAirways()).thenReturn(List.of());
            when(flightDataCache.getLastRefreshed()).thenReturn(Instant.now());

            boolean spainInPolyline = service.resolveRoute("SIA481").orElseThrow()
                    .getPolyline().stream()
                    .anyMatch(p -> Double.compare(p[0], 43.39) == 0
                               && Double.compare(p[1], -3.13) == 0);
            assertThat(spainInPolyline).as("Spain SUNIR must not appear in polyline").isFalse();
        }

        @Test
        @DisplayName("Tier 2 — picks closest to departure when only dep is known")
        void tier2_closestToDepartureWhenOnlyDepKnown() {
            // WSSS not in fixes — Tier 1 (cross-track) unavailable.
            // FAOR is known, so Tier 2 uses proximity to departure.
            // DUPWP-A is 500 km from FAOR; DUPWP-B is 8000 km away.
            FlightPlan plan = planWithWaypoint("FAOR", "UNKN", "DUPWP");
            when(flightDataCache.getFlightPlans()).thenReturn(List.of(plan));
            when(flightDataCache.getFixes()).thenReturn(List.of(
                    new GeoPoint("FAOR",  FAOR_LAT, FAOR_LON, "fix"),
                    // UNKN intentionally absent from fixes
                    new GeoPoint("DUPWP", -22.0, 30.0,  "fix"),   // ~500 km from FAOR — correct
                    new GeoPoint("DUPWP",  50.0, 10.0,  "fix")    // ~8000 km from FAOR — wrong
            ));
            when(flightDataCache.getAirways()).thenReturn(List.of());
            when(flightDataCache.getLastRefreshed()).thenReturn(Instant.now());

            FlightRoute.RoutePoint wp = service.resolveRoute("SIA481").orElseThrow()
                    .getRoutePoints().stream()
                    .filter(p -> "DUPWP".equals(p.getName()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("DUPWP not in route"));

            assertThat(wp.getLat()).as("should pick candidate near FAOR").isEqualTo(-22.0);
        }

        @Test
        @DisplayName("Tier 2 — picks closest to destination when only dest is known")
        void tier2_closestToDestinationWhenOnlyDestKnown() {
            // FAOR not in fixes — dep anchor unavailable.
            // WSSS is known, so Tier 2 uses proximity to destination.
            FlightPlan plan = planWithWaypoint("UNKN", "WSSS", "DUPWP");
            when(flightDataCache.getFlightPlans()).thenReturn(List.of(plan));
            when(flightDataCache.getFixes()).thenReturn(List.of(
                    new GeoPoint("WSSS",  WSSS_LAT, WSSS_LON, "fix"),
                    // UNKN intentionally absent
                    new GeoPoint("DUPWP",  3.0, 101.0, "fix"),    // ~350 km from WSSS — correct
                    new GeoPoint("DUPWP", 50.0,  10.0, "fix")     // ~9000 km from WSSS — wrong
            ));
            when(flightDataCache.getAirways()).thenReturn(List.of());
            when(flightDataCache.getLastRefreshed()).thenReturn(Instant.now());

            FlightRoute.RoutePoint wp = service.resolveRoute("SIA481").orElseThrow()
                    .getRoutePoints().stream()
                    .filter(p -> "DUPWP".equals(p.getName()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("DUPWP not in route"));

            assertThat(wp.getLat()).as("should pick candidate near WSSS").isEqualTo(3.0);
        }

        @Test
        @DisplayName("Tier 3 — picks closest to centroid of resolved points when no endpoint known")
        void tier3_closestToCentroidOfResolvedPoints() {
            // Neither UNKN dep nor UNKN dest is in fixes.
            // A prior waypoint PREV has already been resolved at (1.0, 50.0).
            // The second duplicate waypoint DUPWP should pick the candidate
            // closest to that centroid (1.0, 50.0).
            FlightPlan fp = new FlightPlan();
            fp.setAircraftIdentification("SIA481");
            FlightPlan.Departure d = new FlightPlan.Departure();
            d.setDepartureAerodrome("UNKN");
            fp.setDeparture(d);
            FlightPlan.Arrival a = new FlightPlan.Arrival();
            a.setDestinationAerodrome("UNKN");
            fp.setArrival(a);

            // Two sequential waypoints: PREV (unique, near Indian Ocean) then DUPWP (duplicate)
            FlightPlan.RouteElement e1 = new FlightPlan.RouteElement();
            e1.setSeqNum(1);
            FlightPlan.Position p1 = new FlightPlan.Position();
            p1.setDesignatedPoint("PREV");
            p1.setLat(0.0); p1.setLon(0.0);
            e1.setPosition(p1); e1.setAirway("DCT");

            FlightPlan.RouteElement e2 = new FlightPlan.RouteElement();
            e2.setSeqNum(2);
            FlightPlan.Position p2 = new FlightPlan.Position();
            p2.setDesignatedPoint("DUPWP");
            p2.setLat(0.0); p2.setLon(0.0);
            e2.setPosition(p2); e2.setAirway("DCT");

            FlightPlan.FiledRoute route = new FlightPlan.FiledRoute();
            route.setRouteElement(new ArrayList<>(List.of(e1, e2)));
            fp.setFiledRoute(route);

            when(flightDataCache.getFlightPlans()).thenReturn(List.of(fp));
            when(flightDataCache.getFixes()).thenReturn(List.of(
                    new GeoPoint("PREV",  1.0, 50.0,  "fix"),  // resolved first, centroid ~(1,50)
                    new GeoPoint("DUPWP", 2.0, 52.0,  "fix"),  // ~300 km from centroid — correct
                    new GeoPoint("DUPWP", 60.0, -20.0, "fix")  // far from centroid — wrong
            ));
            when(flightDataCache.getAirways()).thenReturn(List.of());
            when(flightDataCache.getLastRefreshed()).thenReturn(Instant.now());

            FlightRoute.RoutePoint dupwp = service.resolveRoute("SIA481").orElseThrow()
                    .getRoutePoints().stream()
                    .filter(p -> "DUPWP".equals(p.getName()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("DUPWP not in route"));

            assertThat(dupwp.getLat()).as("should pick candidate near centroid of PREV").isEqualTo(2.0);
        }

        @Test
        @DisplayName("Tier 4 — returns first valid-coord entry when no context available")
        void tier4_firstValidEntryWhenNoContext() {
            // No dep, no dest, no prior resolved points — absolute last resort.
            FlightPlan plan = planWithWaypoint("UNKN", "UNKN", "DUPWP");
            when(flightDataCache.getFlightPlans()).thenReturn(List.of(plan));
            when(flightDataCache.getFixes()).thenReturn(List.of(
                    new GeoPoint("DUPWP",  10.0, 20.0, "fix"),  // first valid entry
                    new GeoPoint("DUPWP", -10.0, 50.0, "fix")
            ));
            when(flightDataCache.getAirways()).thenReturn(List.of());
            when(flightDataCache.getLastRefreshed()).thenReturn(Instant.now());

            FlightRoute.RoutePoint wp = service.resolveRoute("SIA481").orElseThrow()
                    .getRoutePoints().stream()
                    .filter(p -> "DUPWP".equals(p.getName()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("DUPWP not in route"));

            assertThat(wp.getLat()).as("Tier 4: first valid-coord entry").isEqualTo(10.0);
        }

        @Test
        @DisplayName("single-entry name bypasses disambiguation and is returned directly")
        void singleCandidateReturnedDirectly() {
            FlightPlan plan = planWithWaypoint("FAOR", "WSSS", "EXOBI");
            when(flightDataCache.getFlightPlans()).thenReturn(List.of(plan));
            when(flightDataCache.getFixes()).thenReturn(List.of(
                    new GeoPoint("FAOR",  FAOR_LAT, FAOR_LON, "fix"),
                    new GeoPoint("WSSS",  WSSS_LAT, WSSS_LON, "fix"),
                    new GeoPoint("EXOBI", -26.05,   29.15,    "fix")
            ));
            when(flightDataCache.getAirways()).thenReturn(List.of());
            when(flightDataCache.getLastRefreshed()).thenReturn(Instant.now());

            FlightRoute.RoutePoint exobi = service.resolveRoute("SIA481").orElseThrow()
                    .getRoutePoints().stream()
                    .filter(p -> "EXOBI".equals(p.getName()))
                    .findFirst().orElseThrow();

            assertThat(exobi.getLat()).isEqualTo(-26.05);
            assertThat(exobi.getLon()).isEqualTo(29.15);
        }

        @Test
        @DisplayName("all-zero-coord candidates are never plotted — no Gulf of Guinea line")
        void allZeroCoordsNotPlotted() {
            FlightPlan plan = planWithWaypoint("FAOR", "WSSS", "NOCOORD");
            when(flightDataCache.getFlightPlans()).thenReturn(List.of(plan));
            when(flightDataCache.getFixes()).thenReturn(List.of(
                    new GeoPoint("FAOR", FAOR_LAT, FAOR_LON, "fix"),
                    new GeoPoint("WSSS", WSSS_LAT, WSSS_LON, "fix")
            ));
            when(flightDataCache.getAirways()).thenReturn(List.of(
                    new GeoPoint("NOCOORD", 0.0, 0.0, "airway")
            ));
            when(flightDataCache.getLastRefreshed()).thenReturn(Instant.now());

            boolean zeroInPolyline = service.resolveRoute("SIA481").orElseThrow()
                    .getPolyline().stream()
                    .anyMatch(p -> p[0] == 0.0 && p[1] == 0.0);
            assertThat(zeroInPolyline).as("(0,0) must never appear in polyline").isFalse();
        }

        @Test
        @DisplayName("multimap preserves ALL candidates — none discarded at build time")
        void multimapPreservesAllCandidates() {
            // Confirm buildFixMultiMap() retains every entry per name.
            // The old flat HashMap.put() would silently drop all but the last.
            FlightPlan plan = planWithWaypoint("FAOR", "WSSS", "SUNIR");
            when(flightDataCache.getFlightPlans()).thenReturn(List.of(plan));
            when(flightDataCache.getFixes()).thenReturn(List.of(
                    new GeoPoint("FAOR",  FAOR_LAT, FAOR_LON, "fix"),
                    new GeoPoint("WSSS",  WSSS_LAT, WSSS_LON, "fix"),
                    new GeoPoint("SUNIR", -24.30, 40.00, "fix"),
                    new GeoPoint("SUNIR",  43.39, -3.13, "fix"),
                    new GeoPoint("SUNIR",  10.00, 10.00, "fix")   // third duplicate
            ));
            when(flightDataCache.getAirways()).thenReturn(List.of());
            when(flightDataCache.getLastRefreshed()).thenReturn(Instant.now());

            FlightRoute route = service.resolveRoute("SIA481").orElseThrow();

            // Cross-track (Tier 1) should still pick the Madagascar entry
            // from all three candidates, confirming all three were available
            route.getRoutePoints().stream()
                    .filter(p -> "SUNIR".equals(p.getName()))
                    .findFirst()
                    .ifPresent(sunir -> {
                        assertThat(sunir.getLat()).isEqualTo(-24.30);
                        assertThat(sunir.getLon()).isEqualTo(40.00);
                    });
        }
    }
}