package local.glasstv.launcher;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONArray;
import org.json.JSONObject;

/** Offline-first directory for the editorially selected Radio Browser catalog. */
final class RadioDirectory {
  static final int PAGE_SIZE = 12;
  private static final String RECHECK_HOST = "de1.api.radio-browser.info";
  private static final String USER_AGENT = "GlassTV/1.0 (Android TV radio)";
  private static final int CONNECT_TIMEOUT_MS = 5_000;
  private static final int READ_TIMEOUT_MS = 7_000;
  private static final int BODY_DEADLINE_MS = 8_000;

  static final class Category {
    final String id, type, label;
    final int artworkIndex;
    final boolean featured;
    final List<String> aliases;
    Category(RadioCatalog.Category source) {
      id = source.id; type = source.type; label = source.label; artworkIndex = source.artworkIndex;
      featured = source.featured; aliases = source.aliases;
    }
  }

  /** Retains its former fields so an in-flight screen can safely copy a cursor. */
  static final class Cursor {
    int bucket;
    final int[] offsets = new int[3];
  }

  static final class Page {
    final List<RadioStationPolicy.Station> stations;
    final boolean hasMore;
    Page(List<RadioStationPolicy.Station> stations, boolean hasMore) { this.stations = stations; this.hasMore = hasMore; }
  }

  private final Context context;
  private volatile RadioCatalog catalog;

  RadioDirectory(Context context) { this.context = context == null ? null : context.getApplicationContext(); }
  /** Compatibility only while callers are migrated to the required Context constructor. */
  @Deprecated RadioDirectory() { this(null); }

  List<Category> categories(String type) throws Exception {
    ArrayList<Category> result = new ArrayList<>();
    for (RadioCatalog.Category category : catalog().categories(type)) result.add(new Category(category));
    return Collections.unmodifiableList(result);
  }

  List<String> musicGenres() throws Exception {
    ArrayList<String> result = new ArrayList<>();
    for (Category category : categories("genre")) result.add(category.id);
    return Collections.unmodifiableList(result);
  }

  Page browseGenre(String categoryId, Cursor cursor) throws Exception { return page(catalog().category(categoryId), cursor); }
  Page searchByName(String name, Cursor cursor) throws Exception { return page(catalog().searchNames(name), cursor); }
  Page searchByTag(String tag, Cursor cursor) throws Exception { return page(catalog().searchTagsAndCategories(tag), cursor); }

  /** Local normalized search across station names, provider tags, and category aliases. */
  List<RadioStationPolicy.Station> searchCatalog(String query) throws Exception { return catalog().searchCatalog(query); }

  /** The bundled set's curated ordering, not a claim about real-time listener popularity. */
  Page popular(Cursor cursor) throws Exception { return page(catalog().featuredStations(), cursor); }

  /** Resolves saved IDs from the bundle without refreshing a general directory. */
  List<RadioStationPolicy.Station> favorites(List<String> uuids) throws Exception {
    if (uuids == null || uuids.isEmpty()) return Collections.emptyList();
    ArrayList<RadioStationPolicy.Station> result = new ArrayList<>();
    HashSet<String> seen = new HashSet<>();
    RadioCatalog current = catalog();
    for (String uuid : uuids) {
      RadioStationPolicy.Station station = current.station(uuid);
      if (station != null && seen.add(station.uuid)) result.add(station);
    }
    return result;
  }

  /** Rechecks one bundled UUID only; its current stream must remain a public HTTPS URL. */
  List<RadioStationPolicy.Station> checkedStation(String uuid) throws Exception {
    RadioStationPolicy.Station bundled = catalog().station(uuid);
    if (bundled == null) return Collections.emptyList();
    JSONArray rows = recheck(bundled.uuid);
    ArrayList<RadioStationPolicy.Station> result = new ArrayList<>();
    for (int index = 0; index < rows.length(); index++) {
      JSONObject row = rows.optJSONObject(index);
      if (row == null) continue;
      String id = row.optString("stationuuid", "").toLowerCase(Locale.US);
      if (!bundled.uuid.equals(id)) continue;
      String name = row.optString("name", "").trim(), url = row.optString("url_resolved", "").trim();
      int bitrate = row.optInt("bitrate", 0);
      if (RadioStationPolicy.qualifies(id, name, url, row.optInt("lastcheckok", 0) == 1, bitrate, safePublicHttpsUrl(url)))
        result.add(new RadioStationPolicy.Station(id, name, url, trim(row.optString("tags", ""), 160),
            trim(row.optString("country", ""), 48), bitrate, trim(row.optString("favicon", ""), 2048)));
    }
    return RadioStationPolicy.uniqueAndRank(result, 1);
  }

