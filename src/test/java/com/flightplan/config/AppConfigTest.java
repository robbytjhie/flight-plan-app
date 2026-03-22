package com.flightplan.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.reactive.function.client.WebClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers {@link AppConfig#flightApiWebClient} TLS branches (secure vs insecure trust manager).
 * Bean is profile-gated — dev must be active.
 */
@DisplayName("AppConfig")
class AppConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AppConfig.class)
            .withPropertyValues(
                    "spring.profiles.active=dev",
                    "flight.api.base-url=https://127.0.0.1:59999",
                    "flight.api.key=test-api-key");

    @Test
    @DisplayName("flightApiWebClient bean loads with insecure-ssl=false (default trust store)")
    void webClientWithSecureSsl() {
        runner.withPropertyValues("flight.api.insecure-ssl=false")
                .run(ctx -> assertThat(ctx).hasSingleBean(WebClient.class));
    }

    @Test
    @DisplayName("flightApiWebClient bean loads with insecure-ssl=true (trust-all)")
    void webClientWithInsecureSsl() {
        runner.withPropertyValues("flight.api.insecure-ssl=true")
                .run(ctx -> assertThat(ctx).hasSingleBean(WebClient.class));
    }
}
