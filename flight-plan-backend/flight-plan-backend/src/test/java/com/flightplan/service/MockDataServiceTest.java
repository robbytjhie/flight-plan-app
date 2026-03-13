package com.flightplan.service;

import com.flightplan.model.FlightPlan;
import com.flightplan.model.GeoPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MockDataService")
class MockDataServiceTest {

    private MockDataService service;

    @BeforeEach
    void setUp() { service = new MockDataService(); }

    // ── getMockFlightPlans ────────────────────────────────────────────

    @Nested
    @DisplayName("getMockFlightPlans()")
    class FlightPlansTests {

        @Test @DisplayName("returns exactly 8 flights")
        void returnsEightFlights() {
            assertThat(service.getMockFlightPlans()).hasSize(8);
        }

        @Test @DisplayName("all 8 expected callsigns are present")
        void allCallsignsPresent() {
            Set<String> cs = service.getMockFlightPlans().stream()
                    .map(FlightPlan::getAircraftIdentification).collect(Collectors.toSet());
            assertThat(cs).containsExactlyInAnyOrder(
                    "SIA200","MAS370","CPA101","UAL837","QFA002","EK432","THA669","GIA723");
        }

        @Test @DisplayName("every flight has messageType FPL")
        void allMessageTypeFPL() {
            service.getMockFlightPlans().forEach(fp ->
                assertThat(fp.getMessageType()).isEqualTo("FPL"));
        }

        @Test @DisplayName("every flight has non-blank aircraft identification")
        void allHaveCallsign() {
            service.getMockFlightPlans().forEach(fp ->
                assertThat(fp.getAircraftIdentification()).isNotBlank());
        }

        @Test @DisplayName("every flight has departure aerodrome")
        void allHaveDeparture() {
            service.getMockFlightPlans().forEach(fp -> {
                assertThat(fp.getDeparture()).isNotNull();
                assertThat(fp.getDeparture().getDepartureAerodrome()).isNotBlank();
            });
        }

        @Test @DisplayName("every flight has destination aerodrome")
        void allHaveDestination() {
            service.getMockFlightPlans().forEach(fp -> {
                assertThat(fp.getArrival()).isNotNull();
                assertThat(fp.getArrival().getDestinationAerodrome()).isNotBlank();
            });
        }

        @Test @DisplayName("every flight has aircraft type")
        void allHaveAircraftType() {
            service.getMockFlightPlans().forEach(fp -> {
                assertThat(fp.getAircraft()).isNotNull();
                assertThat(fp.getAircraft().getAircraftType()).isNotBlank();
            });
        }

        @Test @DisplayName("every flight has at least one route element")
        void allHaveRouteElements() {
            service.getMockFlightPlans().forEach(fp -> {
                assertThat(fp.getFiledRoute()).isNotNull();
                assertThat(fp.getFiledRoute().getRouteElement()).isNotEmpty();
            });
        }

        @Test @DisplayName("route elements all have seqNum and position with lat/lon")
        void routeElementsHavePositions() {
            service.getMockFlightPlans().forEach(fp ->
                fp.getFiledRoute().getRouteElement().forEach(el -> {
                    assertThat(el.getSeqNum()).isNotNull();
                    assertThat(el.getPosition()).isNotNull();
                    assertThat(el.getPosition().getLat()).isNotNull();
                    assertThat(el.getPosition().getLon()).isNotNull();
                }));
        }

        @Test @DisplayName("every flight has supplementary info with pilot name")
        void allHaveSupplementary() {
            service.getMockFlightPlans().forEach(fp -> {
                assertThat(fp.getSupplementary()).isNotNull();
                assertThat(fp.getSupplementary().getNameOfPilot()).isNotBlank();
            });
        }

        @Test @DisplayName("all flights have unique IDs")
        void uniqueIds() {
            List<FlightPlan> plans = service.getMockFlightPlans();
            long distinct = plans.stream().map(FlightPlan::getId).distinct().count();
            assertThat(distinct).isEqualTo(plans.size());
        }

        @Test @DisplayName("SIA200 — WSSS→YSSY, A359, operator SIA")
        void sia200Details() {
            FlightPlan sia = service.getMockFlightPlans().stream()
                    .filter(fp -> "SIA200".equals(fp.getAircraftIdentification())).findFirst().orElseThrow();
            assertThat(sia.getDeparture().getDepartureAerodrome()).isEqualTo("WSSS");
            assertThat(sia.getArrival().getDestinationAerodrome()).isEqualTo("YSSY");
            assertThat(sia.getAircraft().getAircraftType()).isEqualTo("A359");
            assertThat(sia.getAircraftOperating()).isEqualTo("SIA");
            assertThat(sia.getFlightType()).isEqualTo("M");
        }

