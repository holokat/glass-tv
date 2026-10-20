package local.glasstv.launcher;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
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
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/**
 * Remote-first Radio Browser screen. Directory work stays in RadioDirectory; this class owns
 * display and audio.
 */
final class RadioController {
  interface Host {
    void suspendAmbientForRadio();

    void restoreAmbientAfterRadio();

    boolean isRadioHostForeground();
  }

  private static final int MAX_FAVORITES = 40, SECTION_ITEMS = 4, PREPARE_TIMEOUT_MS = 15_000;
  private final Context context;
  private final Host host;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ExecutorService network = Executors.newSingleThreadExecutor();
  private final SharedPreferences prefs;
  private final RadioDirectory directory = new RadioDirectory();
  private final AudioManager audio;
  private final AudioManager.OnAudioFocusChangeListener audioFocus = this::onAudioFocusChanged;
  private final FrameLayout overlay;
  private final TextView browseTab, favoritesTab, search, title, status, nowPlaying, pause, stop;
  private final LinearLayout results, transport;
  private boolean favoritesMode, paused, pendingPlayback;
  private volatile int screenGeneration, playbackGeneration;
  private MediaPlayer player;
  private RadioStationPolicy.Station playing;
  private Runnable retryAction;
  private Runnable activePrepareTimeout;
  private final ArrayList<RadioStationPolicy.Station> collectionStations = new ArrayList<>();

