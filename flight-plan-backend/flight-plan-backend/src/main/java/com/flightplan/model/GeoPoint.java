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
}
