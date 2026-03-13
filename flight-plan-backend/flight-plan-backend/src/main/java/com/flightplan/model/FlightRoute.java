package com.flightplan.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class FlightRoute {
    private String callsign;
    private String departureAerodrome;
    private String destinationAerodrome;
    private String aircraftType;
    private String flightType;
    private List<RoutePoint> routePoints;
    private List<double[]> polyline; // [[lat, lon], ...]

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RoutePoint {
        private String name;
        private double lat;
        private double lon;
        private String type;    // "waypoint", "airway", "airport"
        private Integer seqNum;
    }
}
