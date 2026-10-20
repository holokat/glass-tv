package local.glasstv.launcher;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipFile;
import org.json.JSONArray;
import org.json.JSONObject;

/** Device-runnable configuration checks, optionally decoding the supplied APK artwork without opening an activity. */
public final class WeatherSettingsChecks {
  public static void main(String[] args) throws Exception {
    // app_process does not inherit Zygote's preloaded fonts.
    if (android.graphics.Typeface.DEFAULT == null) {
      java.lang.reflect.Method fonts = android.graphics.Typeface.class
          .getDeclaredMethod("loadPreinstalledSystemFontMap");
      fonts.setAccessible(true);
      fonts.invoke(null);
    }
    JSONObject legacy = WeatherSettings.validate(new JSONObject().put("locations", new JSONArray().put(location())));
    check(
        legacy.getJSONArray("locations").getJSONObject(0).getInt("artworkVariant") == 0,
        "missing artwork variant migrates to scene zero");

    JSONObject selected = location().put("artworkVariant", 4);
    JSONObject preserved = WeatherSettings.validate(new JSONObject().put("locations", new JSONArray().put(selected)));
    check(
        preserved.getJSONArray("locations").getJSONObject(0).getInt("artworkVariant") == 4,
        "valid artwork variant survives sanitization");

    checkRejected(-1, "negative artwork variant is rejected");
    checkRejected(5, "out-of-range artwork variant is rejected");
    checkRejected("2", "non-numeric artwork variant is rejected");

    JSONObject iconStyle = WeatherSettings.validate(new JSONObject().put("style", "icon"));
    check("icon".equals(iconStyle.getString("style")), "compact icon weather style survives validation");
    check(
        "Forecast unavailable".equals(WeatherWidget.iconContentDescription(Double.NaN, -1, "celsius")),
        "icon style exposes an honest unavailable description");
    check(
        "Forecast unavailable; Temperature 21°C".equals(
            WeatherWidget.iconContentDescription(21, -1, "celsius")),
        "temperature without a condition code does not claim a weather condition");

    JSONObject defaults = WeatherSettings.defaults();
    check(Math.abs(defaults.getDouble("contentScale") - 1.25d) < .0001d,
        "content scale defaults to the shared readable baseline");
    check(Math.abs(WeatherSettings.validate(new JSONObject().put("contentScale", 1.6d))
            .getDouble("contentScale") - 1.6d) < .0001d,
        "content scale preserves the maximum selected by the settings dragger");
    check(Math.abs(WeatherSettings.validate(new JSONObject().put("contentScale", .4d))
            .getDouble("contentScale") - 1d) < .0001d,
        "content scale clamps low direct receiver values");
    check(Math.abs(WeatherSettings.validate(new JSONObject().put("contentScale", 4d))
            .getDouble("contentScale") - 1.6d) < .0001d,
        "content scale clamps high direct receiver values");
    check(Math.abs(WeatherSettings.contentScale(Double.NaN) - 1.25d) < .0001d,
        "non-finite content scale returns the shared default");
    android.graphics.Paint labelPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    labelPaint.setTextSize(43.2f);
    String fittedCity = WeatherWidget.fittedLabel(labelPaint,
        "San Fernando del Valle de Catamarca", 180f);
    check(labelPaint.measureText(fittedCity) <= 180.01f && fittedCity.endsWith("…"),
        "long scaled city labels end intentionally instead of clipping into another card");
    WeatherWidget.CompactLayout compactLayout = WeatherWidget.compactLayout(labelPaint, 1.6f);
    check(compactLayout.header.bottom + 8f <= compactLayout.temperature.top + .01f,
        "maximum Compact header clears the current reading");
    check(compactLayout.temperature.bottom + 8f <= compactLayout.footer.top + .01f,
        "maximum Compact reading clears the scaled status footer");
    check(compactLayout.conditionRadius * 2f <= compactLayout.temperature.bottom
            - compactLayout.temperature.top + .01f,
        "maximum Compact condition glyph stays inside the current-reading band");
    check(compactLayout.footer.bottom <= 176.01f,
        "maximum Compact footer clears the fixed card edge");
    check(compactLayout.headerRight + 12f <= compactLayout.attributionLeft + .01f,
        "Compact attribution has a separate top-right text lane");
    check(WeatherWidget.scenicConditionLabelX(1.6f) > 44f + 13f * 1.6f,
        "maximum Scenic condition label clears its enlarged glyph with a gap");
    WeatherWidget.ScenicLayout scenicLayout = WeatherWidget.scenicLayout(labelPaint, 1.6f);
    check(scenicLayout.city.bottom + 4f <= scenicLayout.artworkInfo.top + .01f,
        "maximum Scenic city label clears the artwork disclosure");
    check(scenicLayout.artworkInfo.bottom + 7f <= scenicLayout.temperature.top + .01f,
        "maximum Scenic artwork disclosure clears the current reading");
    check(scenicLayout.temperature.bottom <= 244.01f,
        "maximum Scenic current reading clears its lower status treatment");
    check(scenicLayout.cityRight + 12f <= scenicLayout.attributionLeft + .01f,
        "Scenic attribution has a separate top-right text lane");
    WeatherWidget.MinimalLayout minimalLayout = WeatherWidget.minimalLayout(labelPaint, 1.6f, true);
    check(minimalLayout.header.bottom + 8f <= minimalLayout.temperature.top + .01f,
        "maximum Minimal header clears the current reading");
    check(minimalLayout.temperature.bottom + 8f <= minimalLayout.condition.top + .01f,
        "maximum Minimal reading clears the condition label");
    check(minimalLayout.condition.bottom + 6f <= minimalLayout.highLow.top + .01f,
        "maximum Minimal condition clears the separate high-low line");
    check(minimalLayout.highLow.bottom <= 244.01f,
        "maximum Minimal high-low line clears the daily divider");
    check(minimalLayout.conditionRadius * 2f <= minimalLayout.temperature.bottom
            - minimalLayout.temperature.top + .01f,
        "maximum Minimal condition glyph stays inside the current-reading band");
    WeatherWidget.DailyLayout dailyLayout = WeatherWidget.dailyLayout(labelPaint, 1.6f, 250f);
    check(dailyLayout.label.bottom + 6f <= dailyLayout.iconCenterY - dailyLayout.iconRadius + .01f,
        "maximum Minimal daily label clears its condition glyph");
    check(dailyLayout.iconCenterY + dailyLayout.iconRadius + 6f <= dailyLayout.temperature.top + .01f,
        "maximum Minimal daily glyph clears the temperature footer");
    check(dailyLayout.temperature.bottom <= 377.01f,
        "maximum Minimal daily footer clears the card edge");
    check(!defaults.getBoolean("showAllLocations"), "all-locations mode defaults off for legacy weather payloads");
    check(!defaults.getBoolean("portraitCards"), "portrait presentation defaults off for legacy weather payloads");
    JSONObject portrait = WeatherSettings.validate(
        new JSONObject().put("style", "scenic").put("portraitCards", true).put("showAllLocations", true)
            .put("locations", new JSONArray().put(location())));
    check("scenic".equals(portrait.getString("style")), "portrait presentation retains the selected scenic treatment");
    check(portrait.getBoolean("portraitCards"), "portrait presentation survives validation");
    check(portrait.getBoolean("showAllLocations"), "portrait all-locations preference survives validation");
    check(WeatherSettings.copy(portrait).getBoolean("showAllLocations"),
        "all-locations preference round-trips through the config copy");
    JSONObject rawPreference = WeatherSettings.validate(
        new JSONObject().put("style", "minimal").put("showAllLocations", true));
    check(rawPreference.getBoolean("showAllLocations"),
        "style changes preserve the raw all-locations preference");
    JSONObject migratedPortrait = WeatherSettings.validate(new JSONObject().put("style", "portrait"));
    check("scenic".equals(migratedPortrait.getString("style")) && migratedPortrait.getBoolean("portraitCards"),
        "legacy portrait style migrates to scenic plus the portrait presentation toggle");

    check(WeatherWidget.portraitLocationCount(true, true, 0) == 1,
        "portrait count has a safe lower bound while locations load");
    check(WeatherWidget.portraitLocationCount(true, true, 9) == 6,
        "portrait count caps at the six saved-location limit");
    check(WeatherWidget.portraitLocationCount(true, false, 6) == 1,
        "portrait uses the selected location unless all-locations is enabled");
    check(WeatherWidget.portraitLocationCount(false, true, 6) == 1,
        "all-locations remains inactive when portrait presentation is disabled");
    float twoGroup = WeatherWidget.portraitGroupWidth(2, .01125f);
    check(Math.abs(twoGroup - .372f) < .0001f, "two portrait cards keep the configured viewport gap");
    float twoCard = WeatherWidget.portraitCardWidth(twoGroup, 2);
    check(Math.abs(twoCard - .18f) < .0001f, "portrait card width derives from the group width");
    check(Math.abs(WeatherWidget.portraitHeightFraction(twoCard, 16f / 9f) - .48f) < .0001f,
        "portrait cards use the 2:3 height geometry at 16:9");
    float sixGroup = WeatherWidget.portraitGroupWidth(6, .01125f);
    check(Math.abs(sixGroup - .9775f) < .0001f, "portrait group stops at the horizontal safe area");

    WeatherWidget.ArtworkRequestGate requests = new WeatherWidget.ArtworkRequestGate();
    int firstScene = requests.request("tokyo:0");
    int secondScene = requests.request("tokyo:1");
    check(!requests.isCurrent(firstScene), "late first-scene decode cannot replace a newer same-city scene");
    check(requests.isCurrent(secondScene), "latest scene decode remains current");
    requests.complete(secondScene);
    check(requests.pendingKey() == null, "completed scene decode clears its pending request");
    requests.cancel();
    check(!requests.isCurrent(secondScene), "detached widget rejects a late decode completion");

    WeatherWidget.ArtworkCatalog catalog = WeatherWidget.ArtworkCatalog.parse(catalogJson().toString());
    WeatherWidget.ArtworkCatalog.LoadedCatalog loaded =
        new WeatherWidget.ArtworkCatalog.LoadedCatalog(true, catalog);
    check(
        loaded.referenceFor(catalogLocation("geo-other", "Paris", 33.6609, -95.5555, 0)) == null,
        "homonymous Paris outside the coordinate guard has no destination artwork");
    check(
        loaded.referenceFor(catalogLocation("geo-2988507", "Paris", 48.85341, 2.3488, 1)) == null,
        "missing middle image index remains unavailable");
    WeatherWidget.ArtworkReference sceneThree =
        loaded.referenceFor(catalogLocation("geo-2988507", "Paris", 48.85341, 2.3488, 2));
    check(
        sceneThree != null && "weather-artwork/paris-03.jpg".equals(sceneThree.assetPath),
        "later image keeps its declared scene index");
    if (args.length == 1) checkPackagedArtwork(args[0]);
    System.out.println("WeatherSettingsChecks passed");
  }

