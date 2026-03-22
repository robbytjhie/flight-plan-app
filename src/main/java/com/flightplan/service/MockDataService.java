package com.flightplan.service;

import com.flightplan.model.FlightPlan;
import com.flightplan.model.GeoPoint;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Provides realistic mock data that mirrors the external API structure.
 * Replace calls here with real WebClient calls once you have API credentials.
 */
@Service
public class MockDataService {

    public List<FlightPlan> getMockFlightPlans() {
        List<FlightPlan> plans = new ArrayList<>();
        plans.add(buildFlight("SIA200", "WSSS", "YSSY", "A359", "SIA", "M"));
        plans.add(buildFlight("MAS370", "WMKK", "ZBAA", "B772", "MAS", "S"));
        plans.add(buildFlight("CPA101", "VHHH", "WSSS", "A333", "CPA", "M"));
        plans.add(buildFlight("UAL837", "KSFO", "RJAA", "B789", "UAL", "S"));
        plans.add(buildFlight("QFA002", "YSSY", "EGLL", "A388", "QFA", "M"));
        plans.add(buildFlight("EK432", "OMDB", "WSSS", "A388", "EKA", "S"));
        plans.add(buildFlight("THA669", "VTBS", "WSSS", "B789", "THA", "M"));
        plans.add(buildFlight("GIA723", "WIII", "WSSS", "B738", "GIA", "S"));
        return plans;
    }

    private FlightPlan buildFlight(String callsign, String dep, String dest,
                                    String acType, String operator, String flightType) {
        FlightPlan fp = new FlightPlan();
        fp.setId(UUID.randomUUID().toString().substring(0, 20));
        fp.setAircraftIdentification(callsign);
        fp.setMessageType("FPL");
        fp.setFlightType(flightType);
        fp.setAircraftOperating(operator);
        fp.setFlightPlanOriginator(operator);
        fp.setSpecialHandlingReason("FFR");
        fp.setRemark("ACASII EQUIPPED TCAS");
        fp.setGufi(UUID.randomUUID().toString());
        fp.setGufiOriginator(operator);
        fp.setSrc("AFTN");
        fp.setLastUpdatedTimeStamp("2024-01-15T08:30:00.000Z");

        FlightPlan.Departure departure = new FlightPlan.Departure();
        departure.setDepartureAerodrome(dep);
        departure.setEstimatedOffBLockTime("08:30:00");
        departure.setRunwayDirection("02L");
        fp.setDeparture(departure);

        FlightPlan.Arrival arrival = new FlightPlan.Arrival();
        arrival.setDestinationAerodrome(dest);
        arrival.setAlternativeAerodrome(List.of(dep));
        arrival.setArrivalRunwayDirection("02R");
        fp.setArrival(arrival);

        FlightPlan.Aircraft aircraft = new FlightPlan.Aircraft();
        aircraft.setAircraftType(acType);
        aircraft.setAircraftRegistration("9V-S" + callsign.substring(0, 2));
        aircraft.setWakeTurbulence("H");
        aircraft.setNumberOfAircraft(1);
        fp.setAircraft(aircraft);

        fp.setFiledRoute(buildRouteForFlight(callsign, dep, dest));

        FlightPlan.Supplementary supp = new FlightPlan.Supplementary();
        supp.setTotalNumberOfPeople("350");
        supp.setFuelEnduranceTime("12H00M");
        supp.setNameOfPilot("CAPT SMITH");
        fp.setSupplementary(supp);

        return fp;
    }

    private FlightPlan.FiledRoute buildRouteForFlight(String callsign, String dep, String dest) {
        FlightPlan.FiledRoute route = new FlightPlan.FiledRoute();
        List<FlightPlan.RouteElement> elements = new ArrayList<>();

        // Route waypoints per flight
        List<String[]> waypoints = getWaypointsForRoute(callsign);
        for (int i = 0; i < waypoints.size(); i++) {
            String[] wp = waypoints.get(i);
            FlightPlan.RouteElement el = new FlightPlan.RouteElement();
            el.setSeqNum(i + 1);
            FlightPlan.Position pos = new FlightPlan.Position();
            pos.setDesignatedPoint(wp[0]);
            pos.setLat(Double.parseDouble(wp[1]));
            pos.setLon(Double.parseDouble(wp[2]));
            el.setPosition(pos);
            el.setAirway(wp.length > 3 ? wp[3] : "DCT");
            el.setAirwayType("NAMED");
            el.setChangeSpeed("480 N");
            el.setChangeLevel("350 F");
            elements.add(el);
        }
        route.setRouteElement(elements);
        return route;
    }

