package local.glasstv.launcher;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Loads the shared official-service catalog bundled with the receiver. */
final class AppShortcutCatalog {
  /** Catalog order is part of the fallback launcher order after the legacy shortcuts. */
  static final List<String> SERVICE_IDS =
      Collections.unmodifiableList(
          Arrays.asList(
              "youtube",
              "disney_plus",
              "tubi",
              "hbo_max",
              "paramount_plus",
              "peacock",
              "pluto_tv",
              "apple_tv",
              "espn",
              "crunchyroll",
              "discovery_plus",
              "dazn",
              "starz",
              "mgm_plus"));
  static final Set<String> NEW_SERVICE_IDS =
      Collections.unmodifiableSet(new LinkedHashSet<>(SERVICE_IDS));

  static final class Service {
    final String id, title, minimalIcon, colorIcon, colorBackground;
    final String[] packages;

    Service(
        String id,
        String title,
        String[] packages,
        String minimalIcon,
        String colorIcon,
        String colorBackground) {
      this.id = id;
      this.title = title;
      this.packages = packages;
      this.minimalIcon = minimalIcon;
      this.colorIcon = colorIcon;
      this.colorBackground = colorBackground;
    }
  }

  private AppShortcutCatalog() {}

  static List<Service> load(Context context) {
    try (InputStream input = context.getAssets().open("app-shortcuts.json")) {
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      byte[] buffer = new byte[4096];
      int count;
      while ((count = input.read(buffer)) > 0) output.write(buffer, 0, count);
      return parse(new JSONArray(new String(output.toByteArray(), StandardCharsets.UTF_8)));
    } catch (Exception ignored) {
      // A catalog upgrade must not hide the receiver's existing shortcuts if an asset is absent.
      return Collections.emptyList();
    }
  }

  static List<Service> parse(JSONArray source) throws Exception {
    ArrayList<Service> result = new ArrayList<>();
    Set<String> ids = new HashSet<>();
    for (int i = 0; i < source.length(); i++) {
      JSONObject value = source.optJSONObject(i);
      if (value == null) throw new IllegalArgumentException("shortcut catalog entry must be an object");
      String id = required(value, "id");
      if (!NEW_SERVICE_IDS.contains(id)) throw new IllegalArgumentException("unknown shortcut catalog id");
      if (!ids.add(id)) throw new IllegalArgumentException("duplicate shortcut catalog id");
      JSONArray packageValues = value.optJSONArray("packages");
      if (packageValues == null || packageValues.length() == 0)
        throw new IllegalArgumentException("shortcut catalog packages are required");
      String[] packages = new String[packageValues.length()];
      for (int packageIndex = 0; packageIndex < packageValues.length(); packageIndex++)
        packages[packageIndex] = required(packageValues, packageIndex, "package");
      result.add(
          new Service(
              id,
              required(value, "title"),
              packages,
              required(value, "minimalIcon"),
              required(value, "colorIcon"),
              required(value, "colorBackground")));
    }
    return Collections.unmodifiableList(result);
  }

  private static String required(JSONObject value, String name) {
    Object string = value.opt(name);
    if (!(string instanceof String) || ((String) string).trim().isEmpty())
      throw new IllegalArgumentException(name + " is required");
    return (String) string;
  }

  private static String required(JSONArray value, int index, String name) {
    Object string = value.opt(index);
    if (!(string instanceof String) || ((String) string).trim().isEmpty())
      throw new IllegalArgumentException(name + " is required");
    return (String) string;
  }
}
