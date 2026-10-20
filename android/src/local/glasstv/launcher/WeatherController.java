package local.glasstv.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Foreground-only Open-Meteo reader. It never receives Glass TV credentials. */
final class WeatherController {
    static final long CACHE_MS = 30L * 60L * 1000L;

    interface Listener { void onWeatherChanged(WeatherController controller); }

    static final class Location {
        final String id, name, country;
        final double latitude, longitude;
        final int artworkVariant;

        Location(JSONObject value) {
            id = value.optString("id");
            name = value.optString("name");
            country = value.optString("country");
            latitude = value.optDouble("latitude", Double.NaN);
            longitude = value.optDouble("longitude", Double.NaN);
            int candidate = value.optInt("artworkVariant", 0);
            artworkVariant = candidate >= 0 && candidate <= 4 ? candidate : 0;
        }

        String label() { return country.isEmpty() ? name : name + ", " + country; }
    }

    static final class Reading {
        final JSONObject data;
        final long updatedAt;
        final boolean stale, offline;

        Reading(JSONObject data, long updatedAt, boolean stale, boolean offline) {
            this.data = data;
            this.updatedAt = updatedAt;
            this.stale = stale;
            this.offline = offline;
        }
    }

    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Listener listener;
    private final Map<String, Reading> readings = new ConcurrentHashMap<>();
    private volatile JSONObject config = new JSONObject();
    private volatile boolean foreground;
    private volatile String visibleId;
    private volatile int configGeneration;
    private boolean fetchInFlight;
    private long nextRefreshAtMillis;

    private final Runnable refresh = new Runnable() {
        @Override public void run() { refreshIfNeeded(); }
    };
    private final Runnable cycle = new Runnable() {
        @Override public void run() { selectNext(1); scheduleCycle(); }
    };

    WeatherController(Context context, Listener listener) {
        prefs = context.getSharedPreferences("weather-cache", Context.MODE_PRIVATE);
        this.listener = listener;
    }

    void setConfig(JSONObject next) {
        JSONObject candidate;
        try {
            candidate = next == null ? new JSONObject() : new JSONObject(next.toString());
        } catch (Exception badJson) {
            candidate = new JSONObject();
        }
        // Media-only playlist writes leave transient city selection and refresh timing intact.
        if (sameJson(config, candidate)) return;
        config = candidate;
        configGeneration++;
        fetchInFlight = false;
        nextRefreshAtMillis = 0;
        visibleId = null;
        readings.clear();
        notifyChanged();
        if (foreground) {
            refreshIfNeeded();
            scheduleCycle();
        }
    }

    void start() {
        foreground = true;
        refreshIfNeeded();
        scheduleCycle();
    }

    void stop() {
        foreground = false;
        // A response finishing after a sleep/background transition is stale.
        configGeneration++;
        fetchInFlight = false;
        nextRefreshAtMillis = 0;
        main.removeCallbacks(refresh);
        main.removeCallbacks(cycle);
    }

    /** A user-requested settings refresh bypasses only the normal cache timer, never location validation. */
    void refreshNow() {
        nextRefreshAtMillis = 0;
        if (foreground) refreshIfNeeded();
    }

    void destroy() {
        stop();
        worker.shutdownNow();
    }

    boolean enabled() { return config.optBoolean("enabled", false) && !locations().isEmpty(); }
    String style() {
        String value = config.optString("style", "minimal");
        // Controller callers normally receive WeatherSettings-validated configuration, but this
        // preserves the migration for a transient direct caller too.
        return "detailed".equals(value) ? "minimal" : "portrait".equals(value) ? "scenic" : value;
    }
    boolean temperature() { return config.optBoolean("showTemperature", true); }
    boolean hourly() { return config.optBoolean("showHourly", true); }
    boolean daily() { return config.optBoolean("showDaily", true); }
    /** Content scale is defensive because transient callers can bypass WeatherSettings.validate. */
    double contentScale() { return WeatherSettings.contentScale(config.opt("contentScale")); }
    boolean portraitCards() { return config.optBoolean("portraitCards", false) || "portrait".equals(config.optString("style")); }
    boolean portraitEnabled() { return portraitCards() && !"icon".equals(style()); }
    boolean showAllLocations() { return config.optBoolean("showAllLocations", false); }
    boolean portraitShowsAllLocations() { return portraitEnabled() && showAllLocations(); }
    String unit() { return "fahrenheit".equals(config.optString("unit")) ? "fahrenheit" : "celsius"; }