  RadioController(Context context, Host host, FrameLayout parent) {
    this.context = context;
    this.host = host;
    prefs = context.getSharedPreferences("glass-tv-radio", Context.MODE_PRIVATE);
    audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    overlay = new FrameLayout(context);
    overlay.setBackgroundColor(0xe6000000);
    overlay.setVisibility(View.GONE);
    overlay.setFocusable(true);
    LinearLayout page = new LinearLayout(context);
    page.setOrientation(LinearLayout.VERTICAL);
    page.setPadding(dp(56), dp(38), dp(56), dp(34));
    overlay.addView(
        page,
        new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    LinearLayout header = new LinearLayout(context);
    header.setGravity(Gravity.CENTER_VERTICAL);
    title = text(30);
    title.setTypeface(android.graphics.Typeface.DEFAULT, 1);
    title.setText("Radio");
    nowPlaying = action("");
    nowPlaying.setTextSize(16);
    nowPlaying.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
    nowPlaying.setSingleLine(true);
    nowPlaying.setContentDescription("Pause or resume radio");
    nowPlaying.setOnClickListener(v -> togglePause());
    header.addView(title, new LinearLayout.LayoutParams(0, dp(44), 1f));
    header.addView(nowPlaying, new LinearLayout.LayoutParams(dp(360), dp(44)));
    page.addView(header);
    LinearLayout navigation = new LinearLayout(context);
    navigation.setGravity(Gravity.CENTER_VERTICAL);
    LinearLayout tabs = new LinearLayout(context);
    browseTab = action("Browse");
    favoritesTab = action("Favorites");
    LinearLayout.LayoutParams browseParams = new LinearLayout.LayoutParams(dp(140), dp(52));
    browseParams.setMargins(0, 0, dp(6), 0);
    tabs.addView(browseTab, browseParams);
    tabs.addView(favoritesTab, new LinearLayout.LayoutParams(dp(140), dp(52)));
    navigation.addView(tabs, new LinearLayout.LayoutParams(dp(286), dp(52)));
    search = action("Search station name");
    search.setTextSize(17);
    search.setContentDescription("Search station name");
    search.setOnClickListener(v -> showSearch());
    LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(0, dp(52), 1f);
    searchParams.setMargins(dp(10), 0, 0, 0);
    navigation.addView(search, searchParams);
    page.addView(
        navigation, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(60)));
    status = text(15);
    status.setTextColor(0xc9ffffff);
    status.setPadding(0, dp(4), 0, dp(4));
    transport = new LinearLayout(context);
    transport.setGravity(Gravity.CENTER_HORIZONTAL);
    pause = action("Pause");
    stop = action("Stop");
    pause.setOnClickListener(v -> togglePause());
    stop.setOnClickListener(
        v -> {
          stopPlayback();
          status.setText("Stopped");
        });
    LinearLayout.LayoutParams pauseParams = new LinearLayout.LayoutParams(dp(150), dp(50));
    pauseParams.setMargins(0, 0, dp(8), 0);
    transport.addView(pause, pauseParams);
    transport.addView(stop, new LinearLayout.LayoutParams(dp(150), dp(50)));
    page.addView(
        status, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(32)));
    ScrollView scroll = new ScrollView(context);
    scroll.setFillViewport(true);
    results = new LinearLayout(context);
    results.setOrientation(LinearLayout.VERTICAL);
    scroll.addView(results);
    page.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
    page.addView(
        transport, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)));
    browseTab.setOnClickListener(
        v -> {
          favoritesMode = false;
          updateTabs();
          loadBrowse();
        });
    favoritesTab.setOnClickListener(
        v -> {
          favoritesMode = true;
          updateTabs();
          loadFavorites();
        });
    updateTabs();
    updateTransport();
    parent.addView(
        overlay,
        new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
  }

  void open() {
    if (isOpen()) return;
    host.suspendAmbientForRadio();
    overlay.setVisibility(View.VISIBLE);
    favoritesMode = false;
    updateTabs();
    browseTab.requestFocus();
    loadBrowse();
  }

  boolean isOpen() {
    return overlay.getVisibility() == View.VISIBLE;
  }

  boolean handleKey(int key) {
    if (!isOpen()) return false;
    if (key == KeyEvent.KEYCODE_BACK) {
      close();
      return true;
    }
    if (key == KeyEvent.KEYCODE_MEDIA_STOP) {
      stopPlayback();
      status.setText("Stopped");
      return true;
    }
    if (key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
      togglePause();
      return true;
    }
    if (key == KeyEvent.KEYCODE_MEDIA_PAUSE) {
      pausePlayback();
      return true;
    }
    return false;
  }

  void onHostStopped() {
    closeInternal(false);
  }

  void destroy() {
    closeInternal(false);
    network.shutdownNow();
  }

  void close() {
    closeInternal(true);
  }

  private void closeInternal(boolean restore) {
    screenGeneration++;
    stopPlayback();
    if (isOpen()) overlay.setVisibility(View.GONE);
    if (restore) host.restoreAmbientAfterRadio();
  }

  private void loadBrowse() {
    final int request = ++screenGeneration;
    setLoading("Loading live music genres…", this::loadBrowse);
    network.execute(
        () -> {
          if (request != screenGeneration) return;
          try {
            List<String> genres = directory.musicGenres();
            LinkedHashMap<String, List<RadioStationPolicy.Station>> groups = new LinkedHashMap<>();
            for (String genre : genres) {
              if (request != screenGeneration) return;
              groups.put(genre, directory.browseGenre(genre, new RadioDirectory.Cursor()).stations);
            }
            main.post(
                () -> {
                  if (fresh(request) && !favoritesMode) showBrowse(groups);
                });
          } catch (Exception failure) {
            main.post(
                () -> {
                  if (fresh(request))
                    showRetry(
                        "Radio directory is unavailable. Check the connection and retry.",
                        this::loadBrowse);
                });
          }
        });
  }

  private void showBrowse(Map<String, List<RadioStationPolicy.Station>> groups) {
    results.removeAllViews();
    status.setText("Browse live music genres · Stream checked by the directory");
    boolean any = false;
    for (Map.Entry<String, List<RadioStationPolicy.Station>> entry : groups.entrySet())
      if (!entry.getValue().isEmpty()) {
        addGenreSection(entry.getKey(), entry.getValue());
        any = true;
      }
    if (!any) showRetry("No stream-checked stations were found.", this::loadBrowse);
    else results.post(() -> search.requestFocus());
  }

  private void addGenreSection(final String genre, List<RadioStationPolicy.Station> stations) {
    LinearLayout heading = new LinearLayout(context);
    heading.setGravity(Gravity.CENTER_VERTICAL);
    heading.setPadding(0, dp(9), 0, dp(4));
    TextView label = text(20);
    label.setText(genre);
    label.setTypeface(android.graphics.Typeface.DEFAULT, 1);
    TextView viewAll = action("View all");
    viewAll.setTextSize(15);
    viewAll.setOnClickListener(
        v ->
            loadCollection(
                "Genre: " + genre,
                cursor -> directory.browseGenre(genre, cursor),
                new RadioDirectory.Cursor(),
                false));
    heading.addView(label, new LinearLayout.LayoutParams(0, dp(48), 1f));
    heading.addView(viewAll, new LinearLayout.LayoutParams(dp(122), dp(48)));
    results.addView(heading);
    for (int i = 0; i < stations.size() && i < SECTION_ITEMS; i++) addStationRow(stations.get(i));
  }

  private void loadFavorites() {
    final int request = ++screenGeneration;
    final List<String> ids = new ArrayList<>(favoriteIds());
    if (ids.isEmpty()) {
      showAction(
          "No favorites yet. Save stations from Browse.",
          "Browse",
          () -> {
            favoritesMode = false;
            updateTabs();
            loadBrowse();
          });
      return;
    }
    setLoading("Rechecking favorites…", this::loadFavorites);
    network.execute(
        () -> {
          if (request != screenGeneration) return;
          try {
            List<RadioStationPolicy.Station> stations = directory.favorites(ids);
            main.post(
                () -> {
                  if (fresh(request) && favoritesMode) showFavorites(stations);
                });
          } catch (Exception failure) {
            main.post(
                () -> {
                  if (fresh(request))
                    showRetry(
                        "Favorites could not be rechecked while offline.", this::loadFavorites);
                });
          }
        });
  }

  private interface PageRequest {
    RadioDirectory.Page load(RadioDirectory.Cursor cursor) throws Exception;
  }

  private void loadCollection(
      final String heading,
      final PageRequest request,
      final RadioDirectory.Cursor cursor,
      final boolean append) {
    final int generation = ++screenGeneration;
    favoritesMode = false;
    updateTabs();
    setLoading(
        "Loading " + heading.toLowerCase() + "…",
        () -> loadCollection(heading, request, new RadioDirectory.Cursor(), false));
    network.execute(
        () -> {
          if (generation != screenGeneration) return;
          try {
            RadioDirectory.Page page = request.load(cursor);
            main.post(
                () -> {
                  if (fresh(generation)) showCollection(heading, page, request, cursor, append);
                });
          } catch (Exception failure) {
            main.post(
                () -> {
                  if (fresh(generation))
                    showRetry(
                        "That directory request could not be completed.",
                        () -> loadCollection(heading, request, new RadioDirectory.Cursor(), false));
                });
          }
        });
  }

  private void showFavorites(List<RadioStationPolicy.Station> stations) {
    collectionStations.clear();
    collectionStations.addAll(stations);
    renderCollection("Favorites", false, null, null);
  }

  private void showCollection(
      String heading,
      RadioDirectory.Page page,
      final PageRequest loader,
      final RadioDirectory.Cursor cursor,
      boolean append) {
    if (!append) collectionStations.clear();
    for (RadioStationPolicy.Station station : page.stations)
      if (!contains(collectionStations, station.uuid)) collectionStations.add(station);
    renderCollection(heading, page.hasMore, loader, cursor);
  }

  private void renderCollection(
      String heading,
      boolean hasMore,
      final PageRequest loader,
      final RadioDirectory.Cursor cursor) {
    results.removeAllViews();
    title.setText(heading);
    status.setText("Stream checked by the directory");
    if (collectionStations.isEmpty()) {
      if (loader != null && hasMore)
        showAction(
            "No eligible stations in this page.",
            "Load more",
            () -> loadCollection(heading, loader, cursor, true));
      else
        showRetry(
            "No stream-checked stations were found.",
            () -> {
              if (loader == null) loadFavorites();
              else loadCollection(heading, loader, new RadioDirectory.Cursor(), false);
            });
      return;
    }
    for (RadioStationPolicy.Station station : collectionStations) addStationRow(station);
    if (loader != null && hasMore) {
      TextView more = action("Load more");
      more.setOnClickListener(v -> loadCollection(heading, loader, cursor, true));
      results.addView(
          more, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)));
    }
  }

  private void showSearch() {
    EditText input = new EditText(context);
    input.setSingleLine(true);
    input.setHint("Station name");
    input.setInputType(InputType.TYPE_CLASS_TEXT);
    AlertDialog dialog =
        new AlertDialog.Builder(context)
            .setTitle("Search radio")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Search", null)
            .create();
    dialog.setOnShowListener(
        ignored ->
            dialog
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(
                    v -> {
                      String name = input.getText().toString().trim();
                      if (name.isEmpty()) {
                        input.setError("Enter a station name");
                        return;
                      }
                      dialog.dismiss();
                      loadCollection(
                          "Search: " + name,
                          cursor -> directory.searchByName(name, cursor),
                          new RadioDirectory.Cursor(),
                          false);
                    }));
    dialog.show();
    input.requestFocus();
  }

  private void addStationRow(final RadioStationPolicy.Station station) {
    LinearLayout row = new LinearLayout(context);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(dp(14), dp(5), 0, dp(5));
    TextView stationButton = action(station.name);
    stationButton.setGravity(Gravity.CENTER_VERTICAL);
    stationButton.setPadding(dp(22), 0, dp(16), 0);
    String details =
        station.genre.length() == 0
            ? station.country
            : station.genre + (station.country.length() == 0 ? "" : " · " + station.country);
    stationButton.setText(station.name + "\n" + details + " · " + station.bitrate + " kbps");
    stationButton.setTextSize(17);
    stationButton.setMaxLines(2);
    stationButton.setOnClickListener(v -> play(station));
    TextView favorite = action(favoriteIds().contains(station.uuid) ? "Saved" : "Save");
    favorite.setTextSize(15);
    favorite.setContentDescription("Save " + station.name);
    setIcon(favorite, "favorite", 18);
    favorite.setOnClickListener(
        v -> {
          toggleFavorite(station.uuid);
          if (favoritesMode) {
            collectionStations.remove(station);
            renderCollection("Favorites", false, null, null);
          } else favorite.setText(favoriteIds().contains(station.uuid) ? "Saved" : "Save");
        });
    row.addView(stationButton, new LinearLayout.LayoutParams(0, dp(70), 1f));
    row.addView(favorite, new LinearLayout.LayoutParams(dp(108), dp(58)));
    results.addView(
        row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(80)));
  }

  private void play(final RadioStationPolicy.Station wanted) {
    stopPlayback();
    final int token = ++playbackGeneration;
    pendingPlayback = true;
    updateTransport();
    status.setText("Rechecking “" + wanted.name + "”…");
    network.execute(
        () -> {
          if (token != playbackGeneration) return;
          try {
            List<RadioStationPolicy.Station> checked = directory.checkedStation(wanted.uuid);
            if (checked.isEmpty()) throw new IllegalStateException("not checked");
            final RadioStationPolicy.Station station = checked.get(0);
            if (!RadioDirectory.safePlaybackUrl(station.url))
              throw new IllegalStateException("unsafe URL");
            main.post(
                () -> {
                  if (token == playbackGeneration && isOpen()) startPlayback(station, token);
                });
          } catch (Exception failure) {
            main.post(
                () -> {
                  if (token == playbackGeneration && isOpen()) {
                    pendingPlayback = false;
                    updateTransport();
                    status.setText("This station is no longer stream checked.");
                  }
                });
          }
        });
  }

  private void startPlayback(RadioStationPolicy.Station station, int token) {
    stopPlayerOnly();
    if (!host.isRadioHostForeground() || token != playbackGeneration) return;
    if (audio.requestAudioFocus(audioFocus, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
      pendingPlayback = false;
      updateTransport();
      status.setText("Radio playback could not take audio focus.");
      return;
    }
    try {
      MediaPlayer next = new MediaPlayer();
      player = next; // Release this instance even if source setup throws.
      next.setAudioAttributes(
          new AudioAttributes.Builder()
              .setUsage(AudioAttributes.USAGE_MEDIA)
              .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
              .build());
      next.setDataSource(context, Uri.parse(station.url));
      if (activePrepareTimeout != null) main.removeCallbacks(activePrepareTimeout);
      final Runnable timeout =
          () -> {
            if (token == playbackGeneration && player == next) {
              stopPlayback();
              if (isOpen()) status.setText("This station took too long to start.");
            }
          };
      activePrepareTimeout = timeout;
      next.setOnPreparedListener(
          ready -> {
            if (ready != player || token != playbackGeneration || !host.isRadioHostForeground()) {
              release(ready);
              return;
            }
            main.removeCallbacks(timeout);
            if (activePrepareTimeout == timeout) activePrepareTimeout = null;
            try {
              ready.start();
              paused = false;
              pendingPlayback = false;
              status.setText("Playing · Stream checked");
              nowPlaying.setText(station.name + " · Pause");
              updateTransport();
            } catch (Exception failure) {
              stopPlayback();
              status.setText("This station could not play on this TV.");
            }
          });
      next.setOnCompletionListener(completed -> {
        if (player == completed && token == playbackGeneration) {
          stopPlayback();
          if (isOpen()) status.setText("The station ended its stream. Select it to reconnect.");
        }
      });
      next.setOnErrorListener(
          (failed, what, extra) -> {
            if (failed == player && token == playbackGeneration) {
              stopPlayback();
              if (isOpen()) status.setText("This station could not play on this TV.");
            }
            return true;
          });
      player = next;
      playing = station;
      paused = false;
      nowPlaying.setText("Preparing “" + station.name + "”…");
      updateTransport();
      main.postDelayed(timeout, PREPARE_TIMEOUT_MS);
      next.prepareAsync();
    } catch (Exception failure) {
      stopPlayback();
      status.setText("This station could not be prepared.");
    }
  }

  private void togglePause() {
    if (paused) resumePlayback();
    else pausePlayback();
  }

  private void pausePlayback() {
    if (player == null || paused) return;
    try {
      player.pause();
      paused = true;
      audio.abandonAudioFocus(audioFocus);
      nowPlaying.setText(playing == null ? "Paused" : playing.name + " · Resume");
      status.setText("Paused");
      updateTransport();
    } catch (Exception failure) {
      stopPlayback();
      status.setText("Radio playback stopped.");
    }
  }

  private void resumePlayback() {
    if (player == null || !paused || !host.isRadioHostForeground()) return;
    if (audio.requestAudioFocus(audioFocus, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
      status.setText("Radio playback could not take audio focus.");
      return;
    }
    try {
      player.start();
      paused = false;
      nowPlaying.setText(playing == null ? "Playing" : playing.name + " · Pause");
      status.setText("Playing · Stream checked");
      updateTransport();
    } catch (Exception failure) {
      stopPlayback();
      status.setText("Radio playback stopped.");
    }
  }

  private void stopPlayback() {
    playbackGeneration++;
    if (activePrepareTimeout != null) {
      main.removeCallbacks(activePrepareTimeout);
      activePrepareTimeout = null;
    }
    stopPlayerOnly();
    audio.abandonAudioFocus(audioFocus);
    nowPlaying.setText("");
    paused = false;
    pendingPlayback = false;
    updateTransport();
  }

  private void stopPlayerOnly() {
    MediaPlayer old = player;
    player = null;
    playing = null;
    if (old != null) release(old);
  }

  private static void release(MediaPlayer value) {
    try {
      value.setOnPreparedListener(null);
      value.setOnErrorListener(null);
      value.setOnCompletionListener(null);
      value.reset();
      value.release();
    } catch (Exception ignored) {
    }
  }

  private void onAudioFocusChanged(int change) {
    if (change == AudioManager.AUDIOFOCUS_GAIN) return;
    main.post(
        () -> {
          if (player != null) {
            stopPlayback();
            if (isOpen()) status.setText("Playback stopped because another app took audio.");
          }
        });
  }

  JSONObject statusSnapshot() {
    if (Looper.myLooper() == Looper.getMainLooper()) return statusOnMain();
    AtomicReference<JSONObject> result = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    main.post(
        () -> {
          result.set(statusOnMain());
          latch.countDown();
        });
    try {
      if (latch.await(150, TimeUnit.MILLISECONDS) && result.get() != null) return result.get();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
    return new JSONObject();
  }

  private JSONObject statusOnMain() {
    try {
      boolean isPlaying = player != null && !paused && player.isPlaying();
      JSONObject out = new JSONObject();
      out.put(
          "state",
          player == null ? "idle" : paused ? "paused" : isPlaying ? "playing" : "preparing");
      out.put("isPlaying", isPlaying);
      out.put("name", playing == null ? JSONObject.NULL : playing.name);
      out.put("bitrate", playing == null ? JSONObject.NULL : playing.bitrate);
      out.put("open", isOpen());
      return out;
    } catch (Exception ignored) {
      return new JSONObject();
    }
  }

  private boolean fresh(int request) {
    return request == screenGeneration && isOpen();
  }

  private void setLoading(String message, Runnable retry) {
    retryAction = retry;
    title.setText(favoritesMode ? "Favorites" : "Radio");
    status.setText(message);
    results.removeAllViews();
    TextView loading = text(18);
    loading.setText(message);
    loading.setGravity(Gravity.CENTER);
    results.addView(
        loading, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(120)));
  }

  private void showRetry(String message, Runnable retry) {
    showAction(message, "Retry", retry);
  }

  private void showAction(String message, String label, Runnable action) {
    retryAction = action;
    status.setText(message);
    results.removeAllViews();
    TextView button = action(label);
    button.setOnClickListener(
        v -> {
          if (retryAction != null) retryAction.run();
        });
    results.addView(
        button, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)));
  }

  private void updateTabs() {
    browseTab.setAlpha(favoritesMode ? .62f : 1f);
    favoritesTab.setAlpha(favoritesMode ? 1f : .62f);
  }

  private void updateTransport() {
    boolean active = player != null;
    boolean visible = active || pendingPlayback;
    transport.setVisibility(visible ? View.VISIBLE : View.GONE);
    pause.setEnabled(active && !pendingPlayback);
    stop.setEnabled(visible);
    pause.setFocusable(active && !pendingPlayback);
    stop.setFocusable(visible);
    pause.setAlpha(active && !pendingPlayback ? 1f : .45f);
    stop.setAlpha(visible ? 1f : .45f);
    pause.setText(paused ? "Resume" : "Pause");
    nowPlaying.setVisibility(active ? View.VISIBLE : View.INVISIBLE);
  }

  private Set<String> favoriteIds() {
    return new HashSet<>(
        prefs.getStringSet("favoriteStationUuids", Collections.<String>emptySet()));
  }

  private static boolean contains(List<RadioStationPolicy.Station> stations, String uuid) {
    for (RadioStationPolicy.Station station : stations) if (station.uuid.equals(uuid)) return true;
    return false;
  }

  private void toggleFavorite(String uuid) {
    Set<String> ids = favoriteIds();
    if (!ids.remove(uuid)) {
      if (ids.size() >= MAX_FAVORITES) {
        status.setText("Save up to " + MAX_FAVORITES + " favorites.");
        return;
      }
      ids.add(uuid);
    }
    prefs.edit().putStringSet("favoriteStationUuids", ids).apply();
  }

  private TextView text(int size) {
    TextView view = new TextView(context);
    view.setTextColor(Color.WHITE);
    view.setTextSize(size);
    view.setIncludeFontPadding(false);
    return view;
  }

  private TextView action(String value) {
    TextView view = text(17);
    view.setText(value);
    view.setGravity(Gravity.CENTER);
    view.setFocusable(true);
    view.setFocusableInTouchMode(true);
    view.setBackground(new RadioButtonBackground());
    view.setOnFocusChangeListener(
        (v, focused) -> {
          v.setScaleX(focused ? 1.03f : 1f);
          v.setScaleY(focused ? 1.03f : 1f);
          ((RadioButtonBackground) v.getBackground()).setFocused(focused);
        });
    return view;
  }

  private void setIcon(TextView view, String name, int sizeDp) {
    int id = context.getResources().getIdentifier(name, "drawable", context.getPackageName());
    if (id == 0) return;
    android.graphics.drawable.Drawable icon = context.getResources().getDrawable(id);
    int size = dp(sizeDp);
    icon.setBounds(0, 0, size, size);
    view.setCompoundDrawables(icon, null, null, null);
    view.setCompoundDrawablePadding(dp(7));
  }

  private int dp(int value) {
    return Math.round(value * context.getResources().getDisplayMetrics().density);
  }

  private static final class RadioButtonBackground extends android.graphics.drawable.Drawable {
    private final android.graphics.Paint paint = new android.graphics.Paint(3);
    private boolean focused;

    void setFocused(boolean value) {
      focused = value;
      invalidateSelf();
    }

    @Override
    public void draw(android.graphics.Canvas canvas) {
      android.graphics.Rect b = getBounds();
      float radius = Math.min(b.height() / 2f, 20);
      paint.setColor(0x4dffffff);
      canvas.drawRoundRect(new android.graphics.RectF(b), radius, radius, paint);
      paint.setStyle(android.graphics.Paint.Style.STROKE);
      paint.setStrokeWidth(focused ? 3f : 1.5f);
      paint.setColor(focused ? Color.WHITE : 0x73ffffff);
      canvas.drawRoundRect(
          new android.graphics.RectF(b.left + 1, b.top + 1, b.right - 1, b.bottom - 1),
          radius,
          radius,
          paint);
      paint.setStyle(android.graphics.Paint.Style.FILL);
    }

    @Override
    public void setAlpha(int alpha) {}

    @Override
    public void setColorFilter(android.graphics.ColorFilter filter) {}

    @Override
    public int getOpacity() {
      return android.graphics.PixelFormat.TRANSLUCENT;
    }
  }
}
