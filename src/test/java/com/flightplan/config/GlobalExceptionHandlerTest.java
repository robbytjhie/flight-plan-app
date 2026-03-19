package com.flightplan.config;

import com.flightplan.controller.FlightController;
import com.flightplan.service.FlightService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpMethod;
import static org.assertj.core.api.Assertions.assertThat;
import org.springframework.context.annotation.Import;
import org.springframework.http.ProblemDetail;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Set;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit tests for GlobalExceptionHandler.
 *
 * Covers all five @ExceptionHandler branches:
 *  1. IllegalArgumentException  → 400 (InputSanitiser rejection)
 *  2. MissingServletRequestParameterException → 400 (missing required @RequestParam)
 *  3. ConstraintViolationException → 400 (@Size / @NotBlank violations)
 *  4. NoResourceFoundException → 400 (XSS in path — illegal path chars)
 *  5. Exception (generic)  → 500 (unexpected runtime exception)
 *
 * Also verifies IM8 S6 requirement: NO stack traces / exception class names
 * are leaked in any error response body.
 *
 * Uses @WebMvcTest so the real filter chain and Spring MVC error handling are active.
 */
@WebMvcTest(
    value = FlightController.class,
    properties = "security.cors.allowed-origins=http://localhost:3000"
)
@Import({GlobalExceptionHandler.class, InputSanitiser.class, SecurityConfig.class})
@DisplayName("GlobalExceptionHandler")
class GlobalExceptionHandlerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    FlightService flightService;

    // ── Branch 1: IllegalArgumentException → 400 ─────────────────────

    @Nested
    @DisplayName("IllegalArgumentException → 400")
    class IllegalArgumentExceptionTests {

        @Test
        @DisplayName("InputSanitiser rejection returns 400 with RFC 7807 ProblemDetail")
        void xssInCallsignReturns400() throws Exception {
            mockMvc.perform(get("/api/flights/SIA<script>"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.title").value("Invalid Request"))
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.timestamp").exists());
        }

        @Test
        @DisplayName("SQL injection in search param returns 400")
        void sqlInjectionReturns400() throws Exception {
            mockMvc.perform(get("/api/flights/search")
                            .param("callsign", "'; DROP TABLE--"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.title").value("Invalid Request"));
        }

        @Test
        @DisplayName("IM8 S6: error body contains no Java exception class name")
        void errorBodyHasNoJavaClassName() throws Exception {
            mockMvc.perform(get("/api/flights/search")
                            .param("callsign", "<evil>"))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().string(not(containsString("java."))))
                    .andExpect(content().string(not(containsString("Exception"))));
        }

        @Test
        @DisplayName("error response type URI points to flightplan error domain")
        void errorTypeUriIsSet() throws Exception {
            mockMvc.perform(get("/api/flights/search")
                            .param("callsign", "<xss>"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value(
                            containsString("flightplan.example.io/errors")));
        }
    }

    // ── Branch 2: MissingServletRequestParameterException → 400 ──────

    @Nested
    @DisplayName("MissingServletRequestParameterException → 400")
    class MissingParamTests {

        @Test
        @DisplayName("missing required ?callsign param returns 400")
        void missingCallsignReturns400() throws Exception {
            mockMvc.perform(get("/api/flights/search"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.title").value("Invalid Request"))
                    .andExpect(jsonPath("$.detail").value("Required request parameter is missing."));
        }

        @Test
        @DisplayName("IM8 S6: missing-param response has no stack trace")
        void missingParamHasNoStackTrace() throws Exception {
            mockMvc.perform(get("/api/flights/search"))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().string(not(containsString("java."))))
                    .andExpect(content().string(not(containsString("Exception"))));
        }

        @Test
        @DisplayName("missing-param response includes timestamp property")
        void missingParamIncludesTimestamp() throws Exception {
            mockMvc.perform(get("/api/flights/search"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.timestamp").exists());
        }
    }

    // ── Branch 3: ConstraintViolationException → 400 ─────────────────

    @Nested
    @DisplayName("ConstraintViolationException → 400")
    class ConstraintViolationTests {

        @Test
        @DisplayName("@Size violation on callsign path variable returns 400")
        void oversizeCallsignPathVarReturns400() throws Exception {
            // "TOOLONGID" is 9 chars — exceeds @Size(max=8) on @PathVariable
            mockMvc.perform(get("/api/flights/TOOLONGID"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("@Size violation on search ?callsign param returns 400")
        void oversizeSearchParamReturns400() throws Exception {
            mockMvc.perform(get("/api/flights/search")
                            .param("callsign", "TOOLONGCALLSIGN"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("IM8 S6: constraint-violation response has no stack trace")
        void constraintViolationHasNoStackTrace() throws Exception {
            mockMvc.perform(get("/api/flights/TOOLONGID"))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().string(not(containsString("java."))))
                    .andExpect(content().string(not(containsString("Exception"))));
        }
    }

    // ── Branch 4: NoResourceFoundException → 400 ─────────────────────

    @Nested
    @DisplayName("NoResourceFoundException → 400 (XSS / illegal path chars)")
    class NoResourceFoundTests {

        @Test
        @DisplayName("XSS payload with '<' in path returns 400 (not 404 or 500)")
        void xssInPathReturns400() throws Exception {
            mockMvc.perform(get("/api/flights/<script>alert(1)</script>"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value("The requested path is invalid."));
        }

        @Test
        @DisplayName("IM8 S6: unroutable-path response has no stack trace")
        void noResourceResponseHasNoStackTrace() throws Exception {
            mockMvc.perform(get("/api/flights/<img src=x>"))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().string(not(containsString("java."))))
                    .andExpect(content().string(not(containsString("Exception"))));
        }
    }

    // ── Branch 5: Generic Exception → 500 ────────────────────────────

    @Nested
    @DisplayName("Generic Exception → 500")
    class GenericExceptionTests {

        @Test
        @DisplayName("unexpected RuntimeException from service returns 500")
        void unexpectedExceptionReturns500() throws Exception {
            when(flightService.getAllFlightPlans())
                    .thenThrow(new RuntimeException("unexpected failure"));

            mockMvc.perform(get("/api/flights"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.title").value("Internal Server Error"))
                    .andExpect(jsonPath("$.detail").value(
                            "An unexpected error occurred. Please contact support."));
        }

        @Test
        @DisplayName("IM8 S6: 500 response body contains NO internal exception detail")
        void internalErrorHidesExceptionDetail() throws Exception {
            when(flightService.getAllFlightPlans())
                    .thenThrow(new RuntimeException("sensitive-internal-detail"));

            mockMvc.perform(get("/api/flights"))
                    .andExpect(status().isInternalServerError())
                    // The exception message MUST NOT appear in the response
                    .andExpect(content().string(not(containsString("hunter2"))))
                    .andExpect(content().string(not(containsString("java."))))
                    .andExpect(content().string(not(containsString("Exception"))));
        }

        @Test
        @DisplayName("500 response includes timestamp and correct type URI")
        void internalErrorIncludesTimestampAndType() throws Exception {
            when(flightService.getAllFlightPlans())
                    .thenThrow(new RuntimeException("boom"));

            mockMvc.perform(get("/api/flights"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.timestamp").exists())
                    .andExpect(jsonPath("$.type").value(
                            containsString("flightplan.example.io/errors/internal")));
        }

        @Test
        @DisplayName("NullPointerException from service is handled as 500")
        void nullPointerExceptionHandledAs500() throws Exception {
            when(flightService.getAirways())
                    .thenThrow(new NullPointerException("null ref in service"));

            mockMvc.perform(get("/api/geopoints/airways"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.title").value("Internal Server Error"));
        }

        @Test
        @DisplayName("getFlightByCallsign RuntimeException returns 500")
        void serviceExceptionOnGetByCallsignReturns500() throws Exception {
            when(flightService.getFlightByCallsign(anyString()))
                    .thenThrow(new RuntimeException("cache explosion"));

            mockMvc.perform(get("/api/flights/SIA200"))
                    .andExpect(status().isInternalServerError());
        }
    }

    @Test
    @DisplayName("handleNoResource returns 404 for swagger-ui path directly")
    void swaggerPathReturns404Direct() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        NoResourceFoundException ex = new NoResourceFoundException(
                HttpMethod.GET, "swagger-ui/index.html");

        ProblemDetail result = handler.handleNoResource(ex, null);

        assertThat(result.getStatus()).isEqualTo(404);
        assertThat(result.getTitle()).isEqualTo("Not Found");
    }

    @Test
    @DisplayName("handleNoResource returns 404 for v3/api-docs path directly")
    void apiDocsPathReturns404Direct() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        NoResourceFoundException ex = new NoResourceFoundException(
                HttpMethod.GET, "v3/api-docs/swagger-config");

        ProblemDetail result = handler.handleNoResource(ex, null);

        assertThat(result.getStatus()).isEqualTo(404);
    }
}