  private RadioCatalog catalog() throws Exception {
    RadioCatalog ready = catalog;
    if (ready != null) return ready;
    synchronized (this) {
      if (catalog == null) catalog = RadioCatalog.load(context);
      return catalog;
    }
  }

  private Page page(List<RadioStationPolicy.Station> source, Cursor cursor) {
    if (cursor == null) throw new IllegalArgumentException("Radio catalog cursor is required");
    int start = Math.max(0, cursor.offsets[0]);
    if (start >= source.size()) return new Page(Collections.<RadioStationPolicy.Station>emptyList(), false);
    int end = Math.min(source.size(), start + PAGE_SIZE);
    cursor.offsets[0] = end;
    cursor.bucket = end < source.size() ? 0 : 1;
    return new Page(new ArrayList<>(source.subList(start, end)), end < source.size());
  }

  private static JSONArray recheck(String uuid) throws Exception {
    HttpsURLConnection connection = (HttpsURLConnection) new URL("https", RECHECK_HOST, "/json/stations/byuuid/" + encode(uuid)).openConnection();
    connection.setConnectTimeout(CONNECT_TIMEOUT_MS); connection.setReadTimeout(READ_TIMEOUT_MS);
    connection.setInstanceFollowRedirects(false); connection.setRequestProperty("User-Agent", USER_AGENT);
    connection.setRequestProperty("Accept", "application/json");
    try {
      if (connection.getResponseCode() != 200) throw new IllegalStateException("Radio station recheck failed");
      try (InputStream body = connection.getInputStream()) { return new JSONArray(readBounded(body)); }
    } finally { connection.disconnect(); }
  }

  static boolean safeHttpsUrl(String value) {
    try {
      URI uri = new URI(value);
      return "https".equalsIgnoreCase(uri.getScheme()) && uri.getUserInfo() == null && uri.getHost() != null
          && value.length() <= 2048 && IDN.toASCII(uri.getHost()).length() <= 253 && uri.getPort() >= -1 && uri.getPort() <= 65535;
    } catch (Exception invalid) { return false; }
  }
  static boolean safePlaybackUrl(String value) { return safePublicHttpsUrl(value); }
  /** HTTPS URL with a resolved public destination, suitable for stream or artwork retrieval. */
  static boolean safePublicHttpsUrl(String value) {
    if (!safeHttpsUrl(value)) return false;
    try {
      for (InetAddress address : InetAddress.getAllByName(new URI(value).getHost())) {
        byte[] bytes = address.getAddress();
        boolean uniqueLocalV6 = bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress() || address.isMulticastAddress() || uniqueLocalV6) return false;
      }
      return true;
    } catch (Exception invalid) { return false; }
  }

  private static String readBounded(InputStream input) throws Exception {
    long deadline = System.nanoTime() + BODY_DEADLINE_MS * 1_000_000L;
    ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int count;
    while ((count = input.read(buffer)) != -1) {
      if (System.nanoTime() > deadline || output.size() + count > 512 * 1024) throw new IllegalStateException("Radio station recheck response limit exceeded");
      output.write(buffer, 0, count);
    }
    return output.toString("UTF-8");
  }
  private static String trim(String value, int maximum) { String trimmed = value == null ? "" : value.trim(); return trimmed.length() > maximum ? trimmed.substring(0, maximum) : trimmed; }
  private static String encode(String value) { try { return URLEncoder.encode(value, "UTF-8").replace("+", "%20"); } catch (Exception impossible) { return ""; } }
}
