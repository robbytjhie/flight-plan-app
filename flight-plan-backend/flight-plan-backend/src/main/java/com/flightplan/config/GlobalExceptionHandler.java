package com.flightplan.config;

import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.net.URI;
import java.time.Instant;

/**
 * IM8 S6 – Error Response Hardening
 *
 * Ensures that internal stack traces, class names, and exception messages
 * are NEVER returned in the HTTP response body.
 *
 * All error responses use RFC 7807 ProblemDetail format with a safe,
 * generic message. Full detail is logged server-side with the request ID
 * for SIEM ingestion.
 *
 * This prevents information disclosure that could aid an attacker in
 * fingerprinting the application stack.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleValidationError(IllegalArgumentException ex, WebRequest request) {
        // Validation errors are safe to surface (they contain no internal detail)
        log.warn("[SECURITY] Input validation failed: {}", ex.getMessage());
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        detail.setTitle("Invalid Request");
        detail.setDetail(ex.getMessage());
        detail.setType(URI.create("https://flightplan.example.gov.sg/errors/invalid-input"));
        detail.setProperty("timestamp", Instant.now());
        return detail;
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ProblemDetail handleMissingParam(MissingServletRequestParameterException ex, WebRequest request) {
        // Missing required query parameters are a client error, not a server error.
        log.warn("[SECURITY] Missing required request parameter: {}", ex.getParameterName());
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        detail.setTitle("Invalid Request");
        detail.setDetail("Required request parameter is missing.");
        detail.setType(URI.create("https://flightplan.example.gov.sg/errors/invalid-input"));
        detail.setProperty("timestamp", Instant.now());
        return detail;
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleConstraintViolation(ConstraintViolationException ex, WebRequest request) {
        // Bean Validation (@Size, @Pattern, etc.) failures on @Validated controllers
        // are client errors — map to 400, not 500.
        log.warn("[SECURITY] Constraint violation: {}", ex.getMessage());
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        detail.setTitle("Invalid Request");
        detail.setDetail("Request parameter failed validation constraints.");
        detail.setType(URI.create("https://flightplan.example.gov.sg/errors/invalid-input"));
        detail.setProperty("timestamp", Instant.now());
        return detail;
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ProblemDetail handleNoResource(NoResourceFoundException ex, WebRequest request) {
        // Requests with illegal path characters (e.g. XSS payloads containing '<' '>')
        // cannot be matched to any @PathVariable endpoint and fall through to the static
        // resource handler, which throws NoResourceFoundException.  This is a malformed
        // client request — return 400, not 500.
        log.warn("[SECURITY] Unroutable path (possibly malformed/malicious input): {}", ex.getMessage());
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        detail.setTitle("Invalid Request");
        detail.setDetail("The requested path is invalid.");
        detail.setType(URI.create("https://flightplan.example.gov.sg/errors/invalid-input"));
        detail.setProperty("timestamp", Instant.now());
        return detail;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGenericError(Exception ex, WebRequest request) {
        // Internal exception detail is logged but NEVER returned to the client
        log.error("[ERROR] Unhandled exception: {}", ex.getMessage(), ex);
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        detail.setTitle("Internal Server Error");
        detail.setDetail("An unexpected error occurred. Please contact support.");
        detail.setType(URI.create("https://flightplan.example.gov.sg/errors/internal"));
        detail.setProperty("timestamp", Instant.now());
        return detail;
    }
}
