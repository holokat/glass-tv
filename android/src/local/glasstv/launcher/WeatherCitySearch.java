package local.glasstv.launcher;

import android.os.Handler;
import android.os.Looper;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;

/** Cancellable, bounded Open-Meteo geocoding for the TV settings location picker. */
final class WeatherCitySearch {
  private static final int MAX_RESPONSE_BYTES = 256 * 1024;
  static final class Result {
    final String id;
    final String name;
    final String admin1;
    final String country;
    final double latitude;
    final double longitude;

    Result(String id, String name, String admin1, String country, double latitude, double longitude) {
      this.id = id;
      this.name = name;
      this.admin1 = admin1;
      this.country = country;
      this.latitude = latitude;
      this.longitude = longitude;
    }

    String label() {
      String place = hasDistinctAdmin() ? name + ", " + admin1 : name;
      return country.isEmpty() ? place : place + ", " + country;
    }

    private boolean hasDistinctAdmin() { return !admin1.isEmpty() && !admin1.equalsIgnoreCase(name); }

    JSONObject asLocation() throws Exception {
      return new JSONObject()
          .put("id", id)
          .put("name", name)
          // WeatherSettings has a compact name/country wire format. Keep the regional qualifier
          // in its display lane so same-country city results remain distinguishable after saving.
          .put("country", hasDistinctAdmin() ? admin1 + ", " + country : country)
          .put("latitude", latitude)
          .put("longitude", longitude)
          .put("enabled", true)
          .put("artworkVariant", 0);
    }
  }

  interface Callback {
    void onComplete(List<Result> results, String error);
  }

  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final Handler main = new Handler(Looper.getMainLooper());
  private final AtomicInteger request = new AtomicInteger();

  void search(String query, Callback callback) {
    final int token = request.incrementAndGet();
    final String trimmed = query == null ? "" : query.trim();
    if (trimmed.isEmpty()) {
      callback.onComplete(Collections.<Result>emptyList(), "Enter a city name.");
      return;
    }
    worker.execute(
        () -> {
          List<Result> results = Collections.emptyList();
          String error = null;
          try {
            results = fetch(trimmed);
          } catch (Exception unavailable) {
            error = "Location search is unavailable. Check the TV connection and try again.";
          }
          final List<Result> delivered = results;
          final String deliveredError = error;
          main.post(
              () -> {
                if (request.get() == token) callback.onComplete(delivered, deliveredError);
              });
        });
  }

  void cancel() { request.incrementAndGet(); }

  void destroy() {
    cancel();
    worker.shutdownNow();
  }

  static List<Result> parse(String body) throws Exception {
    JSONObject root = new JSONObject(body);
    JSONArray values = root.optJSONArray("results");
    if (values == null) return Collections.emptyList();
    ArrayList<Result> results = new ArrayList<>();
    for (int index = 0; index < values.length() && results.size() < 8; index++) {
      JSONObject value = values.optJSONObject(index);
      if (value == null) continue;
      Object rawId = value.opt("id");
      String name = value.optString("name", "").trim();
      String admin1 = value.optString("admin1", "").trim();
      double latitude = value.optDouble("latitude", Double.NaN);
      double longitude = value.optDouble("longitude", Double.NaN);
      if (!(rawId instanceof Number)
          || name.isEmpty()
          || name.length() > 100
          || admin1.length() > 100
          || !Double.isFinite(latitude)
          || !Double.isFinite(longitude)
          || latitude < -90d
          || latitude > 90d
          || longitude < -180d
          || longitude > 180d) continue;
      String country = value.optString("country", "").trim();
      double numericId = ((Number) rawId).doubleValue();
      if (!Double.isFinite(numericId) || numericId <= 0d || Math.rint(numericId) != numericId || country.length() > 100)
        continue;
      results.add(new Result("geo-" + ((Number) rawId).longValue(), name, admin1, country, latitude, longitude));
    }
    return Collections.unmodifiableList(results);
  }

  private static List<Result> fetch(String query) throws Exception {
    String encoded = URLEncoder.encode(query, "UTF-8");
    HttpURLConnection connection =
        (HttpURLConnection)
            new URL("https://geocoding-api.open-meteo.com/v1/search?count=8&language=en&format=json&name=" + encoded)
                .openConnection();
    connection.setConnectTimeout(8_000);
    connection.setReadTimeout(8_000);
    connection.setRequestMethod("GET");
    connection.setUseCaches(false);
    try {
      if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) throw new IllegalStateException();
      try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
        byte[] buffer = new byte[4096];
        for (int count; (count = input.read(buffer)) != -1; ) {
          if (output.size() + count > MAX_RESPONSE_BYTES) throw new IllegalStateException("Location response too large");
          output.write(buffer, 0, count);
        }
        return parse(new String(output.toByteArray(), StandardCharsets.UTF_8));
      }
    } finally {
      connection.disconnect();
    }
  }
}
