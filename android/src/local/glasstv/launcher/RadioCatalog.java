package local.glasstv.launcher;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Immutable, bundled Radio Browser selection and its local search index. */
final class RadioCatalog {
  static final String ASSET_NAME = "radio-catalog.json";
  private static final int MAX_ASSET_BYTES = 2 * 1024 * 1024;

  static final class Category {
    final String id;
    final String type;
    final String label;
    final int artworkIndex;
    final boolean featured;
    final List<String> aliases;

    Category(String id, String type, String label, int artworkIndex, boolean featured, List<String> aliases) {
      this.id = id;
      this.type = type;
      this.label = label;
      this.artworkIndex = artworkIndex;
      this.featured = featured;
      this.aliases = Collections.unmodifiableList(new ArrayList<>(aliases));
    }
  }

  private final List<Category> categories;
  private final List<RadioStationPolicy.Station> stations;
  private final List<RadioStationPolicy.Station> featured;
  private final Map<String, Category> categoryById;
  private final Map<String, RadioStationPolicy.Station> stationById;
  private final Map<String, List<RadioStationPolicy.Station>> stationsByCategory;
  private final Map<String, String> searchTextByStationId;

  private RadioCatalog(
      List<Category> categories,
      List<RadioStationPolicy.Station> stations,
      List<RadioStationPolicy.Station> featured,
      Map<String, List<RadioStationPolicy.Station>> stationsByCategory,
      Map<String, String> searchTextByStationId) {
    this.categories = Collections.unmodifiableList(new ArrayList<>(categories));
    this.stations = Collections.unmodifiableList(new ArrayList<>(stations));
    this.featured = Collections.unmodifiableList(new ArrayList<>(featured));
    this.categoryById = new LinkedHashMap<>();
    for (Category category : categories) categoryById.put(category.id, category);
    this.stationById = new LinkedHashMap<>();
    for (RadioStationPolicy.Station station : stations) stationById.put(station.uuid, station);
    this.stationsByCategory = new HashMap<>();
    for (Map.Entry<String, List<RadioStationPolicy.Station>> entry : stationsByCategory.entrySet()) {
      this.stationsByCategory.put(entry.getKey(), Collections.unmodifiableList(new ArrayList<>(entry.getValue())));
    }
    this.searchTextByStationId = Collections.unmodifiableMap(new HashMap<>(searchTextByStationId));
  }

  static RadioCatalog load(Context context) throws Exception {
    if (context == null) throw new IllegalArgumentException("RadioDirectory requires an application Context for its bundled catalog");
    try (InputStream input = context.getAssets().open(ASSET_NAME)) {
      return parse(readBounded(input));
    }
  }

