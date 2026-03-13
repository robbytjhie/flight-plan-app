package com.flightplan.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("FlightRoute")
class FlightRouteTest {

    @Nested
    @DisplayName("FlightRoute")
    class FlightRouteMainTests {

        @Test
        @DisplayName("no-args constructor creates all-null object")
        void noArgsConstructor() {
            FlightRoute r = new FlightRoute();
            assertThat(r.getCallsign()).isNull();
            assertThat(r.getDepartureAerodrome()).isNull();
            assertThat(r.getDestinationAerodrome()).isNull();
            assertThat(r.getAircraftType()).isNull();
            assertThat(r.getFlightType()).isNull();
            assertThat(r.getRoutePoints()).isNull();
            assertThat(r.getPolyline()).isNull();
        }

        @Test
        @DisplayName("all-args constructor sets every field")
        void allArgsConstructor() {
            var pts = List.of(new FlightRoute.RoutePoint("WSSS", 1.36, 103.99, "airport", 0));
            var poly = List.of(new double[]{1.36, 103.99});
            FlightRoute r = new FlightRoute("SIA200", "WSSS", "YSSY", "A359", "M", pts, poly);
            assertThat(r.getCallsign()).isEqualTo("SIA200");
            assertThat(r.getDepartureAerodrome()).isEqualTo("WSSS");
            assertThat(r.getDestinationAerodrome()).isEqualTo("YSSY");
            assertThat(r.getAircraftType()).isEqualTo("A359");
            assertThat(r.getFlightType()).isEqualTo("M");
            assertThat(r.getRoutePoints()).hasSize(1);
            assertThat(r.getPolyline()).hasSize(1);
        }

        @Test
        @DisplayName("setters mutate every field")
        void setters() {
            FlightRoute r = new FlightRoute();
            r.setCallsign("MAS370");
            r.setDepartureAerodrome("WMKK");
            r.setDestinationAerodrome("ZBAA");
            r.setAircraftType("B772");
            r.setFlightType("S");
            r.setRoutePoints(List.of());
            r.setPolyline(List.of());
            assertThat(r.getCallsign()).isEqualTo("MAS370");
            assertThat(r.getDepartureAerodrome()).isEqualTo("WMKK");
            assertThat(r.getDestinationAerodrome()).isEqualTo("ZBAA");
            assertThat(r.getAircraftType()).isEqualTo("B772");
            assertThat(r.getFlightType()).isEqualTo("S");
        }

        @Test
        @DisplayName("equals is true for identical objects")
        void equals() {
            FlightRoute a = new FlightRoute("SIA200", "WSSS", "YSSY", "A359", "M", List.of(), List.of());
            FlightRoute b = new FlightRoute("SIA200", "WSSS", "YSSY", "A359", "M", List.of(), List.of());
            assertThat(a).isEqualTo(b);
        }

        @Test
        @DisplayName("hashCode consistent for equal objects")
        void hashCode_ShouldBeIdentical_WhenObjectsAreEqual() {
            FlightRoute a = new FlightRoute("SIA200", "WSSS", "YSSY", "A359", "M", List.of(), List.of());
            FlightRoute b = new FlightRoute("SIA200", "WSSS", "YSSY", "A359", "M", List.of(), List.of());
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }
    }

    @Nested
    @DisplayName("RoutePoint")
    class RoutePointTests {

        @Test
        @DisplayName("no-args constructor creates zero-valued object")
        void noArgsConstructor() {
            FlightRoute.RoutePoint rp = new FlightRoute.RoutePoint();
            assertThat(rp.getName()).isNull();
            assertThat(rp.getLat()).isZero();
            assertThat(rp.getLon()).isZero();
            assertThat(rp.getType()).isNull();
            assertThat(rp.getSeqNum()).isNull();
        }

        @Test
        @DisplayName("all-args constructor sets every field")
        void allArgsConstructor() {
            FlightRoute.RoutePoint rp = new FlightRoute.RoutePoint("WSSS", 1.3644, 103.9915, "airport", 1);
            assertThat(rp.getName()).isEqualTo("WSSS");
            assertThat(rp.getLat()).isEqualTo(1.3644);
            assertThat(rp.getLon()).isEqualTo(103.9915);
            assertThat(rp.getType()).isEqualTo("airport");
            assertThat(rp.getSeqNum()).isEqualTo(1);
        }

        @Test
        @DisplayName("setters mutate all fields")
        void setters() {
            FlightRoute.RoutePoint rp = new FlightRoute.RoutePoint();
            rp.setName("PARDI");
            rp.setLat(1.10);
            rp.setLon(104.20);
            rp.setType("waypoint");
            rp.setSeqNum(5);
            assertThat(rp.getName()).isEqualTo("PARDI");
            assertThat(rp.getLat()).isEqualTo(1.10);
            assertThat(rp.getLon()).isEqualTo(104.20);
            assertThat(rp.getType()).isEqualTo("waypoint");
            assertThat(rp.getSeqNum()).isEqualTo(5);
        }

        @Test
        @DisplayName("equals true for identical RoutePoints")
        void equals() {
            FlightRoute.RoutePoint a = new FlightRoute.RoutePoint("WSSS", 1.36, 103.99, "airport", 0);
            FlightRoute.RoutePoint b = new FlightRoute.RoutePoint("WSSS", 1.36, 103.99, "airport", 0);
            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }
    }
}
