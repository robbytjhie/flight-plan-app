package com.flightplan.service;

import com.flightplan.model.FlightPlan;
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
import org.springframework.integration.support.locks.LockRegistry;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.locks.Lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for FlightDataCache (leader election + in-memory cache).
 *
 * Key scenarios:
 *  - Pod wins the lock → fetch is performed, cache is populated
 *  - Pod loses the lock → fetch is skipped entirely (no upstream API call)
 *  - Lock is always released after a successful fetch (no lock leak)
 *  - Lock is released even when fetch throws an exception
 *  - Cache read methods return the latest fetched data
 *  - lastRefreshed timestamp is set after successful refresh
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("FlightDataCache")
class FlightDataCacheTest {

    @Mock private FlightFetchService flightFetchService;
    @Mock private LockRegistry lockRegistry;
    @Mock private Lock lock;

    private FlightDataCache cache;

    @BeforeEach
    void setUp() {
        when(lockRegistry.obtain(anyString())).thenReturn(lock);
        cache = new FlightDataCache(flightFetchService, lockRegistry);
    }

    // ── Leader Election: pod wins lock ────────────────────────────────

    @Nested @DisplayName("Pod wins the distributed lock (is leader)")
    class LeaderWinsTests {

        @BeforeEach
        void acquireLock() {
            when(lock.tryLock()).thenReturn(true);
        }

        @Test @DisplayName("fetches flight plans when lock is acquired")
        void fetchesFlightPlansOnLockAcquired() {
            when(flightFetchService.fetchFlightPlans()).thenReturn(List.of(buildPlan("SIA200")));
            when(flightFetchService.fetchAirways()).thenReturn(List.of());
            when(flightFetchService.fetchFixes()).thenReturn(List.of());

            cache.refreshIfLeader();

            verify(flightFetchService).fetchFlightPlans();
            verify(flightFetchService).fetchAirways();
            verify(flightFetchService).fetchFixes();
        }

        @Test @DisplayName("populates flight plan cache after successful fetch")
        void populatesFlightPlanCache() {
            List<FlightPlan> plans = List.of(buildPlan("SIA200"), buildPlan("EK432"));
            when(flightFetchService.fetchFlightPlans()).thenReturn(plans);
            when(flightFetchService.fetchAirways()).thenReturn(List.of());
            when(flightFetchService.fetchFixes()).thenReturn(List.of());

            cache.refreshIfLeader();

            assertThat(cache.getFlightPlans()).hasSize(2);
            assertThat(cache.getFlightPlans().get(0).getAircraftIdentification()).isEqualTo("SIA200");
        }

        @Test @DisplayName("populates airways cache after successful fetch")
        void populatesAirwaysCache() {
            List<GeoPoint> airways = List.of(new GeoPoint("A576", 1.5, 104.1, "airway"));
            when(flightFetchService.fetchFlightPlans()).thenReturn(List.of());
            when(flightFetchService.fetchAirways()).thenReturn(airways);
            when(flightFetchService.fetchFixes()).thenReturn(List.of());

            cache.refreshIfLeader();

            assertThat(cache.getAirways()).hasSize(1);
            assertThat(cache.getAirways().get(0).getName()).isEqualTo("A576");
        }

        @Test @DisplayName("populates fixes cache after successful fetch")
        void populatesFixesCache() {
            List<GeoPoint> fixes = List.of(new GeoPoint("WSSS", 1.36, 103.99, "fix"));
            when(flightFetchService.fetchFlightPlans()).thenReturn(List.of());
            when(flightFetchService.fetchAirways()).thenReturn(List.of());
            when(flightFetchService.fetchFixes()).thenReturn(fixes);

            cache.refreshIfLeader();

            assertThat(cache.getFixes()).hasSize(1);
        }

        @Test @DisplayName("sets lastRefreshed timestamp after successful fetch")
        void setsLastRefreshedTimestamp() {
            when(flightFetchService.fetchFlightPlans()).thenReturn(List.of());
            when(flightFetchService.fetchAirways()).thenReturn(List.of());
            when(flightFetchService.fetchFixes()).thenReturn(List.of());

            Instant before = Instant.now();
            cache.refreshIfLeader();
            Instant after = Instant.now();

            assertThat(cache.getLastRefreshed()).isBetween(before, after);
        }

        @Test @DisplayName("ALWAYS releases the lock after a successful fetch (no lock leak)")
        void alwaysReleasesLockAfterSuccess() {
            when(flightFetchService.fetchFlightPlans()).thenReturn(List.of());
            when(flightFetchService.fetchAirways()).thenReturn(List.of());
            when(flightFetchService.fetchFixes()).thenReturn(List.of());

            cache.refreshIfLeader();

            verify(lock).unlock();
        }

