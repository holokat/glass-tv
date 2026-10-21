package local.glasstv.launcher;

import android.content.Context;
import android.content.ContextWrapper;
import android.os.Looper;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Device-runnable offline catalog checks. They never query a general station directory or play audio. */
public final class RadioDirectoryChecks {
  public static void main(String[] args) throws Exception {
    Looper.prepareMainLooper();
    RadioDirectory directory = new RadioDirectory(applicationContext());
    checkCategories(directory, "genre", 18, new String[] {"Pop", "Rock", "Hip-Hop", "Electronic", "Jazz", "Classical", "R&B / Soul", "Chill / Ambient", "Dance", "House", "Techno", "Indie / Alternative", "Metal", "Country", "Reggae", "Latin", "Blues", "World Music"});
    checkCategories(directory, "style", 30, new String[] {"Lo-Fi", "Chillhop", "Downtempo", "Ambient", "Deep House", "Tropical House", "Synthwave", "Vaporwave", "Retrowave", "Chillout", "Lounge", "Trip-Hop", "Dream Pop", "Indie Folk", "Shoegaze", "Neo-Soul", "Smooth Jazz", "Acid Jazz", "Bossa Nova", "Jazzhop", "Nu-Disco", "Liquid DnB", "Future Garage", "UK Garage", "Psybient", "Organic House", "Afro House", "Instrumental Hip-Hop", "Cinematic", "New Age"});
    checkCategories(directory, "mood", 12, new String[] {"Chill", "Lo-Fi", "Focus", "Relax", "Sleep", "Energetic", "Upbeat", "Dreamy", "Romantic", "Melancholic", "Cozy", "Atmospheric"});

    List<RadioDirectory.Category> all = new ArrayList<>();
    all.addAll(directory.categories("genre")); all.addAll(directory.categories("style")); all.addAll(directory.categories("mood"));
    for (RadioDirectory.Category category : all) {
      RadioDirectory.Page page = directory.browseGenre(category.id, new RadioDirectory.Cursor());
      check(page.stations.size() <= 10 && !page.hasMore,
          "category remains a compact exact browse result: " + category.id);
      if ("genre".equals(category.type))
        check(page.stations.size() == 5, "every main genre has five curated stations: " + category.id);
      // Three narrow styles intentionally remain sparse when the reviewed source has fewer than
      // five candidates. They still resolve their exact membership without unrelated filler.
      if (!page.stations.isEmpty()) validate(page.stations, new HashSet<String>());
    }
    RadioDirectory.Page popular = directory.popular(new RadioDirectory.Cursor());
    check(popular.stations.size() == 8 && !popular.hasMore,
        "the explicit eight-station Featured selection is available offline");
    validate(popular.stations, new HashSet<String>());

    List<RadioStationPolicy.Station> aliases = directory.searchCatalog("RNB");
    check(!aliases.isEmpty(), "category aliases are local-searchable");
    check(!directory.searchCatalog("LO fi").isEmpty(), "case and punctuation normalize locally");
    check("cafe lo fi".equals(RadioCatalog.normalize("Café: Lo-Fi")), "accent and punctuation normalization");
    check(hasName(directory.searchCatalog("雨声"), "雨声"),
        "Unicode station names remain intact through local search");
    RadioDirectory.Page names = directory.searchByName(aliases.get(0).name, new RadioDirectory.Cursor());
    check(!names.stations.isEmpty(), "station names search only the bundle");
    RadioDirectory.Page tags = directory.searchByTag("r&b / soul", new RadioDirectory.Cursor());
    check(!tags.stations.isEmpty(), "category labels search only the bundle");

    List<String> favoriteIds = new ArrayList<>(); favoriteIds.add(aliases.get(0).uuid);
    check(directory.favorites(favoriteIds).size() == 1, "saved bundled favorite resolves locally");
    check(directory.checkedStation("00000000-0000-0000-0000-000000000000").isEmpty(), "non-bundled playback ID cannot be rechecked");
    check(!RadioDirectory.safeHttpsUrl("http://example.com/audio"), "reject HTTP");
    check(!RadioDirectory.safePlaybackUrl("https://127.0.0.1/audio"), "reject loopback playback");
    System.out.println("RadioDirectoryChecks passed: 60-category offline catalog, local normalized search, saved IDs, and playback allowlist.");
  }

  private static Context applicationContext() throws Exception {
    Class<?> activityThread = Class.forName("android.app.ActivityThread");
    Object thread = activityThread.getMethod("systemMain").invoke(null);
    Context system = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
    Context packageContext = system.createPackageContext("local.glasstv.launcher", 0);
    // A shell app_process has no bound Application, so createPackageContext() returns a Context
    // with a null applicationContext. RadioDirectory intentionally requires a bound Context.
    return new ContextWrapper(packageContext) {
      @Override public Context getApplicationContext() { return this; }
    };
  }

  private static void checkCategories(RadioDirectory directory, String type, int expected, String[] labels) throws Exception {
    List<RadioDirectory.Category> values = directory.categories(type);
    check(values.size() == expected, type + " category count");
    for (int index = 0; index < labels.length; index++) {
      check(labels[index].equals(values.get(index).label), type + " category order " + index);
      int expectedArtworkIndex = "genre".equals(type) ? index : "style".equals(type) ? 18 + index : 48 + index;
      check(values.get(index).artworkIndex == expectedArtworkIndex, type + " artwork slot " + index);
    }
  }

  private static void validate(List<RadioStationPolicy.Station> stations, Set<String> ids) {
    for (RadioStationPolicy.Station station : stations) {
      check(station.bitrate >= 128 && station.bitrate <= 10000, "bitrate boundary");
      check(station.url.startsWith("https://"), "HTTPS stream");
      check(ids.add(station.uuid), "no duplicate page station");
    }
  }

  private static boolean hasName(List<RadioStationPolicy.Station> stations, String expected) {
    for (RadioStationPolicy.Station station : stations) if (expected.equals(station.name)) return true;
    return false;
  }

  private static void check(boolean condition, String label) { if (!condition) throw new AssertionError(label); }
}
