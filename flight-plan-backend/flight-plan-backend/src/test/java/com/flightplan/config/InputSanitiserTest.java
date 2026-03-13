package com.flightplan.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for InputSanitiser.
 *
 * Covers IM8 S6 requirements:
 *  - Allowlist validation (only A-Z, 0-9)
 *  - Length limits
 *  - XSS payload rejection
 *  - SQL injection payload rejection
 *  - Path traversal rejection
 *  - Null / blank input rejection
 *  - Normalisation (uppercase, trim)
 */
@DisplayName("InputSanitiser")
class InputSanitiserTest {

    private InputSanitiser sanitiser;

    @BeforeEach
    void setUp() {
        sanitiser = new InputSanitiser();
    }

    // ── sanitiseCallsign ──────────────────────────────────────────────

    @Nested @DisplayName("sanitiseCallsign()")
    class SanitiseCallsignTests {

        // Valid inputs
        @Test @DisplayName("accepts standard ICAO callsign SIA200")
        void acceptsSia200() {
            assertThat(sanitiser.sanitiseCallsign("SIA200")).isEqualTo("SIA200");
        }

        @Test @DisplayName("accepts lowercase and returns uppercase")
        void normalisesToUppercase() {
            assertThat(sanitiser.sanitiseCallsign("sia200")).isEqualTo("SIA200");
        }

        @Test @DisplayName("accepts callsign with leading/trailing whitespace")
        void trimsWhitespace() {
            assertThat(sanitiser.sanitiseCallsign("  EK432  ")).isEqualTo("EK432");
        }

        @Test @DisplayName("accepts 2-character minimum callsign")
        void acceptsTwoChars() {
            assertThat(sanitiser.sanitiseCallsign("EK")).isEqualTo("EK");
        }

        @Test @DisplayName("accepts 8-character maximum callsign")
        void acceptsEightChars() {
            assertThat(sanitiser.sanitiseCallsign("SIA20000")).isEqualTo("SIA20000");
        }

        // Invalid inputs — XSS
        @ParameterizedTest
        @DisplayName("IM8 S6: rejects XSS payloads")
        @ValueSource(strings = {
            "<script>alert(1)</script>",
            "<img src=x onerror=alert(1)>",
            "javascript:alert(1)",
            "<svg onload=alert(1)>",
            "'\"><script>",
            "SIA<200"
        })
        void rejectsXssPayloads(String payload) {
            assertThatThrownBy(() -> sanitiser.sanitiseCallsign(payload))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        // Invalid inputs — SQL injection
        @ParameterizedTest
        @DisplayName("IM8 S6: rejects SQL injection patterns")
        @ValueSource(strings = {
            "'; DROP TABLE flights--",
            "1 OR 1=1",
            "SIA200 UNION SELECT",
            "admin'--"
        })
        void rejectsSqlInjection(String payload) {
            assertThatThrownBy(() -> sanitiser.sanitiseCallsign(payload))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        // Invalid inputs — path traversal
        @ParameterizedTest
        @DisplayName("IM8 S6: rejects path traversal patterns")
        @ValueSource(strings = {
            "../../../etc/passwd",
            "..\\..\\windows\\system32",
            "/etc/hosts"
        })
        void rejectsPathTraversal(String payload) {
            assertThatThrownBy(() -> sanitiser.sanitiseCallsign(payload))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        // Invalid inputs — length
        @Test @DisplayName("rejects callsign longer than 8 characters")
        void rejectsOverlongCallsign() {
            assertThatThrownBy(() -> sanitiser.sanitiseCallsign("TOOLONGCS"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("8");
        }

        // Invalid inputs — blank / null
        @Test @DisplayName("rejects null input")
        void rejectsNull() {
            assertThatThrownBy(() -> sanitiser.sanitiseCallsign(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test @DisplayName("rejects blank string")
        void rejectsBlank() {
            assertThatThrownBy(() -> sanitiser.sanitiseCallsign("   "))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test @DisplayName("rejects empty string")
        void rejectsEmpty() {
            assertThatThrownBy(() -> sanitiser.sanitiseCallsign(""))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        // Invalid inputs — special characters
        @ParameterizedTest
        @DisplayName("rejects callsigns with special characters")
        @ValueSource(strings = {
            "SIA 200",      // space
            "SIA-200",      // hyphen
            "SIA.200",      // dot
            "SIA@200",      // at-sign
            "SIA#200",      // hash
            "SIA%200",      // percent
            "SIA+200"       // plus
        })
        void rejectsSpecialCharacters(String callsign) {
            assertThatThrownBy(() -> sanitiser.sanitiseCallsign(callsign))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ── sanitiseSearchQuery ───────────────────────────────────────────

    @Nested @DisplayName("sanitiseSearchQuery()")
    class SanitiseSearchQueryTests {

        @Test @DisplayName("accepts valid partial callsign prefix SIA")
        void acceptsSiaPrefix() {
            assertThat(sanitiser.sanitiseSearchQuery("SIA")).isEqualTo("SIA");
        }

        @Test @DisplayName("normalises to uppercase")
        void normalisesToUppercase() {
            assertThat(sanitiser.sanitiseSearchQuery("mas")).isEqualTo("MAS");
        }

        @Test @DisplayName("trims whitespace")
        void trimsWhitespace() {
            assertThat(sanitiser.sanitiseSearchQuery("  EK  ")).isEqualTo("EK");
        }

        @Test @DisplayName("rejects XSS payload in search query")
        void rejectsXssInSearch() {
            assertThatThrownBy(() -> sanitiser.sanitiseSearchQuery("<script>"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test @DisplayName("rejects query longer than 100 characters")
        void rejectsOverlongQuery() {
            String overlong = "A".repeat(101);
            assertThatThrownBy(() -> sanitiser.sanitiseSearchQuery(overlong))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test @DisplayName("rejects null search query")
        void rejectsNull() {
            assertThatThrownBy(() -> sanitiser.sanitiseSearchQuery(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test @DisplayName("rejects blank search query")
        void rejectsBlank() {
            assertThatThrownBy(() -> sanitiser.sanitiseSearchQuery(""))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