  static RadioCatalog parse(String source) throws Exception {
    JSONObject root = new JSONObject(source);
    if (root.optInt("schemaVersion", 0) != 1) throw new IllegalArgumentException("Unsupported radio catalog schema");
    JSONArray rawCategories = root.optJSONArray("categories");
    JSONArray rawStations = root.optJSONArray("stations");
    if (rawCategories == null || rawStations == null || rawCategories.length() != 60)
      throw new IllegalArgumentException("Radio catalog has incomplete category coverage");

    ArrayList<Category> categories = new ArrayList<>();
    HashSet<String> categoryIds = new HashSet<>();
    for (int index = 0; index < rawCategories.length(); index++) {
      JSONObject raw = rawCategories.optJSONObject(index);
      if (raw == null) throw new IllegalArgumentException("Invalid radio category");
      String id = nonempty(raw.optString("id", ""), 64, "category id");
      String type = nonempty(raw.optString("type", ""), 16, "category type");
      if (!("genre".equals(type) || "style".equals(type) || "mood".equals(type)))
        throw new IllegalArgumentException("Unknown radio category type");
      if (!categoryIds.add(id) || raw.optInt("artworkIndex", -1) != index)
        throw new IllegalArgumentException("Radio category ordering is invalid");
      JSONArray rawAliases = raw.optJSONArray("aliases");
      ArrayList<String> aliases = new ArrayList<>();
      if (rawAliases != null) for (int aliasIndex = 0; aliasIndex < rawAliases.length(); aliasIndex++) {
        String alias = rawAliases.optString(aliasIndex, "").trim();
        if (!alias.isEmpty() && alias.length() <= 96) aliases.add(alias);
      }
      categories.add(new Category(id, type, nonempty(raw.optString("label", ""), 96, "category label"), index,
          raw.optBoolean("featured", false), aliases));
    }
    requireCategoryCounts(categories);

    ArrayList<RadioStationPolicy.Station> stations = new ArrayList<>();
    HashMap<String, List<RadioStationPolicy.Station>> byCategory = new HashMap<>();
    HashMap<String, String> searchText = new HashMap<>();
    HashSet<String> stationIds = new HashSet<>();
    for (int index = 0; index < rawStations.length(); index++) {
      JSONObject raw = rawStations.optJSONObject(index);
      if (raw == null) throw new IllegalArgumentException("Invalid radio station");
      String uuid = raw.optString("uuid", "").toLowerCase(Locale.US);
      String name = raw.optString("name", "").trim();
      String url = raw.optString("url", "").trim();
      int bitrate = raw.optInt("bitrate", 0);
      if (!stationIds.add(uuid) || !RadioStationPolicy.qualifies(uuid, name, url, true, bitrate, RadioDirectory.safeHttpsUrl(url)))
        throw new IllegalArgumentException("Radio catalog contains an ineligible station");
      RadioStationPolicy.Station station = new RadioStationPolicy.Station(uuid, name, url,
          trim(raw.optString("tags", ""), 160), trim(raw.optString("country", ""), 48), bitrate,
          trim(raw.optString("artworkUrl", ""), 2048));
      JSONArray rawMemberships = raw.optJSONArray("categories");
      if (rawMemberships == null || rawMemberships.length() == 0)
        throw new IllegalArgumentException("Radio catalog station has no category");
      StringBuilder indexed = new StringBuilder(name).append(' ').append(station.genre);
      HashSet<String> memberships = new HashSet<>();
      for (int membership = 0; membership < rawMemberships.length(); membership++) {
        String categoryId = rawMemberships.optString(membership, "");
        Category category = find(categories, categoryId);
        if (category == null || !memberships.add(categoryId))
          throw new IllegalArgumentException("Radio catalog station has invalid category");
        byCategory.computeIfAbsent(categoryId, ignored -> new ArrayList<>()).add(station);
        indexed.append(' ').append(category.label);
        for (String alias : category.aliases) indexed.append(' ').append(alias);
      }
      stations.add(station);
      searchText.put(uuid, normalize(indexed.toString()));
    }
    // Editorial curation prefers an honest sparse category to unrelated filler. The UI retains all
    // requested category slots and can show its explicit empty state when a provider has no good
    // current stream for a narrow style or mood.
    ArrayList<RadioStationPolicy.Station> featured = new ArrayList<>();
    JSONArray featuredIds = root.optJSONArray("featuredStationIds");
    HashSet<String> seenFeatured = new HashSet<>();
    if (featuredIds != null) for (int index = 0; index < featuredIds.length(); index++) {
      String id = featuredIds.optString(index, "");
      if (!seenFeatured.add(id)) continue;
      for (RadioStationPolicy.Station station : stations) if (station.uuid.equals(id)) {
        featured.add(station);
        break;
      }
    }
    return new RadioCatalog(categories, RadioStationPolicy.uniqueAndRank(stations, stations.size()), featured, byCategory, searchText);
  }

  List<Category> categories(String type) {
    if (type == null || type.trim().isEmpty()) return categories;
    ArrayList<Category> result = new ArrayList<>();
    for (Category category : categories) if (type.equals(category.type)) result.add(category);
    return Collections.unmodifiableList(result);
  }

