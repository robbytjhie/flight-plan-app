package com.flightplan.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * OpenAPI 3 / Swagger UI configuration.
 *
 * UI available at: /swagger-ui/index.html
 * Raw spec at:     /v3/api-docs
 *
 * IM8 S6: Swagger UI is enabled only in non-production profiles.
 * In production, set springdoc.swagger-ui.enabled=false via environment variable.
 */
@Configuration
public class OpenApiConfig {

    @Value("${server.port:8080}")
    private String serverPort;

    @Bean
    public OpenAPI flightPlanOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("FlightPlan Route Visualiser API")
                        .description("""
                                REST API for the FlightPlan Route Visualiser.
                                
                                Provides access to cached flight plans, geopoints, and resolved
                                flight routes for map rendering. Data is refreshed from the upstream
                                Flight Manager API every 60 seconds via a leader-elected scheduler.
                                
                                **Cache behaviour**: All `/api/flights` and `/api/geopoints` endpoints
                                serve data from the in-memory cache — no upstream API call is made
                                per request.
                                """)
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("FlightPlan Team")
                                .email("flightplan@example.com"))
                        .license(new License()
                                .name("Proprietary")
                                .url("https://example.com/license")))
                .servers(List.of(
                        new Server().url("http://localhost:" + serverPort).description("Local development"),
                        new Server().url("https://flightplan.example.com").description("Production")
                ));
    }
}
