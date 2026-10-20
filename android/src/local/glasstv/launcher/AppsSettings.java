package local.glasstv.launcher;

import java.util.HashSet;
import java.util.Arrays;
import java.util.Iterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Validates launcher shortcuts. This stays separate from media and receiver input state. */
final class AppsSettings {
  static final String NAVIGATION_SOUND_OFF = "off";
  static final float DEFAULT_NAVIGATION_SOUND_VOLUME = .25f;
  static JSONObject defaults() {
    try {
      return validate(new JSONObject());
    } catch (Exception impossible) {
      return new JSONObject();
    }
  }

  static JSONObject validate(JSONObject source) throws Exception {
    rejectUnknown(source);
    JSONObject result = new JSONObject();
    result.put("netflixEnabled", strictBoolean(source, "netflixEnabled", true));
    result.put("radioEnabled", strictBoolean(source, "radioEnabled", true));
    result.put("hdmiEnabled", strictBoolean(source, "hdmiEnabled", true));
    result.put("moviesEnabled", strictBoolean(source, "moviesEnabled", false));
    // Optional third-party shortcuts stay opt-in for existing installations as well.
    result.put("primeVideoEnabled", strictBoolean(source, "primeVideoEnabled", false));
    result.put("spotifyEnabled", strictBoolean(source, "spotifyEnabled", false));
    result.put("twitchEnabled", strictBoolean(source, "twitchEnabled", false));
    result.put("huluEnabled", strictBoolean(source, "huluEnabled", false));
    result.put("enabledServices", strictServiceIds(source));
    String shortcutStyle = strictEnum(source, "shortcutStyle", "minimal", "minimal", "cards");
    result.put("shortcutStyle", shortcutStyle);
    // Keep this optional so old companion payloads retain their legacy ordering on disk.
    if (source.has("shortcutOrder")) result.put("shortcutOrder", normalizedShortcutOrder(source));
    // Both sound fields remain optional so an older Apps payload reads back unchanged.
    if (source.has("navigationSound"))
      result.put("navigationSound", strictNavigationSound(source.opt("navigationSound")));
    if (source.has("navigationSoundVolume"))
      result.put(
          "navigationSoundVolume",
          strictNavigationSoundVolume(source.opt("navigationSoundVolume")));
    String style = strictEnum(source, "hdmiStyle", "switch", "switch", "ps5", "hdmi");
    result.put("hdmiStyle", style);
    return result;
  }

  static JSONObject copy(JSONObject source) {
    try {
      return new JSONObject(source.toString());
    } catch (Exception impossible) {
      return defaults();
    }
  }

  private static boolean strictBoolean(JSONObject source, String name, boolean fallback) {
    if (!source.has(name)) return fallback;
    Object value = source.opt(name);
    if (!(value instanceof Boolean))
      throw new IllegalArgumentException(name + " must be a boolean");
    return ((Boolean) value).booleanValue();
  }

  private static void rejectUnknown(JSONObject source) {
    Set<String> known =
        new HashSet<>(
            Arrays.asList(
                "netflixEnabled",
                "radioEnabled",
                "hdmiEnabled",
                "moviesEnabled",
                "primeVideoEnabled",
                "spotifyEnabled",
                "twitchEnabled",
                "huluEnabled",
                "enabledServices",
                "shortcutStyle",
                "shortcutOrder",
                "navigationSound",
                "navigationSoundVolume",
                "hdmiStyle"));
    Iterator<String> keys = source.keys();
    while (keys.hasNext())
      if (!known.contains(keys.next())) throw new IllegalArgumentException("Unknown app setting");
  }

  private static String strictEnum(
      JSONObject source, String name, String fallback, String... allowed) {
    if (!source.has(name)) return fallback;
    Object value = source.opt(name);
    if (!(value instanceof String)) throw new IllegalArgumentException(name + " must be a string");
    for (String candidate : allowed) if (candidate.equals(value)) return candidate;
    throw new IllegalArgumentException("Unknown " + name);
  }

  /** New catalog shortcuts are opt-in and can be persisted before a receiver gains their assets. */
  private static JSONArray strictServiceIds(JSONObject source) {
    JSONArray result = new JSONArray();
    if (!source.has("enabledServices")) return result;
    Object value = source.opt("enabledServices");
    if (!(value instanceof JSONArray))
      throw new IllegalArgumentException("enabledServices must be an array");
    Set<String> seen = new HashSet<>();
    JSONArray supplied = (JSONArray) value;
    for (int i = 0; i < supplied.length(); i++) {
      Object id = supplied.opt(i);
      if (!(id instanceof String) || ((String) id).trim().isEmpty())
        throw new IllegalArgumentException("enabledServices entries must be non-empty strings");
      if (!AppShortcutCatalog.NEW_SERVICE_IDS.contains((String) id))
        throw new IllegalArgumentException("enabledServices contains an unknown service");
      if (!seen.add((String) id))
        throw new IllegalArgumentException("enabledServices must not contain duplicates");
      result.put(id);
    }
    return result;
  }

  /**
   * Keeps a complete stable sequence on the wire. User-supplied duplicate or stale IDs are
   * harmless: retain the first valid occurrence, then append every omitted known shortcut.
   */
  private static JSONArray normalizedShortcutOrder(JSONObject source) {
    Object value = source.opt("shortcutOrder");
    if (!(value instanceof JSONArray))
      throw new IllegalArgumentException("shortcutOrder must be an array");
    List<String> canonical = canonicalShortcutOrder();
    Set<String> seen = new HashSet<>();
    JSONArray result = new JSONArray();
    JSONArray supplied = (JSONArray) value;
    for (int i = 0; i < supplied.length(); i++) {
      Object id = supplied.opt(i);
      if (!(id instanceof String))
        throw new IllegalArgumentException("shortcutOrder entries must be strings");
      if (canonical.contains(id) && seen.add((String) id)) result.put(id);
    }
    for (String id : canonical) if (seen.add(id)) result.put(id);
    return result;
  }

  static List<String> canonicalShortcutOrder() {
    ArrayList<String> result =
        new ArrayList<>(
            Arrays.asList(
                "netflix", "radio", "prime_video", "spotify", "twitch", "hulu", "movies", "hdmi"));
    result.addAll(AppShortcutCatalog.SERVICE_IDS);
    return result;
  }

  static String navigationSound(JSONObject apps) {
    return apps != null && apps.has("navigationSound")
        ? apps.optString("navigationSound", NAVIGATION_SOUND_OFF)
        : NAVIGATION_SOUND_OFF;
  }

  static float navigationSoundVolume(JSONObject apps) {
    return apps != null && apps.has("navigationSoundVolume")
        ? (float) apps.optDouble("navigationSoundVolume", DEFAULT_NAVIGATION_SOUND_VOLUME)
        : DEFAULT_NAVIGATION_SOUND_VOLUME;
  }

  private static String strictNavigationSound(Object value) {
    if (!(value instanceof String))
      throw new IllegalArgumentException("navigationSound must be a string");
    for (String id : new String[] {"off", "soft", "glass", "wood", "bubble", "chime"})
      if (id.equals(value)) return (String) value;
    throw new IllegalArgumentException("Unknown navigationSound");
  }

  private static double strictNavigationSoundVolume(Object value) {
    if (!(value instanceof Number))
      throw new IllegalArgumentException("navigationSoundVolume must be a number");
    double volume = ((Number) value).doubleValue();
    if (Double.isNaN(volume) || Double.isInfinite(volume) || volume < 0d || volume > 1d)
      throw new IllegalArgumentException("navigationSoundVolume must be finite and between 0 and 1");
    return volume;
  }
}