  private static void checkPackagedArtwork(String apk) throws Exception {
    try (ZipFile zip = new ZipFile(apk)) {
      String json;
      try (InputStream input = zip.getInputStream(zip.getEntry("assets/weather-artwork/catalog.json"))) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        for (int count; (count = input.read(buffer)) != -1;) bytes.write(buffer, 0, count);
        json = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
      }
      JSONArray cities = new JSONObject(json).getJSONArray("destinations");
      check(cities.length() == 50, "50 destinations bundled");
      WeatherWidget.ArtworkCatalog.LoadedCatalog loaded = new WeatherWidget.ArtworkCatalog.LoadedCatalog(
          true, WeatherWidget.ArtworkCatalog.parse(json));
      int decodedCount = 0;
      for (int cityIndex = 0; cityIndex < cities.length(); cityIndex++) {
        JSONObject city = cities.getJSONObject(cityIndex);
        JSONArray images = city.getJSONArray("images");
        check(images.length() == 5, "five scenes for " + city.getString("key"));
        for (int variant = 0; variant < images.length(); variant++) {
          WeatherWidget.ArtworkReference reference = loaded.referenceFor(catalogLocation(
              city.getJSONArray("geoIDs").getString(0), city.getJSONArray("names").getString(0),
              city.getDouble("latitude"), city.getDouble("longitude"), variant));
          String expected = "weather-artwork/" + images.getString(variant);
          check(reference != null && expected.equals(reference.assetPath), "scene resolves: " + expected);
          try (InputStream input = zip.getInputStream(zip.getEntry("assets/" + expected))) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap bitmap = BitmapFactory.decodeStream(input, null, options);
            check(bitmap != null, "scene decodes: " + expected);
            check(bitmap.getWidth() <= 1672 && bitmap.getWidth() > bitmap.getHeight(), "bounded landscape: " + expected);
            bitmap.recycle();
            decodedCount++;
          }
        }
      }
      System.out.println("Decoded and resolved " + decodedCount + " packaged weather scenes");
    }
  }

  private static JSONObject location() throws Exception {
    return new JSONObject()
        .put("id", "geo-1850147")
        .put("name", "Tokyo")
        .put("country", "Japan")
        .put("latitude", 35.6895)
        .put("longitude", 139.69171)
        .put("enabled", true);
  }

  private static void checkRejected(Object variant, String message) throws Exception {
    boolean rejected = false;
    try {
      WeatherSettings.validate(
          new JSONObject().put("locations", new JSONArray().put(location().put("artworkVariant", variant))));
    } catch (IllegalArgumentException expected) {
      rejected = true;
    }
    check(rejected, message);
  }

  private static JSONObject catalogJson() throws Exception {
    return new JSONObject()
        .put("version", 1)
        .put(
            "destinations",
            new JSONArray()
                .put(
                    new JSONObject()
                        .put("key", "paris")
                        .put("names", new JSONArray().put("paris"))
                        .put("geoIDs", new JSONArray().put("geo-2988507"))
                        .put("latitude", 48.85341)
                        .put("longitude", 2.3488)
                        .put("radiusKM", 30)
                        .put(
                            "images",
                            new JSONArray()
                                .put("paris-01.jpg")
                                .put(JSONObject.NULL)
                                .put("paris-03.jpg"))));
  }

  private static WeatherController.Location catalogLocation(
      String id, String name, double latitude, double longitude, int variant) throws Exception {
    return new WeatherController.Location(
        new JSONObject()
            .put("id", id)
            .put("name", name)
            .put("latitude", latitude)
            .put("longitude", longitude)
            .put("artworkVariant", variant));
  }

  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
