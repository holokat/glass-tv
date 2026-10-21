package local.glasstv.launcher;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.LinearGradient;
import android.graphics.Shader;
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
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native TV Radio Browser surface. It displays only live directory results and saved history. */
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


  private final Context context;
  private final Host host;
  private final FrostedBackdrop frostedBackdrop;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ExecutorService network = Executors.newSingleThreadExecutor();
  private final SharedPreferences prefs;
  private final RadioDirectory directory = new RadioDirectory();
  private final AudioManager audio;
  private final AudioManager.OnAudioFocusChangeListener audioFocus = this::onAudioFocusChanged;
  private final FrameLayout overlay;
  private final ImageView backdrop;
  private final TextView browseTab, favoritesTab, searchTab, searchField, title, subtitle, status;
  private final LinearLayout sections;
  private final FrameLayout playerBar;
  private final ImageView playerArtwork;
  private final TextView playerName, playerGenre, previous, pause, next;
  private final SeekBar volume;
  private final LinearLayout content, sidebar;
  private final RadioSearchOverlay searchOverlay;
  private final ArrayList<RadioStationPolicy.Station> browseStations = new ArrayList<>();
  private final RadioArtwork stationArtwork = new RadioArtwork();
  private final Map<String, Bitmap> categoryArtwork = new HashMap<>();
  private final ArrayList<RadioStationPolicy.Station> playableStations = new ArrayList<>();
  private SearchState searchState;
  private boolean favoritesMode, paused, pendingPlayback;
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
    frostedBackdrop = context instanceof MainActivity ? ((MainActivity) context).getFrostedBackdrop() : null;
    prefs = context.getSharedPreferences("glass-tv-radio", Context.MODE_PRIVATE);
    audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);

    overlay = new FrameLayout(context);
    // The host continues to render its current scenic background beneath Radio.
    overlay.setBackgroundColor(Color.TRANSPARENT);
    overlay.setVisibility(View.GONE);
    overlay.setFocusable(true);
    backdrop = new ImageView(context);
    backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
    backdrop.setVisibility(View.GONE);
    overlay.addView(backdrop, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    View shade = new View(context);
    shade.setBackground(new RadioBackdropShade());
    shade.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    overlay.addView(shade, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

    sidebar = new LinearLayout(context);
    sidebar.setOrientation(LinearLayout.VERTICAL);
    // Three generous remote targets sit within the inset glass rail.
    sidebar.setPadding(dp(10), dp(12), dp(10), dp(12));
    sidebar.setBackground(new GlassBackground(dp(24), 0x22ffffff, true));
    FrameLayout.LayoutParams sidebarParams = new FrameLayout.LayoutParams(dp(134), dp(151), Gravity.LEFT | Gravity.TOP);
    sidebarParams.setMargins(dp(16), dp(29), 0, 0);
    overlay.addView(sidebar, sidebarParams);
    browseTab = sideAction("Browse", "podcast");
    favoritesTab = sideAction("Favorites", "heart");
    searchTab = sideAction("Search", "search");
    sidebar.addView(browseTab, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));
    LinearLayout.LayoutParams favoriteTabParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42));
    favoriteTabParams.setMargins(0, 0, 0, 0);
    sidebar.addView(favoritesTab, favoriteTabParams);
    LinearLayout.LayoutParams searchTabParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42));
    searchTabParams.setMargins(0, 0, 0, 0);
    sidebar.addView(searchTab, searchTabParams);

    content = new LinearLayout(context);
    content.setOrientation(LinearLayout.VERTICAL);
    FrameLayout.LayoutParams contentParams = new FrameLayout.LayoutParams(dp(760), dp(494), Gravity.LEFT | Gravity.TOP);
    contentParams.setMargins(dp(180), dp(22), 0, 0);
    overlay.addView(content, contentParams);
    title = text(40, Color.WHITE);
    title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    title.setText("Radio");
    content.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
    subtitle = text(12, 0xc7ffffff);
    subtitle.setText("Discover stations from around the world");
    subtitle.setVisibility(View.GONE);
    searchField = action("Search stations or genres", "search");
    searchField.setBackground(new GlassBackground(dp(20), 0x28ffffff, true));
    searchField.setGravity(Gravity.CENTER_VERTICAL);
    searchField.setPadding(dp(17), 0, dp(18), 0);
    searchField.setTextColor(0xd9ffffff);
    searchField.setTextSize(16);
    searchField.setSingleLine(true);
    searchField.setContentDescription("Search stations, genres, or moods");
    searchField.setOnClickListener(v -> showSearch());
    LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(dp(390), dp(40));
    searchParams.setMargins(0, dp(12), 0, dp(20));
    content.addView(searchField, searchParams);
    status = text(13, 0xbfffffff);
    status.setSingleLine(true);
    status.setVisibility(View.GONE);
    FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(dp(760), dp(22), Gravity.LEFT | Gravity.TOP);
    statusParams.setMargins(dp(180), dp(138), 0, 0);
    overlay.addView(status, statusParams);
    ScrollView scroll = new ScrollView(context);
    scroll.setFillViewport(true);
    scroll.setClipToPadding(false);
    sections = new LinearLayout(context);
    sections.setOrientation(LinearLayout.VERTICAL);
    scroll.addView(sections, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    content.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

    playerBar = new FrameLayout(context);
    playerBar.setBackground(new GlassBackground(dp(26), 0x25ffffff, true));
    FrameLayout.LayoutParams playerParams = new FrameLayout.LayoutParams(dp(936), dp(61), Gravity.LEFT | Gravity.TOP);
    playerParams.setMargins(dp(12), dp(475), 0, 0);
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
    TextView volumeIcon = new TransportButton(context);
    setIcon(volumeIcon, "volume", 23);
    volumeIcon.setContentDescription("Volume");
    volumeIcon.setGravity(Gravity.CENTER);
    FrameLayout.LayoutParams volumeIconParams = new FrameLayout.LayoutParams(dp(36), dp(48), Gravity.LEFT | Gravity.TOP);
    volumeIconParams.setMargins(dp(748), dp(6), 0, 0);
    playerBar.addView(volumeIcon, volumeIconParams);
    volume = new SeekBar(context);
    volume.setMax(100);
    volume.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
    volume.setThumbTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
    volume.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0x35ffffff));
    volume.setProgress(prefs.getInt(VOLUME_KEY, 70));
    volume.setContentDescription("Radio volume");
    volume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
      @Override public void onProgressChanged(SeekBar bar, int value, boolean user) { updateVolume(value); }
      @Override public void onStartTrackingTouch(SeekBar bar) {}
      @Override public void onStopTrackingTouch(SeekBar bar) {}
    });
    FrameLayout.LayoutParams volumeParams = new FrameLayout.LayoutParams(dp(142), dp(48), Gravity.LEFT | Gravity.TOP);
    volumeParams.setMargins(dp(784), dp(6), 0, 0);
    playerBar.addView(volume, volumeParams);
    browseTab.setOnClickListener(v -> openBrowse());
    favoritesTab.setOnClickListener(v -> openFavorites());
    searchTab.setOnClickListener(v -> showSearch());
    updateTabs();
    updatePlayer();
    searchOverlay = new RadioSearchOverlay(context, overlay, stationArtwork, this::play);
    parent.addView(overlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
  }

  void open() {
    if (isOpen()) return;
    host.suspendAmbientForRadio();
    overlay.setVisibility(View.VISIBLE);
    openBrowse();
    browseTab.requestFocus();
  }

  boolean isOpen() { return overlay.getVisibility() == View.VISIBLE; }

  /** Optional host snapshot for a stable radio backdrop when the live media surface is paused. */
  void setBackdrop(Bitmap bitmap) {
    if (Looper.myLooper() == Looper.getMainLooper()) setBackdropOnMain(bitmap);
    else main.post(() -> setBackdropOnMain(bitmap));
  }

  private void setBackdropOnMain(Bitmap bitmap) {
    backdrop.setImageBitmap(bitmap);
    backdrop.setVisibility(bitmap == null ? View.GONE : View.VISIBLE);
  }

  boolean handleKey(int key) {
    if (!isOpen()) return false;
    if (key == KeyEvent.KEYCODE_BACK) { if (searchOverlay.isOpen()) searchOverlay.close(); else close(); return true; }
    if (key == KeyEvent.KEYCODE_MEDIA_STOP) { stopPlayback(); clearStatus(); return true; }
    if (key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) { togglePause(); return true; }
    if (key == KeyEvent.KEYCODE_MEDIA_PAUSE) { pausePlayback(); return true; }
    if (key == KeyEvent.KEYCODE_MEDIA_NEXT) { playAdjacent(1); return true; }
    if (key == KeyEvent.KEYCODE_MEDIA_PREVIOUS) { playAdjacent(-1); return true; }
    return false;
  }

  void onHostStopped() { closeInternal(false); }
  void destroy() { closeInternal(false); network.shutdownNow(); searchOverlay.destroy(); stationArtwork.close(); }
  private void close() { closeInternal(true); }

  private void closeInternal(boolean restore) {
    screenGeneration++;
    searchOverlay.dismissImmediately();
    stopPlayback();
    if (isOpen()) overlay.setVisibility(View.GONE);
    setBackdropOnMain(null);
    if (restore) host.restoreAmbientAfterRadio();
  }

  private void openBrowse() {
    favoritesMode = false;
    title.setText("Radio");
    subtitle.setText("Discover stations from around the world");
    updateTabs();
    loadBrowse();
  }

  private void openFavorites() {
    favoritesMode = true;
    title.setText("Favorites");
    subtitle.setText("Saved stations are rechecked before they play");
    updateTabs();
    showFavorites();
  }

  private void loadBrowse() {
    final int request = ++screenGeneration;
    setStatus("Loading popular stations from Radio Browser…");
    sections.removeAllViews();
    addLoading();
    network.execute(() -> {
      try {
        RadioDirectory.Page popular = directory.popular(new RadioDirectory.Cursor());
        main.post(() -> { if (fresh(request) && !favoritesMode) showBrowse(popular.stations); });
      } catch (Exception failure) {
        main.post(() -> { if (fresh(request)) showRetry("Radio directory is unavailable. Check the connection and retry.", this::loadBrowse); });
      }
    });
  }

  private void showBrowse(List<RadioStationPolicy.Station> popular) {
    updateTabs();
    sections.removeAllViews();
    clearStatus();
    playableStations.clear();
    List<RadioStationPolicy.Station> recent = recentStations();
    List<RadioStationPolicy.Station> favorites = favoriteStations();
    browseStations.clear();
    browseStations.addAll(popular);
    if (!recent.isEmpty()) addStationSection("Recent", recent);
    if (!favorites.isEmpty()) addStationSection("Favorites", favorites);
    if (popular.isEmpty()) addEmptySection("Popular", "No eligible popular stations were found.");
    else addStationSection("Popular", popular);
    addGenreSection();
  }

  private void showFavorites() {
    ++screenGeneration;
    sections.removeAllViews();
    List<RadioStationPolicy.Station> favorites = favoriteStations();
    playableStations.clear();
    // Migrate favorites saved by the previous UUID-only screen without inventing station data.
    if (favorites.isEmpty() && !favoriteIds().isEmpty()) {
      loadLegacyFavorites();
      return;
    }
    if (favorites.isEmpty()) {
      clearStatus();
      addEmptySection("Favorites", "No saved stations yet.");
    } else {
      clearStatus();
      addStationSection("Favorites", favorites);
    }
  }

  private void loadLegacyFavorites() {
    final int request = ++screenGeneration;
    setStatus("Loading saved stations…");
    addLoading();
    final List<String> ids = new ArrayList<>(favoriteIds());
    network.execute(() -> {
      try {
        List<RadioStationPolicy.Station> stations = directory.favorites(ids);
        main.post(() -> {
          if (!fresh(request) || !favoritesMode) return;
          prefs.edit().putString(FAVORITE_RECORDS_KEY, encodeStations(stations, MAX_FAVORITES)).apply();
          sections.removeAllViews();
          playableStations.clear();
          if (stations.isEmpty()) addEmptySection("Favorites", "No saved stations are currently eligible.");
          else addStationSection("Favorites", stations);
          clearStatus();
        });
      } catch (Exception failure) {
        main.post(() -> {
          if (fresh(request) && favoritesMode)
            showRetry("Saved station details could not be loaded while offline.", this::showFavorites);
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
    int limit = Math.min(maximumCards, stations.size());
    for (int index = 0; index < limit; index++) {
      RadioStationPolicy.Station station = stations.get(index);
      if (!contains(playableStations, station.uuid)) playableStations.add(station);
      View card = stationCard(station, index, favoriteIds().contains(station.uuid));
      LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(dp(142), dp(100));
      if (index > 0) cardParams.setMargins(dp(8), 0, 0, 0);
      cards.addView(card, cardParams);
    }
    strip.addView(cards, new HorizontalScrollView.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(100)));
    sections.addView(strip, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(100)));
  }

  private void addGenreSection() {
    TextView heading = text(22, Color.WHITE);
    heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    heading.setText("Genres");
    LinearLayout.LayoutParams header = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34));
    header.topMargin = dp(16);
    sections.addView(heading, header);
    HorizontalScrollView strip = new HorizontalScrollView(context);
    strip.setHorizontalScrollBarEnabled(false);
    LinearLayout cards = new LinearLayout(context);
    for (String genre : directory.musicGenres()) {
      LinearLayout card = new LinearLayout(context);
      card.setOrientation(LinearLayout.VERTICAL);
      card.setFocusable(true);
      card.setContentDescription("Browse " + genre);
      card.setOnClickListener(v -> loadSearch(genre));
      ImageView picture = new ImageView(context);
      picture.setScaleType(ImageView.ScaleType.CENTER_CROP);
      picture.setBackground(roundRect(dp(11), 0x18ffffff));
      picture.setClipToOutline(true);
      picture.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
      final CardFocusForeground focus = new CardFocusForeground(dp(11));
      picture.setForeground(focus);
      card.setOnFocusChangeListener((view, focused) -> focus.setFocused(focused));
      String filename = "category-" + genre.replace(' ', '-') + ".jpg";
      Bitmap photo = categoryArtwork.get(filename);
      if (photo == null) {
        try (InputStream input = context.getAssets().open("radio-artwork/" + filename)) {
          BitmapFactory.Options options = new BitmapFactory.Options();
          options.inSampleSize = 2;
          photo = BitmapFactory.decodeStream(input, null, options);
          categoryArtwork.put(filename, photo);
        } catch (Exception ignored) {}
      }
      picture.setImageBitmap(photo);
      card.addView(picture, new LinearLayout.LayoutParams(dp(142), dp(80)));
      TextView label = text(14, Color.WHITE);
      label.setText(Character.toUpperCase(genre.charAt(0)) + genre.substring(1));
      label.setPadding(dp(2), dp(5), 0, 0);
      card.addView(label, new LinearLayout.LayoutParams(dp(142), dp(24)));
      LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(142), dp(104));
      params.rightMargin = dp(8);
      cards.addView(card, params);
    }
    strip.addView(cards);
    sections.addView(strip, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(104)));
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

  private View stationCard(final RadioStationPolicy.Station station, int artworkIndex, boolean isFavorite) {
    LinearLayout card = new LinearLayout(context);
    card.setOrientation(LinearLayout.VERTICAL);
    card.setFocusable(true);
    card.setFocusableInTouchMode(true);
    card.setContentDescription("Play " + station.name);
    card.setTag(station.uuid);
    card.setOnClickListener(v -> play(station));
    final CardFocusForeground focusOutline = new CardFocusForeground(dp(11));
    card.setOnFocusChangeListener((view, focused) -> { focusOutline.setFocused(focused); });
    FrameLayout artFrame = new FrameLayout(context);
    artFrame.setBackground(roundRect(dp(11), 0x18ffffff));
    artFrame.setClipToOutline(true);
    artFrame.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
    artFrame.setForeground(focusOutline);
    ImageView art = new ImageView(context);
    bindStationArtwork(art, station);
    artFrame.addView(art, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(67)));
    ImageView heart = new ImageView(context);
    heart.setPadding(dp(8), dp(8), dp(8), dp(8));
    heart.setContentDescription((isFavorite ? "Remove " : "Save ") + station.name);
    setImageIcon(heart, isFavorite ? "heart_filled" : "heart");
    heart.setColorFilter(isFavorite ? 0xffff375f : Color.WHITE);
    heart.setBackground(new IconFocusBackground());
    heart.setFocusable(true);
    heart.setFocusableInTouchMode(true);
    heart.setOnFocusChangeListener((view, focused) -> ((IconFocusBackground) view.getBackground()).setFocused(focused));
    heart.setOnClickListener(v -> {
      toggleFavorite(station);
      boolean saved = favoriteIds().contains(station.uuid);
      setImageIcon(heart, saved ? "heart_filled" : "heart");
      heart.setColorFilter(saved ? 0xffff375f : Color.WHITE);
      heart.setContentDescription((saved ? "Remove " : "Save ") + station.name);
      if (favoritesMode && !saved) { if (favoriteIds().isEmpty()) openBrowse(); else showFavorites(); }
      else if ("Radio".contentEquals(title.getText())) {
        showBrowse(new ArrayList<>(browseStations));
        View restored = sections.findViewWithTag(station.uuid);
        if (restored != null) restored.requestFocus();
      }
    });
    FrameLayout.LayoutParams heartParams = new FrameLayout.LayoutParams(dp(36), dp(36), Gravity.RIGHT | Gravity.TOP);
    heartParams.setMargins(0, dp(1), dp(1), 0);
    artFrame.addView(heart, heartParams);
    card.addView(artFrame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(67)));
    TextView name = text(14, Color.WHITE);
    name.setSingleLine(true);
    name.setEllipsize(android.text.TextUtils.TruncateAt.END);
    name.setPadding(dp(2), dp(5), dp(2), 0);
    name.setText(station.name);
    card.addView(name, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(29)));
    TextView genre = text(10, 0xc1ffffff);
    genre.setSingleLine(true);
    genre.setEllipsize(android.text.TextUtils.TruncateAt.END);
    genre.setPadding(dp(2), 0, dp(2), 0);
    genre.setText(stationGenre(station));
    // Station cards use a single clear title; directory tags stay out of the visual hierarchy.
    return card;
  }

  private void showSearch() { searchOverlay.show(); }

  private void loadSearch(final String query) {
    searchState = new SearchState(query);
    loadSearchPage(false);
  }

  private void loadSearchPage(final boolean append) {
    final SearchState state = searchState;
    if (state == null || (!state.namesMore && !state.tagsMore)) return;
    final int request = ++screenGeneration;
    favoritesMode = false;
    title.setText("Search");
    subtitle.setText("Results for “" + state.query + "”");
    updateTabs();
    setStatus(append ? "Loading more stations…" : "Searching eligible stations…");
    if (!append) {
      sections.removeAllViews();
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
    sections.removeAllViews();
    clearStatus();
    playableStations.clear();
    if (state.stations.isEmpty()) addEmptySection("Results", "No eligible stations matched “" + state.query + "”.");
    else {
      // The viewport exposes five cards at once; the horizontal row retains earlier real pages.
      addStationSection("Results", state.stations, state.stations.size());
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
    sections.removeAllViews();
    View spacer = new View(context);
    sections.addView(spacer, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34)));
    TextView retryView = action("Retry", null);
    retryView.setOnClickListener(v -> { if (retryAction != null) retryAction.run(); });
    sections.addView(retryView, new LinearLayout.LayoutParams(dp(120), dp(48)));
  }

  private void play(final RadioStationPolicy.Station wanted) {
    stopPlayback();
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
        main.post(() -> { if (token == playbackGeneration && isOpen()) { pendingPlayback = false; setStatus("This station is no longer stream checked."); updatePlayer(); } });
      }
    });
  }

  private void startPlayback(RadioStationPolicy.Station station, int token) {
    stopPlayerOnly();
    if (!host.isRadioHostForeground() || token != playbackGeneration) return;
    if (audio.requestAudioFocus(audioFocus, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
      pendingPlayback = false;
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
          updatePlayerVolume(ready, volume.getProgress());
          ready.start();
          paused = false;
          pendingPlayback = false;
          playing = station;
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
  private void stopPlayback() {
    playbackGeneration++;
    if (activePrepareTimeout != null) { main.removeCallbacks(activePrepareTimeout); activePrepareTimeout = null; }
    stopPlayerOnly();
    audio.abandonAudioFocus(audioFocus);
    paused = false;
    pendingPlayback = false;
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
  private void updateTabs() {
    boolean hasFavorites = !favoriteIds().isEmpty();
    favoritesTab.setVisibility(hasFavorites ? View.VISIBLE : View.GONE);
    FrameLayout.LayoutParams rail = (FrameLayout.LayoutParams) sidebar.getLayoutParams();
    rail.height = dp(hasFavorites ? 151 : 108);
    sidebar.setLayoutParams(rail);
    setSelected(browseTab, !favoritesMode && "Radio".contentEquals(title == null ? "" : title.getText()));
    setSelected(favoritesTab, favoritesMode);
    setSelected(searchTab, "Search".contentEquals(title == null ? "" : title.getText()));
  }
  private void setSelected(TextView view, boolean selected) { ((GlassBackground) view.getBackground()).setSelected(selected); }
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
  }
  private void setControlEnabled(TextView view, boolean enabled) { view.setFocusable(enabled); view.setAlpha(enabled ? 1f : .42f); }
  private void updateVolume(int value) { prefs.edit().putInt(VOLUME_KEY, value).apply(); if (player != null) updatePlayerVolume(player, value); }
  private static void updatePlayerVolume(MediaPlayer value, int volume) { float level = Math.max(0f, Math.min(1f, volume / 100f)); try { value.setVolume(level, level); } catch (Exception ignored) {} }

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
  private static String stationGenre(RadioStationPolicy.Station station) { return station.genre.length() == 0 ? (station.country.length() == 0 ? station.bitrate + " kbps" : station.country) : station.genre; }
  private void bindStationArtwork(ImageView view, RadioStationPolicy.Station station) {
    String key = station == null ? "" : station.uuid + ":" + station.artworkUrl;
    if (key.equals(view.getTag())) return;
    view.setTag(key);
    view.setScaleType(ImageView.ScaleType.CENTER_CROP);
    view.setPadding(0, 0, 0, 0);
    view.setImageDrawable(null);
    if (station == null) return;
    setImageIcon(view, "podcast");
    view.setAlpha(.35f);
    stationArtwork.load(station, (loaded, bitmap) -> {
      if (!key.equals(view.getTag())) return;
      if (bitmap != null) {
        view.setImageBitmap(bitmap);
        view.setScaleType(ImageView.ScaleType.CENTER_CROP);
        view.setAlpha(1f);
      }
    });
  }


  private TextView text(int size, int color) { TextView view = new TextView(context); view.setTextColor(color); view.setTextSize(size); view.setIncludeFontPadding(false); return view; }
  private TextView sideAction(String label, String icon) { TextView view = action(label, icon); view.setTextSize(14); view.setBackground(new GlassBackground(dp(16), Color.TRANSPARENT)); view.setGravity(Gravity.CENTER_VERTICAL); view.setPadding(dp(12), 0, dp(8), 0); return view; }
  private TextView action(String value, String icon) {
    TextView view = text(12, Color.WHITE);
    view.setText(value);
    view.setGravity(Gravity.CENTER);
    view.setFocusable(true);
    view.setFocusableInTouchMode(true);
    view.setBackground(new GlassBackground(dp(17), 0x3cffffff));
    if (icon != null) setIcon(view, icon, 18);
    view.setOnFocusChangeListener((v, focused) -> { ((GlassBackground) v.getBackground()).setFocused(focused); });
    return view;
  }
  private TextView iconAction(String description, String icon) {
    TextView view = new TransportButton(context);
    view.setFocusable(true);
    view.setFocusableInTouchMode(true);
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
    private boolean focused, selected;
    private final boolean frosted;
    GlassBackground(float radius, int color) { this(radius, color, false); }
    GlassBackground(float radius, int color, boolean frosted) { this.radius = radius; this.color = color; this.frosted = frosted; }
    void setFocused(boolean value) { focused = value; invalidateSelf(); }
    void setSelected(boolean value) { selected = value; invalidateSelf(); }
    @Override public void draw(Canvas canvas) {
      Rect b = getBounds();
      paint.setStyle(Paint.Style.FILL);
      if (frosted && frostedBackdrop != null && getCallback() instanceof View) {
        View view = (View) getCallback();
        frostedBackdrop.registerGlassView(view);
        frostedBackdrop.draw(canvas, view, new RectF(b), radius);
        paint.setColor(0x38000000);
        canvas.drawRoundRect(new RectF(b), radius, radius, paint);
      }
      paint.setColor(selected ? 0x42ffffff : color);
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
  /** Low-cost static tint. It leaves the top-right readable while grounding content on the left. */
  private static final class RadioBackdropShade extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    @Override public void draw(Canvas canvas) {
      Rect b = getBounds();
      paint.setShader(new LinearGradient(b.left, b.top, b.right, b.top, 0x90000000, 0x18000000, Shader.TileMode.CLAMP));
      canvas.drawRect(b, paint);
      paint.setShader(new LinearGradient(b.left, b.top, b.left, b.bottom, 0x05000000, 0x78000000, Shader.TileMode.CLAMP));
      canvas.drawRect(b, paint);
      paint.setShader(null);
    }
    @Override public void setAlpha(int alpha) {}
    @Override public void setColorFilter(android.graphics.ColorFilter filter) {}
    @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
  }
  private static final class IconFocusBackground extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean focused;
    void setFocused(boolean value) { focused = value; invalidateSelf(); }
    @Override public void draw(Canvas canvas) {
      if (!focused) return;
      Rect b = getBounds();
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeWidth(2.5f);
      paint.setColor(Color.WHITE);
      canvas.drawRoundRect(new RectF(b.left + 1, b.top + 1, b.right - 1, b.bottom - 1), 12, 12, paint);
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
