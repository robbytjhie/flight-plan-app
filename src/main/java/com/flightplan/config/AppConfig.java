package com.flightplan.config;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import javax.net.ssl.TrustManagerFactory;
import java.security.KeyStore;

/**
 * Application Bean Configuration
 *
 * ── prod profile only ────────────────────────────────────────────────────────
 * The WebClient bean (and its TLS context + apikey header) is only constructed
 * when running in the prod profile.  In dev, no WebClient is created because
 * MockDataFetchStrategy never makes network calls.
 *
 * IM8 S5 (Data in Transit):
 *   - WebClient enforces TLS for all outbound calls to api.swimapisg.info
 *   - API key injected via FLIGHT_API_KEY environment variable / K8s Secret
 *   - SSL context validates upstream certificate against JVM system trust store
 *   - Redirects disabled to prevent SSRF
 */
@Configuration
@Slf4j
public class AppConfig {

    // ── prod WebClient — only wired when profile = prod ───────────────────────

    /**
     * TLS-enforced WebClient for the upstream Flight Manager API.
     * Only created in the prod profile — dev uses MockDataFetchStrategy instead.
     *
     * The apikey header value comes from FLIGHT_API_KEY environment variable
     * (or Kubernetes Secret mounted as env).  It is never hardcoded here.
     *
     * K8s pod spec example:
     *   env:
     *     - name: FLIGHT_API_KEY
     *       valueFrom:
     *         secretKeyRef:
     *           name: flight-api-secret
     *           key: api-key
     */
    @Bean
    @Profile("prod")
    public WebClient flightApiWebClient(
            @Value("${flight.api.base-url}") String baseUrl,
            @Value("${flight.api.key}")      String apiKey) throws Exception {

        log.info("[WEBCLIENT] prod profile — configuring TLS WebClient for {}", baseUrl);

        TrustManagerFactory tmf = TrustManagerFactory
                .getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init((KeyStore) null); // JVM default trust store (system CA bundle)

        SslContext sslContext = SslContextBuilder.forClient()
                .trustManager(tmf)
                .build();

        HttpClient httpClient = HttpClient.create()
                .secure(sslSpec -> sslSpec.sslContext(sslContext))
                .followRedirect(false); // never follow redirects — prevents SSRF

        return WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("apikey", apiKey) // IM8 S5: value from K8s Secret
                .filter(logRequest())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(configurer -> configurer
                        .defaultCodecs()
                        .maxInMemorySize(10 * 1024 * 1024)) // 10MB
                .build();
    }

    /**
     * IM8 S2 (Audit): log outbound method + URI at DEBUG.
     * Response / request body intentionally NOT logged to prevent data leakage.
     */
    private ExchangeFilterFunction logRequest() {
        return ExchangeFilterFunction.ofRequestProcessor(req -> {
            log.debug("[OUTBOUND] {} {}", req.method(), req.url());
            return Mono.just(req);
        });
    }
}
