package local.glasstv.launcher;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Looper;
import android.view.FocusFinder;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Headless, controlled Radio rendering check. It never opens Radio, starts playback, requests
 * audio focus, or calls a controller directory method. The Popular row uses a real directory page
 * loaded before construction; empty Recent/Favorites keep the browse surface intentionally compact.
 */
public final class RadioUiChecks {
  private static final int WIDTH = 1920;
  private static final int HEIGHT = 1080;

  public static void main(String[] args) throws Exception {
    Looper.prepareMainLooper();
    Context app = applicationContext();
    step("application context ready");
    RadioDirectory.Page popular = loadPopularBeforeController();
    check(!popular.stations.isEmpty(), "real Popular directory page is available");

    MemoryPreferences preferences = new MemoryPreferences();
    Context isolated = new ContextWrapper(app) {
      @Override public SharedPreferences getSharedPreferences(String name, int mode) { return preferences; }
    };
    FrameLayout shell = new FrameLayout(isolated);
    // This neutral test shell is only a render target, never a representation of the user's TV.
    shell.setBackgroundColor(0xff101419);
    step("test shell ready");
    RadioController controller = new RadioController(isolated, new NoAudioHost(), shell);
    step("controller constructed");
    FrameLayout overlay = privateField(controller, "overlay", FrameLayout.class);
    overlay.setVisibility(View.VISIBLE);
    step("overlay made visible");
    Method showBrowse = RadioController.class.getDeclaredMethod("showBrowse", List.class);
    showBrowse.setAccessible(true);
    showBrowse.invoke(controller, popular.stations);
    step("browse fixture rendered");

    measureAndLayout(shell);
    step("fixture laid out");
    // app_process on this TV is killed whenever it enters Looper.loop(), so do not pump remote
    // favicon callbacks in the headless renderer. Close the test-only loader after card layout;
    // the screenshot verifies the same contained placeholder bounds used until artwork arrives.
    privateField(controller, "stationArtwork", RadioArtwork.class).close();
    measureAndLayout(shell);
    step("test-only artwork loader closed");
    float density = isolated.getResources().getDisplayMetrics().density;
    assertBrowseState(controller, shell, density);
    assertStationCards(shell, popular.stations, density);
    assertIcons(controller, shell, isolated);
    render(shell, new File(args.length == 1 ? args[0] : "/data/local/tmp/glasstv-radio-ui", "popular-fixture.png"));
    assertPlayerFavoriteFocusRoute(controller, shell, popular.stations);
    controller.destroy();
    System.out.println("RadioUiChecks passed; controlled fixture render in "
        + (args.length == 1 ? args[0] : "/data/local/tmp/glasstv-radio-ui"));
  }

  private static Context applicationContext() throws Exception {
    Class<?> activityThread = Class.forName("android.app.ActivityThread");
    Object thread = activityThread.getMethod("systemMain").invoke(null);
    if (android.graphics.Typeface.DEFAULT == null) {
      Method fonts = android.graphics.Typeface.class.getDeclaredMethod("loadPreinstalledSystemFontMap");
      fonts.setAccessible(true);
      fonts.invoke(null);
    }
    Context system = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
    return system.createPackageContext("local.glasstv.launcher", 0);
  }

  private static RadioDirectory.Page loadPopularBeforeController() throws Exception {
    final RadioDirectory.Page[] page = new RadioDirectory.Page[1];
    final Exception[] failure = new Exception[1];
    Thread fetch = new Thread(() -> {
      try { page[0] = new RadioDirectory().popular(new RadioDirectory.Cursor()); }
      catch (Exception error) { failure[0] = error; }
    }, "radio-ui-check-directory");
    fetch.start();
    fetch.join(45_000);
    if (fetch.isAlive()) {
      fetch.interrupt();
      throw new IllegalStateException("Radio directory fixture timed out");
    }
    if (failure[0] != null) throw failure[0];
    if (page[0] == null) throw new IllegalStateException("Radio directory fixture missing");
    return page[0];
  }

