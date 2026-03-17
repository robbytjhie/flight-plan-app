package com.flightplan.service;

import com.flightplan.config.LeaderElectionConfig;
import com.flightplan.model.FlightPlan;
import com.flightplan.model.GeoPoint;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.integration.support.locks.LockRegistry;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;

/**
 * Leader-elected data cache for flight plan and geopoint data.
 *
 * ──────────────────────────────────────────────────────────────────────────
 * PROBLEM SOLVED
 * ──────────────────────────────────────────────────────────────────────────
 * When the Deployment is scaled to N replicas (e.g. HPA triggers under load),
 * every pod would independently call the upstream flight API on every
 * scheduled tick — multiplying the request count by N and risking rate-limit
 * errors or duplicate data processing.
 *
 * ──────────────────────────────────────────────────────────────────────────
 * SOLUTION: DISTRIBUTED REDIS LOCK (leader election pattern)
 * ──────────────────────────────────────────────────────────────────────────
 *  1. On every scheduled tick, ALL pods attempt to acquire the same Redis lock
 *     keyed on {@link LeaderElectionConfig#FLIGHT_DATA_FETCH_LOCK}.
 *  2. Only one pod succeeds (the "leader"). It fetches data from the upstream
 *     API and populates its local in-memory cache.
 *  3. All other pods fail to acquire the lock and skip the fetch entirely.
 *  4. The lock is released after the fetch completes OR auto-expires after
 *     {@code leader.lock.ttl-ms} milliseconds if the leader pod crashes.
 *
 * ──────────────────────────────────────────────────────────────────────────
 * NOTE ON CACHE CONSISTENCY
 * ──────────────────────────────────────────────────────────────────────────
 * This uses per-pod in-memory caches. Non-leader pods serve slightly stale
 * data until they become leader on a future tick. This is acceptable for
 * flight plan data (refreshed every 60 s), but if strong consistency is
 * required, replace the AtomicReference cache with a shared Redis cache
 * populated by the leader and read by all pods.
 *
 * IM8 S4 (Availability): lock TTL ensures no permanent single point of failure.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FlightDataCache {

    private final FlightFetchService flightFetchService;
    private final LockRegistry flightDataLockRegistry;

    // Thread-safe references to the latest fetched data
    private final AtomicReference<List<FlightPlan>> flightPlansCache =
            new AtomicReference<>(Collections.emptyList());
    private final AtomicReference<List<GeoPoint>> airwaysCache =
            new AtomicReference<>(Collections.emptyList());
    private final AtomicReference<List<GeoPoint>> fixesCache =
            new AtomicReference<>(Collections.emptyList());
    private final AtomicReference<Instant> lastRefreshed =
            new AtomicReference<>(null);

    // ── Eager startup population ─────────────────────────────────────────

    /**
     * Eagerly populates the cache on startup so the application serves data
     * immediately on the first request rather than waiting for the scheduled
     * tick.  The @Scheduled method below continues to keep data fresh every
     * 60 seconds.
     *
     * This also fixes integration tests: the @Scheduled initialDelay means
     * the cache is still empty when tests run, causing spurious failures on
     * /api/geopoints/fixes and /api/geopoints/airways endpoints.
     */
    @PostConstruct
    public void initCache() {
        log.info("[CACHE] Eagerly populating cache on startup");
        fetchAndCache();
    }

    // ── Scheduled leader-elected refresh ────────────────────────────────

    /**
     * Runs every 60 seconds on all pods.
     * Only the pod that wins the Redis lock actually fetches data.
     *
     * initialDelay = 5 s: slight delay on startup to let the application
     * fully initialise before the first fetch attempt.
     */
    @Scheduled(fixedRateString = "${cache.refresh.rate-ms:60000}",
               initialDelayString = "${cache.refresh.initial-delay-ms:5000}")
    public void refreshIfLeader() {
        Lock lock = flightDataLockRegistry.obtain(LeaderElectionConfig.FLIGHT_DATA_FETCH_LOCK);

        boolean acquired = false;
        try {
            acquired = lock.tryLock();
            if (!acquired) {
                log.debug("[LEADER] Lock not acquired – skipping fetch (another pod is leader)");
                return;
            }

            log.info("[LEADER] Lock acquired – this pod is leader, refreshing flight data");
            fetchAndCache();

        } catch (Exception e) {
            log.error("[LEADER] Error during leader data fetch: {}", e.getMessage(), e);
        } finally {
            if (acquired) {
                try {
                    lock.unlock();
                    log.debug("[LEADER] Lock released");
                } catch (Exception e) {
                    log.warn("[LEADER] Failed to release lock: {}", e.getMessage());
                }
            }
        }
    }

    // ── Internal fetch ───────────────────────────────────────────────────

    private void fetchAndCache() {
        List<FlightPlan> plans = dedupeFlightPlans(flightFetchService.fetchFlightPlans());
        List<GeoPoint> airways = flightFetchService.fetchAirways();
        List<GeoPoint> fixes = flightFetchService.fetchFixes();

        flightPlansCache.set(plans);
        airwaysCache.set(airways);
        fixesCache.set(fixes);
        lastRefreshed.set(Instant.now());

        log.info("[LEADER] Cache refreshed: {} flight plans, {} airways, {} fixes",
                plans.size(), airways.size(), fixes.size());
    }

    /**
     * Upstream feeds can occasionally contain multiple FlightPlan objects for the same callsign.
     * The UI and route resolution assume one plan per callsign, so we normalise here.
     */
    private List<FlightPlan> dedupeFlightPlans(List<FlightPlan> raw) {
        if (raw == null || raw.isEmpty()) return Collections.emptyList();

        // Preserve upstream ordering (important for UI/tests) while deduping by callsign.
        Map<String, FlightPlan> byCallsign = new LinkedHashMap<>();
        for (FlightPlan fp : raw) {
            if (fp == null) continue;
            String callsign = fp.getAircraftIdentification();
            if (callsign == null || callsign.isBlank()) continue;

            String key = callsign.trim().toUpperCase(Locale.ROOT);
            FlightPlan existing = byCallsign.get(key);
            if (existing == null) {
                byCallsign.put(key, fp);
                continue;
            }

            // Prefer the entry with a later lastUpdatedTimeStamp when available.
            if (isNewer(fp.getLastUpdatedTimeStamp(), existing.getLastUpdatedTimeStamp())) {
                byCallsign.put(key, fp);
            }
        }

        List<FlightPlan> deduped = byCallsign.values().stream()
                .filter(Objects::nonNull)
                .toList();

        int removed = raw.size() - deduped.size();
        if (removed > 0) {
            log.warn("[CACHE] Deduped flight plans: removed {} duplicates ({} -> {})",
                    removed, raw.size(), deduped.size());
        }
        return deduped;
    }

    private boolean isNewer(String candidate, String current) {
        Optional<Instant> cand = parseInstant(candidate);
        Optional<Instant> curr = parseInstant(current);
        if (cand.isPresent() && curr.isPresent()) return cand.get().isAfter(curr.get());
        if (cand.isPresent() && curr.isEmpty()) return true;
        return false;
    }

    private Optional<Instant> parseInstant(String ts) {
        if (ts == null || ts.isBlank()) return Optional.empty();
        try {
            return Optional.of(Instant.parse(ts.trim()));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    // ── Cache read methods (called by FlightService on every request) ────

    public List<FlightPlan> getFlightPlans() {
        return flightPlansCache.get();
    }

    public List<GeoPoint> getAirways() {
        return airwaysCache.get();
    }

    public List<GeoPoint> getFixes() {
        return fixesCache.get();
    }

    public Instant getLastRefreshed() {
        return lastRefreshed.get();
    }
}