        @Test @DisplayName("releases lock even when fetchFlightPlans throws")
        void releasesLockOnFetchException() {
            when(flightFetchService.fetchFlightPlans())
                    .thenThrow(new RuntimeException("upstream down"));

            cache.refreshIfLeader(); // must not propagate the exception

            verify(lock).unlock();
        }

        @Test @DisplayName("does not throw when fetch service throws — exception is swallowed")
        void doesNotPropagateException() {
            when(flightFetchService.fetchFlightPlans())
                    .thenThrow(new RuntimeException("network error"));

            // Should complete without throwing
            cache.refreshIfLeader();
        }
    }

    // ── Leader Election: pod loses lock ───────────────────────────────

    @Nested @DisplayName("Pod loses the distributed lock (not leader)")
    class LeaderLosesTests {

        @BeforeEach
        void denyLock() {
            when(lock.tryLock()).thenReturn(false);
        }

        @Test @DisplayName("does NOT fetch data when lock is not acquired")
        void skipsAllFetchesWhenLockNotAcquired() {
            cache.refreshIfLeader();

            verifyNoInteractions(flightFetchService);
        }

        @Test @DisplayName("does NOT call lock.unlock() when lock was never acquired")
        void doesNotUnlockWhenNotAcquired() {
            cache.refreshIfLeader();

            verify(lock, never()).unlock();
        }

        @Test @DisplayName("cache remains at initial empty state when not leader")
        void cacheRemainsEmptyWhenNotLeader() {
            cache.refreshIfLeader();

            assertThat(cache.getFlightPlans()).isEmpty();
            assertThat(cache.getAirways()).isEmpty();
            assertThat(cache.getFixes()).isEmpty();
        }

        @Test @DisplayName("lastRefreshed remains null when not leader")
        void lastRefreshedNullWhenNotLeader() {
            cache.refreshIfLeader();
            assertThat(cache.getLastRefreshed()).isNull();
        }
    }

    // ── Multiple refresh cycles ────────────────────────────────────────

    @Nested @DisplayName("Multiple refresh cycles")
    class MultipleRefreshTests {

        @Test @DisplayName("second refresh overwrites cache with new data")
        void secondRefreshOverwritesCache() {
            when(lock.tryLock()).thenReturn(true);

            // First fetch: 1 flight
            when(flightFetchService.fetchFlightPlans())
                    .thenReturn(List.of(buildPlan("SIA200")))
                    .thenReturn(List.of(buildPlan("SIA200"), buildPlan("EK432")));
            when(flightFetchService.fetchAirways()).thenReturn(List.of());
            when(flightFetchService.fetchFixes()).thenReturn(List.of());

            cache.refreshIfLeader();
            assertThat(cache.getFlightPlans()).hasSize(1);

            cache.refreshIfLeader();
            assertThat(cache.getFlightPlans()).hasSize(2);
        }

        @Test @DisplayName("pod wins then loses: cache retains last successful data")
        void cacheRetainsDataWhenLockLost() {
            // First cycle: win lock
            when(lock.tryLock()).thenReturn(true);
            when(flightFetchService.fetchFlightPlans()).thenReturn(List.of(buildPlan("SIA200")));
            when(flightFetchService.fetchAirways()).thenReturn(List.of());
            when(flightFetchService.fetchFixes()).thenReturn(List.of());
            cache.refreshIfLeader();

            // Second cycle: lose lock
            reset(lock);
            when(lockRegistry.obtain(anyString())).thenReturn(lock);
            when(lock.tryLock()).thenReturn(false);
            cache.refreshIfLeader();

            // Cache still holds data from first cycle
            assertThat(cache.getFlightPlans()).hasSize(1);
            assertThat(cache.getFlightPlans().get(0).getAircraftIdentification()).isEqualTo("SIA200");
        }
    }

    // ── Initial state ──────────────────────────────────────────────────

    @Nested @DisplayName("Initial state (no refresh yet)")
    class InitialStateTests {

        @Test @DisplayName("flight plans cache starts empty")
        void flightPlansCacheStartsEmpty() {
            assertThat(cache.getFlightPlans()).isEmpty();
        }

        @Test @DisplayName("airways cache starts empty")
        void airwaysCacheStartsEmpty() {
            assertThat(cache.getAirways()).isEmpty();
        }

        @Test @DisplayName("fixes cache starts empty")
        void fixesCacheStartsEmpty() {
            assertThat(cache.getFixes()).isEmpty();
        }

        @Test @DisplayName("lastRefreshed starts null")
        void lastRefreshedStartsNull() {
            assertThat(cache.getLastRefreshed()).isNull();
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private FlightPlan buildPlan(String callsign) {
        FlightPlan fp = new FlightPlan();
        fp.setAircraftIdentification(callsign);
        fp.setMessageType("FPL");
        return fp;
    }
}