    List<Location> locations() {
        ArrayList<Location> result = new ArrayList<>();
        JSONArray source = config.optJSONArray("locations");
        if (source == null) return result;
        for (int i = 0; i < source.length(); i++) {
            JSONObject value = source.optJSONObject(i);
            if (value == null || !value.optBoolean("enabled", false)) continue;
            double latitude = value.optDouble("latitude", Double.NaN);
            double longitude = value.optDouble("longitude", Double.NaN);
            if (finite(latitude) && finite(longitude)) result.add(new Location(value));
        }
        return result;
    }

    Location selected() {
        List<Location> all = locations();
        if (all.isEmpty()) return null;
        String wanted = visibleId != null ? visibleId : config.optString("selectedLocationId", null);
        for (Location location : all) if (location.id.equals(wanted)) return location;
        return all.get(0);
    }

    void selectNext(int direction) {
        List<Location> all = locations();
        if (all.isEmpty()) return;
        Location current = selected();
        int index = 0;
        for (int i = 0; i < all.size(); i++) if (all.get(i).id.equals(current.id)) index = i;
        visibleId = all.get((index + direction + all.size()) % all.size()).id;
        notifyChanged();
    }

    void select(String id) {
        for (Location location : locations()) {
            if (location.id.equals(id)) {
                visibleId = id;
                notifyChanged();
                return;
            }
        }
    }

    Reading reading() {
        Location selected = selected();
        return selected == null ? null : readingFor(selected);
    }

    Reading readingFor(Location location) { return location == null ? null : readings.get(location.id); }

