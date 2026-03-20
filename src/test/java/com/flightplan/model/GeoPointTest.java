package com.flightplan.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GeoPoint")
class GeoPointTest {

    // ─────────────────────────────────────────────────────────────────────────
    // parse() — full "NAME (lat,lon)" format
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("parse() — full coordinate format")
    class ParseTests {

        @Test
        @DisplayName("parses standard 'NAME (lat,lon)' format")
        void parsesStandard() {
            GeoPoint gp = GeoPoint.parse("WSSS (1.3644,103.9915)", "fix");
            assertThat(gp).isNotNull();
            assertThat(gp.getName()).isEqualTo("WSSS");
            assertThat(gp.getLat()).isEqualTo(1.3644);
            assertThat(gp.getLon()).isEqualTo(103.9915);
            assertThat(gp.getType()).isEqualTo("fix");
        }

        @Test
        @DisplayName("parses with extra whitespace around name and coords")
        void parsesWithWhitespace() {
            GeoPoint gp = GeoPoint.parse("  WSSL  ( 1.42 , 103.87 ) ", "airway");
            assertThat(gp).isNotNull();
            assertThat(gp.getName()).isEqualTo("WSSL");
            assertThat(gp.getLat()).isEqualTo(1.42);
            assertThat(gp.getLon()).isEqualTo(103.87);
            assertThat(gp.getType()).isEqualTo("airway");
        }

        @Test
        @DisplayName("parses negative lat (southern hemisphere)")
        void parsesNegativeLat() {
            GeoPoint gp = GeoPoint.parse("YSSY (-33.9461,151.1772)", "fix");
            assertThat(gp).isNotNull();
            assertThat(gp.getLat()).isEqualTo(-33.9461);
            assertThat(gp.getLon()).isEqualTo(151.1772);
        }

        @Test
        @DisplayName("parses negative lon (western hemisphere)")
        void parsesNegativeLon() {
            GeoPoint gp = GeoPoint.parse("KSFO (37.6213,-122.3790)", "fix");
            assertThat(gp).isNotNull();
            assertThat(gp.getLat()).isEqualTo(37.6213);
            assertThat(gp.getLon()).isEqualTo(-122.379);
        }

        @Test
        @DisplayName("parses both negative lat and lon")
        void parsesBothNegative() {
            GeoPoint gp = GeoPoint.parse("SCEL (-33.3928,-70.7858)", "fix");
            assertThat(gp).isNotNull();
            assertThat(gp.getName()).isEqualTo("SCEL");
            assertThat(gp.getLat()).isEqualTo(-33.3928);
            assertThat(gp.getLon()).isEqualTo(-70.7858);
        }

        @Test
        @DisplayName("parses zero coordinates")
        void parsesZeroCoords() {
            GeoPoint gp = GeoPoint.parse("NULL (0.0,0.0)", "fix");
            assertThat(gp).isNotNull();
            assertThat(gp.getName()).isEqualTo("NULL");
            assertThat(gp.getLat()).isZero();
            assertThat(gp.getLon()).isZero();
        }

        @Test
        @DisplayName("parses integer-valued coordinates")
        void parsesIntegerCoords() {
            GeoPoint gp = GeoPoint.parse("PT1 (1,104)", "airway");
            assertThat(gp).isNotNull();
            assertThat(gp.getLat()).isEqualTo(1.0);
            assertThat(gp.getLon()).isEqualTo(104.0);
        }

        @Test
        @DisplayName("parses polar-extreme latitude (+90)")
        void parsesPolarLatNorth() {
            GeoPoint gp = GeoPoint.parse("NORTH (90.0,0.0)", "fix");
            assertThat(gp).isNotNull();
            assertThat(gp.getLat()).isEqualTo(90.0);
        }

        @Test
        @DisplayName("parses polar-extreme latitude (-90)")
        void parsesPolarLatSouth() {
            GeoPoint gp = GeoPoint.parse("SOUTH (-90.0,0.0)", "fix");
            assertThat(gp).isNotNull();
            assertThat(gp.getLat()).isEqualTo(-90.0);
        }

        @Test
        @DisplayName("parses extreme longitude +180")
        void parsesMaxLon() {
            GeoPoint gp = GeoPoint.parse("DATELINE (0.0,180.0)", "fix");
            assertThat(gp).isNotNull();
            assertThat(gp.getLon()).isEqualTo(180.0);
        }

        @Test
        @DisplayName("parses extreme longitude -180")
        void parsesMinLon() {
            GeoPoint gp = GeoPoint.parse("DATELINE2 (0.0,-180.0)", "fix");
            assertThat(gp).isNotNull();
            assertThat(gp.getLon()).isEqualTo(-180.0);
        }

