package com.flightplan.service;

import com.flightplan.model.FlightPlan;
import com.flightplan.model.GeoPoint;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * PROD profile implementation of {@link DataFetchStrategy}.
 *
 * Makes live HTTP calls to the upstream Flight Manager API at
 * https://api.swimapisg.info.  The apikey header is injected automatically
 * by the WebClient configured in {@link com.flightplan.config.AppConfig}.
 *
 * Endpoints called:
 *   GET /flight-manager/displayAll    — all current flight plans
 *   GET /geopoints/list/airways       — airway geopoints
 *   GET /geopoints/list/fixes         — fix / waypoint geopoints
 *
 * On any upstream failure the method logs the error and returns an empty list
 * so the application remains functional with stale cached data.
 *
 * Active when:  spring.profiles.active=prod
 * Inactive when: spring.profiles.active=dev
 *
 * IM8 S5 (Data in Transit): TLS enforced via the WebClient SSL context
 * configured in {@link com.flightplan.config.AppConfig}.
 */
@Service
@Profile("prod")
@Slf4j
@RequiredArgsConstructor
public class LiveDataFetchStrategy implements DataFetchStrategy {

    private final WebClient flightApiWebClient;

    private static final String PATH_FLIGHT_PLANS = "/flight-manager/displayAll";
    private static final String PATH_AIRWAYS      = "/geopoints/list/airways";
    private static final String PATH_FIXES        = "/geopoints/list/fixes";

    @Override
    public List<FlightPlan> fetchFlightPlans() {
        log.info("[FETCH][prod] GET {}", PATH_FLIGHT_PLANS);
        return flightApiWebClient.get()
                .uri(PATH_FLIGHT_PLANS)
                .retrieve()
                .bodyToFlux(FlightPlan.class)
                .collectList()
                .onErrorResume(e -> {
                    log.error("[FETCH][prod] Flight plans call failed — returning empty list. Error: {}",
                            e.getMessage());
                    return Mono.just(Collections.emptyList());
                })
                .block();
    }

    @Override
    public List<GeoPoint> fetchAirways() {
        return fetchGeoPoints(PATH_AIRWAYS, "airway");
    }

    @Override
    public List<GeoPoint> fetchFixes() {
        return fetchGeoPoints(PATH_FIXES, "fix");
    }

    private List<GeoPoint> fetchGeoPoints(String path, String type) {
        log.info("[FETCH][prod] GET {}", path);

        // bodyToFlux(String.class) uses StringDecoder which reads the entire body
        // as a single chunk regardless of Content-Type — it does NOT split a JSON
        // array into per-element emissions.  bodyToMono(String[].class) uses the
        // Jackson decoder which correctly deserialises a JSON array of strings.
        String[] rawArray = flightApiWebClient.get()
                .uri(path)
                .retrieve()
                .bodyToMono(String[].class)
                .onErrorResume(e -> {
                    log.error("[FETCH][prod] Geopoints call failed for {} — returning empty list. Error: {}",
                            path, e.getMessage());
                    return Mono.just(new String[0]);
                })
                .block();

        List<String> raw = (rawArray == null) ? Collections.emptyList()
                                              : java.util.Arrays.asList(rawArray);

        if (raw == null || raw.isEmpty()) {
            log.warn("[FETCH][prod] Empty response from {}", path);
            return Collections.emptyList();
        }

        List<GeoPoint> parsed = raw.stream()
                .map(s -> GeoPoint.parse(s, type))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        log.info("[FETCH][prod] Parsed {} {} geopoints", parsed.size(), type);
        return parsed;
    }
}
