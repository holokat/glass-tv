package local.glasstv.launcher;

import org.json.JSONObject;
import org.json.JSONArray;

/**
 * Device-runnable app-settings and shortcut-row checks; no activity or external app is launched.
 */
public final class AppsSettingsChecks {
  public static void main(String[] args) throws Exception {
    JSONObject defaults = AppsSettings.validate(new JSONObject());
    check(!defaults.getBoolean("moviesEnabled"), "Movies defaults off");
    check(!defaults.getBoolean("primeVideoEnabled"), "Prime Video defaults off");
    check(!defaults.getBoolean("spotifyEnabled"), "Spotify defaults off");
    check(!defaults.getBoolean("twitchEnabled"), "Twitch defaults off");
    check(!defaults.getBoolean("huluEnabled"), "Hulu defaults off");
    check(defaults.getJSONArray("enabledServices").length() == 0, "new services default off");
    check("minimal".equals(defaults.getString("shortcutStyle")), "minimal shortcuts remain the default");
    check(!defaults.has("shortcutOrder"), "missing shortcut order remains absent for legacy sync");
    check(
        !defaults.has("navigationSound")
            && !defaults.has("navigationSoundVolume")
            && "off".equals(AppsSettings.navigationSound(defaults))
            && close(AppsSettings.navigationSoundVolume(defaults), .25f),
        "missing navigation feedback remains silent and wire-compatible");

    String[] optional = {"moviesEnabled", "primeVideoEnabled", "spotifyEnabled", "twitchEnabled", "huluEnabled"};
    for (String key : optional) {
      JSONObject enabled = AppsSettings.validate(new JSONObject().put(key, true));
      check(enabled.getBoolean(key), key + " accepts a boolean");
      boolean rejected = false;
      try {
        AppsSettings.validate(new JSONObject().put(key, "true"));
      } catch (IllegalArgumentException expected) {
        rejected = true;
      }
      check(rejected, key + " rejects a non-boolean");
    }

    boolean[] selected = {true, false, true, false, false, true, true};
    check(ShortcutRow.packedIndex(selected, 0) == 0, "first visible shortcut starts the row");
    check(ShortcutRow.packedIndex(selected, 2) == 1, "hidden shortcut leaves no blank slot");
    check(ShortcutRow.packedIndex(selected, 6) == 3, "last visible shortcut is packed");
    check(
        Float.isNaN(ShortcutRow.leftFraction(selected, 1)), "hidden shortcut has no row position");
    check(ShortcutRow.fitsInRow(8), "eight shortcuts fit without overlap");
    check(!ShortcutRow.fitsInPage(ShortcutRow.MINIMAL, 9), "minimal shortcuts page after eight");
    check(ShortcutRow.fitsInPage(ShortcutRow.CARDS, 5), "five cards fit without overlap");
    check(!ShortcutRow.fitsInPage(ShortcutRow.CARDS, 6), "cards page after five");
    check(close(ShortcutRow.pageWidth(ShortcutRow.MINIMAL, 8), .86f), "minimal page width is .86");
    check(close(ShortcutRow.pageWidth(ShortcutRow.MINIMAL, 22), .75f), "minimal carousel reserves both arrow gutters");
    check(close(ShortcutRow.pageWidth(ShortcutRow.CARDS, 5), .89f), "card page width is .89");
    check(close(ShortcutRow.pageWidth(ShortcutRow.CARDS, 22), .71f), "card carousel reserves both arrow gutters");
    check(
        close(
                ShortcutRow.pageWidth(ShortcutRow.MINIMAL, 22, 1f, 1f, .02f, 16f / 9f),
                ShortcutRow.pageWidth(ShortcutRow.MINIMAL, 22, 1f, .02f, 16f / 9f))
            && ShortcutRow.capacityForCount(ShortcutRow.MINIMAL, 22, 1f, 1f, .02f, 16f / 9f)
                == ShortcutRow.capacityForCount(ShortcutRow.MINIMAL, 22, 1f, .02f, 16f / 9f),
        "default custom spacing keeps the established row geometry and capacity");
    check(
        close(ShortcutRow.gap(ShortcutRow.MINIMAL, 1f, 0f), 0f)
            && close(ShortcutRow.stride(ShortcutRow.MINIMAL, 1f, 0f), ShortcutRow.width(ShortcutRow.MINIMAL, 1f))
            && close(ShortcutRow.rowWidth(ShortcutRow.MINIMAL, 8, 1f, 0f), .72f)
            && close(
                ShortcutRow.rowWidth(ShortcutRow.MINIMAL, 1, 1f, 0f),
                ShortcutRow.rowWidth(ShortcutRow.MINIMAL, 1, 1f, 3f))
            && close(ShortcutRow.rowWidth(ShortcutRow.MINIMAL, 1, 1f, 0f), ShortcutRow.width(ShortcutRow.MINIMAL, 1f)),
        "zero spacing changes only the gap and row stride");
    check(
        ShortcutRow.capacityForCount(ShortcutRow.MINIMAL, 22, 1f, 0f, .02f, 16f / 9f) == 8
            && ShortcutRow.capacityForCount(ShortcutRow.MINIMAL, 22, 1f, 0f, .02f, 16f / 9f)
                > ShortcutRow.capacityForCount(ShortcutRow.MINIMAL, 22, 1f, 1f, .02f, 16f / 9f),
        "zero spacing makes room for a wider minimal shortcut page");
    check(
        close(ShortcutRow.focusedScale(ShortcutRow.MINIMAL, 1f, 0f), 1f)
            && close(ShortcutRow.focusedOverflow(ShortcutRow.MINIMAL, 1f, 0f), 0f)
            && close(ShortcutRow.focusedScale(ShortcutRow.MINIMAL, 1f, 1f), ShortcutRow.FOCUSED_SCALE),
        "focus expansion stays inside the available custom spacing");
    check(
        close(
                ShortcutRow.rowWidth(ShortcutRow.MINIMAL, 22, 1f, 1f, .02f, 16f / 9f),
                .9775f)
            && close(
                ShortcutRow.coreRowWidth(ShortcutRow.MINIMAL, 22, 1f, 1f, .02f, 16f / 9f),
                .8274f)
            && close(
                ShortcutRow.coreRowWidth(0, ShortcutRow.MINIMAL, 22, 1f, 1f, .02f, 16f / 9f),
                .7887f)
            && close(
                ShortcutRow.rowWidth(ShortcutRow.MINIMAL, 8, 1f, 1f, .02f, 16f / 9f),
                .86f),
        "paged rows own the safe-width viewport while one-page rows retain their compact width");
    check(
        close(
            ShortcutRow.nextPagePeekOffset(ShortcutRow.MINIMAL, 22, 1f, 1f, .02f, 16f / 9f),
            .77f),
        "the next-page hint starts exactly one stride after the current page capacity");
    check(
        close(ShortcutRow.leadingCarouselGutter(0, ShortcutRow.MINIMAL, 1f, 1f), 0f)
            && close(
                ShortcutRow.leadingCarouselGutter(1, ShortcutRow.MINIMAL, 1f, 1f),
                ShortcutRow.carouselGutter(ShortcutRow.MINIMAL, 1f, 1f))
            && close(ShortcutRow.leadingFocusedScale(ShortcutRow.MINIMAL, 1f, 0f), 1f)
            && close(
                ShortcutRow.leadingFocusedScale(ShortcutRow.CARDS, 1f, 1f),
                1f + .01f / .17f),
        "page zero removes its idle leading gutter while its focused edge stays within the next gap");
    JSONObject pagedLayout = LauncherLayout.validate(new JSONObject().put("version", 1));
    check(
        close(
            LauncherLayout.x(
                pagedLayout,
                "apps",
                ShortcutRow.START_FRACTION,
                ShortcutRow.rowWidth(ShortcutRow.MINIMAL, 22, 1f, 1f, .02f, 16f / 9f),
                16f / 9f),
            .01125f),
        "the first page card area begins at the left safe edge without an idle arrow gutter");
    check(close(ShortcutRow.heightWithLabel(ShortcutRow.MINIMAL, true), .167f), "minimal labels retain row height");
    check(close(ShortcutRow.heightWithLabel(ShortcutRow.CARDS, true), .217f), "cards reserve label space");
    check(
        ShortcutRow.capacity(ShortcutRow.MINIMAL, 1.5f, .02f, 16f / 9f) == 5,
        "larger minimal icons reduce carousel capacity");
    check(
        ShortcutRow.capacity(ShortcutRow.CARDS, 1.5f, .02f, 16f / 9f) == 3,
        "larger cards reduce carousel capacity");
    check(
        ShortcutRow.capacityForCount(ShortcutRow.MINIMAL, 22, 1f, .02f, 16f / 9f) == 7
            && ShortcutRow.capacityForCount(ShortcutRow.CARDS, 22, 1f, .02f, 16f / 9f) == 4,
        "paged rows reduce capacity by the symmetric arrow gutters");
    check(
        ShortcutRow.capacity(ShortcutRow.CARDS, 1f, .10f, 16f / 9f) == 4,
        "safe area reduces card capacity before a row can clip");
    check(
        close(
            ShortcutRow.pageWidth(ShortcutRow.MINIMAL, 22, 1.5f, .02f, 16f / 9f),
            .63f),
        "scaled carousel width uses the gutter-reserved capacity");
    check(
        close(ShortcutRow.labelGap(1.5f), .018f)
            && close(ShortcutRow.labelHeight(1.5f), .0525f),
        "labels scale with the shortcut tile");
    check(
        close(ShortcutRow.focusedOverflow(ShortcutRow.CARDS, 1.5f), .00765f)
            && close(ShortcutRow.focusedOverflow(ShortcutRow.MINIMAL, 1.5f), .00405f),
        "selected shortcut expansion is reserved before carousel arrows");
    check(
        close(ShortcutRow.focusedContainerSize(.255f), .2703f)
            && close(
                ShortcutRow.focusedContainerSize(.255f)
                    * ShortcutRow.UNFOCUSED_CONTAINER_SCALE,
                .255f),
        "focus container preserves the resting shortcut size without transformed clipping");
    check(
        close(ShortcutRow.focusedEdgeOverflow(.18f), .0054f),
        "focused shortcut height reserves its safe-area edge expansion");

    JSONObject cards =
        AppsSettings.validate(
            new JSONObject()
                .put("shortcutStyle", "cards")
                .put("enabledServices", new JSONArray().put("youtube").put("disney_plus")));
    check("cards".equals(cards.getString("shortcutStyle")), "card style persists");
    check(cards.getJSONArray("enabledServices").length() == 2, "enabled service ids persist");
    check(
        AppsSettings.copy(cards).toString().equals(cards.toString()),
        "new app settings survive a defensive round trip");
    JSONObject ordered =
        AppsSettings.validate(
            new JSONObject()
                .put(
                    "shortcutOrder",
                    new JSONArray()
                        .put("hdmi")
                        .put("radio")
                        .put("hdmi")
                        .put("not-a-shortcut")
                        .put("youtube")));
    JSONArray normalizedOrder = ordered.getJSONArray("shortcutOrder");
    check("hdmi".equals(normalizedOrder.getString(0)), "saved shortcut order retains valid first ID");
    check("radio".equals(normalizedOrder.getString(1)), "saved shortcut order retains valid relative order");
    check("youtube".equals(normalizedOrder.getString(2)), "catalog services can move before legacy IDs");
    check(
        normalizedOrder.length() == AppsSettings.canonicalShortcutOrder().size(),
        "shortcut order appends every missing stable ID");
    check(
        AppsSettings.copy(ordered).toString().equals(ordered.toString()),
        "normalized shortcut order survives a defensive round trip");
    for (String sound : new String[] {"off", "soft", "glass", "wood", "bubble", "chime"}) {
      check(
          sound.equals(
              AppsSettings.validate(new JSONObject().put("navigationSound", sound))
                  .getString("navigationSound")),
          sound + " navigation feedback ID validates");
    }
    JSONObject navigationSound =
        AppsSettings.validate(
            new JSONObject().put("navigationSound", "glass").put("navigationSoundVolume", .7));
    check(
        "glass".equals(navigationSound.getString("navigationSound"))
            && close((float) navigationSound.getDouble("navigationSoundVolume"), .7f)
            && AppsSettings.copy(navigationSound).toString().equals(navigationSound.toString()),
        "navigation feedback settings validate and round-trip");
    rejected(new JSONObject().put("shortcutStyle", "large"), "unknown shortcut style");
    rejected(new JSONObject().put("shortcutStyle", true), "non-string shortcut style");
    rejected(new JSONObject().put("enabledServices", "youtube"), "non-array enabled services");
    rejected(new JSONObject().put("enabledServices", new JSONArray().put("youtube").put("youtube")), "duplicate enabled services");
    rejected(new JSONObject().put("enabledServices", new JSONArray().put("google_tv_home")), "unknown enabled service");
    rejected(new JSONObject().put("shortcutOrder", "netflix"), "non-array shortcut order");
    rejected(
        new JSONObject().put("shortcutOrder", new JSONArray().put("netflix").put(true)),
        "non-string shortcut order entry");
    rejected(new JSONObject().put("navigationSound", "beep"), "unknown navigation sound");
    rejected(new JSONObject().put("navigationSound", true), "non-string navigation sound");
    rejected(new JSONObject().put("navigationSoundVolume", -0.01), "negative navigation sound volume");
    rejected(new JSONObject().put("navigationSoundVolume", 1.01), "over-limit navigation sound volume");
    rejected(new JSONObject().put("navigationSoundVolume", "0.25"), "non-numeric navigation sound volume");
    rejected(new JSONObject().put("unexpected", true), "unknown app setting");
    System.out.println("AppsSettingsChecks passed");
  }

  private static void rejected(JSONObject value, String message) throws Exception {
    boolean rejected = false;
    try {
      AppsSettings.validate(value);
    } catch (IllegalArgumentException expected) {
      rejected = true;
    }
    check(rejected, message);
  }

  private static boolean close(float left, float right) {
    return Math.abs(left - right) < .0001f;
  }

  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
