package local.glasstv.launcher;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import org.json.JSONObject;

/** Strict playlist layout schema plus the fractional geometry shared by launcher controls. */
final class LauncherLayout {
  static final int VERSION = 1;
  static final float DEFAULT_SAFE_AREA_PADDING = .02f;
  private static final double MAX_SAFE_AREA_PADDING = .10d;
  private static final float DEFAULT_ASPECT_RATIO = 16f / 9f;

  private LauncherLayout() {}

  /**
   * Canonicalizes a supplied layout. A missing layout is represented by {@code null} by callers so
   * legacy playlists retain their exact on-disk shape until a layout is explicitly supplied.
   */
  static JSONObject validate(JSONObject source) {
    if (source == null) throw new IllegalArgumentException("layout must be an object");
    rejectUnknown(
        source,
        setOf(
            "version",
            "safeAreaPadding",
            "wifiEnabled",
            "snapToEdges",
            "appsShowLabels",
            "appIconScale",
            "appSpacingEnabled",
            "appSpacingScale",
            "clockScale",
            "weatherIconScale",
            "clock",
            "settings",
            "wifi",
            "weather",
            "apps"),
        "layout");
    if (!isExactInteger(source.opt("version"), VERSION))
      throw new IllegalArgumentException("layout version must be 1");

    JSONObject result = new JSONObject();
    try {
      result.put("version", VERSION);
      if (source.has("safeAreaPadding")) {
        result.put("safeAreaPadding", validateSafeAreaPadding(source.opt("safeAreaPadding")));
      }
      if (source.has("wifiEnabled")) {
        if (!(source.opt("wifiEnabled") instanceof Boolean))
          throw new IllegalArgumentException("layout wifiEnabled must be a boolean");
        result.put("wifiEnabled", source.getBoolean("wifiEnabled"));
      } else {
        // New layout documents opt into the native Wi-Fi settings shortcut by default.
        result.put("wifiEnabled", true);
      }
      if (source.has("snapToEdges")) {
        if (!(source.opt("snapToEdges") instanceof Boolean))
          throw new IllegalArgumentException("layout snapToEdges must be a boolean");
        result.put("snapToEdges", source.getBoolean("snapToEdges"));
      }
      if (source.has("appsShowLabels")) {
        if (!(source.opt("appsShowLabels") instanceof Boolean))
          throw new IllegalArgumentException("layout appsShowLabels must be a boolean");
        result.put("appsShowLabels", source.getBoolean("appsShowLabels"));
      }
      // Omit the default so existing layout documents retain their exact wire shape.
      if (source.has("appIconScale"))
        result.put("appIconScale", validateAppIconScale(source.opt("appIconScale")));
      // Keep both optional so a legacy layout remains byte-shape compatible until the companion
      // explicitly opts into custom shortcut spacing.
      if (source.has("appSpacingEnabled")) {
        if (!(source.opt("appSpacingEnabled") instanceof Boolean))
          throw new IllegalArgumentException("layout appSpacingEnabled must be a boolean");
        result.put("appSpacingEnabled", source.getBoolean("appSpacingEnabled"));
      }
      if (source.has("appSpacingScale"))
        result.put("appSpacingScale", validateAppSpacingScale(source.opt("appSpacingScale")));
      // Omit the default to preserve the exact shape of pre-scale layout documents.
      if (source.has("clockScale")) result.put("clockScale", validateScale(source.opt("clockScale"), "clockScale"));
      if (source.has("weatherIconScale")) result.put("weatherIconScale", validateScale(source.opt("weatherIconScale"), "weatherIconScale"));
      for (String name : new String[] {"clock", "settings", "wifi", "weather", "apps"}) {
        if (!source.has(name)) continue;
        result.put(name, validatePosition(source.getJSONObject(name), name));
      }
      return result;
    } catch (IllegalArgumentException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalArgumentException("invalid layout");
    }
  }

  static JSONObject copy(JSONObject source) {
    return source == null ? null : validate(source);
  }

  static boolean wifiEnabled(JSONObject layout) {
    return layout == null || layout.optBoolean("wifiEnabled", true);
  }

