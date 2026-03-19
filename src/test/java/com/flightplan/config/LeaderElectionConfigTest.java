package com.flightplan.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.integration.redis.util.RedisLockRegistry;
import org.springframework.integration.support.locks.DefaultLockRegistry;
import org.springframework.integration.support.locks.LockRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for LeaderElectionConfig.
 *
 * Covers both @Profile beans and all branches in the dev fallback logic:
 *
 *  prod profile:
 *    - redisLockRegistry() returns a RedisLockRegistry (no try/catch — fail fast)
 *
 *  dev profile — two branches:
 *    - Redis reachable (PING succeeds) → RedisLockRegistry
 *    - Redis unreachable (PING throws)  → DefaultLockRegistry (in-memory fallback)
 *
 * Uses plain Mockito (no Spring context) for speed. We test the config class
 * methods directly since they are @Bean factory methods, not @Component-scanned.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("LeaderElectionConfig")
class LeaderElectionConfigTest {

    @Mock
    private RedisConnectionFactory connectionFactory;

    @Mock
    private RedisConnection redisConnection;

    // ── prod profile ──────────────────────────────────────────────────

    @Nested
    @DisplayName("prod profile: redisLockRegistry()")
    class ProdProfileTests {

        @Test
        @DisplayName("returns a RedisLockRegistry when Redis is reachable")
        void returnsRedisLockRegistry() {
            LeaderElectionConfig config = new LeaderElectionConfig();
            // RedisLockRegistry requires a live connection to register the lock key;
            // we only assert the type here since no actual PING is performed in the prod bean.
            LockRegistry registry = config.redisLockRegistry(connectionFactory);

            assertThat(registry).isInstanceOf(RedisLockRegistry.class);
        }

        @Test
        @DisplayName("does NOT call connectionFactory.getConnection() — no probe in prod (fail fast design)")
        void doesNotProbeConnectionInProd() {
            LeaderElectionConfig config = new LeaderElectionConfig();
            config.redisLockRegistry(connectionFactory);

            // Prod bean deliberately skips the PING probe — a Redis failure must
            // crash the pod immediately rather than silently degrade to in-memory.
            verify(connectionFactory, never()).getConnection();
        }
    }

    // ── dev profile ───────────────────────────────────────────────────

    @Nested
    @DisplayName("dev profile: devLockRegistry()")
    class DevProfileTests {

        @Test
        @DisplayName("returns RedisLockRegistry when Redis PING succeeds")
        void returnsRedisLockRegistryWhenRedisReachable() {
            when(connectionFactory.getConnection()).thenReturn(redisConnection);
            when(redisConnection.ping()).thenReturn("PONG");

            LeaderElectionConfig config = new LeaderElectionConfig();
            LockRegistry registry = config.devLockRegistry(connectionFactory);

            assertThat(registry).isInstanceOf(RedisLockRegistry.class);
        }

        @Test
        @DisplayName("returns DefaultLockRegistry when Redis is unreachable (connection throws)")
        void returnsDefaultLockRegistryWhenRedisUnreachable() {
            when(connectionFactory.getConnection())
                    .thenThrow(new RuntimeException("Connection refused: 127.0.0.1:6379"));

            LeaderElectionConfig config = new LeaderElectionConfig();
            LockRegistry registry = config.devLockRegistry(connectionFactory);

            assertThat(registry).isInstanceOf(DefaultLockRegistry.class);
        }

        @Test
        @DisplayName("returns DefaultLockRegistry when Redis PING throws after connection")
        void returnsDefaultLockRegistryWhenPingThrows() {
            when(connectionFactory.getConnection()).thenReturn(redisConnection);
            when(redisConnection.ping()).thenThrow(new RuntimeException("PING timeout"));

            LeaderElectionConfig config = new LeaderElectionConfig();
            LockRegistry registry = config.devLockRegistry(connectionFactory);

            assertThat(registry).isInstanceOf(DefaultLockRegistry.class);
        }

        @Test
        @DisplayName("dev bean probes with getConnection() to decide registry type")
        void devBeanProbesConnection() {
            when(connectionFactory.getConnection()).thenReturn(redisConnection);
            when(redisConnection.ping()).thenReturn("PONG");

            LeaderElectionConfig config = new LeaderElectionConfig();
            config.devLockRegistry(connectionFactory);

            verify(connectionFactory, atLeastOnce()).getConnection();
        }

        @Test
        @DisplayName("returned DefaultLockRegistry can obtain and lock a key (smoke test)")
        void defaultRegistryIsFunctional() {
            when(connectionFactory.getConnection())
                    .thenThrow(new RuntimeException("no redis"));

            LeaderElectionConfig config = new LeaderElectionConfig();
            LockRegistry registry = config.devLockRegistry(connectionFactory);

            // Verify the fallback registry is actually usable (not null, can obtain a lock)
            assertThat(registry).isNotNull();
            assertThat(registry.obtain(LeaderElectionConfig.FLIGHT_DATA_FETCH_LOCK)).isNotNull();
        }

        @Test
        @DisplayName("returned RedisLockRegistry can obtain a lock key (smoke test)")
        void redisRegistryIsFunctional() {
            when(connectionFactory.getConnection()).thenReturn(redisConnection);
            when(redisConnection.ping()).thenReturn("PONG");

            LeaderElectionConfig config = new LeaderElectionConfig();
            LockRegistry registry = config.devLockRegistry(connectionFactory);

            assertThat(registry).isNotNull();
            assertThat(registry.obtain(LeaderElectionConfig.FLIGHT_DATA_FETCH_LOCK)).isNotNull();
        }
    }

    // ── FLIGHT_DATA_FETCH_LOCK constant ───────────────────────────────

    @Nested
    @DisplayName("FLIGHT_DATA_FETCH_LOCK constant")
    class LockKeyTests {

        @Test
        @DisplayName("lock key constant is non-blank")
        void lockKeyIsNonBlank() {
            assertThat(LeaderElectionConfig.FLIGHT_DATA_FETCH_LOCK).isNotBlank();
        }

        @Test
        @DisplayName("lock key contains the service namespace prefix")
        void lockKeyHasServicePrefix() {
            assertThat(LeaderElectionConfig.FLIGHT_DATA_FETCH_LOCK)
                    .startsWith("flight-plan:");
        }
    }
}
