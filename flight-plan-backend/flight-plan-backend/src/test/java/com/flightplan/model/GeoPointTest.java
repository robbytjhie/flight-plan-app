package com.flightplan.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GeoPoint")
class GeoPointTest {

    @Nested
    @DisplayName("parse()")
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
        @DisplayName("returns null when opening parenthesis is missing")
        void returnsNullMissingOpenParen() {
            assertThat(GeoPoint.parse("WSSS 1.36,103.99)", "fix")).isNull();
        }

        @Test
        @DisplayName("returns null when closing parenthesis is missing")
        void returnsNullMissingCloseParen() {
            assertThat(GeoPoint.parse("WSSS (1.36,103.99", "fix")).isNull();
        }

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
        @DisplayName("preserves 'airway' type string correctly")
        void preservesAirwayType() {
            GeoPoint gp = GeoPoint.parse("A576 (1.50,104.10)", "airway");
            assertThat(gp).isNotNull();
            assertThat(gp.getType()).isEqualTo("airway");
        }
    }

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
        @DisplayName("toString contains name field")
        void toStringContainsName() {
            assertThat(new GeoPoint("WSSS", 1.36, 103.99, "fix").toString()).contains("WSSS");
        }
    }
}
