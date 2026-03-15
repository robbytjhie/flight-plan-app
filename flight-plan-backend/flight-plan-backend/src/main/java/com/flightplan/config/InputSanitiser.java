package com.flightplan.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * IM8 S6 – Input Validation and XSS Prevention
 *
 * Provides lightweight, zero-dependency input sanitisation for all
 * user-supplied path variables and query parameters.
 *
 * Why not AntiSamy here? AntiSamy is appropriate for sanitising rich HTML content.
 * Flight API parameters (callsigns, ICAO codes) are purely alphanumeric – we can
 * apply a strict allowlist regex, which is simpler and more reliable.
 *
 * OWASP guidelines applied:
 *  - Validate on input (allowlist, not denylist).
 *  - Reject early with a clear error; never silently strip.
 *  - Length limits prevent buffer-overflow style attacks.
 */
@Component
@Slf4j
public class InputSanitiser {

    // Callsign: ICAO / IATA format – 3-8 uppercase alphanumeric characters
    private static final Pattern CALLSIGN_PATTERN = Pattern.compile("^[A-Z0-9]{2,8}$");

    // Generic safe string – alphanumeric + limited punctuation, max 100 chars
    private static final Pattern SAFE_STRING_PATTERN = Pattern.compile("^[a-zA-Z0-9 _\\-]{1,100}$");

    private static final int MAX_CALLSIGN_LENGTH = 8;
    private static final int MAX_QUERY_LENGTH = 100;

    /**
     * Validate and normalise a flight callsign path variable.
     * Throws {@link IllegalArgumentException} if the value fails validation.
     *
     * @param callsign raw callsign from request
     * @return uppercased, trimmed callsign
     */
    public String sanitiseCallsign(String callsign) {
        if (callsign == null || callsign.isBlank()) {
            throw new IllegalArgumentException("Callsign must not be blank");
        }

        String cleaned = callsign.trim().toUpperCase();

        if (cleaned.length() > MAX_CALLSIGN_LENGTH) {
            log.warn("[SECURITY] Callsign exceeds max length: length={}", cleaned.length());
            throw new IllegalArgumentException(
                    "Callsign must not exceed " + MAX_CALLSIGN_LENGTH + " characters");
        }

        if (!CALLSIGN_PATTERN.matcher(cleaned).matches()) {
            // Log warning without echoing the raw value to prevent log injection
            log.warn("[SECURITY] Invalid callsign format rejected");
            throw new IllegalArgumentException(
                    "Callsign contains invalid characters. Only A-Z 0-9 are permitted.");
        }

        return cleaned;
    }

    /**
     * Validate a free-text search query parameter.
     * Used for the /flights/search?callsign= endpoint.
     */
    public String sanitiseSearchQuery(String query) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("Search query must not be blank");
        }

        String cleaned = query.trim().toUpperCase();

        if (cleaned.length() > MAX_QUERY_LENGTH) {
            log.warn("[SECURITY] Search query exceeds max length: length={}", cleaned.length());
            throw new IllegalArgumentException(
                    "Search query must not exceed " + MAX_QUERY_LENGTH + " characters");
        }

        if (!CALLSIGN_PATTERN.matcher(cleaned).matches()) {
            log.warn("[SECURITY] Invalid search query format rejected");
            throw new IllegalArgumentException(
                    "Search query contains invalid characters. Only A-Z 0-9 are permitted.");
        }

        return cleaned;
    }
}
