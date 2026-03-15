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

        FlightPlan.RouteElement e2 = new FlightPlan.RouteElement();
        e2.setSeqNum(2);
        FlightPlan.Position p2 = new FlightPlan.Position();
        p2.setDesignatedPoint("PARDI");
        p2.setLat(1.10); p2.setLon(104.20);
        e2.setPosition(p2);

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
}