  private static void assertBrowseState(RadioController controller, FrameLayout shell, float density) {
    TextView title = privateField(controller, "title", TextView.class);
    TextView search = privateField(controller, "searchField", TextView.class);
    LinearLayout content = privateField(controller, "content", LinearLayout.class);
    LinearLayout sections = privateField(controller, "sections", LinearLayout.class);
    FrameLayout player = privateField(controller, "playerBar", FrameLayout.class);
    TextView playerFavorite = privateField(controller, "playerFavorite", TextView.class);
    Rect searchBounds = boundsInShell(shell, search);
    Rect contentBounds = boundsInShell(shell, content);
    check(textSizeIs(title, 40), "Radio heading uses 40sp type");
    check(contentBounds.left == px(24, density) && contentBounds.width() == px(912, density),
        "Radio uses the full-width 24dp content inset without a left navigation rail");
    check(searchBounds.left == px(24, density) && searchBounds.top == px(86, density)
            && searchBounds.width() == px(424, density) && searchBounds.height() == px(40, density),
        "search remains a reachable full-width content control");
    check(viewWithDescription(shell, "Browse") == null && viewWithDescription(shell, "Favorites") == null,
        "the former left navigation controls are absent");
    check(textView(sections, "Recent") == null && textView(sections, "Favorites") == null,
        "empty Recent and Favorites sections are absent");
    check(player.getVisibility() == View.GONE, "idle radio does not show a player bar");
    check("Save station".contentEquals(playerFavorite.getContentDescription()) && playerFavorite.isFocusable() == false,
        "player favorite replaces the volume meter and only activates for a playback session");
    TextView popular = (TextView) textView(sections, "Popular");
    TextView genres = (TextView) textView(sections, "Genres");
    check(popular != null && genres != null && textSizeIs(popular, 22)
            && textSizeIs(genres, 22),
        "browse section headings use 22sp type");
  }

  private static void assertStationCards(FrameLayout shell, List<RadioStationPolicy.Station> stations, float density) {
    ArrayList<View> cards = new ArrayList<>();
    collectDescriptionPrefix(shell, "Play ", cards);
    int expected = Math.min(5, stations.size());
    check(cards.size() >= expected, "Popular renders five-or-fewer real station cards");
    for (int index = 0; index < expected; index++) {
      View card = cards.get(index);
      RadioStationPolicy.Station station = stations.get(index);
      check(("Play " + station.name).contentEquals(card.getContentDescription()),
          "card retains the unmodified directory station title");
      check(card.getWidth() == px(284, density) && card.getHeight() == px(200, density),
          "card " + index + " doubles the previous common image-and-metadata bounds");
      if (index < 3) check(card.getRight() <= px(936, density),
          "visible card " + index + " fits inside the full-width content edge");
      check(card instanceof ViewGroup && ((ViewGroup) card).getChildAt(0) instanceof FrameLayout,
          "card " + index + " has a bounded artwork frame");
      FrameLayout art = (FrameLayout) ((ViewGroup) card).getChildAt(0);
      check(art.getWidth() == px(284, density) && art.getHeight() == px(158, density),
          "artwork frame " + index + " grows with the card regardless of favicon dimensions");
      check(art.getForeground() != null, "focus outline is constrained to artwork " + index);
      check(art.getChildCount() > 0 && art.getChildAt(0) instanceof ImageView
              && artworkScale(((ImageView) art.getChildAt(0)).getScaleType()),
          "station artwork " + index + " supports placeholder containment and loaded cover");
      check(((ViewGroup) card).getChildCount() == 2 && ((ViewGroup) card).getChildAt(1) instanceof TextView
              && station.name.contentEquals(((TextView) ((ViewGroup) card).getChildAt(1)).getText()),
          "station card " + index + " renders only the real station title below artwork");
      check(card.requestFocus() && card.hasFocus(), "card " + index + " receives TV focus");
      check(firstImageWithDescription(art, "Save ", "Remove ") == null,
          "station card " + index + " has no favorite overlay");
      check(card.getNextFocusUpId() != View.NO_ID,
          "first-row card " + index + " has an explicit route back to search");
    }
  }