        @Test @DisplayName("EK432 — OMDB→WSSS, A388")
        void ek432Details() {
            FlightPlan ek = service.getMockFlightPlans().stream()
                    .filter(fp -> "EK432".equals(fp.getAircraftIdentification())).findFirst().orElseThrow();
            assertThat(ek.getDeparture().getDepartureAerodrome()).isEqualTo("OMDB");
            assertThat(ek.getArrival().getDestinationAerodrome()).isEqualTo("WSSS");
            assertThat(ek.getAircraft().getAircraftType()).isEqualTo("A388");
        }

        @Test @DisplayName("UAL837 — KSFO→RJAA, B789, S flightType")
        void ual837Details() {
            FlightPlan ual = service.getMockFlightPlans().stream()
                    .filter(fp -> "UAL837".equals(fp.getAircraftIdentification())).findFirst().orElseThrow();
            assertThat(ual.getDeparture().getDepartureAerodrome()).isEqualTo("KSFO");
            assertThat(ual.getArrival().getDestinationAerodrome()).isEqualTo("RJAA");
            assertThat(ual.getFlightType()).isEqualTo("S");
        }

        @Test @DisplayName("GIA723 — uses default waypoints branch (WIII→WSSS)")
        void gia723DefaultBranch() {
            FlightPlan gia = service.getMockFlightPlans().stream()
                    .filter(fp -> "GIA723".equals(fp.getAircraftIdentification())).findFirst().orElseThrow();
            assertThat(gia.getDeparture().getDepartureAerodrome()).isEqualTo("WIII");
            assertThat(gia.getArrival().getDestinationAerodrome()).isEqualTo("WSSS");
        }
    }

    // ── getMockAirways ────────────────────────────────────────────────

    @Nested
    @DisplayName("getMockAirways()")
    class AirwaysTests {

        @Test @DisplayName("returns 17 airways")
        void returnsSeventeenAirways() {
            assertThat(service.getMockAirways()).hasSize(17);
        }

        @Test @DisplayName("every airway has type 'airway'")
        void allTypeAirway() {
            service.getMockAirways().forEach(gp -> assertThat(gp.getType()).isEqualTo("airway"));
        }

        @Test @DisplayName("all airways have valid lat/lon and non-blank name")
        void allValidCoords() {
            service.getMockAirways().forEach(gp -> {
                assertThat(gp.getLat()).isBetween(-90.0, 90.0);
                assertThat(gp.getLon()).isBetween(-180.0, 180.0);
                assertThat(gp.getName()).isNotBlank();
            });
        }

        @Test @DisplayName("known airways A576, M635, G579 are present")
        void knownAirwaysPresent() {
            Set<String> names = service.getMockAirways().stream()
                    .map(GeoPoint::getName).collect(Collectors.toSet());
            assertThat(names).contains("A576", "M635", "G579");
        }
    }

    // ── getMockFixes ──────────────────────────────────────────────────

    @Nested
    @DisplayName("getMockFixes()")
    class FixesTests {

        @Test @DisplayName("returns 45 fix points")
        void returnsFortyFiveFixes() {
            assertThat(service.getMockFixes()).hasSize(45);
        }

        @Test @DisplayName("every fix has type 'fix'")
        void allTypeFix() {
            service.getMockFixes().forEach(gp -> assertThat(gp.getType()).isEqualTo("fix"));
        }

        @Test @DisplayName("all fixes have valid lat/lon and non-blank name")
        void allValidCoords() {
            service.getMockFixes().forEach(gp -> {
                assertThat(gp.getLat()).isBetween(-90.0, 90.0);
                assertThat(gp.getLon()).isBetween(-180.0, 180.0);
                assertThat(gp.getName()).isNotBlank();
            });
        }

        @Test @DisplayName("major airports are present in fixes")
        void majorAirportsPresent() {
            Set<String> names = service.getMockFixes().stream()
                    .map(GeoPoint::getName).collect(Collectors.toSet());
            assertThat(names).contains("WSSS","WMKK","VHHH","YSSY","OMDB","EGLL","KSFO","RJAA","ZBAA");
        }

        @Test @DisplayName("WSSS has exact coordinates 1.3644, 103.9915")
        void wsssExactCoords() {
            GeoPoint wsss = service.getMockFixes().stream()
                    .filter(gp -> "WSSS".equals(gp.getName())).findFirst().orElseThrow();
            assertThat(wsss.getLat()).isEqualTo(1.3644);
            assertThat(wsss.getLon()).isEqualTo(103.9915);
        }

        @Test @DisplayName("EGLL has negative longitude (London)")
        void egllNegativeLon() {
            GeoPoint egll = service.getMockFixes().stream()
                    .filter(gp -> "EGLL".equals(gp.getName())).findFirst().orElseThrow();
            assertThat(egll.getLon()).isNegative();
        }
    }
}