  /** This preference is consumed by the editor; the receiver only persists and validates it. */
  static boolean snapToEdges(JSONObject layout) {
    return layout != null && layout.optBoolean("snapToEdges", false);
  }

  /** Keeps omitted legacy layout documents byte-shape compatible while rendering at the normal size. */
  static float clockScale(JSONObject layout) {
    return layout != null && layout.has("clockScale")
        ? (float) layout.optDouble("clockScale")
        : 1f;
  }

  static boolean appsShowLabels(JSONObject layout) {
    return layout == null || layout.optBoolean("appsShowLabels", true);
  }

  /** Shortcut scale is owned by Layout so all receiver and preview geometry stays aligned. */
  static float appIconScale(JSONObject layout) {
    return layout != null && layout.has("appIconScale")
        ? (float) layout.optDouble("appIconScale")
        : 1f;
  }

  /** Custom shortcut spacing is opt-in so older layout documents retain their original geometry. */
  static boolean appSpacingEnabled(JSONObject layout) {
    return layout != null && layout.optBoolean("appSpacingEnabled", false);
  }

  /** Returns the stored spacing scale, which is intentionally distinct from its effective value. */
  static float appSpacingScale(JSONObject layout) {
    return layout != null && layout.has("appSpacingScale")
        ? (float) layout.optDouble("appSpacingScale")
        : 1f;
  }

  /** A disabled custom-spacing field always renders with the established gap. */
  static float effectiveAppSpacingScale(JSONObject layout) {
    return appSpacingEnabled(layout) ? appSpacingScale(layout) : 1f;
  }

  static float appsHeight(JSONObject layout) {
    return appsHeight(layout, ShortcutRow.MINIMAL);
  }

  static float appsHeight(JSONObject layout, String shortcutStyle) {
    return appsHeight(layout, shortcutStyle, appIconScale(layout));
  }

  static float appsHeight(JSONObject layout, String shortcutStyle, float appIconScale) {
    return ShortcutRow.heightWithLabel(shortcutStyle, appsShowLabels(layout), appIconScale);
  }

  static float weatherIconScale(JSONObject layout) {
    return layout != null && layout.has("weatherIconScale")
        ? (float) layout.optDouble("weatherIconScale") : 1f;
  }

  /** Returns the configured short-edge padding, without adding an absent optional field to layout. */
  static float safeAreaPadding(JSONObject layout) {
    return layout != null && layout.has("safeAreaPadding")
        ? (float) layout.optDouble("safeAreaPadding")
        : DEFAULT_SAFE_AREA_PADDING;
  }

  /** Resolves a display aspect ratio while a zero-size first layout pass is in progress. */
  static float aspectRatio(int width, int height) {
    return width > 0 && height > 0 ? width / (float) height : DEFAULT_ASPECT_RATIO;
  }

  static float x(
      JSONObject layout,
      String name,
      float defaultValue,
      float elementWidth,
      float aspectRatio) {
    return coordinate(
        layout, name, "x", defaultValue, elementWidth, horizontalInset(safeAreaPadding(layout), aspectRatio));
  }

  /** Horizontal fraction reserved at either screen edge by the configured safe area. */
  static float horizontalSafeInset(JSONObject layout, float aspectRatio) {
    return horizontalInset(safeAreaPadding(layout), aspectRatio);
  }

  static float y(
      JSONObject layout,
      String name,
      float defaultValue,
      float elementHeight,
      float aspectRatio) {
    return coordinate(
        layout, name, "y", defaultValue, elementHeight, verticalInset(safeAreaPadding(layout), aspectRatio));
  }

  private static float coordinate(
      JSONObject layout,
      String name,
      String axis,
      float defaultValue,
      float elementSize,
      float inset) {
    float value = defaultValue;
    if (layout != null && layout.has(name)) {
      JSONObject position = layout.optJSONObject(name);
      value = (float) position.optDouble(axis);
    }
    return clamp(value, elementSize, inset);
  }

  /**
   * Keeps controls inside an equal-pixel safe area. Controls too large for both safe edges remain
   * centered so a changing widget size cannot leave an arbitrary edge clipped.
   */
  static float clamp(float value, float elementSize, float inset) {
    float maximum = 1f - elementSize;
    if (maximum >= inset * 2f) return Math.max(inset, Math.min(maximum - inset, value));
    return maximum / 2f;
  }

