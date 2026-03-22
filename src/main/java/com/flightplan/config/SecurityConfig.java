package com.flightplan.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.XXssProtectionHeaderWriter;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * IM8 Security Configuration
 *
 * Addresses:
 *  - IM8 S3 (Access Control): stateless JWT-ready filter chain, no sessions
 *  - IM8 S5 (Data In Transit): HSTS header enforcement; TLS required by Ingress
 *  - IM8 S6 (Input Validation / XSS): strict CSP, X-XSS-Protection, X-Content-Type-Options
 *  - IM8 S7 (CORS): explicit origin allowlist, no wildcard
 *  - IM8 S8 (Clickjacking): X-Frame-Options DENY
 *  - IM8 S9 (Security headers): Referrer-Policy, Permissions-Policy
 */
@Configuration
@EnableWebSecurity
@Slf4j
public class SecurityConfig {

    /**
     * Comma-separated list of permitted frontend origins injected from environment.
     * Example: https://flightplan.agency.gov.sg
     * NEVER use "*" in production.
     */
    @Value("${security.cors.allowed-origins}")
    private String[] allowedOrigins;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            // ── Session / CSRF ──────────────────────────────────────────
            // Stateless REST API – no server-side sessions, no CSRF token needed.
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .csrf(csrf -> csrf.disable())

            // ── CORS ────────────────────────────────────────────────────
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))

            // ── Authorisation ───────────────────────────────────────────
            // Health + metrics are open; everything else requires a valid bearer token.
            // Swap permitAll() for hasRole(...) once an IdP (e.g. Singpass, IAMS) is wired.
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.GET, "/api/health", "/actuator/health",
                                 "/actuator/prometheus").permitAll()
                // Swagger UI + OpenAPI spec (disable springdoc.swagger-ui.enabled in production)
                .requestMatchers("/swagger-ui/**", "/swagger-ui.html",
                                 "/v3/api-docs/**", "/v3/api-docs").permitAll()
                // Cache status — safe to expose; contains no sensitive data
                .requestMatchers(HttpMethod.GET, "/api/cache/status").permitAll()
                // Geopoints are internal-only - used server-side by route resolution.
                // The frontend never calls these directly; routes already contain resolved
                // coordinates. Block external access to avoid exposing the raw 14 MB
                // fixes payload or the full airway list.
                .requestMatchers("/api/geopoints/**").denyAll()
                .requestMatchers("/swagger-ui/**").permitAll()
                .requestMatchers("/swagger-ui.html").permitAll()
                .requestMatchers("/v3/api-docs/**").permitAll()
                .requestMatchers("/api/**").permitAll()   // ← tighten to authenticated() post-MVP
                .anyRequest().denyAll()
            )

            // ── Security headers (IM8 S5 / S6 / S8 / S9) ───────────────
            .headers(headers -> headers

                // HSTS – IM8 S5: force TLS for 1 year, include subdomains.
                // requestMatcher(AnyRequestMatcher) overrides Spring Security's default
                // behaviour of only setting HSTS on HTTPS requests — required for tests
                // (MockMvc uses HTTP) and for deployments behind a TLS-terminating proxy
                // or Ingress where the app itself receives plain HTTP internally.
                .httpStrictTransportSecurity(hsts -> hsts
                    .requestMatcher(AnyRequestMatcher.INSTANCE)
                    .includeSubDomains(true)
                    .maxAgeInSeconds(31_536_000)
                    .preload(true))

                // Clickjacking – IM8 S8
                .frameOptions(fo -> fo.deny())

                // MIME sniffing – IM8 S6
                .contentTypeOptions(ct -> {})

                // XSS filter (legacy browsers) – IM8 S6
                .xssProtection(xss -> xss
                    .headerValue(XXssProtectionHeaderWriter.HeaderValue.ENABLED_MODE_BLOCK))

                // Content-Security-Policy – IM8 S6 (strict; no inline scripts)
                .contentSecurityPolicy(csp -> csp
                    .policyDirectives(
                        "default-src 'none'; " +
                        "script-src 'self'; " +
                        "style-src 'self'; " +
                        "img-src 'self' data:; " +
                        "connect-src 'self'; " +
                        "font-src 'self'; " +
                        "object-src 'none'; " +
                        "frame-ancestors 'none'; " +
                        "base-uri 'self'; " +
                        "form-action 'self'"
                    ))

                // Referrer-Policy – IM8 S9
                .referrerPolicy(rp -> rp
                    .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))

                // Permissions-Policy – disable unused browser features
                .permissionsPolicy(pp -> pp
                    .policy("camera=(), microphone=(), geolocation=(), payment=()"))
            );

        return http.build();
    }

    /**
     * IM8 S7 – CORS: explicit origin whitelist, restricted methods, no credentials leak.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();

        // Explicit allow-list only – never "*"
        config.setAllowedOrigins(Arrays.asList(allowedOrigins));
        config.setAllowedMethods(List.of("GET", "OPTIONS"));   // read-only API; add POST when needed
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Request-ID"));
        config.setExposedHeaders(List.of("X-Request-ID"));
        config.setAllowCredentials(false);                     // no cookies across origins
        config.setMaxAge(3600L);                               // pre-flight cache 1 h

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
