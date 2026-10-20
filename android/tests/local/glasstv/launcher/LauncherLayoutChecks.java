package local.glasstv.launcher;

import org.json.JSONObject;

/** Dependency-free schema and geometry checks for draggable launcher controls. */
public final class LauncherLayoutChecks {
  private static final float LANDSCAPE = 16f / 9f;

  public static void main(String[] args) throws Exception {
    JSONObject supplied =
        new JSONObject()
            .put("version", 1)
            .put("clock", new JSONObject().put("x", 0d).put("y", 1d))
            .put("apps", new JSONObject().put("x", 1d).put("y", 0d));
    JSONObject layout = LauncherLayout.validate(supplied);
    check(layout.getBoolean("wifiEnabled"), "new layout enables native Wi-Fi by default");
    check(!layout.has("safeAreaPadding"), "absent safe-area padding stays absent for legacy layout output");
    check(
        close(LauncherLayout.safeAreaPadding(layout), .02f),
        "absent safe-area padding uses the rendering default");
    check(
        !LauncherLayout.wifiEnabled(LauncherLayout.validate(new JSONObject().put("version", 1).put("wifiEnabled", false))),
        "explicit Wi-Fi disable survives validation");
    check(
        !LauncherLayout.snapToEdges(layout),
        "omitted edge snapping uses the false default without changing legacy layout output");
    check(
        close(LauncherLayout.clockScale(layout), 1f) && !layout.has("clockScale"),
        "omitted clock scale renders normally without changing legacy layout output");
    check(close(LauncherLayout.weatherIconScale(layout), 1f) && !layout.has("weatherIconScale"), "legacy inline weather size remains unchanged");
    JSONObject inline = LauncherLayout.validate(new JSONObject().put("version", 1).put("weatherIconScale", 1.75));
    check(close(LauncherLayout.weatherIconScale(LauncherLayout.copy(inline)), 1.75f), "inline scale persists");
    check(close(LauncherLayout.x(inline, "weather", 1f, .13f * 1.75f, LANDSCAPE), 1f - .01125f - .13f * 1.75f), "scaled weather stays inside safe area");
    rejected(new JSONObject().put("version", 1).put("weatherIconScale", .49), "too-small inline scale");
    rejected(new JSONObject().put("version", 1).put("weatherIconScale", 2.01), "too-large inline scale");
    rejected(new JSONObject().put("version", 1).put("weatherIconScale", "1"), "non-numeric inline scale");
    check(LauncherLayout.appsShowLabels(layout) && !layout.has("appsShowLabels"), "labels default on without adding a legacy field");
    check(
        close(LauncherLayout.appIconScale(layout), 1f) && !layout.has("appIconScale"),
        "shortcut scale defaults without changing legacy layout output");
    check(
        !LauncherLayout.appSpacingEnabled(layout)
            && close(LauncherLayout.appSpacingScale(layout), 1f)
            && close(LauncherLayout.effectiveAppSpacingScale(layout), 1f)
            && !layout.has("appSpacingEnabled")
            && !layout.has("appSpacingScale"),
        "shortcut spacing defaults without changing legacy layout output");
    JSONObject disabledSpacing =
        LauncherLayout.validate(
            new JSONObject()
                .put("version", 1)
                .put("appSpacingEnabled", false)
                .put("appSpacingScale", 0d));
    check(
        !LauncherLayout.appSpacingEnabled(disabledSpacing)
            && close(LauncherLayout.appSpacingScale(disabledSpacing), 0f)
            && close(LauncherLayout.effectiveAppSpacingScale(disabledSpacing), 1f)
            && LauncherLayout.copy(disabledSpacing).toString().equals(disabledSpacing.toString()),
        "disabled custom spacing preserves its wire values while rendering the default gap");
    JSONObject zeroSpacing =
        LauncherLayout.validate(
            new JSONObject()
                .put("version", 1)
                .put("appSpacingEnabled", true)
                .put("appSpacingScale", 0d));
    check(
        LauncherLayout.appSpacingEnabled(zeroSpacing)
            && close(LauncherLayout.effectiveAppSpacingScale(LauncherLayout.copy(zeroSpacing)), 0f),
        "enabled zero shortcut spacing survives receiver layout round trip");
    JSONObject wideSpacing =
        LauncherLayout.validate(
            new JSONObject().put("version", 1).put("appSpacingScale", 3d));
    check(close(LauncherLayout.appSpacingScale(wideSpacing), 3f), "maximum shortcut spacing validates");
    JSONObject appScale =
        LauncherLayout.validate(new JSONObject().put("version", 1).put("appIconScale", 1.5));
    check(
        close(LauncherLayout.appIconScale(LauncherLayout.copy(appScale)), 1.5f),
        "shortcut scale survives receiver layout round trip");
    check(
        close(LauncherLayout.appsHeight(appScale, ShortcutRow.MINIMAL), .2505f),
        "shortcut scale includes the label height and gap");
    rejected(new JSONObject().put("version", 1).put("appIconScale", .49), "too-small shortcut scale");
    rejected(new JSONObject().put("version", 1).put("appIconScale", 1.51), "too-large shortcut scale");
    rejected(new JSONObject().put("version", 1).put("appIconScale", "1"), "non-numeric shortcut scale");
    rejected(new JSONObject().put("version", 1).put("appSpacingEnabled", "false"), "non-Boolean spacing flag");
    rejected(new JSONObject().put("version", 1).put("appSpacingScale", -.01), "negative shortcut spacing");
    rejected(new JSONObject().put("version", 1).put("appSpacingScale", 3.01), "over-limit shortcut spacing");
    rejected(new JSONObject().put("version", 1).put("appSpacingScale", "1"), "non-numeric shortcut spacing");
    JSONObject hiddenLabels = LauncherLayout.validate(new JSONObject().put("version", 1).put("appsShowLabels", false));
    check(!LauncherLayout.appsShowLabels(LauncherLayout.copy(hiddenLabels)), "hidden labels persist");
    check(close(LauncherLayout.appsHeight(hiddenLabels), .12f), "hidden labels reserve icon height only");
    check(close(LauncherLayout.appsHeight(hiddenLabels, ShortcutRow.CARDS), .17f), "hidden card labels reserve card height only");
    check(close(LauncherLayout.appsHeight(layout, ShortcutRow.CARDS), .217f), "cards include the shared label space");
    check(close(LauncherLayout.y(hiddenLabels, "apps", 1f, LauncherLayout.appsHeight(hiddenLabels), LANDSCAPE) + .12f, .98f), "icons snap directly to bottom safe edge");
    rejected(new JSONObject().put("version", 1).put("appsShowLabels", "false"), "non-Boolean label setting");
    check(LauncherLayout.copy(null) == null, "legacy absent layout remains absent");
    check(
        LauncherLayout.copy(layout).toString().equals(layout.toString()),
        "stored layout survives a defensive copy");

    check(
        close(LauncherLayout.x(layout, "clock", .045f, .28f, LANDSCAPE), .01125f),
        "clock uses the short-edge left inset");
    check(
        close(LauncherLayout.y(layout, "clock", .035f, .11f, LANDSCAPE), .87f),
        "clock uses the default bottom inset");
    check(
        close(LauncherLayout.x(layout, "apps", ShortcutRow.START_FRACTION, .86f, LANDSCAPE), .12875f),
        "packed eight-app row remains fully visible");
    check(
        close(LauncherLayout.y(layout, "apps", .78f, .167f, LANDSCAPE), .02f),
        "app row top safe inset");
    check(
        close(LauncherLayout.x(layout, "wifi", .88f, .04f, LANDSCAPE), .88f),
        "missing position keeps the Wi-Fi default");
    JSONObject defaults = LauncherLayout.validate(new JSONObject().put("version", 1));
    check(
        close(LauncherLayout.x(defaults, "clock", .045f, .28f, LANDSCAPE), .045f)
            && close(LauncherLayout.y(defaults, "clock", .035f, .11f, LANDSCAPE), .035f),
        "normal default clock placement remains compatible");
    check(
        close(LauncherLayout.x(defaults, "apps", ShortcutRow.START_FRACTION, .86f, LANDSCAPE), .04f)
            && close(LauncherLayout.y(defaults, "apps", .78f, .167f, LANDSCAPE), .78f),
        "normal default app row placement remains compatible");

    JSONObject padded =
        LauncherLayout.validate(new JSONObject().put("version", 1).put("safeAreaPadding", .10d));
    check(close(LauncherLayout.safeAreaPadding(padded), .10f), "safe-area padding survives validation");
    check(
        close((float) LauncherLayout.copy(padded).getDouble("safeAreaPadding"), .10f),
        "safe-area padding survives persistence copies");
    JSONObject scaled =
        LauncherLayout.validate(
            new JSONObject().put("version", 1).put("snapToEdges", true).put("clockScale", 1.5d));
    check(
        scaled.getBoolean("snapToEdges") && close(LauncherLayout.clockScale(scaled), 1.5f),
        "edge snap preference and clock scale survive validation");
    check(
        LauncherLayout.copy(scaled).toString().equals(scaled.toString()),
        "edge snap preference and clock scale survive persistence copies");
    check(
        close(LauncherLayout.x(scaled, "clock", .045f, .28f * LauncherLayout.clockScale(scaled), LANDSCAPE), .045f),
        "scaled clock geometry shares the normal safe-area clamp");
    float xInset = LauncherLayout.x(padded, "clock", 0f, .04f, LANDSCAPE);
    float yInset = LauncherLayout.y(padded, "clock", 0f, .06f, LANDSCAPE);
    check(close(xInset * 1920f, yInset * 1080f), "landscape safe edges have equal pixel insets");
    check(close(xInset, .05625f) && close(yInset, .10f), "landscape inset fractions use the short edge");
    check(
        close(LauncherLayout.horizontalSafeInset(padded, LANDSCAPE), .05625f),
        "carousel arrows use the same horizontal safe edge as the layout");
    check(
        close(LauncherLayout.x(padded, "apps", 1f, .86f, LANDSCAPE), .08375f),
        "eight-app row fits inside the largest safe area");
    check(
        close(LauncherLayout.clamp(1f, .90f, .10f), .05f),
        "oversize controls center when both safe edges do not fit");

    rejected(new JSONObject().put("version", 2), "unknown version");
    rejected(new JSONObject().put("version", 1).put("wifiEnabled", "true"), "non-Boolean Wi-Fi flag");
    rejected(new JSONObject().put("version", 1).put("snapToEdges", "false"), "non-Boolean snap flag");
    rejected(new JSONObject().put("version", 1).put("clockScale", .49), "too-small clock scale");
    rejected(new JSONObject().put("version", 1).put("clockScale", 2.01), "too-large clock scale");
    rejected(new JSONObject().put("version", 1).put("clockScale", "1"), "non-numeric clock scale");
    rejected(new JSONObject().put("version", 1).put("safeAreaPadding", -0.01), "negative safe-area padding");
    rejected(new JSONObject().put("version", 1).put("safeAreaPadding", "0.02"), "non-numeric safe-area padding");
    rejected(new JSONObject().put("version", 1).put("safeAreaPadding", .101), "too-large safe-area padding");
    rejectedSafeAreaPadding(Double.NaN, "non-finite safe-area padding");
    rejected(
        new JSONObject().put("version", 1).put("weather", new JSONObject().put("x", .5)),
        "incomplete position");
    rejected(
        new JSONObject()
            .put("version", 1)
            .put("settings", new JSONObject().put("x", "0.2").put("y", .2)),
        "non-numeric coordinate");
    rejected(
        new JSONObject()
            .put("version", 1)
            .put("settings", new JSONObject().put("x", 1.01).put("y", .2)),
        "out-of-range coordinate");
    rejected(new JSONObject().put("version", 1).put("unexpected", true), "unknown field");
    System.out.println("LauncherLayoutChecks passed");
  }

  private static void rejected(JSONObject value, String message) throws Exception {
    boolean failed = false;
    try {
      LauncherLayout.validate(value);
    } catch (IllegalArgumentException expected) {
      failed = true;
    }
    check(failed, message);
  }

  private static void rejectedSafeAreaPadding(Object value, String message) {
    boolean failed = false;
    try {
      LauncherLayout.validateSafeAreaPadding(value);
    } catch (IllegalArgumentException expected) {
      failed = true;
    }
    check(failed, message);
  }

  private static boolean close(float left, float right) {
    return Math.abs(left - right) < .0001f;
  }

  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
