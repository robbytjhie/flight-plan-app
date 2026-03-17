package com.flightplan.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.integration.redis.util.RedisLockRegistry;
import org.springframework.integration.support.locks.DefaultLockRegistry;
import org.springframework.integration.support.locks.LockRegistry;

/**
 * Leader Election Configuration
 *
 * Ensures only one pod fetches from the upstream API per scheduled tick,
 * preventing N×API calls when the Deployment is scaled to N replicas.
 *
 * ── prod profile ─────────────────────────────────────────────────────────────
 * RedisLockRegistry is REQUIRED.  If Redis is unreachable at startup,
 * the application fails fast — running multi-pod with an in-memory lock
 * would defeat the entire purpose of leader election.
 *
 * ── dev profile ──────────────────────────────────────────────────────────────
 * Redis is OPTIONAL.  The lock bean probes the Redis connection at startup:
 *
 *   Redis reachable   →  RedisLockRegistry
 *                        Full prod-like behaviour — use this when reproducing
 *                        Redis-specific issues (lock contention, TTL expiry,
 *                        connection pool exhaustion, etc.)
 *
 *   Redis unreachable →  DefaultLockRegistry (in-memory fallback)
 *                        Logged at WARN level so you always know which lock
 *                        is active.  Safe for a single local instance only.
 *
 * This means you can run dev with or without Redis — the app starts either
 * way, but you get the same lock behaviour as prod when Redis is available.
 *
 * IM8 S4 (Availability): lock TTL ensures no permanent leader lock — another
 * pod takes over on the next scheduled tick if the leader crashes.
 */
@Configuration
@Slf4j
public class LeaderElectionConfig {

    public static final String FLIGHT_DATA_FETCH_LOCK = "flight-plan:data-fetch-leader";

    @Value("${leader.lock.ttl-ms:50000}")
    private long lockTtlMs;

    // ── prod: Redis is required — fail fast if unreachable ───────────────────

    @Bean
    @Profile("prod")
    public LockRegistry redisLockRegistry(RedisConnectionFactory connectionFactory) {
        log.info("[LEADER] prod — Redis distributed lock (key={}, TTL={}ms)",
                FLIGHT_DATA_FETCH_LOCK, lockTtlMs);
        // No try/catch — if Redis is down in prod, we WANT the app to fail fast.
        // A multi-pod deployment with an in-memory fallback would cause every
        // pod to fetch from the upstream API simultaneously.
        return new RedisLockRegistry(connectionFactory, FLIGHT_DATA_FETCH_LOCK, lockTtlMs);
    }

    // ── dev: Redis is optional — probe connection, fall back gracefully ───────

    @Bean
    @Profile({"dev", "mock"})
    public LockRegistry devLockRegistry(RedisConnectionFactory connectionFactory) {
        try {
            // Probe the connection with a cheap PING before committing to Redis.
            // Lettuce is lazy by default — we force an actual connection here so
            // the fallback decision is made at startup, not mid-request.
            connectionFactory.getConnection().ping();
            connectionFactory.getConnection().close();

            log.info("[LEADER] dev — Redis reachable, using RedisLockRegistry (key={}, TTL={}ms). " +
                     "Full prod-like leader election active — good for reproducing Redis issues.",
                     FLIGHT_DATA_FETCH_LOCK, lockTtlMs);
            return new RedisLockRegistry(connectionFactory, FLIGHT_DATA_FETCH_LOCK, lockTtlMs);

        } catch (Exception e) {
            log.warn("[LEADER] dev — Redis not reachable ({}). " +
                     "Falling back to in-memory DefaultLockRegistry. " +
                     "Start Redis (docker run -d -p 6379:6379 redis:alpine) to test prod-like lock behaviour.",
                     e.getMessage());
            return new DefaultLockRegistry();
        }
    }
}