  private static void assertPlayerFavoriteFocusRoute(
      RadioController controller, FrameLayout shell, List<RadioStationPolicy.Station> popularStations)
      throws Exception {
    ArrayList<View> cards = new ArrayList<>();
    collectDescriptionPrefix(shell, "Play ", cards);
    ArrayList<View> genres = new ArrayList<>();
    collectDescriptionPrefix(shell, "Browse ", genres);
    View popular = cards.get(0);
    View genre = genres.get(0);
    TextView search = privateField(controller, "searchField", TextView.class);
    FrameLayout player = privateField(controller, "playerBar", FrameLayout.class);
    TextView favorite = privateField(controller, "playerFavorite", TextView.class);
    Method updateRoutes = RadioController.class.getDeclaredMethod("updateCardPlayerRoutes", boolean.class);
    updateRoutes.setAccessible(true);
    player.setVisibility(View.VISIBLE);
    favorite.setFocusable(true);
    updateRoutes.invoke(controller, true);
    measureAndLayout(shell);
    check(popular.getNextFocusDownId() == genre.getId(),
        "D-pad Down from Popular continues to Genres while playback is active");
    check(genre.getNextFocusUpId() == popular.getId(),
        "D-pad Up from Genres returns to the same Popular column");
    check(genre.getNextFocusDownId() == favorite.getId(),
        "only the last row exposes the player favorite below it");
    check(popular.requestFocus() && popular.hasFocus(), "Popular card focus is restored before entering the player");
    check(FocusFinder.getInstance().findNextFocus(shell, popular, View.FOCUS_DOWN) == genre,
        "FocusFinder reaches Genres before the player");
    check(search.requestFocus(), "search takes focus before entering the first row");
    search.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,
        android.view.KeyEvent.KEYCODE_DPAD_DOWN));
    check(popular.hasFocus(), "D-pad Down from Search reaches the first card");
    check(genre.requestFocus() && genre.hasFocus(), "last row focus is restored before entering the player");
    check(FocusFinder.getInstance().findNextFocus(shell, genre, View.FOCUS_DOWN) == favorite,
        "D-pad Down from the last row reaches the player favorite");
    check(favorite.requestFocus() && favorite.hasFocus(), "player favorite is focusable during playback");
    check(FocusFinder.getInstance().findNextFocus(shell, favorite, View.FOCUS_UP) == genre,
        "D-pad Up returns from player favorite to the last card");
    Method showBrowse = RadioController.class.getDeclaredMethod("showBrowse", List.class);
    showBrowse.setAccessible(true);
    showBrowse.invoke(controller, popularStations);
    measureAndLayout(shell);
    ArrayList<View> rebuiltGenres = new ArrayList<>();
    collectDescriptionPrefix(shell, "Browse ", rebuiltGenres);
    check(favorite.hasFocus(), "refreshing the Favorites row keeps focus in the player");
    check(FocusFinder.getInstance().findNextFocus(shell, favorite, View.FOCUS_UP) == rebuiltGenres.get(0),
        "player Up is remapped to the rebuilt matching card");
  }

  private static void assertIcons(RadioController controller, FrameLayout shell, Context context) {
    for (String icon : new String[] {"podcast", "heart", "heart_filled", "search", "previous", "next", "play", "pause"})
      check(context.getResources().getIdentifier(icon, "drawable", context.getPackageName()) != 0,
          "installed Central " + icon + " drawable is available");
    TextView search = privateField(controller, "searchField", TextView.class);
    check(search.getCompoundDrawables()[0] != null,
        "search field renders its search icon");
  }

  private static void measureAndLayout(FrameLayout shell) {
    shell.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY));
    shell.layout(0, 0, WIDTH, HEIGHT);
  }

  private static void render(FrameLayout shell, File file) throws Exception {
    File directory = file.getParentFile();
    if (!directory.exists() && !directory.mkdirs()) throw new IllegalStateException("Could not create screenshot directory");
    Bitmap bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
    shell.draw(new Canvas(bitmap));
    try (FileOutputStream stream = new FileOutputStream(file)) {
      if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) throw new IllegalStateException("Could not encode radio fixture");
    }
    bitmap.recycle();
  }

  private static int px(int dp, float density) { return Math.round(dp * density); }

  private static boolean artworkScale(ImageView.ScaleType type) {
    return type == ImageView.ScaleType.CENTER_INSIDE || type == ImageView.ScaleType.CENTER_CROP;
  }

  private static android.graphics.Rect boundsInShell(FrameLayout shell, View target) {
    if (target == null) return new android.graphics.Rect();
    android.graphics.Rect result = new android.graphics.Rect(0, 0, target.getWidth(), target.getHeight());
    shell.offsetDescendantRectToMyCoords(target, result);
    return result;
  }

  private static float sp(int value, TextView view) {
    return value * view.getResources().getDisplayMetrics().scaledDensity;
  }

  private static boolean textSizeIs(TextView view, int expectedSp) {
    return Math.abs(view.getTextSize() - sp(expectedSp, view)) < .01f;
  }

  private static View viewWithDescription(View root, String description) {
    if (description.contentEquals(root.getContentDescription())) return root;
    if (!(root instanceof ViewGroup)) return null;
    ViewGroup group = (ViewGroup) root;
    for (int index = 0; index < group.getChildCount(); index++) {
      View found = viewWithDescription(group.getChildAt(index), description);
      if (found != null) return found;
    }
    return null;
  }

  private static View textView(View root, String expected) {
    if (root instanceof TextView && expected.contentEquals(((TextView) root).getText())) return root;
    if (!(root instanceof ViewGroup)) return null;
    ViewGroup group = (ViewGroup) root;
    for (int index = 0; index < group.getChildCount(); index++) {
      View found = textView(group.getChildAt(index), expected);
      if (found != null) return found;
    }
    return null;
  }

  private static void collectDescriptionPrefix(View root, String prefix, List<View> output) {
    CharSequence description = root.getContentDescription();
    if (description != null && description.toString().startsWith(prefix)) output.add(root);
    if (!(root instanceof ViewGroup)) return;
    ViewGroup group = (ViewGroup) root;
    for (int index = 0; index < group.getChildCount(); index++) collectDescriptionPrefix(group.getChildAt(index), prefix, output);
  }

  private static ImageView firstImageWithDescription(View root, String first, String second) {
    if (root instanceof ImageView) {
      CharSequence description = root.getContentDescription();
      if (description != null && (description.toString().startsWith(first) || description.toString().startsWith(second))) return (ImageView) root;
    }
    if (!(root instanceof ViewGroup)) return null;
    ViewGroup group = (ViewGroup) root;
    for (int index = 0; index < group.getChildCount(); index++) {
      ImageView found = firstImageWithDescription(group.getChildAt(index), first, second);
      if (found != null) return found;
    }
    return null;
  }

  @SuppressWarnings("unchecked")
  private static <T> T privateField(Object target, String name, Class<T> type) {
    try {
      Field field = target.getClass().getDeclaredField(name);
      field.setAccessible(true);
      return (T) field.get(target);
    } catch (Exception failure) {
      throw new IllegalStateException("Could not read Radio " + name, failure);
    }
  }

  private static void check(boolean value, String description) {
    if (!value) throw new AssertionError(description);
    System.out.println("PASS " + description);
  }

  private static void step(String description) {
    System.out.println("STEP " + description);
  }

  private static final class NoAudioHost implements RadioController.Host {
    @Override public void suspendAmbientForRadio() {}
    @Override public void restoreAmbientAfterRadio() {}
    @Override public boolean isRadioHostForeground() { return false; }
  }

  /** Complete in-memory SharedPreferences implementation; production radio preferences are untouched. */
  private static final class MemoryPreferences implements SharedPreferences {
    private final Map<String, Object> values = new HashMap<>();
    @Override public Map<String, ?> getAll() { return Collections.unmodifiableMap(new HashMap<>(values)); }
    @Override public String getString(String key, String fallback) { Object value = values.get(key); return value instanceof String ? (String) value : fallback; }
    @Override @SuppressWarnings("unchecked") public Set<String> getStringSet(String key, Set<String> fallback) { Object value = values.get(key); return value instanceof Set ? new HashSet<>((Set<String>) value) : fallback; }
    @Override public int getInt(String key, int fallback) { Object value = values.get(key); return value instanceof Integer ? (Integer) value : fallback; }
    @Override public long getLong(String key, long fallback) { Object value = values.get(key); return value instanceof Long ? (Long) value : fallback; }
    @Override public float getFloat(String key, float fallback) { Object value = values.get(key); return value instanceof Float ? (Float) value : fallback; }
    @Override public boolean getBoolean(String key, boolean fallback) { Object value = values.get(key); return value instanceof Boolean ? (Boolean) value : fallback; }
    @Override public boolean contains(String key) { return values.containsKey(key); }
    @Override public Editor edit() { return new MemoryEditor(); }
    @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {}
    @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {}

    private final class MemoryEditor implements Editor {
      private final Map<String, Object> edits = new HashMap<>();
      private final Set<String> removals = new HashSet<>();
      private boolean clear;
      @Override public Editor putString(String key, String value) { edits.put(key, value); return this; }
      @Override public Editor putStringSet(String key, Set<String> value) { edits.put(key, value == null ? null : new HashSet<>(value)); return this; }
      @Override public Editor putInt(String key, int value) { edits.put(key, value); return this; }
      @Override public Editor putLong(String key, long value) { edits.put(key, value); return this; }
      @Override public Editor putFloat(String key, float value) { edits.put(key, value); return this; }
      @Override public Editor putBoolean(String key, boolean value) { edits.put(key, value); return this; }
      @Override public Editor remove(String key) { removals.add(key); return this; }
      @Override public Editor clear() { clear = true; return this; }
      @Override public boolean commit() { apply(); return true; }
      @Override public void apply() {
        if (clear) values.clear();
        for (String key : removals) values.remove(key);
        for (Map.Entry<String, Object> entry : edits.entrySet()) {
          if (entry.getValue() == null) values.remove(entry.getKey()); else values.put(entry.getKey(), entry.getValue());
        }
      }
    }
  }
}