    private List<String[]> getWaypointsForRoute(String callsign) {
        return switch (callsign) {
            // Do not repeat departure ICAO as the first route row — resolveRoute already seeds dep.
            case "SIA200" -> List.of(
                    new String[]{"PARDI", "1.10", "104.20", "A576"},
                    new String[]{"BATAM", "1.12", "104.04", "A576"},
                    new String[]{"RIGVU", "-0.50", "104.60", "M635"},
                    new String[]{"PALAP", "-2.30", "107.20", "M635"},
                    new String[]{"LEMAX", "-5.50", "111.30", "G579"},
                    new String[]{"TAKAS", "-8.20", "115.50", "G579"},
                    new String[]{"SCOTT", "-12.80", "121.10", "H66"},
                    new String[]{"YSSY", "-33.9461", "151.1772", "DCT"}
            );
            case "MAS370" -> List.of(
                    new String[]{"IKELA", "4.00", "103.20", "M646"},
                    new String[]{"IGARI", "6.93", "103.58", "N571"},
                    new String[]{"ALBOX", "8.30", "108.40", "M646"},
                    new String[]{"BITOD", "10.50", "108.10", "N571"},
                    new String[]{"VICHY", "15.20", "112.30", "A1"},
                    new String[]{"RAMBO", "20.10", "116.50", "A1"},
                    new String[]{"SANLI", "25.80", "120.40", "G591"},
                    new String[]{"ZBAA", "40.0799", "116.6031", "DCT"}
            );
            case "CPA101" -> List.of(
                    new String[]{"MARPA", "19.50", "112.80", "B330"},
                    new String[]{"ELATO", "15.20", "109.50", "B330"},
                    new String[]{"ANITO", "10.30", "107.20", "A1"},
                    new String[]{"REMES", "5.50", "105.80", "A1"},
                    new String[]{"WSSS", "1.3644", "103.9915", "DCT"}
            );
            case "UAL837" -> List.of(
                    new String[]{"KSEA1", "40.00", "-145.00", "NOPAC"},
                    new String[]{"ADAK1", "51.88", "176.65", "NOPAC"},
                    new String[]{"NIPPI", "46.00", "160.00", "A590"},
                    new String[]{"NANAC", "38.00", "148.00", "A590"},
                    new String[]{"RJAA", "35.7647", "140.3864", "DCT"}
            );
            case "QFA002" -> List.of(
                    new String[]{"TESAT", "-29.50", "148.20", "H65"},
                    new String[]{"ENTRA", "-22.10", "139.50", "H65"},
                    new String[]{"MIPAK", "-12.50", "128.80", "M635"},
                    new String[]{"MURDA", "0.50", "107.30", "M635"},
                    new String[]{"ITUKU", "8.30", "97.50", "B466"},
                    new String[]{"PARDI", "15.00", "80.20", "B466"},
                    new String[]{"OREMA", "25.30", "56.50", "P570"},
                    new String[]{"EGLL", "51.4775", "-0.4614", "DCT"}
            );
            case "EK432" -> List.of(
                    new String[]{"DESDI", "15.00", "65.50", "P570"},
                    new String[]{"AGOSA", "8.30", "75.20", "P570"},
                    new String[]{"OPAMO", "3.50", "85.10", "M300"},
                    new String[]{"MURDA", "0.50", "95.80", "M300"},
                    new String[]{"WSSS", "1.3644", "103.9915", "DCT"}
            );
            case "THA669" -> List.of(
                    new String[]{"ATMAP", "10.50", "102.10", "M752"},
                    new String[]{"ANOKA", "7.20", "103.50", "M752"},
                    new String[]{"PARDI", "4.30", "103.80", "A576"},
                    new String[]{"WSSS", "1.3644", "103.9915", "DCT"}
            );
            default -> List.of(
                    new String[]{"DUBSA", "-4.50", "105.80", "W7"},
                    new String[]{"RIGVU", "-2.80", "104.90", "W7"},
                    new String[]{"WSSS", "1.3644", "103.9915", "DCT"}
            );
        };
    }