    JSONObject statusSnapshot() {
        try {
            JSONObject out = new JSONObject();
            Location location = selected();
            Reading reading = reading();
            out.put("enabled", enabled());
            out.put("style", style());
            out.put("portraitCards", portraitCards());
            out.put("portraitEnabled", portraitEnabled());
            out.put("showAllLocations", showAllLocations());
            out.put("portraitShowsAllLocations", portraitShowsAllLocations());
            out.put("selectedLocationId", location == null ? JSONObject.NULL : location.id);
            out.put("cacheLocationCount", readings.size());
            out.put("state", !enabled() ? "idle" : reading == null || reading.data == null
                    ? (reading != null && reading.offline ? "unavailable" : "loading") : "ready");
            out.put("updatedAtMillis", reading == null ? JSONObject.NULL : reading.updatedAt);
            JSONObject current = reading == null || reading.data == null ? null : reading.data.optJSONObject("current");
            out.put("currentTemperature", current == null ? JSONObject.NULL : current.opt("temperature_2m"));
            JSONObject hourly = reading == null || reading.data == null ? null : reading.data.optJSONObject("hourly");
            JSONObject daily = reading == null || reading.data == null ? null : reading.data.optJSONObject("daily");
            out.put("hourlyCount", hourly == null || hourly.optJSONArray("time") == null ? 0 : hourly.optJSONArray("time").length());
            out.put("dailyCount", daily == null || daily.optJSONArray("time") == null ? 0 : daily.optJSONArray("time").length());
            return out;
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    private void refreshIfNeeded() {
        if (!foreground || !enabled()) return;
        long now = System.currentTimeMillis();
        if (now < nextRefreshAtMillis) {
            schedule();
            return;
        }
        List<Location> locations = locations();
        String unit = unit();
        boolean needsFetch = false;
        long next = Long.MAX_VALUE;
        for (Location location : locations) {
            Reading cached = load(location, unit);
            readings.put(location.id, cached);
            if (cached.updatedAt <= 0 || now - cached.updatedAt >= CACHE_MS) needsFetch = true;
            else next = Math.min(next, cached.updatedAt + CACHE_MS);
        }
        notifyChanged();
        if (needsFetch && !fetchInFlight) {
            // Failed cities wait for the normal interval instead of retrying in a tight loop.
            nextRefreshAtMillis = now + CACHE_MS;
            fetchInFlight = true;
            final int generation = configGeneration;
            worker.execute(() -> fetchAll(locations, unit, generation));
        } else if (!needsFetch) {
            nextRefreshAtMillis = next == Long.MAX_VALUE ? now + CACHE_MS : next;
        }
        schedule();
    }

    private void schedule() {
        main.removeCallbacks(refresh);
        if (!foreground || !enabled()) return;
        long delay = Math.max(1000L, nextRefreshAtMillis - System.currentTimeMillis());
        main.postDelayed(refresh, delay);
    }

    private void scheduleCycle() {
        main.removeCallbacks(cycle);
        int seconds = config.optInt("cycleSeconds", 0);
        if (foreground && enabled() && !portraitShowsAllLocations()
                && (seconds == 30 || seconds == 60 || seconds == 300)
                && locations().size() > 1) main.postDelayed(cycle, seconds * 1000L);
    }

    private void fetchAll(List<Location> locations, String unit, int generation) {
        for (Location location : locations) {
            if (!foreground || generation != configGeneration) break;
            try {
                JSONObject fresh = new JSONObject(get(url(location, unit)));
                long now = System.currentTimeMillis();
                if (generation == configGeneration) {
                    String key = cacheKey(location, unit);
                    prefs.edit().putString("data." + key, fresh.toString()).putLong("time." + key, now).apply();
                    readings.put(location.id, new Reading(fresh, now, false, false));
                }
            } catch (Exception failure) {
                if (generation == configGeneration) {
                    Reading cached = load(location, unit);
                    readings.put(location.id, new Reading(cached.data, cached.updatedAt, true, true));
                }
            }
            main.post(this::notifyChanged);
        }
        main.post(() -> {
            if (generation != configGeneration) return;
            fetchInFlight = false;
            schedule();
        });
    }

    private Reading load(Location location, String unit) {
        String key = cacheKey(location, unit);
        String raw = prefs.getString("data." + key, null);
        long updatedAt = prefs.getLong("time." + key, 0);
        try {
            return new Reading(raw == null ? null : new JSONObject(raw), updatedAt,
                    System.currentTimeMillis() - updatedAt >= CACHE_MS, false);
        } catch (Exception badCache) {
            return new Reading(null, 0, true, false);
        }
    }

    private static boolean sameJson(Object first, Object second) {
        if (first == second) return true;
        if (first == null || second == null) return false;
        if (first instanceof JSONObject && second instanceof JSONObject) {
            JSONObject left = (JSONObject) first;
            JSONObject right = (JSONObject) second;
            if (left.length() != right.length()) return false;
            Iterator<String> keys = left.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (!right.has(key) || !sameJson(left.opt(key), right.opt(key))) return false;
            }
            return true;
        }
        if (first instanceof JSONArray && second instanceof JSONArray) {
            JSONArray left = (JSONArray) first;
            JSONArray right = (JSONArray) second;
            if (left.length() != right.length()) return false;
            for (int i = 0; i < left.length(); i++) if (!sameJson(left.opt(i), right.opt(i))) return false;
            return true;
        }
        if (first instanceof Number && second instanceof Number) {
            return Double.compare(((Number) first).doubleValue(), ((Number) second).doubleValue()) == 0;
        }
        return first.equals(second);
    }

    private static String cacheKey(Location location, String unit) {
        return location.id + "." + unit + "." + Double.doubleToLongBits(location.latitude)
                + "." + Double.doubleToLongBits(location.longitude);
    }

    private String url(Location location, String unit) {
        return "https://api.open-meteo.com/v1/forecast?latitude=" + location.latitude
                + "&longitude=" + location.longitude
                + "&current=temperature_2m,weather_code,is_day"
                + "&hourly=temperature_2m,weather_code,is_day"
                + "&daily=weather_code,temperature_2m_max,temperature_2m_min"
                + "&temperature_unit=" + unit + "&timezone=auto&timeformat=unixtime&forecast_days=5";
    }

    private static String get(String value) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(value).openConnection();
        connection.setConnectTimeout(7000);
        connection.setReadTimeout(10000);
        connection.setRequestProperty("Accept", "application/json");
        try (BufferedInputStream input = new BufferedInputStream(connection.getInputStream())) {
            byte[] buffer = new byte[8192];
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            int count;
            while ((count = input.read(buffer)) > 0) {
                if (output.size() + count > 131072) throw new Exception("weather response too large");
                output.write(buffer, 0, count);
            }
            return output.toString("UTF-8");
        } finally {
            connection.disconnect();
        }
    }

    private void notifyChanged() { main.post(() -> listener.onWeatherChanged(this)); }
    private static boolean finite(double value) { return !Double.isNaN(value) && !Double.isInfinite(value); }
}
