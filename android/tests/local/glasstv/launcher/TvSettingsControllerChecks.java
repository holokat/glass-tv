package local.glasstv.launcher;

import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Settings choices exercised without opening an Activity or changing receiver preferences. */
public final class TvSettingsControllerChecks {
  public static void main(String[] args) throws Exception {
    JSONObject apps =
        AppsSettings.validate(
            new JSONObject()
                .put("netflixEnabled", false)
                .put("moviesEnabled", true)
                .put("shortcutStyle", "cards")
                .put("hdmiStyle", "ps5")
                .put("navigationSound", "chime")
                .put("navigationSoundVolume", 1d)
                .put("enabledServices", new JSONArray().put("youtube"))
                .put("shortcutOrder", new JSONArray().put("movies").put("netflix")));
    check(!apps.getBoolean("netflixEnabled"), "Apps visibility choice validates");
    check(apps.getBoolean("moviesEnabled"), "Movies shortcut choice validates");
    check("cards".equals(apps.getString("shortcutStyle")), "Apps style choice validates");
    check("ps5".equals(apps.getString("hdmiStyle")), "HDMI appearance choice validates");
    check("chime".equals(apps.getString("navigationSound")), "Navigation sound choice validates");
    check(apps.getDouble("navigationSoundVolume") == 1d, "Navigation volume choice validates");
    check("movies".equals(apps.getJSONArray("shortcutOrder").getString(0)), "Shortcut reordering preserves selected first item");

    JSONObject weather =
        WeatherSettings.validate(
            new JSONObject()
                .put("enabled", true)
                .put("style", "scenic")
                .put("portraitCards", true)
                .put("showAllLocations", true)
                .put("unit", "fahrenheit")
                .put("contentScale", 1.6d)
                .put("showDaily", false)
                .put("cycleSeconds", 300)
                .put("locations", new JSONArray().put(tokyo())));
    check(weather.getBoolean("enabled"), "Weather master switch validates");
    check(weather.getBoolean("portraitCards") && weather.getBoolean("showAllLocations"), "Portrait side-by-side choices validate");
    check("fahrenheit".equals(weather.getString("unit")), "Unit choice validates");
    check(weather.getDouble("contentScale") == 1.6d, "Content size choice validates");
    check(!weather.getBoolean("showDaily") && weather.getInt("cycleSeconds") == 300, "Forecast and cycle choices validate");

    // This is a stable Open-Meteo-shaped fixture. It verifies the UI's city-search boundary only.
    List<WeatherCitySearch.Result> results =
        WeatherCitySearch.parse(
            new JSONObject()
                .put(
                    "results",
                    new JSONArray()
                        .put(
                            new JSONObject()
                                .put("id", 1850147)
                                .put("name", "Tokyo")
                                .put("admin1", "Tokyo")
                                .put("country", "Japan")
                                .put("latitude", 35.6895)
                                .put("longitude", 139.6917))
                        .put(new JSONObject().put("id", 1).put("name", "Invalid")))
                .toString());
    check(results.size() == 1, "City search filters incomplete geocoding values");
    WeatherCitySearch.Result tokyo = results.get(0);
    check("geo-1850147".equals(tokyo.id) && "Tokyo, Japan".equals(tokyo.label()), "City search creates stable display and persistence IDs");
    check(WeatherSettings.validate(new JSONObject().put("locations", new JSONArray().put(tokyo.asLocation())))
            .getJSONArray("locations").length() == 1,
        "City search result passes the weather location validator");
    check(WeatherCitySearch.parse("{}").isEmpty(), "City search exposes an honest empty result");
    check(
        WeatherCitySearch.parse(
                new JSONObject()
                    .put("results", new JSONArray().put(new JSONObject().put("id", -2).put("name", "Bad").put("latitude", 100).put("longitude", 0)))
                    .toString())
            .isEmpty(),
        "City search rejects invalid provider coordinates and IDs before presentation");
    System.out.println("TvSettingsControllerChecks passed");
  }

  private static JSONObject tokyo() throws Exception {
    return new JSONObject()
        .put("id", "geo-1850147")
        .put("name", "Tokyo")
        .put("country", "Japan")
        .put("latitude", 35.6895)
        .put("longitude", 139.6917)
        .put("enabled", true);
  }

  private static void check(boolean value, String description) {
    if (!value) throw new AssertionError(description);
  }
}
