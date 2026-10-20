package local.glasstv.launcher;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashSet;
import java.util.Set;

/** Validates and copies the small weather configuration; it contains no credentials. */
final class WeatherSettings {
    static final double DEFAULT_CONTENT_SCALE = 1.25d;
    static final double MIN_CONTENT_SCALE = 1d;
    static final double MAX_CONTENT_SCALE = 1.6d;

    static JSONObject defaults() {
        try { return validate(new JSONObject()); }
        catch (Exception impossible) { return new JSONObject(); }
    }

    static JSONObject validate(JSONObject source) throws Exception {
        JSONObject result = new JSONObject();
        result.put("enabled", source.optBoolean("enabled", false));
        String style = source.optString("style", "minimal");
        // Existing playlists used detailed. Preserve their weather data while rendering the new minimal card.
        if (style.equals("detailed")) style = "minimal";
        // Portrait shipped briefly as a style. Its wire migration keeps the scenic treatment and
        // turns on the new presentation toggle so old playlists continue to render identically.
        boolean legacyPortrait = style.equals("portrait");
        if (legacyPortrait) style = "scenic";
        if (!style.equals("compact") && !style.equals("minimal") && !style.equals("scenic")
                && !style.equals("icon"))
            throw new IllegalArgumentException("Unknown weather style");
        result.put("style", style);
        String unit = source.optString("unit", "celsius");
        if (!unit.equals("celsius") && !unit.equals("fahrenheit")) throw new IllegalArgumentException("Unknown temperature unit");
        result.put("unit", unit);
        result.put("showTemperature", source.optBoolean("showTemperature", true));
        result.put("showHourly", source.optBoolean("showHourly", true));
        result.put("showDaily", source.optBoolean("showDaily", true));
        // Widget content grows inside its existing card. Keep legacy payloads readable and make
        // malformed direct receiver writes harmless before they reach the Canvas renderer.
        result.put("contentScale", contentScale(source.opt("contentScale")));
        // This presentation applies to every surfaced card style except Icon & temperature.
        result.put("portraitCards", legacyPortrait || source.optBoolean("portraitCards", false));
        // Kept as a raw preference even outside portrait so changing styles does not erase it.
        result.put("showAllLocations", source.optBoolean("showAllLocations", false));
        int cycle = source.optInt("cycleSeconds", 0);
        if (cycle != 0 && cycle != 30 && cycle != 60 && cycle != 300) throw new IllegalArgumentException("Unknown location cycle interval");
        result.put("cycleSeconds", cycle);
        JSONArray input = source.optJSONArray("locations");
        if (source.has("locations") && input == null) throw new IllegalArgumentException("Weather locations must be a list");
        if (input == null) input = new JSONArray();
        if (input.length() > 6) throw new IllegalArgumentException("Add up to six weather locations");
        JSONArray locations = new JSONArray();
        Set<String> seen = new HashSet<>();
        Set<String> enabled = new HashSet<>();
        String firstEnabled = null;
        for (int i = 0; i < input.length(); i++) {
            JSONObject location = input.getJSONObject(i);
            String id = location.getString("id");
            String name = location.getString("name").trim();
            String country = location.optString("country", "").trim();
            double latitude = location.getDouble("latitude");
            double longitude = location.getDouble("longitude");
            if (!id.matches("[a-zA-Z0-9_-]{1,80}") || !seen.add(id) || name.isEmpty() || name.length() > 100 || country.length() > 100 || !Double.isFinite(latitude) || !Double.isFinite(longitude) || latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) throw new IllegalArgumentException("Invalid weather location");
            int artworkVariant = artworkVariant(location);
            boolean show = location.optBoolean("enabled", true);
            if (show) { enabled.add(id); if (firstEnabled == null) firstEnabled = id; }
            locations.put(new JSONObject().put("id", id).put("name", name).put("country", country).put("latitude", latitude).put("longitude", longitude).put("enabled", show).put("artworkVariant", artworkVariant));
        }
        result.put("locations", locations);
        String selected = source.isNull("selectedLocationId") ? null : source.optString("selectedLocationId", null);
        if (selected == null || !enabled.contains(selected)) selected = firstEnabled;
        result.put("selectedLocationId", selected == null ? JSONObject.NULL : selected);
        return result;
    }

    static double contentScale(Object value) {
        if (!(value instanceof Number)) return DEFAULT_CONTENT_SCALE;
        double candidate = ((Number) value).doubleValue();
        if (!Double.isFinite(candidate)) return DEFAULT_CONTENT_SCALE;
        return Math.max(MIN_CONTENT_SCALE, Math.min(MAX_CONTENT_SCALE, candidate));
    }

    /** Missing variants migrate to the first scene; values outside the Mac picker range are rejected. */
    private static int artworkVariant(JSONObject location) {
        if (!location.has("artworkVariant")) return 0;
        Object value = location.opt("artworkVariant");
        if (!(value instanceof Number)) throw new IllegalArgumentException("Invalid weather artwork variant");
        double number = ((Number) value).doubleValue();
        if (!Double.isFinite(number) || Math.rint(number) != number || number < 0 || number > 4) {
            throw new IllegalArgumentException("Invalid weather artwork variant");
        }
        return (int) number;
    }

    static JSONObject copy(JSONObject source) {
        try { return new JSONObject(source.toString()); }
        catch (Exception impossible) { return defaults(); }
    }
}
