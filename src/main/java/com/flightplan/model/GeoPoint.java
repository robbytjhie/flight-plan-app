package com.flightplan.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class GeoPoint {
    private String name;
    private double lat;
    private double lon;
    private String type; // "airway" or "fix"

    /**
     * Parse a geopoint string.
     *
     * Supports two formats returned by the upstream API:
     *   1. Full format with coordinates: "WSSS (1.36,103.99)"
     *   2. Name-only format (airways): "W218"
     *
     * For name-only entries, lat/lon default to 0.0. These entries are still
     * useful for route lookups by name (e.g. airway identification in filed routes).
     */
    public static GeoPoint parse(String raw, String type) {
        if (raw == null || raw.isBlank()) return null;
        try {
            String trimmed = raw.trim();
            int parenOpen = trimmed.indexOf('(');
            int parenClose = trimmed.indexOf(')');

            if (parenOpen >= 0 && parenClose > parenOpen) {
                // Full format: "NAME (lat,lon)"
                String name = trimmed.substring(0, parenOpen).trim();
                if (name.isEmpty()) return null;
                String coords = trimmed.substring(parenOpen + 1, parenClose);
                String[] parts = coords.split(",");
                double lat = Double.parseDouble(parts[0].trim());
                double lon = Double.parseDouble(parts[1].trim());
                return new GeoPoint(name, lat, lon, type);
            } else {
                // Name-only format: "W218"
                // No coordinates available — store with 0,0 so name-based lookups still work.
                return new GeoPoint(trimmed, 0.0, 0.0, type);
            }
        } catch (Exception e) {
            return null;
        }
    }
}