    public List<GeoPoint> getMockAirways() {
        List<GeoPoint> airways = new ArrayList<>();
        // Key Asian airways
        String[][] data = {
                {"A576", "1.50", "104.10"},
                {"M635", "-1.20", "106.50"},
                {"G579", "-6.80", "113.20"},
                {"H66", "-13.50", "122.80"},
                {"B330", "18.20", "111.50"},
                {"M646", "6.20", "105.80"},
                {"N571", "9.50", "106.20"},
                {"A1", "13.50", "109.80"},
                {"B466", "12.00", "90.50"},
                {"P570", "20.00", "70.00"},
                {"M300", "5.00", "90.00"},
                {"M752", "9.50", "101.80"},
                {"W7", "-3.50", "105.30"},
                {"A590", "42.00", "155.00"},
                {"NOPAC", "48.00", "-160.00"},
                {"G591", "28.50", "118.50"},
                {"H65", "-25.00", "145.00"}
        };
        for (String[] d : data) {
            airways.add(new GeoPoint(d[0], Double.parseDouble(d[1]), Double.parseDouble(d[2]), "airway"));
        }
        return airways;
    }

    public List<GeoPoint> getMockFixes() {
        List<GeoPoint> fixes = new ArrayList<>();
        // Major airports and waypoints
        String[][] data = {
                {"WSSS", "1.3644", "103.9915"},
                {"WMKK", "2.7456", "101.7099"},
                {"VHHH", "22.3080", "113.9185"},
                {"VTBS", "13.6811", "100.7470"},
                {"WIII", "-6.1275", "106.6537"},
                {"YSSY", "-33.9461", "151.1772"},
                {"ZBAA", "40.0799", "116.6031"},
                {"RJAA", "35.7647", "140.3864"},
                {"EGLL", "51.4775", "-0.4614"},
                {"OMDB", "25.2528", "55.3644"},
                {"KSFO", "37.6213", "-122.3790"},
                {"PARDI", "1.10", "104.20"},
                {"BATAM", "1.12", "104.04"},
                {"RIGVU", "-0.50", "104.60"},
                {"PALAP", "-2.30", "107.20"},
                {"LEMAX", "-5.50", "111.30"},
                {"TAKAS", "-8.20", "115.50"},
                {"SCOTT", "-12.80", "121.10"},
                {"IKELA", "4.00", "103.20"},
                {"ALBOX", "8.30", "108.40"},
                {"IGARI", "6.93", "103.58"},
                {"BITOD", "10.50", "108.10"},
                {"VICHY", "15.20", "112.30"},
                {"RAMBO", "20.10", "116.50"},
                {"SANLI", "25.80", "120.40"},
                {"MARPA", "19.50", "112.80"},
                {"ELATO", "15.20", "109.50"},
                {"ANITO", "10.30", "107.20"},
                {"REMES", "5.50", "105.80"},
                {"NIPPI", "46.00", "160.00"},
                {"NANAC", "38.00", "148.00"},
                {"TESAT", "-29.50", "148.20"},
                {"ENTRA", "-22.10", "139.50"},
                {"MIPAK", "-12.50", "128.80"},
                {"MURDA", "0.50", "107.30"},
                {"ITUKU", "8.30", "97.50"},
                {"OREMA", "25.30", "56.50"},
                {"DESDI", "15.00", "65.50"},
                {"AGOSA", "8.30", "75.20"},
                {"OPAMO", "3.50", "85.10"},
                {"ATMAP", "10.50", "102.10"},
                {"ANOKA", "7.20", "103.50"},
                {"DUBSA", "-4.50", "105.80"},
                {"KAT", "15.00", "103.00"},
                {"WSSL", "1.42", "103.87"}
        };
        for (String[] d : data) {
            fixes.add(new GeoPoint(d[0], Double.parseDouble(d[1]), Double.parseDouble(d[2]), "fix"));
        }
        return fixes;
    }
}
