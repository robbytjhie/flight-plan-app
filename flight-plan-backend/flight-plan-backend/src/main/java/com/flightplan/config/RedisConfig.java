package com.flightplan.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

/**
 * Redis connection factory — active in BOTH dev and prod profiles.
 *
 * ── Why active in dev too? ───────────────────────────────────────────────────
 * If a Redis-related bug surfaces in prod (e.g. lock contention, TTL expiry,
 * connection pooling), you need to be able to reproduce it locally in dev.
 * Skipping Redis in dev entirely means you can never catch those issues before
 * they reach production.
 *
 * ── Dev behaviour ───────────────────────────────────────────────────────────
 * Redis is OPTIONAL in dev.  LeaderElectionConfig probes the connection at
 * startup:
 *   • Redis reachable  →  RedisLockRegistry used (full prod-like behaviour)
 *   • Redis not running →  falls back to DefaultLockRegistry (in-memory)
 *                          with a clear WARN log so you know the difference
 *
 * ── Prod behaviour ──────────────────────────────────────────────────────────
 * Redis is REQUIRED in prod.  If the connection fails, the app fails fast —
 * no silent fallback.  This is intentional: a multi-pod deployment with an
 * in-memory lock would cause every pod to fetch from the upstream API
 * simultaneously, which is the exact problem we are solving.
 *
 * IM8 S5 (Data at Rest): Redis credentials injected from env / K8s Secret.
 */
@Configuration
@Slf4j
public class RedisConfig {

    @Value("${spring.data.redis.host:localhost}")
    private String redisHost;

    @Value("${spring.data.redis.port:6379}")
    private int redisPort;

    @Value("${spring.data.redis.password:}")
    private String redisPassword;

    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(redisHost, redisPort);

        if (redisPassword != null && !redisPassword.isBlank()) {
            config.setPassword(redisPassword);
        }

        log.info("[REDIS] Configuring connection factory for {}:{} (auth={})",
                redisHost, redisPort, !redisPassword.isBlank());

        return new LettuceConnectionFactory(config);
    }
}
