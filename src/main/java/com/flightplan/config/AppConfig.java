package com.flightplan.config;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
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
 *   - WebClient uses TLS for all outbound calls to api.swimapisg.info
 *   - API key injected via FLIGHT_API_KEY environment variable / K8s Secret
 *   - By default the SSL context validates the upstream certificate (JVM trust store)
 *   - Optional {@code flight.api.insecure-ssl} / {@code FLIGHT_API_INSECURE_SSL=true}
 *     disables certificate verification (same idea as {@code curl -k}) — use only when
 *     the upstream presents an expired or otherwise invalid certificate until it is fixed
 *   - Redirects disabled to prevent SSRF
 */
@Configuration
@Slf4j
public class AppConfig {

    // ── prod WebClient — only wired when profile = prod ───────────────────────

    /**
     * TLS-enforced WebClients for the upstream Flight Manager API.
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
    @Profile({"dev", "prod"})
    public WebClient flightApiWebClient(
            @Value("${flight.api.base-url}") String baseUrl,
            @Value("${flight.api.key}") String apiKey,
            @Value("${flight.api.insecure-ssl:false}") boolean insecureSsl) throws Exception {

        log.info("[WEBCLIENT] configuring TLS WebClient for {} (insecure-ssl={})", baseUrl, insecureSsl);

        final SslContext sslContext;
        if (insecureSsl) {
            log.warn(
                    "[WEBCLIENT] flight.api.insecure-ssl=true — server TLS certificate verification is "
                            + "DISABLED (curl -k behaviour). Re-enable verification after the upstream "
                            + "certificate is renewed.");
            sslContext = SslContextBuilder.forClient()
                    .trustManager(InsecureTrustManagerFactory.INSTANCE)
                    .build();
        } else {
            TrustManagerFactory tmf = TrustManagerFactory
                    .getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init((KeyStore) null); // JVM default trust store (system CA bundle)
            sslContext = SslContextBuilder.forClient()
                    .trustManager(tmf)
                    .build();
        }

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
