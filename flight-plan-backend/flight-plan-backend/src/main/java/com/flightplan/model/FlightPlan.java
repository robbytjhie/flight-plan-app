package com.flightplan.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class FlightPlan {

    @JsonProperty("_id")
    private String id;
    private String messageType;
    private String aircraftIdentification;
    private String flightType;
    private String specialHandlingReason;
    private String aircraftOperating;
    private String flightPlanOriginator;
    private String remark;
    private Departure departure;
    private Arrival arrival;
    private Aircraft aircraft;
    private FiledRoute filedRoute;
    private EnRoute enRoute;
    private Supplementary supplementary;
    private String gufi;
    private String gufiOriginator;
    private String lastUpdatedTimeStamp;
    private String src;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Departure {
        private String departureAerodrome;
        private String dateOfFlight;
        private String estimatedOffBLockTime;
        private String actualTimeOfDeparture;
        private String runwayDirection;
        private String departureAlternativeAerodrome;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Arrival {
        private String destinationAerodrome;
        private List<String> alternativeAerodrome;
        private String arrivalRunwayDirection;
        private String arrivalAerodrome;
        private String actualTimeOfArrival;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Aircraft {
        private String aircraftRegistration;
        private String aircraftAddress;
        private String aircraftType;
        private String wakeTurbulence;
        private Integer numberOfAircraft;
        private String aircraftApproachCategory;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FiledRoute {
        private List<RouteElement> routeElement;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RouteElement {
        private Integer seqNum;
        private Position position;
        private String airway;
        private String airwayType;
        private String changeSpeed;
        private String changeLevel;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Position {
        private Double lat;
        private Double lon;
        private String designatedPoint;
        private String bearing;
        private String distance;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EnRoute {
        private String currentModeACode;
        private String alternativeEnRouteAerodrome;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Supplementary {
        private String fuelEnduranceTime;
        private String totalNumberOfPeople;
        private String nameOfPilot;
    }
}
