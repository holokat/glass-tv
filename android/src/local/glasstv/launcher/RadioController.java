package local.glasstv.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native TV Radio surface for the bundled, curated catalog and saved history. */
final class RadioController {
  interface Host {
    void suspendAmbientForRadio();
    void restoreAmbientAfterRadio();
    boolean isRadioHostForeground();
  }

  private static final int MAX_FAVORITES = 40;
  private static final int MAX_RECENT = 12;
  private static final int CARD_COUNT = 5;
  private static final int PREPARE_TIMEOUT_MS = 15_000;
  private static final String FAVORITES_KEY = "favoriteStationUuids";
  private static final String FAVORITE_RECORDS_KEY = "favoriteStationRecords";
  private static final String RECENT_KEY = "recentStationRecords";
  private static final String VOLUME_KEY = "radioVolume";
  private static final int DEFAULT_VOLUME = 70;
  private final Context context;
  private final Host host;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ExecutorService network = Executors.newSingleThreadExecutor();
  private final SharedPreferences prefs;
  private final RadioDirectory directory;
  private final AudioManager audio;
  private final AudioManager.OnAudioFocusChangeListener audioFocus = this::onAudioFocusChanged;
  private final FrameLayout overlay;
  private final View backdrop;
  private final RadioBackdropDrawable radioBackdrop;
  private static final int CARD_WIDTH_DP = 168;
  private static final int CARD_IMAGE_DP = 94;
  private static final int CARD_LABEL_DP = 42;
  private static final int CARD_HEIGHT_DP = CARD_IMAGE_DP + CARD_LABEL_DP;
  private static final int CATEGORY_IMAGE_DP = 168;
  private static final int CATEGORY_LABEL_DP = 39;

  private final TextView searchField, title, status;
  private final LinearLayout sections;
  private final FrameLayout playerBar;
  private final ImageView playerArtwork;
  private final TextView playerName, playerGenre, previous, pause, next, playerFavorite;
  private final LinearLayout content;
  private final RadioSearchOverlay searchOverlay;
  private final ArrayList<RadioStationPolicy.Station> browseStations = new ArrayList<>();
  private final RadioCategoryArtwork categoryArtwork;
  private final ArrayList<CategoryStrip> categoryStrips = new ArrayList<>();
  private final ArrayList<RadioStationPolicy.Station> playableStations = new ArrayList<>();
  private final ArrayList<ArrayList<View>> focusRows = new ArrayList<>();
  private SearchState searchState;
  private boolean paused, pendingPlayback, legacyFavoritesHydrationAttempted;
  private String playerReturnFocusKey;
  private volatile int screenGeneration, playbackGeneration;
  private MediaPlayer player;
  private RadioStationPolicy.Station playing;
  private Runnable retryAction, activePrepareTimeout;

  private static final class SearchState {
    final String query;
    final RadioDirectory.Cursor names = new RadioDirectory.Cursor();
    final RadioDirectory.Cursor tags = new RadioDirectory.Cursor();
    final ArrayList<RadioStationPolicy.Station> stations = new ArrayList<>();
    boolean namesMore = true;
    boolean tagsMore = true;

    SearchState(String query) { this.query = query; }
  }

  RadioController(Context context, Host host, FrameLayout parent) {
    this.context = context;
    this.host = host;
    directory = new RadioDirectory(context);
    categoryArtwork = new RadioCategoryArtwork(context);
    prefs = context.getSharedPreferences("glass-tv-radio", Context.MODE_PRIVATE);
    audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);