  private static float horizontalInset(float padding, float aspectRatio) {
    float ratio = validAspectRatio(aspectRatio);
    return padding * Math.min(1f, 1f / ratio);
  }

  private static float verticalInset(float padding, float aspectRatio) {
    float ratio = validAspectRatio(aspectRatio);
    return padding * Math.min(1f, ratio);
  }

  private static float validAspectRatio(float aspectRatio) {
    return Float.isFinite(aspectRatio) && aspectRatio > 0f ? aspectRatio : DEFAULT_ASPECT_RATIO;
  }

  private static JSONObject validatePosition(JSONObject source, String name) {
    if (source == null) throw new IllegalArgumentException("layout " + name + " must be an object");
    rejectUnknown(source, setOf("x", "y"), "layout " + name);
    if (!source.has("x") || !source.has("y"))
      throw new IllegalArgumentException("layout " + name + " requires x and y");
    double x = number(source.opt("x"), "layout " + name + " x");
    double y = number(source.opt("y"), "layout " + name + " y");
    try {
      return new JSONObject().put("x", x).put("y", y);
    } catch (Exception impossible) {
      throw new IllegalArgumentException("invalid layout " + name);
    }
  }

  private static double number(Object value, String label) {
    if (!(value instanceof Number)) throw new IllegalArgumentException(label + " must be a number");
    double number = ((Number) value).doubleValue();
    if (Double.isNaN(number) || Double.isInfinite(number) || number < 0d || number > 1d)
      throw new IllegalArgumentException(label + " must be finite and between 0 and 1");
    return number;
  }

  static double validateSafeAreaPadding(Object value) {
    if (!(value instanceof Number))
      throw new IllegalArgumentException("layout safeAreaPadding must be a number");
    double padding = ((Number) value).doubleValue();
    if (Double.isNaN(padding)
        || Double.isInfinite(padding)
        || padding < 0d
        || padding > MAX_SAFE_AREA_PADDING)
      throw new IllegalArgumentException(
          "layout safeAreaPadding must be finite and between 0 and " + MAX_SAFE_AREA_PADDING);
    return padding;
  }

  private static double validateScale(Object value, String name) {
    if (!(value instanceof Number)) throw new IllegalArgumentException("layout " + name + " must be a number");
    double scale = ((Number) value).doubleValue();
    if (Double.isNaN(scale) || Double.isInfinite(scale) || scale < .5d || scale > 2d)
      throw new IllegalArgumentException("layout " + name + " must be finite and between 0.5 and 2");
    return scale;
  }

  private static double validateAppIconScale(Object value) {
    if (!(value instanceof Number))
      throw new IllegalArgumentException("layout appIconScale must be a number");
    double scale = ((Number) value).doubleValue();
    if (Double.isNaN(scale) || Double.isInfinite(scale) || scale < .5d || scale > 1.5d)
      throw new IllegalArgumentException(
          "layout appIconScale must be finite and between 0.5 and 1.5");
    return scale;
  }

  private static double validateAppSpacingScale(Object value) {
    if (!(value instanceof Number))
      throw new IllegalArgumentException("layout appSpacingScale must be a number");
    double scale = ((Number) value).doubleValue();
    if (Double.isNaN(scale) || Double.isInfinite(scale) || scale < 0d || scale > 3d)
      throw new IllegalArgumentException(
          "layout appSpacingScale must be finite and between 0 and 3");
    return scale;
  }

  private static boolean isExactInteger(Object value, int expected) {
    return value instanceof Number
        && ((Number) value).doubleValue() == expected
        && Math.rint(((Number) value).doubleValue()) == expected;
  }

  private static Set<String> setOf(String... names) {
    Set<String> result = new HashSet<>();
    for (String name : names) result.add(name);
    return result;
  }

  private static void rejectUnknown(JSONObject source, Set<String> allowed, String label) {
    Iterator<String> keys = source.keys();
    while (keys.hasNext()) {
      if (!allowed.contains(keys.next())) throw new IllegalArgumentException("unknown " + label + " field");
    }
  }
}