  List<RadioStationPolicy.Station> allStations() { return stations; }
  List<RadioStationPolicy.Station> featuredStations() { return featured; }

  List<RadioStationPolicy.Station> category(String id) {
    List<RadioStationPolicy.Station> result = stationsByCategory.get(id);
    return result == null ? Collections.<RadioStationPolicy.Station>emptyList() : result;
  }

  RadioStationPolicy.Station station(String uuid) {
    return stationById.get(uuid == null ? "" : uuid.toLowerCase(Locale.US));
  }

  List<RadioStationPolicy.Station> searchCatalog(String query) {
    String normalized = normalize(query);
    if (normalized.isEmpty()) return Collections.emptyList();
    ArrayList<RadioStationPolicy.Station> result = new ArrayList<>();
    for (RadioStationPolicy.Station station : stations) {
      if (matches(searchTextByStationId.get(station.uuid), normalized)) result.add(station);
    }
    return RadioStationPolicy.uniqueAndRank(result, result.size());
  }

  List<RadioStationPolicy.Station> searchNames(String query) {
    String normalized = normalize(query);
    if (normalized.isEmpty()) return Collections.emptyList();
    ArrayList<RadioStationPolicy.Station> result = new ArrayList<>();
    for (RadioStationPolicy.Station station : stations) if (matches(normalize(station.name), normalized)) result.add(station);
    return RadioStationPolicy.uniqueAndRank(result, result.size());
  }

  List<RadioStationPolicy.Station> searchTagsAndCategories(String query) {
    String normalized = normalize(query);
    if (normalized.isEmpty()) return Collections.emptyList();
    ArrayList<RadioStationPolicy.Station> result = new ArrayList<>();
    for (RadioStationPolicy.Station station : stations) {
      String categoryAndTags = searchTextByStationId.get(station.uuid);
      if (matches(categoryAndTags, normalized) && !matches(normalize(station.name), normalized)) result.add(station);
    }
    return RadioStationPolicy.uniqueAndRank(result, result.size());
  }

  static String normalize(String value) {
    String folded = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKD)
        .replaceAll("\\p{M}+", "").toLowerCase(Locale.US);
    return folded.replaceAll("[^\\p{L}\\p{N}]+", " ").trim().replaceAll(" +", " ");
  }

  private static boolean matches(String haystack, String needle) {
    if (haystack == null || needle.isEmpty()) return false;
    for (String token : needle.split(" ")) if (!haystack.contains(token)) return false;
    return true;
  }

  private static Category find(List<Category> categories, String id) {
    for (Category category : categories) if (category.id.equals(id)) return category;
    return null;
  }

  private static void requireCategoryCounts(List<Category> categories) {
    int genres = 0, styles = 0, moods = 0;
    for (Category category : categories) {
      if ("genre".equals(category.type)) genres++;
      else if ("style".equals(category.type)) styles++;
      else if ("mood".equals(category.type)) moods++;
    }
    if (genres != 18 || styles != 30 || moods != 12)
      throw new IllegalArgumentException("Radio category type coverage is invalid");
  }

  private static String nonempty(String value, int maximum, String label) {
    String trimmed = value == null ? "" : value.trim();
    if (trimmed.isEmpty() || trimmed.length() > maximum) throw new IllegalArgumentException("Invalid " + label);
    return trimmed;
  }

  private static String trim(String value, int maximum) {
    String trimmed = value == null ? "" : value.trim();
    return trimmed.length() > maximum ? trimmed.substring(0, maximum) : trimmed;
  }

  private static String readBounded(InputStream input) throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    int count;
    while ((count = input.read(buffer)) != -1) {
      if (output.size() + count > MAX_ASSET_BYTES) throw new IllegalArgumentException("Radio catalog asset is too large");
      output.write(buffer, 0, count);
    }
    return output.toString("UTF-8");
  }
}
