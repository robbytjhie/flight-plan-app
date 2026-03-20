package com.flightplan.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import com.fasterxml.jackson.databind.JsonNode;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class GeoPoint {
    private String name;
    private double lat;
    private double lon;
    private String type; // "airway" or "fix"

    /**
     * Parse a geopoint string like "WSSS (1.36,103.99)"
     */
    public static GeoPoint parse(String raw, String type) {
        try {
            String trimmed = raw.trim();
            int parenOpen = trimmed.indexOf('(');
            int parenClose = trimmed.indexOf(')');
            if (parenOpen < 0 || parenClose < 0) return null;

            String name = trimmed.substring(0, parenOpen).trim();
            String coords = trimmed.substring(parenOpen + 1, parenClose);
            String[] parts = coords.split(",");
            double lat = Double.parseDouble(parts[0].trim());
            double lon = Double.parseDouble(parts[1].trim());
            return new GeoPoint(name, lat, lon, type);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Parse a JSON object geopoint (live upstream sometimes returns objects instead of strings).
     *
     * Supported shapes (best-effort):
     * - { "name": "WSSS", "lat": 1.36, "lon": 103.99 }
     * - { "icao": "WSSS", "latitude": 1.36, "longitude": 103.99 }
     * - { "designatedPoint": "WSSS", "lat": 1.36, "lng": 103.99 }
     */
    public static GeoPoint fromJsonObject(JsonNode node, String type) {
        if (node == null || !node.isObject()) return null;

        try {
            String name = firstNonBlankText(node,
                    "name",
                    "icao",
                    "designatedPoint",
                    "designated_point",
                    "id",
                    "fix",
                    "waypoint",
                    "code");

            if (name == null || name.isBlank()) return null;

            Double lat = firstDouble(node,
                    "lat",
                    "latitude",
                    "y");
            Double lon = firstDouble(node,
                    "lon",
                    "lng",
                    "longitude",
                    "x");

            if (lat == null || lon == null) return null;

            return new GeoPoint(name, lat, lon, type);
        } catch (Exception e) {
            return null;
        }
    }

    private static String firstNonBlankText(JsonNode node, String... fields) {
        for (String f : fields) {
            JsonNode v = node.get(f);
            if (v != null && !v.isNull()) {
                String s = v.asText(null);
                if (s != null && !s.isBlank()) return s;
            }
        }
        return null;
    }

    private static Double firstDouble(JsonNode node, String... fields) {
        for (String f : fields) {
            JsonNode v = node.get(f);
            if (v == null || v.isNull()) continue;
            if (v.isNumber()) return v.asDouble();
            // sometimes numbers are strings
            if (v.isTextual()) {
                String s = v.asText(null);
                if (s == null || s.isBlank()) continue;
                try {
                    return Double.parseDouble(s.trim());
                } catch (NumberFormatException ignored) {
                    // continue searching
                }
            }
        }
        return null;
    }
}