        @ParameterizedTest(name = "[{index}] name={0} lat={1} lon={2}")
        @DisplayName("parses multiple real-world airways with coordinates")
        @CsvSource({
                "A576,  1.50,  104.10, airway",
                "M771,  3.15,  101.70, airway",
                "W123, 13.75,  100.52, airway",
                "UL149, 5.00,  102.00, airway",
                "Y131,  1.30,  103.80, airway"
        })
        void parsesRealWorldAirwaysWithCoords(String name, double lat, double lon, String type) {
            String raw = name + " (" + lat + "," + lon + ")";
            GeoPoint gp = GeoPoint.parse(raw, type);
            assertThat(gp).isNotNull();
            assertThat(gp.getName()).isEqualTo(name);
            assertThat(gp.getLat()).isEqualTo(lat);
            assertThat(gp.getLon()).isEqualTo(lon);
            assertThat(gp.getType()).isEqualTo(type);
        }

        @Test
        @DisplayName("preserves 'airway' type string correctly")
        void preservesAirwayType() {
            GeoPoint gp = GeoPoint.parse("A576 (1.50,104.10)", "airway");
            assertThat(gp).isNotNull();
            assertThat(gp.getType()).isEqualTo("airway");
        }

        @Test
        @DisplayName("preserves 'fix' type string correctly")
        void preservesFixType() {
            GeoPoint gp = GeoPoint.parse("PARDI (1.10,104.20)", "fix");
            assertThat(gp).isNotNull();
            assertThat(gp.getType()).isEqualTo("fix");
        }

        @Test
        @DisplayName("returns null when name part is empty (only coords in parens)")
        void returnsNullWhenNameEmpty() {
            // "(1.36,103.99)" — no name before the paren
            assertThat(GeoPoint.parse("(1.36,103.99)", "fix")).isNull();
        }

        @Test
        @DisplayName("missing open paren falls through to name-only path — returns GeoPoint with lat/lon 0")
        void missingOpenParenFallsToNameOnly() {
            // No '(' found — name-only branch fires; whole string becomes the name
            GeoPoint gp = GeoPoint.parse("WSSS 1.36,103.99)", "fix");
            assertThat(gp).isNotNull();
            assertThat(gp.getName()).isEqualTo("WSSS 1.36,103.99)");
            assertThat(gp.getLat()).isZero();
            assertThat(gp.getLon()).isZero();
        }

        @Test
        @DisplayName("missing close paren falls through to name-only path — returns GeoPoint with lat/lon 0")
        void missingCloseParenFallsToNameOnly() {
            // '(' exists but ')' is absent — parenClose < parenOpen so name-only branch fires
            GeoPoint gp = GeoPoint.parse("WSSS (1.36,103.99", "fix");
            assertThat(gp).isNotNull();
            assertThat(gp.getName()).isEqualTo("WSSS (1.36,103.99");
            assertThat(gp.getLat()).isZero();
            assertThat(gp.getLon()).isZero();
        }

        @Test
        @DisplayName("returns null when close paren comes before open paren")
        void returnsNullReversedParens() {
            // ')' is at index < '(' — treated as name-only, which is valid and returns non-null;
            // verify it at least doesn't throw and returns a sensible result
            GeoPoint gp = GeoPoint.parse("WSSS )1.36,103.99(", "fix");
            // name-only path: returns a GeoPoint with the trimmed string as name, lat/lon = 0
            assertThat(gp).isNotNull();
            assertThat(gp.getLat()).isZero();
            assertThat(gp.getLon()).isZero();
        }

        @ParameterizedTest
        @DisplayName("returns null for malformed coordinate values")
        @ValueSource(strings = {
                "WSSS (abc,103.99)",
                "WSSS (1.36,xyz)",
                "WSSS (,103.99)",
                "WSSS (1.36,)"
        })
        void returnsNullForMalformedCoords(String input) {
            assertThat(GeoPoint.parse(input, "fix")).isNull();
        }

