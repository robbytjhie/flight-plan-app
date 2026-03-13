package com.flightplan;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * IM8 S4: @EnableScheduling activates the leader-elected cache refresh job
 * defined in {@link com.flightplan.service.FlightDataCache}.
 */
@SpringBootApplication
@EnableScheduling
public class FlightPlanApplication {
    public static void main(String[] args) {
        SpringApplication.run(FlightPlanApplication.class, args);
    }
}
