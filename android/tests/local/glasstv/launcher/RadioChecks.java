package local.glasstv.launcher;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

/** Device-runnable checks: no network, UI, or playback is needed. */
public final class RadioChecks {
  public static void main(String[] args) throws Exception {
    check(
        !RadioStationPolicy.qualifies(
            "not-a-uuid", "Station", "https://example.com/a", true, 320, true),
        "reject malformed UUID");
    check(
        !RadioStationPolicy.qualifies(
            "11111111-1111-1111-1111-111111111111",
            "Station",
            "https://example.com/a",
            false,
            320,
            true),
        "reject unchecked station");
    check(
        !RadioStationPolicy.qualifies(
            "11111111-1111-1111-1111-111111111111",
            "Station",
            "https://example.com/a",
            true,
            127,
            true),
        "reject low bitrate");
    check(
        !RadioStationPolicy.qualifies(
            "11111111-1111-1111-1111-111111111111",
            "Station",
            "https://example.com/a",
            true,
            512000,
            true),
        "reject implausible bitrate");
    List<RadioStationPolicy.Station> ranked = new ArrayList<>();
    ranked.add(station("11111111-1111-1111-1111-111111111111", "high", 384));
    ranked.add(station("22222222-2222-2222-2222-222222222222", "ideal", 320));
    ranked.add(station("33333333-3333-3333-3333-333333333333", "mid", 256));
    ranked.add(station("44444444-4444-4444-4444-444444444444", "low", 192));
    ranked.add(station("22222222-2222-2222-2222-222222222222", "duplicate", 320));
    List<RadioStationPolicy.Station> ordered = RadioStationPolicy.uniqueAndRank(ranked, 12);
    check(
        ordered.size() == 4
            && ordered.get(0).bitrate == 320
            && ordered.get(1).bitrate == 384
            && ordered.get(2).bitrate == 256
            && ordered.get(3).bitrate == 192,
        "320-first ranking and de-duplication");
    RadioStationPolicy.Station paletteStation = station("55555555-5555-5555-5555-555555555555", "Original name", 320);
    RadioStationPolicy.Station refreshedPaletteStation = station("55555555-5555-5555-5555-555555555555", "Refreshed name", 320);
    check(RadioStationGradient.paletteFor(paletteStation).sameAs(RadioStationGradient.paletteFor(refreshedPaletteStation)),
        "station UUID keeps the deterministic palette stable across provider display-name refreshes");
    JSONObject defaults = AppsSettings.validate(new JSONObject());
    check(
        defaults.getBoolean("netflixEnabled")
            && defaults.getBoolean("radioEnabled")
            && defaults.getBoolean("hdmiEnabled")
            && "switch".equals(defaults.getString("hdmiStyle")),
        "legacy missing apps defaults");
    boolean rejected = false;
    try {
      AppsSettings.validate(new JSONObject().put("radioEnabled", "true"));
    } catch (IllegalArgumentException expected) {
      rejected = true;
    }
    check(rejected, "strict boolean validation");
    rejected = false;
    try {
      AppsSettings.validate(new JSONObject().put("hdmiStyle", "console"));
    } catch (IllegalArgumentException expected) {
      rejected = true;
    }
    check(rejected, "strict HDMI style validation");
    JSONObject original = new JSONObject().put("radioEnabled", false);
    JSONObject copy = AppsSettings.copy(original);
    copy.put("radioEnabled", true);
    check(!original.getBoolean("radioEnabled"), "copy keeps independent settings");
    System.out.println("RadioChecks passed");
  }

  private static RadioStationPolicy.Station station(String uuid, String name, int bitrate) {
    return new RadioStationPolicy.Station(
        uuid, name, "https://example.com/stream", "music", "", bitrate);
  }

  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