    overlay = new FrameLayout(context);
    // Radio is intentionally opaque: its own ambient surface must never reveal or sample Home media.
    overlay.setBackgroundColor(Color.BLACK);
    overlay.setVisibility(View.GONE);
    overlay.setFocusable(true);
    radioBackdrop = new RadioBackdropDrawable();
    backdrop = new View(context);
    backdrop.setBackground(radioBackdrop);
    backdrop.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    overlay.addView(backdrop, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

    content = new LinearLayout(context);
    content.setOrientation(LinearLayout.VERTICAL);
    FrameLayout.LayoutParams contentParams = new FrameLayout.LayoutParams(dp(912), dp(494), Gravity.LEFT | Gravity.TOP);
    contentParams.setMargins(dp(24), dp(22), 0, 0);
    overlay.addView(content, contentParams);
    LinearLayout header = new LinearLayout(context);
    header.setOrientation(LinearLayout.HORIZONTAL);
    header.setGravity(Gravity.CENTER_VERTICAL);
    content.addView(header, new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
    title = text(40, Color.WHITE);
    title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    title.setText("Radio");
    header.addView(title, new LinearLayout.LayoutParams(0, dp(52), 1f));
    searchField = action("Search stations or genres", "search");
    searchField.setBackground(new GlassBackground(dp(20), 0x28ffffff));
    searchField.setGravity(Gravity.CENTER_VERTICAL);
    searchField.setPadding(dp(17), 0, dp(18), 0);
    searchField.setTextColor(0xd9ffffff);
    searchField.setTextSize(16);
    searchField.setSingleLine(true);
    searchField.setContentDescription("Search stations, genres, or moods");
    searchField.setId(View.generateViewId());
    searchField.setOnClickListener(v -> showSearch());
    searchField.setOnKeyListener((view, key, event) -> {
      if (event.getAction() == KeyEvent.ACTION_DOWN && key == KeyEvent.KEYCODE_DPAD_DOWN) {
        View first = firstFocusableCard();
        if (first != null) {
          first.requestFocus();
          return true;
        }
      }
      return false;
    });
    header.addView(searchField, new LinearLayout.LayoutParams(dp(424), dp(40)));
    status = text(13, 0xbfffffff);
    status.setSingleLine(true);
    status.setVisibility(View.GONE);
    // Keep transient loading and playback errors in the vertical flow. A floating status line
    // would otherwise sit over the first section heading after the compact shared header.
    content.addView(status, new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, dp(22)));
    ScrollView scroll = new ScrollView(context);
    scroll.setFillViewport(true);
    scroll.setClipToPadding(false);
    sections = new LinearLayout(context);
    sections.setOrientation(LinearLayout.VERTICAL);
    scroll.addView(sections, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    content.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
    scroll.setOnScrollChangeListener((view, left, top, oldLeft, oldTop) -> refreshCategoryArtwork());

    playerBar = new FrameLayout(context);
    playerBar.setBackground(new GlassBackground(dp(26), 0x25ffffff));
    FrameLayout.LayoutParams playerParams = new FrameLayout.LayoutParams(dp(936), dp(61), Gravity.LEFT | Gravity.BOTTOM);
    // The launcher can reserve a small system inset at the bottom. Anchoring to the visible
    // surface keeps the transport and its favorite control entirely reachable on TV.
    playerParams.setMargins(dp(12), 0, 0, dp(10));
    overlay.addView(playerBar, playerParams);
    playerArtwork = new ImageView(context);
    playerArtwork.setScaleType(ImageView.ScaleType.CENTER_CROP);
    playerArtwork.setBackground(roundRect(dp(11), 0x18ffffff));
    playerArtwork.setClipToOutline(true);
    playerArtwork.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
    FrameLayout.LayoutParams artworkParams = new FrameLayout.LayoutParams(dp(47), dp(47), Gravity.LEFT | Gravity.TOP);
    artworkParams.setMargins(dp(12), dp(7), 0, 0);
    playerBar.addView(playerArtwork, artworkParams);
    LinearLayout playerMeta = new LinearLayout(context);
    playerMeta.setOrientation(LinearLayout.VERTICAL);
    playerMeta.setGravity(Gravity.CENTER_VERTICAL);
    FrameLayout.LayoutParams playerMetaParams = new FrameLayout.LayoutParams(dp(260), dp(47), Gravity.LEFT | Gravity.TOP);
    playerMetaParams.setMargins(dp(71), dp(8), 0, 0);
    playerBar.addView(playerMeta, playerMetaParams);
    playerName = text(14, Color.WHITE);
    playerName.setSingleLine(true);
    playerGenre = text(11, 0xbbffffff);
    playerGenre.setSingleLine(true);
    playerMeta.addView(playerName, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(25)));
    playerMeta.addView(playerGenre, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(19)));
    LinearLayout controls = new LinearLayout(context);
    controls.setGravity(Gravity.CENTER);
    previous = iconAction("Previous station", "previous");
    pause = iconAction("Play or pause radio", "play");
    next = iconAction("Next station", "next");
    previous.setId(View.generateViewId());
    pause.setId(View.generateViewId());
    next.setId(View.generateViewId());
    previous.setOnClickListener(v -> playAdjacent(-1));
    pause.setOnClickListener(v -> togglePause());
    pause.setBackground(new GlassBackground(dp(20), 0x65ffffff));
    previous.setBackground(new GlassBackground(dp(18), Color.TRANSPARENT));
    next.setBackground(new GlassBackground(dp(18), Color.TRANSPARENT));
    next.setOnClickListener(v -> playAdjacent(1));
    controls.addView(previous, new LinearLayout.LayoutParams(dp(48), dp(48)));
    LinearLayout.LayoutParams pauseParams = new LinearLayout.LayoutParams(dp(40), dp(40));
    pauseParams.setMargins(dp(4), 0, dp(4), 0);
    controls.addView(pause, pauseParams);
    controls.addView(next, new LinearLayout.LayoutParams(dp(48), dp(48)));
    FrameLayout.LayoutParams controlsParams = new FrameLayout.LayoutParams(dp(144), dp(56), Gravity.LEFT | Gravity.TOP);
    controlsParams.setMargins(dp(396), dp(2), 0, 0);
    playerBar.addView(controls, controlsParams);
    playerFavorite = iconAction("Save station", "heart");
    playerFavorite.setBackground(new GlassBackground(dp(24), 0x25ffffff));
    playerFavorite.setId(View.generateViewId());
    playerFavorite.setOnClickListener(v -> togglePlayingFavorite());
    FrameLayout.LayoutParams favoriteParams = new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.RIGHT | Gravity.TOP);
    favoriteParams.setMargins(0, dp(6), dp(16), 0);
    playerBar.addView(playerFavorite, favoriteParams);
    next.setNextFocusRightId(playerFavorite.getId());
    playerFavorite.setNextFocusLeftId(next.getId());
    updatePlayer();
    searchOverlay = new RadioSearchOverlay(context, overlay, directory, this::play);
    parent.addView(overlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
  }

  void open() {
    if (isOpen()) return;
    host.suspendAmbientForRadio();
    overlay.setVisibility(View.VISIBLE);
    legacyFavoritesHydrationAttempted = false;
    openBrowse();
    searchField.requestFocus();
  }

  boolean isOpen() { return overlay.getVisibility() == View.VISIBLE; }

  boolean handleKey(int key) {
    if (!isOpen()) return false;
    if (key == KeyEvent.KEYCODE_BACK) {
      if (searchOverlay.isOpen()) searchOverlay.close();
      else if (searchState != null) { openBrowse(); searchField.requestFocus(); }
      else close();
      return true;
    }
    if (key == KeyEvent.KEYCODE_MEDIA_STOP) { stopPlayback(); clearStatus(); return true; }
    if (key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) { togglePause(); return true; }
    if (key == KeyEvent.KEYCODE_MEDIA_PAUSE) { pausePlayback(); return true; }
    if (key == KeyEvent.KEYCODE_MEDIA_NEXT) { playAdjacent(1); return true; }
    if (key == KeyEvent.KEYCODE_MEDIA_PREVIOUS) { playAdjacent(-1); return true; }
    return false;
  }

  void onHostStopped() { closeInternal(false); }
  void destroy() { closeInternal(false); network.shutdownNow(); searchOverlay.destroy(); categoryArtwork.close(); }
  private void close() { closeInternal(true); }

  private void closeInternal(boolean restore) {
    screenGeneration++;
    searchOverlay.dismissImmediately();
    stopPlayback();
    radioBackdrop.cancelAnimation();
    radioBackdrop.showIdle(false);
    if (isOpen()) overlay.setVisibility(View.GONE);
    if (restore) host.restoreAmbientAfterRadio();
  }

  private void openBrowse() {
    searchState = null;
    title.setText("Radio");
    loadBrowse();
  }

  private void loadBrowse() {
    final int request = ++screenGeneration;
    setStatus("Loading popular stations…");
    clearSections();
    addLoading();
    network.execute(() -> {
      try {
        RadioDirectory.Page popular = directory.popular(new RadioDirectory.Cursor());
        main.post(() -> { if (fresh(request)) showBrowse(popular.stations); });
      } catch (Exception failure) {
        main.post(() -> { if (fresh(request)) showRetry("The Radio catalog could not be loaded.", this::loadBrowse); });
      }
    });
  }

  private void showBrowse(List<RadioStationPolicy.Station> popular) {
    View focusedCard = sections.findFocus();
    String focusedKey = focusedCard == null ? null : focusKey(focusedCard);
    clearSections();
    clearStatus();
    playableStations.clear();
    List<RadioStationPolicy.Station> recent = recentStations();
    List<RadioStationPolicy.Station> favorites = favoriteStations();
    browseStations.clear();
    browseStations.addAll(popular);
    addBrowseSections(recent, favorites, popular);
    if (focusedKey != null) {
      View replacement = findCardByFocusKey(focusedKey);
      if (replacement == null) replacement = firstFocusableCard();
      if (replacement != null) replacement.requestFocus();
    }
    if (favorites.isEmpty() && !favoriteIds().isEmpty() && !legacyFavoritesHydrationAttempted) {
      legacyFavoritesHydrationAttempted = true;
      loadLegacyFavoritesForBrowse(popular);
    }
  }

  private void addBrowseSections(
      List<RadioStationPolicy.Station> recent,
      List<RadioStationPolicy.Station> favorites,
      List<RadioStationPolicy.Station> popular) {
    if (!recent.isEmpty()) addStationSection("Recent", recent);
    if (!favorites.isEmpty()) addStationSection("Favorites", favorites);
    if (popular.isEmpty()) addEmptySection("Featured stations", "No eligible featured stations were found.");
    else addStationSection("Featured stations", popular);
    addCategorySection("Genres", categories("genre"), 0);
    addCategorySection("Styles", categories("style"), 0);
    addCategorySection("Moods", categories("mood"), 0);
    updateCardPlayerRoutes(hasActivePlayerSession());
    refreshPlayerReturnFocus();
  }

  private List<RadioDirectory.Category> categories(String type) {
    try {
      return directory.categories(type);
    } catch (Exception failure) {
      setStatus("The Radio catalog categories could not be loaded.");
      return Collections.emptyList();
    }
  }

  /** Migrates UUID-only saves into the real metadata cards used by the sidebar-free browse view. */
  private void loadLegacyFavoritesForBrowse(final List<RadioStationPolicy.Station> popular) {
    final int request = ++screenGeneration;
    setStatus("Loading saved stations…");
    final List<String> ids = new ArrayList<>(favoriteIds());
    network.execute(() -> {
      try {
        List<RadioStationPolicy.Station> stations = directory.favorites(ids);
        main.post(() -> {
          if (!fresh(request)) return;
          prefs.edit().putString(FAVORITE_RECORDS_KEY, encodeStations(stations, MAX_FAVORITES)).apply();
          showBrowse(popular);
        });
      } catch (Exception failure) {
        main.post(() -> {
          if (fresh(request))
            setStatus("Saved stations could not be refreshed from this catalog.");
        });
      }
    });
  }

  private void addStationSection(String label, List<RadioStationPolicy.Station> stations) {
    addStationSection(label, stations, CARD_COUNT);
  }

  private void addStationSection(String label, List<RadioStationPolicy.Station> stations, int maximumCards) {
    TextView heading = text(22, Color.WHITE);
    heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    heading.setText(label);
    int children = sections.getChildCount();
    int headingHeight = 34;
    int headingTop = children == 0 ? 0 : 16;
    LinearLayout.LayoutParams headingParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(headingHeight));
    headingParams.setMargins(0, dp(headingTop), 0, 0);
    sections.addView(heading, headingParams);
    HorizontalScrollView strip = new HorizontalScrollView(context);
    strip.setHorizontalScrollBarEnabled(false);
    strip.setClipToPadding(false);
    LinearLayout cards = new LinearLayout(context);
    cards.setOrientation(LinearLayout.HORIZONTAL);
    cards.setPadding(0, 0, dp(8), 0);
    ArrayList<View> focusRow = new ArrayList<>();
    int limit = Math.min(maximumCards, stations.size());
    for (int index = 0; index < limit; index++) {
      RadioStationPolicy.Station station = stations.get(index);
      if (!contains(playableStations, station.uuid)) playableStations.add(station);
      View card = stationCard(station);
      focusRow.add(card);
      LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(dp(CARD_WIDTH_DP), dp(CARD_HEIGHT_DP));
      if (index > 0) cardParams.setMargins(dp(12), 0, 0, 0);
      cards.addView(card, cardParams);
    }
    strip.addView(cards, new HorizontalScrollView.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(CARD_HEIGHT_DP)));
    sections.addView(strip, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(CARD_HEIGHT_DP)));
    if (!focusRow.isEmpty()) focusRows.add(focusRow);
  }

  private void addCategorySection(
      String headingLabel, List<RadioDirectory.Category> categories, int maximumCards) {
    if (categories == null || categories.isEmpty()) return;
    TextView heading = text(22, Color.WHITE);
    heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    heading.setText(headingLabel);
    LinearLayout.LayoutParams header = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34));
    header.topMargin = dp(16);
    sections.addView(heading, header);
    CategoryStrip strip = new CategoryStrip(context);
    strip.setHorizontalScrollBarEnabled(false);
    strip.setClipToPadding(false);
    categoryStrips.add(strip);
    LinearLayout cards = new LinearLayout(context);
    ArrayList<View> focusRow = new ArrayList<>();
    int limit = maximumCards > 0 ? Math.min(maximumCards, categories.size()) : categories.size();
    for (int index = 0; index < limit; index++) {
      RadioDirectory.Category category = categories.get(index);
      LinearLayout card = new LinearLayout(context);
      card.setOrientation(LinearLayout.VERTICAL);
      card.setFocusable(true);
      card.setDefaultFocusHighlightEnabled(false);
      card.setFocusableInTouchMode(true);
      card.setId(View.generateViewId());
      card.setContentDescription("Browse " + category.label);
      card.setTag("category:" + category.id);
      card.setOnClickListener(v -> loadCategory(category));
      ImageView picture = new ImageView(context);
      picture.setScaleType(ImageView.ScaleType.CENTER_CROP);
      picture.setBackground(roundRect(dp(14), 0x18ffffff));
      picture.setClipToOutline(true);
      picture.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
      final CardFocusForeground focus = new CardFocusForeground(dp(14));
      picture.setForeground(focus);
      card.setOnFocusChangeListener((view, focused) -> {
        focus.setFocused(focused);
        if (focused) rememberCardFocus(view);
      });
      strip.addArtwork(picture, category.artworkIndex);
      card.addView(picture, new LinearLayout.LayoutParams(dp(CATEGORY_IMAGE_DP), dp(CATEGORY_IMAGE_DP)));
      TextView label = text(18, Color.WHITE);
      label.setSingleLine(true);
      label.setEllipsize(android.text.TextUtils.TruncateAt.END);
      label.setText(category.label);
      label.setPadding(dp(2), dp(5), 0, 0);
      card.addView(label, new LinearLayout.LayoutParams(dp(CATEGORY_IMAGE_DP), dp(CATEGORY_LABEL_DP)));
      LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
          dp(CATEGORY_IMAGE_DP), dp(CATEGORY_IMAGE_DP + CATEGORY_LABEL_DP));
      params.rightMargin = dp(12);
      cards.addView(card, params);
      focusRow.add(card);
    }
    strip.addView(cards);
    sections.addView(strip, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
        dp(CATEGORY_IMAGE_DP + CATEGORY_LABEL_DP)));
    if (!focusRow.isEmpty()) focusRows.add(focusRow);
  }

  private void refreshCategoryArtwork() {
    for (CategoryStrip strip : categoryStrips) strip.refreshArtwork();
  }

  private void clearSections() {
    for (CategoryStrip strip : categoryStrips) strip.releaseArtwork();
    categoryStrips.clear();
    sections.removeAllViews();
    focusRows.clear();
  }

  /**
   * Cards remain attached while a HorizontalScrollView or its parent vertical scroll view moves.
   * Bind only the cards that Android reports as clipped-visible and discard their ImageView bitmap
   * once they leave that clipped region; the bounded loader cache retains just recent tiles.
   */
  private final class CategoryStrip extends HorizontalScrollView {
    private final ArrayList<CategoryArtworkTarget> targets = new ArrayList<>();
    private final Rect visibleBounds = new Rect();

    CategoryStrip(Context context) { super(context); }

    void addArtwork(ImageView view, int artworkIndex) {
      targets.add(new CategoryArtworkTarget(view, artworkIndex));
    }

    void refreshArtwork() {
      if (!isAttachedToWindow() || !isShown()) return;
      for (CategoryArtworkTarget target : targets) {
        boolean visible = target.view.isShown() && target.view.getWidth() > 0
            && target.view.getGlobalVisibleRect(visibleBounds);
        if (!visible) {
          target.release();
          continue;
        }
        target.request();
      }
    }

    void releaseArtwork() { for (CategoryArtworkTarget target : targets) target.release(); }

    @Override protected void onAttachedToWindow() {
      super.onAttachedToWindow();
      post(this::refreshArtwork);
    }

    @Override protected void onDetachedFromWindow() {
      releaseArtwork();
      super.onDetachedFromWindow();
    }

    @Override protected void onScrollChanged(int left, int top, int oldLeft, int oldTop) {
      super.onScrollChanged(left, top, oldLeft, oldTop);
      refreshArtwork();
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
      super.onLayout(changed, left, top, right, bottom);
      if (changed) refreshArtwork();
    }
  }

  private final class CategoryArtworkTarget {
    final ImageView view;
    final int artworkIndex;
    final String key;
    boolean requested;
    boolean failed;

    CategoryArtworkTarget(ImageView view, int artworkIndex) {
      this.view = view;
      this.artworkIndex = artworkIndex;
      key = "category:" + artworkIndex;
    }

    void request() {
      if (!key.equals(view.getTag())) {
        view.setTag(key);
        requested = false;
        failed = false;
      }
      if (requested || failed || view.getDrawable() != null) return;
      requested = true;
      categoryArtwork.load(artworkIndex, dp(CATEGORY_IMAGE_DP), (loadedIndex, bitmap) -> {
        requested = false;
        if (!key.equals(view.getTag())) return;
        if (bitmap == null) {
          failed = true;
          return;
        }
        view.setImageBitmap(bitmap);
      });
    }

    void release() {
      if (view.getTag() == null && view.getDrawable() == null) return;
      view.setTag(null);
      view.setImageDrawable(null);
      requested = false;
      failed = false;
    }
  }

  private void addEmptySection(String label, String message) {
    TextView heading = text(22, Color.WHITE);
    heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    heading.setText(label);
    int children = sections.getChildCount();
    int headingHeight = 34;
    int headingTop = children == 0 ? 0 : 16;
    LinearLayout.LayoutParams headingParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(headingHeight));
    headingParams.setMargins(0, dp(headingTop), 0, 0);
    sections.addView(heading, headingParams);
    TextView empty = text(15, 0xbfffffff);
    empty.setText(message);
    empty.setGravity(Gravity.CENTER_VERTICAL);
    empty.setPadding(dp(2), 0, 0, 0);
    sections.addView(empty, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(37)));
  }

  private View stationCard(final RadioStationPolicy.Station station) {
    LinearLayout card = new LinearLayout(context);
    card.setOrientation(LinearLayout.VERTICAL);
    card.setFocusable(true);
    card.setDefaultFocusHighlightEnabled(false);
    card.setFocusableInTouchMode(true);
    card.setId(View.generateViewId());
    card.setContentDescription("Play " + station.name);
    card.setTag(station.uuid);
    card.setOnClickListener(v -> play(station));
    final CardFocusForeground focusOutline = new CardFocusForeground(dp(11));
    card.setOnFocusChangeListener((view, focused) -> {
      focusOutline.setFocused(focused);
      if (focused) rememberCardFocus(view);
    });
    FrameLayout artFrame = new FrameLayout(context);
    artFrame.setBackground(roundRect(dp(11), 0x18ffffff));
    artFrame.setClipToOutline(true);
    artFrame.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
    artFrame.setForeground(focusOutline);
    ImageView art = new ImageView(context);
    bindStationArtwork(art, station);
    artFrame.addView(art, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(CARD_IMAGE_DP)));
    card.addView(artFrame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(CARD_IMAGE_DP)));
    TextView name = text(18, Color.WHITE);
    name.setSingleLine(true);
    name.setEllipsize(android.text.TextUtils.TruncateAt.END);
    name.setPadding(dp(2), dp(9), dp(2), 0);
    name.setText(station.name);
    card.addView(name, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(CARD_LABEL_DP)));
    // Station cards keep deterministic gradient art edge to edge and one real title outside it.
    return card;
  }

  private void showSearch() { searchOverlay.show(); }

  /** Category cards resolve only their exact curated membership, never a broad text match. */
  private void loadCategory(final RadioDirectory.Category category) {
    searchState = new SearchState(category.id);
    final int request = ++screenGeneration;
    title.setText(category.label);
    setStatus("Loading " + category.label + "…");
    clearSections();
    addLoading();
    network.execute(() -> {
      try {
        RadioDirectory.Page page = directory.browseGenre(category.id, new RadioDirectory.Cursor());
        main.post(() -> {
          if (!fresh(request) || searchState == null || !category.id.equals(searchState.query)) return;
          clearSections();
          clearStatus();
          playableStations.clear();
          if (page.stations.isEmpty()) addEmptySection(category.label,
              "No eligible stations are in this category.");
          else {
            // A category is a compact, horizontal browse result. The directory can page up to
            // twelve records, while the editorial catalog keeps each category meaningful at
            // five to ten cards.
            addStationSection(category.label, page.stations, Math.min(10, page.stations.size()));
            updateCardPlayerRoutes(hasActivePlayerSession());
            refreshPlayerReturnFocus();
          }
        });
      } catch (Exception failure) {
        main.post(() -> {
          if (fresh(request) && searchState != null && category.id.equals(searchState.query))
            showRetry("This category could not be loaded.", () -> loadCategory(category));
        });
      }
    });
  }

  private void loadSearch(final String query) {
    searchState = new SearchState(query);
    loadSearchPage(false);
  }

  private void loadSearchPage(final boolean append) {
    final SearchState state = searchState;
    if (state == null || (!state.namesMore && !state.tagsMore)) return;
    final int request = ++screenGeneration;
    title.setText("Search");
    setStatus(append ? "Loading more stations…" : "Searching eligible stations…");
    if (!append) {
      clearSections();
      addLoading();
    }
    final RadioDirectory.Cursor nameCursor = copyCursor(state.names);
    final RadioDirectory.Cursor tagCursor = copyCursor(state.tags);
    network.execute(() -> {
      try {
        RadioDirectory.Page names = state.namesMore ? directory.searchByName(state.query, nameCursor) : new RadioDirectory.Page(Collections.<RadioStationPolicy.Station>emptyList(), false);
        RadioDirectory.Page tags = state.tagsMore ? directory.searchByTag(state.query, tagCursor) : new RadioDirectory.Page(Collections.<RadioStationPolicy.Station>emptyList(), false);
        main.post(() -> {
          if (!fresh(request) || state != searchState) return;
          copyCursor(nameCursor, state.names);
          copyCursor(tagCursor, state.tags);
          state.namesMore = names.hasMore;
          state.tagsMore = tags.hasMore;
          addUnique(state.stations, names.stations);
          addUnique(state.stations, tags.stations);
          showSearchResults(state);
        });
      } catch (Exception failure) {
        main.post(() -> { if (fresh(request) && state == searchState) showRetry("That directory search could not be completed.", () -> loadSearchPage(append)); });
      }
    });
  }

  private void showSearchResults(SearchState state) {
    clearSections();
    clearStatus();
    playableStations.clear();
    if (state.stations.isEmpty()) addEmptySection("Results", "No eligible stations matched “" + state.query + "”.");
    else {
      // The viewport exposes five cards at once; the horizontal row retains earlier real pages.
      addStationSection("Results", state.stations, state.stations.size());
      updateCardPlayerRoutes(hasActivePlayerSession());
      refreshPlayerReturnFocus();
      if (state.namesMore || state.tagsMore) {
        TextView more = action("Load more", null);
        more.setOnClickListener(v -> loadSearchPage(true));
        LinearLayout.LayoutParams moreParams = new LinearLayout.LayoutParams(dp(128), dp(36));
        moreParams.setMargins(0, dp(8), 0, 0);
        sections.addView(more, moreParams);
      }
    }
  }

  private static RadioDirectory.Cursor copyCursor(RadioDirectory.Cursor source) {
    RadioDirectory.Cursor copy = new RadioDirectory.Cursor();
    copy.bucket = source.bucket;
    System.arraycopy(source.offsets, 0, copy.offsets, 0, copy.offsets.length);
    return copy;
  }

  private static void copyCursor(RadioDirectory.Cursor source, RadioDirectory.Cursor target) {
    target.bucket = source.bucket;
    System.arraycopy(source.offsets, 0, target.offsets, 0, target.offsets.length);
  }

  private static void addUnique(List<RadioStationPolicy.Station> target, List<RadioStationPolicy.Station> additions) {
    for (RadioStationPolicy.Station station : additions) if (!contains(target, station.uuid)) target.add(station);
  }

  private void addLoading() {
    View spacer = new View(context);
    sections.addView(spacer, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(22)));
  }

  private void showRetry(String message, Runnable retry) {
    retryAction = retry;
    setStatus(message);
    clearSections();
    View spacer = new View(context);
    sections.addView(spacer, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34)));
    TextView retryView = action("Retry", null);
    retryView.setOnClickListener(v -> { if (retryAction != null) retryAction.run(); });
    sections.addView(retryView, new LinearLayout.LayoutParams(dp(120), dp(48)));
  }

  private void play(final RadioStationPolicy.Station wanted) {
    // Keep the prior palette through validation so a failed recheck returns cleanly to idle.
    stopPlayback(false);
    final int token = ++playbackGeneration;
    pendingPlayback = true;
    setStatus("Rechecking “" + wanted.name + "”…");
    updatePlayer();
    network.execute(() -> {
      try {
        if (token != playbackGeneration) return;
        List<RadioStationPolicy.Station> checked = directory.checkedStation(wanted.uuid);
        if (checked.isEmpty()) throw new IllegalStateException("station not checked");
        RadioStationPolicy.Station station = checked.get(0);
        if (!RadioDirectory.safePlaybackUrl(station.url)) throw new IllegalStateException("unsafe URL");
        main.post(() -> { if (token == playbackGeneration && isOpen()) startPlayback(station, token); });
      } catch (Exception failure) {
        main.post(() -> { if (token == playbackGeneration && isOpen()) { pendingPlayback = false; radioBackdrop.showIdle(true); setStatus("This station is no longer stream checked."); updatePlayer(); } });
      }
    });
  }

  private void startPlayback(RadioStationPolicy.Station station, int token) {
    stopPlayerOnly();
    if (!host.isRadioHostForeground() || token != playbackGeneration) return;
    if (audio.requestAudioFocus(audioFocus, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
      pendingPlayback = false;
      radioBackdrop.showIdle(true);
      setStatus("Radio playback could not take audio focus.");
      updatePlayer();
      return;
    }
    try {
      MediaPlayer nextPlayer = new MediaPlayer();
      player = nextPlayer;
      nextPlayer.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
      nextPlayer.setDataSource(context, Uri.parse(station.url));
      final Runnable timeout = () -> { if (token == playbackGeneration && player == nextPlayer) { stopPlayback(); if (isOpen()) setStatus("This station took too long to start."); } };
      activePrepareTimeout = timeout;
      nextPlayer.setOnPreparedListener(ready -> {
        if (ready != player || token != playbackGeneration || !host.isRadioHostForeground()) { release(ready); return; }
        main.removeCallbacks(timeout);
        if (activePrepareTimeout == timeout) activePrepareTimeout = null;
        try {
          // The visible meter is gone, but retain the user's existing saved radio gain.
          updatePlayerVolume(ready, prefs.getInt(VOLUME_KEY, DEFAULT_VOLUME));
          ready.start();
          paused = false;
          pendingPlayback = false;
          playing = station;
          // A stale prepare callback cannot reach here because token/player are verified above.
          radioBackdrop.showStation(station, true);
          recordRecent(station);
          if ("Radio".contentEquals(title.getText())) showBrowse(new ArrayList<>(browseStations));
          clearStatus();
          updatePlayer();
        } catch (Exception failure) { stopPlayback(); setStatus("This station could not play on this TV."); }
      });
      nextPlayer.setOnCompletionListener(completed -> { if (player == completed && token == playbackGeneration) { stopPlayback(); if (isOpen()) setStatus("The station ended its stream. Select it to reconnect."); } });
      nextPlayer.setOnErrorListener((failed, what, extra) -> { if (failed == player && token == playbackGeneration) { stopPlayback(); if (isOpen()) setStatus("This station could not play on this TV."); } return true; });
      playing = station;
      paused = false;
      main.postDelayed(timeout, PREPARE_TIMEOUT_MS);
      nextPlayer.prepareAsync();
    } catch (Exception failure) { stopPlayback(); setStatus("This station could not be prepared."); }
  }

  private void playAdjacent(int direction) {
    if (playableStations.isEmpty()) return;
    int current = -1;
    if (playing != null) for (int i = 0; i < playableStations.size(); i++) if (playing.uuid.equals(playableStations.get(i).uuid)) { current = i; break; }
    int selected = current < 0 ? 0 : (current + direction + playableStations.size()) % playableStations.size();
    play(playableStations.get(selected));
  }

  private void togglePause() { if (paused) resumePlayback(); else pausePlayback(); }
  private void pausePlayback() {
    if (player == null || paused) return;
    try { player.pause(); paused = true; audio.abandonAudioFocus(audioFocus); clearStatus(); updatePlayer(); }
    catch (Exception failure) { stopPlayback(); setStatus("Radio playback stopped."); }
  }
  private void resumePlayback() {
    if (player == null || !paused || !host.isRadioHostForeground()) return;
    if (audio.requestAudioFocus(audioFocus, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { setStatus("Radio playback could not take audio focus."); return; }
    try { player.start(); paused = false; clearStatus(); updatePlayer(); }
    catch (Exception failure) { stopPlayback(); setStatus("Radio playback stopped."); }
  }
  private void stopPlayback() { stopPlayback(true); }
  private void stopPlayback(boolean returnToIdle) {
    playbackGeneration++;
    if (activePrepareTimeout != null) { main.removeCallbacks(activePrepareTimeout); activePrepareTimeout = null; }
    stopPlayerOnly();
    audio.abandonAudioFocus(audioFocus);
    paused = false;
    pendingPlayback = false;
    if (returnToIdle) radioBackdrop.showIdle(isOpen());
    updatePlayer();
  }
  private void stopPlayerOnly() { MediaPlayer old = player; player = null; playing = null; if (old != null) release(old); }
  private static void release(MediaPlayer value) { try { value.setOnPreparedListener(null); value.setOnErrorListener(null); value.setOnCompletionListener(null); value.reset(); value.release(); } catch (Exception ignored) {} }
  private void onAudioFocusChanged(int change) { if (change != AudioManager.AUDIOFOCUS_GAIN) main.post(() -> { if (player != null) { stopPlayback(); if (isOpen()) setStatus("Playback stopped because another app took audio."); } }); }

  JSONObject statusSnapshot() {
    if (Looper.myLooper() == Looper.getMainLooper()) return statusOnMain();
    AtomicReference<JSONObject> result = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    main.post(() -> { result.set(statusOnMain()); latch.countDown(); });
    try { if (latch.await(150, TimeUnit.MILLISECONDS) && result.get() != null) return result.get(); }
    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    return new JSONObject();
  }
  private JSONObject statusOnMain() {
    try {
      boolean isPlaying = player != null && !paused && player.isPlaying();
      JSONObject out = new JSONObject();
      out.put("state", player == null ? "idle" : paused ? "paused" : isPlaying ? "playing" : "preparing");
      out.put("isPlaying", isPlaying);
      out.put("name", playing == null ? JSONObject.NULL : playing.name);
      out.put("bitrate", playing == null ? JSONObject.NULL : playing.bitrate);
      out.put("open", isOpen());
      return out;
    } catch (Exception ignored) { return new JSONObject(); }
  }

  private boolean fresh(int request) { return request == screenGeneration && isOpen(); }
  private void setStatus(String message) { status.setText(message); status.setVisibility(View.VISIBLE); }
  private void clearStatus() { status.setText(""); status.setVisibility(View.GONE); }
  private void updatePlayer() {
    boolean active = player != null;
    boolean ready = active && !pendingPlayback;
    playerBar.setVisibility(ready ? View.VISIBLE : View.GONE);
    FrameLayout.LayoutParams bounds = (FrameLayout.LayoutParams) content.getLayoutParams();
    int desiredHeight = dp(ready ? 446 : 494);
    if (bounds.height != desiredHeight) { bounds.height = desiredHeight; content.setLayoutParams(bounds); }
    playerName.setText(playing == null ? "Choose a station" : playing.name);
    playerGenre.setVisibility(View.GONE);
    bindStationArtwork(playerArtwork, playing);
    setIcon(pause, paused || !ready ? "play" : "pause", 18);
    pause.setEnabled(ready);
    previous.setEnabled(!playableStations.isEmpty() && !pendingPlayback);
    next.setEnabled(!playableStations.isEmpty() && !pendingPlayback);
    setControlEnabled(pause, ready);
    setControlEnabled(previous, previous.isEnabled());
    setControlEnabled(next, next.isEnabled());
    boolean saved = playing != null && favoriteIds().contains(playing.uuid);
    setIcon(playerFavorite, saved ? "heart_filled" : "heart", 18);
    playerFavorite.setContentDescription(
        playing == null ? "Save station" : (saved ? "Remove " : "Save ") + playing.name);
    setControlEnabled(playerFavorite, ready && playing != null);
    updateCardPlayerRoutes(ready && playing != null);
  }
  private void setControlEnabled(TextView view, boolean enabled) {
    view.setEnabled(enabled);
    view.setFocusable(enabled);
    view.setAlpha(enabled ? 1f : .42f);
  }

  private boolean hasActivePlayerSession() { return player != null && !pendingPlayback; }

  private void updateCardPlayerRoutes(boolean playerAvailable) {
    for (int rowIndex = 0; rowIndex < focusRows.size(); rowIndex++) {
      ArrayList<View> row = focusRows.get(rowIndex);
      for (int column = 0; column < row.size(); column++) {
        View card = row.get(column);
        if (rowIndex == 0) card.setNextFocusUpId(searchField.getId());
        else card.setNextFocusUpId(neighbor(focusRows.get(rowIndex - 1), column).getId());
        if (rowIndex + 1 < focusRows.size())
          card.setNextFocusDownId(neighbor(focusRows.get(rowIndex + 1), column).getId());
        else card.setNextFocusDownId(playerAvailable ? playerFavorite.getId() : View.NO_ID);
      }
    }
  }

  private static View neighbor(List<View> row, int column) {
    return row.get(Math.min(column, row.size() - 1));
  }

  private void togglePlayingFavorite() {
    if (playing == null) return;
    toggleFavorite(playing);
    updatePlayer();
    if ("Radio".contentEquals(title.getText()) && !browseStations.isEmpty()) {
      // Rebuild the saved row immediately while retaining the player's explicit focus target.
      showBrowse(new ArrayList<>(browseStations));
      playerFavorite.requestFocus();
    }
  }

  private void rememberCardFocus(View card) {
    playerReturnFocusKey = focusKey(card);
    setPlayerReturnFocus(card);
  }

  private void refreshPlayerReturnFocus() {
    View card = findCardByFocusKey(playerReturnFocusKey);
    if (card == null) card = firstFocusableCard();
    if (card != null) setPlayerReturnFocus(card);
  }

  private void setPlayerReturnFocus(View card) {
    playerFavorite.setNextFocusUpId(card.getId());
    previous.setNextFocusUpId(card.getId());
    pause.setNextFocusUpId(card.getId());
    next.setNextFocusUpId(card.getId());
  }

  private static String focusKey(View card) {
    Object tag = card.getTag();
    if (tag != null) return "station:" + tag;
    CharSequence description = card.getContentDescription();
    return description == null ? null : "description:" + description;
  }

  private View findCardByFocusKey(String key) {
    if (key == null) return null;
    return findCardByFocusKey(sections, key);
  }

  private View findCardByFocusKey(View view, String key) {
    if (view != sections && view.isFocusable() && key.equals(focusKey(view))) return view;
    if (!(view instanceof ViewGroup)) return null;
    ViewGroup group = (ViewGroup) view;
    for (int index = 0; index < group.getChildCount(); index++) {
      View found = findCardByFocusKey(group.getChildAt(index), key);
      if (found != null) return found;
    }
    return null;
  }

  private View firstFocusableCard() {
    return firstFocusableCard(sections);
  }

  private View firstFocusableCard(View view) {
    if (view != sections && view.isFocusable() && view.getVisibility() == View.VISIBLE) return view;
    if (!(view instanceof ViewGroup)) return null;
    ViewGroup group = (ViewGroup) view;
    for (int index = 0; index < group.getChildCount(); index++) {
      View found = firstFocusableCard(group.getChildAt(index));
      if (found != null) return found;
    }
    return null;
  }

  private Set<String> favoriteIds() { return new HashSet<>(prefs.getStringSet(FAVORITES_KEY, Collections.<String>emptySet())); }
  private void toggleFavorite(RadioStationPolicy.Station station) {
    Set<String> ids = favoriteIds();
    ArrayList<RadioStationPolicy.Station> records = favoriteStations();
    if (ids.remove(station.uuid)) removeStation(records, station.uuid);
    else {
      if (ids.size() >= MAX_FAVORITES) { setStatus("Save up to " + MAX_FAVORITES + " favorites."); return; }
      ids.add(station.uuid);
      removeStation(records, station.uuid);
      records.add(0, station);
    }
    prefs.edit().putStringSet(FAVORITES_KEY, ids).putString(FAVORITE_RECORDS_KEY, encodeStations(records, MAX_FAVORITES)).apply();
  }
  private static void updatePlayerVolume(MediaPlayer value, int volume) {
    float level = Math.max(0f, Math.min(1f, volume / 100f));
    try { value.setVolume(level, level); } catch (Exception ignored) {}
  }
  private void recordRecent(RadioStationPolicy.Station station) {
    ArrayList<RadioStationPolicy.Station> records = recentStations();
    removeStation(records, station.uuid);
    records.add(0, station);
    prefs.edit().putString(RECENT_KEY, encodeStations(records, MAX_RECENT)).apply();
  }
  private ArrayList<RadioStationPolicy.Station> recentStations() { return decodeStations(prefs.getString(RECENT_KEY, ""), MAX_RECENT); }
  private ArrayList<RadioStationPolicy.Station> favoriteStations() { return decodeStations(prefs.getString(FAVORITE_RECORDS_KEY, ""), MAX_FAVORITES); }
  private static ArrayList<RadioStationPolicy.Station> decodeStations(String serialized, int limit) {
    ArrayList<RadioStationPolicy.Station> result = new ArrayList<>();
    try {
      JSONArray records = new JSONArray(serialized == null || serialized.length() == 0 ? "[]" : serialized);
      for (int i = 0; i < records.length() && result.size() < limit; i++) {
        JSONObject value = records.optJSONObject(i);
        if (value == null) continue;
        String uuid = value.optString("uuid", ""), name = value.optString("name", ""), url = value.optString("url", "");
        int bitrate = value.optInt("bitrate", 0);
        if (RadioStationPolicy.qualifies(uuid, name, url, true, bitrate, RadioDirectory.safeHttpsUrl(url)) && !contains(result, uuid)) result.add(new RadioStationPolicy.Station(uuid, name, url, value.optString("genre", ""), value.optString("country", ""), bitrate, value.optString("artworkUrl", "")));
      }
    } catch (Exception ignored) {}
    return result;
  }
  private static String encodeStations(List<RadioStationPolicy.Station> stations, int limit) {
    JSONArray records = new JSONArray();
    for (int i = 0; i < stations.size() && i < limit; i++) try {
      RadioStationPolicy.Station station = stations.get(i);
      records.put(new JSONObject().put("uuid", station.uuid).put("name", station.name).put("url", station.url).put("genre", station.genre).put("country", station.country).put("bitrate", station.bitrate).put("artworkUrl", station.artworkUrl));
    } catch (Exception ignored) {}
    return records.toString();
  }
  private static void removeStation(List<RadioStationPolicy.Station> stations, String uuid) { for (int i = stations.size() - 1; i >= 0; i--) if (uuid.equals(stations.get(i).uuid)) stations.remove(i); }
  private static boolean contains(List<RadioStationPolicy.Station> stations, String uuid) { for (RadioStationPolicy.Station station : stations) if (station.uuid.equals(uuid)) return true; return false; }
  private void bindStationArtwork(ImageView view, RadioStationPolicy.Station station) {
    String key = station == null ? "" : station.uuid;
    if (key.equals(view.getTag())) return;
    view.setTag(key);
    view.setScaleType(ImageView.ScaleType.CENTER_CROP);
    view.setPadding(0, 0, 0, 0);
    view.setImageDrawable(RadioStationGradient.forStation(station));
    view.setAlpha(1f);
  }


  private TextView text(int size, int color) { TextView view = new TextView(context); view.setTextColor(color); view.setTextSize(size); view.setIncludeFontPadding(false); return view; }
  private TextView action(String value, String icon) {
    TextView view = text(12, Color.WHITE);
    view.setText(value);
    view.setGravity(Gravity.CENTER);
    view.setFocusable(true);
    view.setFocusableInTouchMode(true);
    view.setDefaultFocusHighlightEnabled(false);
    view.setBackground(new GlassBackground(dp(17), 0x3cffffff));
    if (icon != null) setIcon(view, icon, 18);
    view.setOnFocusChangeListener((v, focused) -> { ((GlassBackground) v.getBackground()).setFocused(focused); });
    return view;
  }
  private TextView iconAction(String description, String icon) {
    TextView view = new TransportButton(context);
    view.setFocusable(true);
    view.setFocusableInTouchMode(true);
    view.setDefaultFocusHighlightEnabled(false);
    view.setGravity(Gravity.CENTER);
    view.setBackground(new GlassBackground(dp(20), Color.TRANSPARENT));
    view.setOnFocusChangeListener((v, focused) -> ((GlassBackground) v.getBackground()).setFocused(focused));
    view.setContentDescription(description);
    setIcon(view, icon, 18);
    return view;
  }

  private static final class TransportButton extends TextView {
    TransportButton(Context context) { super(context); setIncludeFontPadding(false); }
    @Override protected void onDraw(Canvas canvas) {
      Drawable icon = getCompoundDrawables()[1];
      if (icon == null) { super.onDraw(canvas); return; }
      int width = icon.getBounds().width(), height = icon.getBounds().height();
      canvas.save();
      canvas.translate((getWidth() - width) / 2f, (getHeight() - height) / 2f);
      icon.draw(canvas);
      canvas.restore();
    }
  }
  private void setIcon(TextView view, String name, int sizeDp) {
    int id = context.getResources().getIdentifier(name, "drawable", context.getPackageName());
    if (id == 0) return;
    Drawable icon = context.getResources().getDrawable(id);
    int size = dp(sizeDp);
    icon.setBounds(0, 0, size, size);
    if (view.getText().length() == 0) {
      view.setCompoundDrawables(null, icon, null, null);
      view.setPadding(0, 0, 0, 0);
    } else view.setCompoundDrawables(icon, null, null, null);
    view.setCompoundDrawablePadding(dp(8));
  }
  private void setImageIcon(ImageView view, String name) { int id = context.getResources().getIdentifier(name, "drawable", context.getPackageName()); if (id != 0) view.setImageResource(id); view.setScaleType(ImageView.ScaleType.CENTER_INSIDE); }
  private GradientDrawable roundRect(int radius, int color) { GradientDrawable drawable = new GradientDrawable(); drawable.setColor(color); drawable.setCornerRadius(radius); return drawable; }
  private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }

  private final class GlassBackground extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float radius;
    private final int color;
    private boolean focused;
    GlassBackground(float radius, int color) { this.radius = radius; this.color = color; }
    void setFocused(boolean value) { focused = value; invalidateSelf(); }
    @Override public void draw(Canvas canvas) {
      Rect b = getBounds();
      paint.setStyle(Paint.Style.FILL);
      paint.setColor(color);
      canvas.drawRoundRect(new RectF(b), radius, radius, paint);
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeWidth(focused ? dp(2) : 1f);
      paint.setColor(focused ? Color.WHITE : color == Color.TRANSPARENT ? Color.TRANSPARENT : 0x25ffffff);
      float inset = paint.getStrokeWidth() / 2f;
      canvas.drawRoundRect(new RectF(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset), radius, radius, paint);
    }
    @Override public void setAlpha(int alpha) {}
    @Override public void setColorFilter(android.graphics.ColorFilter filter) {}
    @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
  }
  private static final class CardFocusForeground extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float radius;
    private boolean focused;
    CardFocusForeground(float radius) { this.radius = radius; }
    void setFocused(boolean value) { focused = value; invalidateSelf(); }
    @Override public void draw(Canvas canvas) {
      if (!focused) return;
      Rect b = getBounds();
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeWidth(3f);
      paint.setColor(Color.WHITE);
      canvas.drawRoundRect(new RectF(b.left + 1.5f, b.top + 1.5f, b.right - 1.5f, b.bottom - 1.5f), radius, radius, paint);
    }
    @Override public void setAlpha(int alpha) {}
    @Override public void setColorFilter(android.graphics.ColorFilter filter) {}
    @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
  }
}