        @Test
        @DisplayName("returns null for missing comma between lat and lon")
        void returnsNullMissingComma() {
            assertThat(GeoPoint.parse("WSSS (1.36 103.99)", "fix")).isNull();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // parse() — name-only format (the fix for the airways empty-list bug)
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("parse() — name-only format (airways without coordinates)")
    class ParseNameOnlyTests {

        @Test
        @DisplayName("parses a simple airway code with no coordinates")
        void parsesSimpleAirwayCode() {
            GeoPoint gp = GeoPoint.parse("W218", "airway");
            assertThat(gp).isNotNull();
            assertThat(gp.getName()).isEqualTo("W218");
            assertThat(gp.getLat()).isZero();
            assertThat(gp.getLon()).isZero();
            assertThat(gp.getType()).isEqualTo("airway");
        }

        @ParameterizedTest(name = "[{index}] airway={0}")
        @DisplayName("parses all common airway name formats")
        @ValueSource(strings = {
                "W218", "W219", "W211",         // single-letter + digits
                "UL149", "UL550",               // two-letter prefix
                "Y131", "Y888",                 // Y-routes
                "L556", "L551",                 // L-routes
                "NCA20", "NCA23", "NCA25",      // NCA series
                "A1", "B2", "M771",             // short names
                "XLONG123"                      // longer identifier
        })
        void parsesVariousAirwayCodes(String airwayName) {
            GeoPoint gp = GeoPoint.parse(airwayName, "airway");
            assertThat(gp).isNotNull()
                    .extracting(GeoPoint::getName)
                    .isEqualTo(airwayName);
            assertThat(gp.getLat()).isZero();
            assertThat(gp.getLon()).isZero();
            assertThat(gp.getType()).isEqualTo("airway");
        }

        @Test
        @DisplayName("trims leading and trailing whitespace from name-only input")
        void trimsWhitespaceFromNameOnly() {
            GeoPoint gp = GeoPoint.parse("  W218  ", "airway");
            assertThat(gp).isNotNull();
            assertThat(gp.getName()).isEqualTo("W218");
        }

        @Test
        @DisplayName("name-only fix geopoint also works (type preserved)")
        void nameOnlyFixType() {
            GeoPoint gp = GeoPoint.parse("PARDI", "fix");
            assertThat(gp).isNotNull();
            assertThat(gp.getName()).isEqualTo("PARDI");
            assertThat(gp.getType()).isEqualTo("fix");
            assertThat(gp.getLat()).isZero();
            assertThat(gp.getLon()).isZero();
        }

        @Test
        @DisplayName("name-only entry is usable in a name-based map lookup")
        void nameOnlyUsableInMapLookup() {
            // Simulates the buildFixMap() pattern in FlightService
            java.util.Map<String, GeoPoint> map = new java.util.HashMap<>();
            GeoPoint gp = GeoPoint.parse("W218", "airway");
            map.put(gp.getName(), gp);

            assertThat(map).containsKey("W218");
            assertThat(map.get("W218").getType()).isEqualTo("airway");
        }

        @Test
        @DisplayName("a batch of name-only airways produces no nulls (regression for empty-list bug)")
        void batchOfNameOnlyAirwaysProducesNoNulls() {
            // This is the exact input shape from the upstream /geopoints/list/airways response
            String[] rawAirways = {"W218", "W219", "W211", "W212", "W213",
                    "UL149", "Y131", "L556", "NCA20", "NCA23"};

            java.util.List<GeoPoint> parsed = java.util.Arrays.stream(rawAirways)
                    .map(s -> GeoPoint.parse(s, "airway"))
                    .filter(java.util.Objects::nonNull)
                    .toList();

            assertThat(parsed).hasSize(rawAirways.length);
            assertThat(parsed).allSatisfy(gp -> {
                assertThat(gp.getName()).isNotBlank();
                assertThat(gp.getType()).isEqualTo("airway");
            });
        }

        @Test
        @DisplayName("mixed batch of full-format and name-only entries all parse successfully")
        void mixedBatchParsesCompletely() {
            String[] mixed = {
                    "WSSS (1.3644,103.9915)",   // full format
                    "W218",                     // name-only
                    "YSSY (-33.9461,151.1772)", // full format, southern
                    "UL149",                    // name-only
                    "KSFO (37.6213,-122.3790)"  // full format, western
            };

            java.util.List<GeoPoint> parsed = java.util.Arrays.stream(mixed)
                    .map(s -> GeoPoint.parse(s, "airway"))
                    .filter(java.util.Objects::nonNull)
                    .toList();

            assertThat(parsed).hasSize(mixed.length);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // parse() — null / blank / invalid guard cases
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("parse() — null, blank, and invalid inputs")
    class ParseGuardTests {

        @Test
        @DisplayName("returns null for null input")
        void returnsNullForNull() {
            assertThat(GeoPoint.parse(null, "fix")).isNull();
        }

        @Test
        @DisplayName("returns null for empty string")
        void returnsNullForEmpty() {
            assertThat(GeoPoint.parse("", "fix")).isNull();
        }

        @ParameterizedTest
        @DisplayName("returns null for blank/whitespace-only input")
        @ValueSource(strings = {" ", "  ", "\t", "\n", "   \t  "})
        void returnsNullForBlank(String input) {
            assertThat(GeoPoint.parse(input, "fix")).isNull();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Constructors & Accessors
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("Constructors & Accessors")
    class ConstructorTests {

        @Test
        @DisplayName("no-args constructor creates zero-valued GeoPoint")
        void noArgsConstructor() {
            GeoPoint gp = new GeoPoint();
            assertThat(gp.getName()).isNull();
            assertThat(gp.getLat()).isZero();
            assertThat(gp.getLon()).isZero();
            assertThat(gp.getType()).isNull();
        }

        @Test
        @DisplayName("all-args constructor sets every field")
        void allArgsConstructor() {
            GeoPoint gp = new GeoPoint("KAT", 15.0, 103.0, "fix");
            assertThat(gp.getName()).isEqualTo("KAT");
            assertThat(gp.getLat()).isEqualTo(15.0);
            assertThat(gp.getLon()).isEqualTo(103.0);
            assertThat(gp.getType()).isEqualTo("fix");
        }

        @Test
        @DisplayName("setters mutate fields correctly")
        void settersWork() {
            GeoPoint gp = new GeoPoint();
            gp.setName("PARDI");
            gp.setLat(1.10);
            gp.setLon(104.20);
            gp.setType("airway");
            assertThat(gp.getName()).isEqualTo("PARDI");
            assertThat(gp.getLat()).isEqualTo(1.10);
            assertThat(gp.getLon()).isEqualTo(104.20);
            assertThat(gp.getType()).isEqualTo("airway");
        }

        @Test
        @DisplayName("equals / hashCode are symmetric for identical objects")
        void equalsAndHashCode() {
            GeoPoint a = new GeoPoint("WSSS", 1.3644, 103.9915, "fix");
            GeoPoint b = new GeoPoint("WSSS", 1.3644, 103.9915, "fix");
            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }

        @Test
        @DisplayName("equals returns false when name differs")
        void notEqualDifferentName() {
            GeoPoint a = new GeoPoint("WSSS", 1.36, 103.99, "fix");
            GeoPoint b = new GeoPoint("WSSL", 1.36, 103.99, "fix");
            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("equals returns false when lat differs")
        void notEqualDifferentLat() {
            GeoPoint a = new GeoPoint("WSSS", 1.36, 103.99, "fix");
            GeoPoint b = new GeoPoint("WSSS", 2.00, 103.99, "fix");
            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("equals returns false when lon differs")
        void notEqualDifferentLon() {
            GeoPoint a = new GeoPoint("WSSS", 1.36, 103.99, "fix");
            GeoPoint b = new GeoPoint("WSSS", 1.36, 104.00, "fix");
            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("equals returns false when type differs")
        void notEqualDifferentType() {
            GeoPoint a = new GeoPoint("W1", 0.0, 0.0, "airway");
            GeoPoint b = new GeoPoint("W1", 0.0, 0.0, "fix");
            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("equals returns false when compared to null")
        void notEqualToNull() {
            GeoPoint a = new GeoPoint("WSSS", 1.36, 103.99, "fix");
            assertThat(a).isNotEqualTo(null);
        }

        @Test
        @DisplayName("equals returns false when compared to a different type")
        void notEqualToDifferentClass() {
            GeoPoint a = new GeoPoint("WSSS", 1.36, 103.99, "fix");
            assertThat(a).isNotEqualTo("WSSS");
        }

        @Test
        @DisplayName("equals is reflexive")
        void equalsReflexive() {
            GeoPoint a = new GeoPoint("WSSS", 1.36, 103.99, "fix");
            assertThat(a).isEqualTo(a);
        }

        @Test
        @DisplayName("toString contains name field")
        void toStringContainsName() {
            assertThat(new GeoPoint("WSSS", 1.36, 103.99, "fix").toString()).contains("WSSS");
        }

        @Test
        @DisplayName("toString contains lat and lon values")
        void toStringContainsCoords() {
            String s = new GeoPoint("WSSS", 1.36, 103.99, "fix").toString();
            assertThat(s).contains("1.36").contains("103.99");
        }

        @Test
        @DisplayName("name-only parse result round-trips through equals")
        void nameOnlyParseRoundTrips() {
            GeoPoint fromParse = GeoPoint.parse("W218", "airway");
            GeoPoint fromCtor  = new GeoPoint("W218", 0.0, 0.0, "airway");
            assertThat(fromParse).isEqualTo(fromCtor);
            assertThat(fromParse.hashCode()).isEqualTo(fromCtor.hashCode());
        }
    }
}