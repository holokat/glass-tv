package local.glasstv.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.media.MediaPlayer;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.Interpolator;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;

/** Deliberately sparse Android TV HOME surface. It does not change device defaults. */
public final class MainActivity extends Activity
    implements LocalSyncServer.DeviceModel, MovieController.Host, RadioController.Host {
  private static final String SONY_HW4 =
      "com.sony.dtv.tvinput.external/.ExternalTvInputService/HW4";
  private static final String PRIME_VIDEO_PACKAGE = "com.amazon.amazonvideo.livingroom";
  private static final String SPOTIFY_PACKAGE = "com.spotify.tv.android";
  private static final String TWITCH_PACKAGE = "tv.twitch.android.app";
  private static final String HULU_PACKAGE = "com.hulu.livingroomplus";
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ExecutorService decode = Executors.newSingleThreadExecutor();
  /** Settings writes are serialized off the UI thread; receiver storage owns their atomic commit. */
  private final ExecutorService settingsWork = Executors.newSingleThreadExecutor();
  private LocalSyncServer server;
  private ReceiverService receiverService;
  private boolean receiverBound;
  private boolean launcherInitialized;
  private boolean sleepReceiverRegistered;
  private final ServiceConnection receiverConnection =
      new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
          receiverService = ((ReceiverService.LocalBinder) binder).service();
          receiverBound = true;
          server = receiverService.server();
          movieLibrary = receiverService.movieLibrary();
          if (!launcherInitialized) initializeLauncher();
          else {
            movieLibrary.detach();
            movieLibrary.attach(layout, () -> { revealTvInterface(); layout.focusFirstShortcutOrSettings(); });
          }
          receiverService.attach(MainActivity.this);
          refreshAttachedReceiver();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
          receiverBound = false;
          receiverService = null;
        }
      };
  private GlassLayout layout;
  private FrostedBackdrop frostedBackdrop;
  private WallpaperView wallpaper;
  private WeatherController weather;
  private WeatherWidget weatherWidget;
  private TvSettingsController tvSettings;
  private MovieController movies;
  private MovieLibraryController movieLibrary;
  private RadioController radio;
  private View videoMatte;
  private SurfaceView videoSurface;
  private MediaPlayer player;
  private int videoWidth, videoHeight;
  private PlaylistStore.Item pendingVideo;
  private PlaylistStore.Item activeMedia;
  private String videoState = "idle";
  private int playbackGeneration;

  /** A video started after a photo must wait for the newly-created SurfaceHolder. */
  private boolean surfaceReady;

  private int surfaceGeneration;
  private int requiredSurfaceGeneration;
  private boolean foreground;
  private PlaylistStore.Playlist playlist;
  private int rotationIndex;
  private boolean screenAwake = true;

  /** Prevent playlist updates from waking or decoding ambient media beneath a movie overlay. */
  private boolean ambientSuspendedForMovie;

  /** Radio owns audio and pauses all ambient media until its full-screen screen closes. */
  private boolean ambientSuspendedForRadio;

  /** A hidden interface is revealed by the next remote gesture, then kept visible briefly. */
  private boolean tvInterfaceRevealed = true;

  private int launcherDialogCount;

  /** Key which revealed hidden chrome; its up/repeat events are also swallowed. */
  private int revealConsumedKeyCode = KeyEvent.KEYCODE_UNKNOWN;

  private final Runnable hideTvInterface =
      new Runnable() {
        @Override
        public void run() {
          if (canAutoHideTvInterface()) {
            tvInterfaceRevealed = false;
            updateLauncherInterfaceVisibility();
          }
        }
      };
  private final java.util.Random random = new java.util.Random();
  private final AtomicInteger decodeGeneration = new AtomicInteger();
  private final Set<String> failedVideos = new HashSet<>();
  private String lastVideoFailure;
  private long lastVideoFailureAtMillis;
  private final Runnable rotate =
      new Runnable() {
        public void run() {
          advanceWallpaper();
          scheduleRotation();
        }
      };
  private final BroadcastReceiver sleepReceiver =
      new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
          if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
            screenAwake = false;
            decodeGeneration.incrementAndGet();
            main.removeCallbacks(rotate);
            if (movies != null) movies.onHostStopped();
            if (movieLibrary != null) movieLibrary.onHostStopped();
            if (radio != null) radio.onHostStopped();
            ambientSuspendedForMovie = false;
            ambientSuspendedForRadio = false;
            releaseVideo(true);
            weather.stop();
            if (layout != null) layout.stopClock();
            if (wallpaper != null) wallpaper.setForeground(false);
            if (frostedBackdrop != null) frostedBackdrop.setActive(false);
            main.removeCallbacks(hideTvInterface);
            tvInterfaceRevealed = true;
            updateLauncherInterfaceVisibility();
          } else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction()) && foreground && launcherInitialized) {
            screenAwake = true;
            wallpaper.setForeground(true);
            if (frostedBackdrop != null) frostedBackdrop.setActive(true);
            layout.startClock();
            weather.start();
            if (playlist != null && playlist.slideshowEnabled) advanceWallpaper();
            else showPausedInitialMedia();
            scheduleRotation();
            updateLauncherInterfaceVisibility();
          }
        }
      };

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    Window window = getWindow();
    window
        .getDecorView()
        .setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    ReceiverService.start(this);
  }

  private void initializeLauncher() {
    try {
      playlist = server.playlist();
    } catch (Exception e) {
      playlist = new PlaylistStore.Playlist();
    }
    weather =
        new WeatherController(
            this,
            controller -> {
              if (weatherWidget != null) {
                weatherWidget.setWeather(controller);
                updateLauncherInterfaceVisibility();
              }
            });
    tvSettings =
        new TvSettingsController(
            this,
            new TvSettingsController.Host() {
              @Override
              public JSONObject apps() {
                return playlist == null ? AppsSettings.defaults() : AppsSettings.copy(playlist.apps);
              }

              @Override
              public JSONObject weather() {
                return playlist == null ? WeatherSettings.defaults() : WeatherSettings.copy(playlist.weather);
              }

              @Override
              public void saveApps(JSONObject apps, SaveResult result) {
                updateStandaloneSettings(
                    next -> next.apps = AppsSettings.copy(apps), result);
              }

              @Override
              public void saveWeather(JSONObject weather, SaveResult result) {
                updateStandaloneSettings(
                    next -> next.weather = WeatherSettings.copy(weather), result);
              }

              @Override
              public void refreshWeather() {
                if (weather != null) weather.refreshNow();
              }

              @Override
              public void previewNavigationSound(JSONObject apps) {
                if (layout != null) layout.previewNavigationSound(apps);
              }

              @Override
              public void showDialog(AlertDialog dialog, Runnable onDismiss) {
                showLauncherDialog(dialog, onDismiss);
              }

              @Override
              public void pairMac() { showPairing(); }

              @Override
              public boolean companionReceiverEnabled() {
                return ReceiverStartupPolicy.isReceiverEnabled(MainActivity.this);
              }

              @Override
              public void setCompanionReceiverEnabled(boolean enabled) {
                setCompanionReceiverEnabledFromSettings(enabled);
              }

              @Override
              public void chooseInput() { showInputPicker(); }

              @Override
              public void openPrivacyPolicy() { MainActivity.this.openPrivacyPolicy(); }

              @Override
              public void openSystemSettings() { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
            });
    wallpaper = new WallpaperView(this);
    wallpaper.setFitMode("fit".equals(playlist.fitMode));
    layout = new GlassLayout(this);
    frostedBackdrop = new FrostedBackdrop(layout);
    frostedBackdrop.setActive(foreground && screenAwake);
    layout.addView(
        wallpaper,
        0,
        new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    videoMatte = new View(this);
    videoMatte.setBackgroundColor(Color.BLACK);
    videoMatte.setVisibility(View.GONE);
    layout.addView(
        videoMatte,
        1,
        new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    videoSurface = new SurfaceView(this);
    videoSurface.setVisibility(View.GONE);
    videoSurface
        .getHolder()
        .addCallback(
            new SurfaceHolder.Callback() {
              @Override
              public void surfaceCreated(SurfaceHolder holder) {
                surfaceReady = holder.getSurface().isValid();
                surfaceGeneration++;
                startPendingVideo(holder);
              }

              @Override
              public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                surfaceReady = holder.getSurface().isValid();
                startPendingVideo(holder);
              }

              @Override
              public void surfaceDestroyed(SurfaceHolder holder) {
                onVideoSurfaceDestroyed();
              }
            });
    layout.addView(
        videoSurface,
        2,
        new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    weatherWidget = new WeatherWidget(this);
    weatherWidget.setSelector(this::showWeatherLocations);
    weatherWidget.setWeather(weather);
    layout.addView(
        weatherWidget,
        new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    weather.setConfig(playlist.weather);
    layout.addChrome(this);
    movies = new MovieController(this, this, layout);
    movieLibrary.attach(layout, () -> { revealTvInterface(); layout.focusFirstShortcutOrSettings(); });
    radio = new RadioController(this, this, layout);
    layout.setApps(playlist.apps);
    layout.setLayout(playlist.layout);
    setContentView(layout);
    updateLauncherInterfaceVisibility();
    IntentFilter sleepEvents = new IntentFilter(Intent.ACTION_SCREEN_OFF);
    sleepEvents.addAction(Intent.ACTION_SCREEN_ON);
    registerReceiver(sleepReceiver, sleepEvents);
    sleepReceiverRegistered = true;
    launcherInitialized = true;
  }

  private void refreshAttachedReceiver() {
    try {
      onPlaylistChanged(server.playlist());
    } catch (Exception ignored) {
    }
    if (foreground) resumeForegroundLauncher();
  }

  private void bindReceiver() {
    bindReceiver(true);
  }

  /** Binding may follow an explicit start request without sending that request twice. */
  private void bindReceiver(boolean startService) {
    if (receiverBound) return;
    if (startService) ReceiverService.start(this);
    receiverBound = bindService(new Intent(this, ReceiverService.class), receiverConnection, Context.BIND_AUTO_CREATE);
  }

  private void setCompanionReceiverEnabledFromSettings(boolean enabled) {
    ReceiverStartupPolicy.setReceiverEnabled(this, enabled);
    if (enabled) {
      // A previous Stop can call stopSelf while this Activity still holds a binding. Starting
      // explicitly restores the service's started lifetime; LocalSyncServer.start is idempotent.
      ReceiverService.start(this);
      if (!receiverBound) bindReceiver(false);
    } else if (receiverService != null) {
      receiverService.setReceiverEnabled(false);
    } else {
      ReceiverService.stop(this);
    }
  }

  private void resumeForegroundLauncher() {
    if (!launcherInitialized) return;
    wallpaper.setForeground(true);
    if (frostedBackdrop != null) frostedBackdrop.setActive(true);
    layout.startClock();
    weather.start();
    if (playlist != null && playlist.slideshowEnabled) advanceWallpaper();
    else showPausedInitialMedia();
    scheduleRotation();
    updateLauncherInterfaceVisibility();
  }

  @Override
  protected void onStart() {
    super.onStart();
    foreground = true;
    screenAwake = true;
    tvInterfaceRevealed = true;
    bindReceiver();
    if (launcherInitialized) resumeForegroundLauncher();
  }

  @Override
  protected void onStop() {
    foreground = false;
    decodeGeneration.incrementAndGet();
    main.removeCallbacks(rotate);
    main.removeCallbacks(hideTvInterface);
    if (movies != null) movies.onHostStopped();
    if (movieLibrary != null) movieLibrary.detach();
    if (radio != null) radio.onHostStopped();
    ambientSuspendedForMovie = false;
    ambientSuspendedForRadio = false;
    releaseVideo(true);
    if (weather != null) weather.stop();
    if (layout != null) {
      layout.stopClock();
      layout.stopNavigationSounds();
    }
    if (wallpaper != null) wallpaper.setForeground(false);
    if (frostedBackdrop != null) frostedBackdrop.setActive(false);
    if (receiverService != null) receiverService.detach(this);
    if (receiverBound) {
      unbindService(receiverConnection);
      receiverBound = false;
    }
    receiverService = null;
    super.onStop();
  }

  @Override
  protected void onDestroy() {
    if (receiverBound) {
      if (receiverService != null) receiverService.detach(this);
      unbindService(receiverConnection);
      receiverBound = false;
    }
    if (sleepReceiverRegistered) {
      try {
        unregisterReceiver(sleepReceiver);
      } catch (Exception ignored) {
      }
      sleepReceiverRegistered = false;
    }
    if (movies != null) movies.destroy();
    if (radio != null) radio.destroy();
    if (layout != null) layout.destroyNavigationSounds();
    releaseVideo(true);
    if (weather != null) weather.destroy();
    if (tvSettings != null) tvSettings.destroy();
    if (frostedBackdrop != null) frostedBackdrop.close();
    decode.shutdownNow();
    settingsWork.shutdownNow();
    super.onDestroy();
  }

  @Override
  public boolean dispatchKeyEvent(KeyEvent event) {
    if (!launcherInitialized) return super.dispatchKeyEvent(event);
    int keyCode = event.getKeyCode();
    if (keyCode == revealConsumedKeyCode) {
      if (event.getAction() == KeyEvent.ACTION_UP) revealConsumedKeyCode = KeyEvent.KEYCODE_UNKNOWN;
      // Includes repeats and key-up, so the revealing press cannot also activate chrome.
      return true;
    }
    // Movies own remote handling while their overlay is active; ambient chrome never consumes those
    // keys.
    if (event.getAction() == KeyEvent.ACTION_DOWN && movieLibrary != null && movieLibrary.handleKey(keyCode))
      return true;
    if (event.getAction() == KeyEvent.ACTION_DOWN && movies != null && movies.handleKey(keyCode))
      return true;
    if (event.getAction() == KeyEvent.ACTION_DOWN && radio != null && radio.handleKey(keyCode))
      return true;
    if (event.getAction() == KeyEvent.ACTION_DOWN) {
      if (isInterfaceRevealKey(keyCode) && canAutoHideTvInterface() && !tvInterfaceRevealed) {
        revealConsumedKeyCode = keyCode;
        revealTvInterface();
        return true;
      }
      if (isInterfaceRevealKey(keyCode) && canAutoHideTvInterface()) scheduleTvInterfaceHide();
      if (isAppbarNavigationKey(keyCode)) layout.armNavigationSound();
    }
    return super.dispatchKeyEvent(event);
  }

  /**
   * Only remote navigation may reveal ambient chrome. Keep volume, power and app keys with the TV.
   */
  private static boolean isInterfaceRevealKey(int keyCode) {
    switch (keyCode) {
      case KeyEvent.KEYCODE_DPAD_UP:
      case KeyEvent.KEYCODE_DPAD_DOWN:
      case KeyEvent.KEYCODE_DPAD_LEFT:
      case KeyEvent.KEYCODE_DPAD_RIGHT:
      case KeyEvent.KEYCODE_DPAD_CENTER:
      case KeyEvent.KEYCODE_ENTER:
      case KeyEvent.KEYCODE_NUMPAD_ENTER:
      case KeyEvent.KEYCODE_BUTTON_A:
      case KeyEvent.KEYCODE_BUTTON_B:
      case KeyEvent.KEYCODE_BACK:
        return true;
      default:
        return false;
    }
  }

  /** Only directional focus moves get launcher feedback; activation and overlay keys remain silent. */
  private static boolean isAppbarNavigationKey(int keyCode) {
    return keyCode == KeyEvent.KEYCODE_DPAD_UP
        || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
        || keyCode == KeyEvent.KEYCODE_DPAD_LEFT
        || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
  }

  @Override
  public boolean isMovieHostForeground() {
    return foreground && screenAwake;
  }

  @Override
  public void suspendAmbientForMovie() {
    // A receiver-started movie can arrive while the radio overlay is up.
    if (radio != null) radio.onHostStopped();
    ambientSuspendedForRadio = false;
    ambientSuspendedForMovie = true;
    main.removeCallbacks(rotate);
    releaseVideo(true);
    weather.stop();
    updateLauncherInterfaceVisibility();
  }

  @Override
  public void onMovieStopped() {
    if (movieLibrary != null) movieLibrary.playbackStopped();
  }

  @Override
  public void suspendAmbientForRadio() {
    ambientSuspendedForRadio = true;
    main.removeCallbacks(rotate);
    // Preserve ambient media in a paused state for restoration after the opaque Radio screen.
    // Radio owns audio; ambient playback must remain silent, including pending preparation.
    if (player != null) {
      try {
        player.setVolume(0f, 0f);
        if ("playing".equals(videoState)) {
          player.pause();
          videoState = "paused";
        }
        player.setScreenOnWhilePlaying(false);
      } catch (IllegalStateException ignored) {}
    }
    weather.stop();
    updateLauncherInterfaceVisibility();
  }

  @Override
  public void restoreAmbientAfterRadio() {
    ambientSuspendedForRadio = false;
    if (!foreground || !screenAwake || ambientSuspendedForMovie) return;
    wallpaper.setFitMode(playlist != null && "fit".equals(playlist.fitMode));
    updateVideoPresentation();
    if (playlist != null) weather.setConfig(playlist.weather);
    weatherWidget.setWeather(weather);
    weather.start();
    boolean retained = false;
    if (playlist != null && activeMedia != null) {
      for (PlaylistStore.Item item : playlist.images) {
        if (item.id.equals(activeMedia.id)) { retained = true; break; }
      }
    }
    if (retained && player != null && "paused".equals(videoState)) {
      try {
        float volume = playlist.muteVideos ? 0f : 1f;
        player.setVolume(volume, volume);
        player.setLooping("loop".equals(playlist.videoPlayback));
        if (playlist.slideshowEnabled) {
          player.setScreenOnWhilePlaying(true);
          player.start();
          videoState = "playing";
        }
      } catch (IllegalStateException ignored) { retained = false; }
    }
    if (!retained) {
      releaseVideo(true);
      activeMedia = null;
      if (playlist != null && playlist.slideshowEnabled) advanceWallpaper();
      else showPausedInitialMedia();
    }
    scheduleRotation();
    updateLauncherInterfaceVisibility();
  }

  @Override
  public boolean isRadioHostForeground() {
    return foreground && screenAwake && !ambientSuspendedForMovie;
  }

  /** Shared, background-only sampling source for launcher and Radio glass treatments. */
  FrostedBackdrop getFrostedBackdrop() { return frostedBackdrop; }

  @Override
  public void restoreAmbientAfterMovie() {
    ambientSuspendedForMovie = false;
    if (!foreground || !screenAwake) return;
    wallpaper.setFitMode(playlist != null && "fit".equals(playlist.fitMode));
    updateVideoPresentation();
    if (playlist != null) weather.setConfig(playlist.weather);
    weatherWidget.setWeather(weather);
    weather.start();
    if (playlist != null && playlist.slideshowEnabled) advanceWallpaper();
    else showPausedInitialMedia();
    scheduleRotation();
    updateLauncherInterfaceVisibility();
  }

  private boolean hasActiveAmbientMedia() {
    return activeMedia != null;
  }

  private boolean canAutoHideTvInterface() {
    return TvInterfaceVisibility.shouldHide(
        playlist != null && playlist.showTVInterface,
        playlist != null && playlist.slideshowEnabled,
        hasActiveAmbientMedia(),
        ambientSuspendedForMovie || ambientSuspendedForRadio || (movieLibrary != null && movieLibrary.isOpen()),
        launcherDialogCount > 0,
        foreground,
        screenAwake,
        false);
  }

  private void revealTvInterface() {
    tvInterfaceRevealed = true;
    updateLauncherInterfaceVisibility();
  }

  private void scheduleTvInterfaceHide() {
    main.removeCallbacks(hideTvInterface);
    if (canAutoHideTvInterface() && tvInterfaceRevealed) main.postDelayed(hideTvInterface, 8_000L);
  }

  private void updateLauncherInterfaceVisibility() {
    boolean hidden =
        TvInterfaceVisibility.shouldHide(
            playlist != null && playlist.showTVInterface,
            playlist != null && playlist.slideshowEnabled,
            hasActiveAmbientMedia(),
            ambientSuspendedForMovie || ambientSuspendedForRadio || (movieLibrary != null && movieLibrary.isOpen()),
            launcherDialogCount > 0,
            foreground,
            screenAwake,
            tvInterfaceRevealed);
    if (layout != null) layout.setChromeVisible(!hidden && !ambientSuspendedForRadio);
    if (weatherWidget != null)
      weatherWidget.setInterfaceVisible(!hidden && !ambientSuspendedForMovie && !ambientSuspendedForRadio);
    main.removeCallbacks(hideTvInterface);
    if (!hidden && canAutoHideTvInterface() && tvInterfaceRevealed)
      main.postDelayed(hideTvInterface, 8_000L);
  }

  @Override
  public JSONObject movieStatus() throws MovieController.MovieRequestException {
    if (movies == null)
      throw new MovieController.MovieRequestException(
          503, "unsupported", "Movie playback is unavailable");
    return movies.status();
  }

  @Override
  public JSONObject playMovie(JSONObject request, InetAddress peer)
      throws MovieController.MovieRequestException {
    if (!isMovieHostForeground())
      throw new MovieController.MovieRequestException(
          503, "unsupported", "Open Glass TV to control movie playback");
    if (movies == null)
      throw new MovieController.MovieRequestException(
          503, "unsupported", "Movie playback is unavailable");
    JSONObject response = movies.play(request, peer);
    if (movieLibrary != null) movieLibrary.receivedPlaybackStart(request.optString("movieId", ""));
    return response;
  }

  @Override
  public JSONObject controlMovie(JSONObject request) throws MovieController.MovieRequestException {
    if (!isMovieHostForeground())
      throw new MovieController.MovieRequestException(
          503, "unsupported", "Open Glass TV to control movie playback");
    if (movies == null)
      throw new MovieController.MovieRequestException(
          503, "unsupported", "Movie playback is unavailable");
    return movies.control(request);
  }

  private void scheduleRotation() {
    main.removeCallbacks(rotate);
    if (!ambientSuspendedForMovie
        && !ambientSuspendedForRadio
        && foreground
        && playlist != null
        && playlist.slideshowEnabled
        && !playlist.images.isEmpty()
        && currentItemIsPhoto()) {
      main.postDelayed(
          rotate, Math.max(PlaylistStore.MIN_INTERVAL, playlist.intervalSeconds) * 1000L);
    }
  }

  private void advanceWallpaper() {
    if (ambientSuspendedForMovie || ambientSuspendedForRadio || !foreground || playlist == null || !playlist.slideshowEnabled)
      return;
    final PlaylistStore.Item item = nextItem();
    if (item != null && item.isVideo()) {
      playVideo(item);
      return;
    }
    releaseVideo(true);
    showPhoto(item, true);
  }

  /** Draws the selected item once while a slideshow is paused, without scheduling rotation. */
  private void showPausedInitialMedia() {
    if (ambientSuspendedForMovie
        || ambientSuspendedForRadio
        || !foreground
        || playlist == null
        || playlist.slideshowEnabled
        || activeMedia != null) return;
    PlaylistStore.Item item = nextItem();
    if (item != null && item.isVideo()) {
      playVideo(item);
    } else {
      releaseVideo(true);
      showPhoto(item, false);
    }
  }

  private void showPhoto(final PlaylistStore.Item item, final boolean animate) {
    activeMedia = item;
    videoState = "idle";
    final String imageId = item == null ? null : item.id;
    final boolean useDefaultBackground = playlist == null || playlist.defaultBackgroundEnabled;
    final int generation = decodeGeneration.incrementAndGet();
    decode.execute(
        new Runnable() {
          public void run() {
            final Bitmap decoded =
                imageId == null ? (useDefaultBackground ? decodeInitial() : blankWallpaper()) : decodePhoto(server.photoFile(imageId));
            if (decoded != null)
              main.post(
                  new Runnable() {
                    public void run() {
                      if (foreground && generation == decodeGeneration.get()) {
                        wallpaper.setBitmap(decoded, animate);
                        if (frostedBackdrop != null)
                          frostedBackdrop.setWallpaperSource(
                              decoded, playlist != null && "fit".equals(playlist.fitMode));
                        updateLauncherInterfaceVisibility();
                      } else decoded.recycle();
                    }
                  });
          }
        });
  }

  private PlaylistStore.Item lastItem;

  private PlaylistStore.Item nextItem() {
    if (playlist == null || playlist.images.isEmpty()) {
      lastItem = null;
      return null;
    }
    if ("loop".equals(playlist.videoPlayback) && playlist.loopVideoId != null)
      for (PlaylistStore.Item item : playlist.images)
        if (item.isVideo()
            && playlist.loopVideoId.equals(item.id)
            && !failedVideos.contains(item.id)) {
          lastItem = item;
          return item;
        }
    int count = playlist.images.size();
    if (playlist.shuffle) {
      ArrayList<PlaylistStore.Item> playable = new ArrayList<>();
      for (PlaylistStore.Item item : playlist.images)
        if (!item.isVideo() || !failedVideos.contains(item.id)) playable.add(item);
      if (!playable.isEmpty()) {
        lastItem = playable.get(random.nextInt(playable.size()));
        return lastItem;
      }
    } else
      for (int attempt = 0; attempt < count; attempt++) {
        PlaylistStore.Item item = playlist.images.get(rotationIndex++ % count);
        if (!item.isVideo() || !failedVideos.contains(item.id)) {
          lastItem = item;
          return item;
        }
      }
    lastItem = null;
    return null;
  }

  private boolean currentItemIsPhoto() {
    return lastItem == null || !lastItem.isVideo();
  }

  private void playVideo(final PlaylistStore.Item item) {
    if (playlist == null || item == null) return;
    main.removeCallbacks(rotate);
    releaseVideo(false);
    activeMedia = item;
    videoState = "preparing";
    final int generation = ++playbackGeneration;
    pendingVideo = item;
    // A photo hides the SurfaceView. Do not bind to its old, soon-to-be-destroyed surface.
    videoSurface.setVisibility(View.VISIBLE);
    if (frostedBackdrop != null) frostedBackdrop.setVideoSurface(videoSurface);
    updateVideoPresentation();
    startPendingVideo(videoSurface.getHolder());
  }

  /**
   * Runs on the UI thread from SurfaceHolder callbacks and never treats a not-yet-created surface
   * as a failed file.
   */
  private void startPendingVideo(SurfaceHolder holder) {
    final PlaylistStore.Item item = pendingVideo;
    if (item == null
        || !foreground
        || videoSurface.getVisibility() != View.VISIBLE
        || !surfaceReady
        || surfaceGeneration < requiredSurfaceGeneration
        || !holder.getSurface().isValid()) return;
    final int generation = playbackGeneration;
    pendingVideo = null;
    try {
      final MediaPlayer candidate = new MediaPlayer();
      player = candidate;
      candidate.setDataSource(server.videoFile(item.id).getAbsolutePath());
      candidate.setDisplay(holder);
      candidate.setScreenOnWhilePlaying(true);
      candidate.setLooping("loop".equals(playlist.videoPlayback));
      candidate.setOnVideoSizeChangedListener(
          (ignored, width, height) -> {
            videoWidth = width;
            videoHeight = height;
            updateVideoPresentation();
          });
      if (playlist.muteVideos || ambientSuspendedForRadio) candidate.setVolume(0f, 0f);
      candidate.setOnPreparedListener(
          ready -> {
            if (foreground && generation == playbackGeneration && player == ready) {
              if (playlist != null && playlist.slideshowEnabled
                  && !ambientSuspendedForRadio && !ambientSuspendedForMovie) {
                ready.setVolume(playlist.muteVideos ? 0f : 1f, playlist.muteVideos ? 0f : 1f);
                videoState = "playing";
                ready.setScreenOnWhilePlaying(true);
                ready.start();
                updateLauncherInterfaceVisibility();
                if (frostedBackdrop != null) frostedBackdrop.captureVideoNow();
              } else {
                // Prepared video keeps its first frame available without waking the TV.
                videoState = "paused";
                ready.setScreenOnWhilePlaying(false);
                updateLauncherInterfaceVisibility();
                if (frostedBackdrop != null) frostedBackdrop.captureVideoNow();
              }
            } else {
              ready.release();
            }
          });
      candidate.setOnCompletionListener(
          done -> {
            if (playlist.slideshowEnabled
                && !ambientSuspendedForRadio && !ambientSuspendedForMovie
                && !"loop".equals(playlist.videoPlayback)
                && generation == playbackGeneration
                && player == done) {
              releaseVideo(true);
              advanceWallpaper();
              scheduleRotation();
            }
          });
      candidate.setOnErrorListener(
          (failed, what, extra) -> {
            if (generation == playbackGeneration && player == failed) {
              failVideo(item, "media-error:" + what + "/" + extra);
            }
            return true;
          });
      candidate.prepareAsync();
    } catch (Exception unsupported) {
      if (generation == playbackGeneration) {
        failVideo(item, "prepare-exception:" + unsupported.getClass().getSimpleName());
      }
    }
  }

  private void failVideo(PlaylistStore.Item item, String reason) {
    failedVideos.add(item.id);
    lastVideoFailure = reason;
    lastVideoFailureAtMillis = System.currentTimeMillis();
    android.util.Log.w("GlassTV", "Video playback failed: " + reason);
    videoState = "failed";
    showVideoError();
    releaseVideo(true);
    fallbackFromVideo();
  }

  private void showVideoError() {
    if (foreground)
      Toast.makeText(this, "This video could not be played", Toast.LENGTH_SHORT).show();
  }

  private void fallbackFromVideo() {
    main.post(
        () -> {
          advanceWallpaper();
          scheduleRotation();
        });
  }

  /**
   * A delayed destruction can belong to the photo transition that preceded this request. Keep the
   * pending item and wait for the corresponding new surface instead of binding to it.
   */
  private void onVideoSurfaceDestroyed() {
    surfaceReady = false;
    if (pendingVideo != null && videoSurface.getVisibility() == View.VISIBLE) {
      requiredSurfaceGeneration = surfaceGeneration + 1;
      return;
    }
    releaseVideo(false);
  }

  /**
   * Cancels both a queued SurfaceView start and an already-active decoder. Must run on the main
   * thread.
   */
  private void releaseVideo(boolean hideSurface) {
    pendingVideo = null;
    playbackGeneration++;
    if (player != null) {
      try {
        player.setOnPreparedListener(null);
        player.setOnCompletionListener(null);
        player.setOnErrorListener(null);
        player.setScreenOnWhilePlaying(false);
        player.reset();
        player.release();
      } catch (Exception ignored) {
      }
      player = null;
    }
    if (activeMedia != null && activeMedia.isVideo()) activeMedia = null;
    videoWidth = 0;
    videoHeight = 0;
    if (videoMatte != null) videoMatte.setVisibility(View.GONE);
    if (!"failed".equals(videoState)) videoState = "idle";
    updateLauncherInterfaceVisibility();
    if (hideSurface && videoSurface != null && videoSurface.getVisibility() != View.GONE) {
      // Force the next request to bind only after SurfaceView publishes a new holder.
      surfaceReady = false;
      requiredSurfaceGeneration = surfaceGeneration + 1;
      videoSurface.setVisibility(View.GONE);
    }
    if (frostedBackdrop != null) frostedBackdrop.useWallpaperSource();
  }

  private static Bitmap blankWallpaper() {
    Bitmap bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
    bitmap.eraseColor(Color.BLACK);
    return bitmap;
  }

  private Bitmap decodeInitial() {
    try (InputStream in = getAssets().open("wallpaper.jpg")) {
      return decodeBounded(in);
    } catch (Exception e) {
      return null;
    }
  }

  private Bitmap decodePhoto(File file) {
    try (InputStream in = new java.io.FileInputStream(file)) {
      return decodeBounded(in);
    } catch (Exception e) {
      return null;
    }
  }

  private static Bitmap decodeBounded(InputStream in) throws Exception {
    byte[] data = readAll(in);
    BitmapFactory.Options bounds = new BitmapFactory.Options();
    bounds.inJustDecodeBounds = true;
    BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
    int sample = 1;
    while (bounds.outWidth / sample > 1920 || bounds.outHeight / sample > 1080) sample *= 2;
    BitmapFactory.Options options = new BitmapFactory.Options();
    options.inSampleSize = sample;
    options.inPreferredConfig = Bitmap.Config.RGB_565;
    return BitmapFactory.decodeByteArray(data, 0, data.length, options);
  }

  private static byte[] readAll(InputStream in) throws Exception {
    java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
    byte[] a = new byte[32768];
    int n;
    while ((n = in.read(a)) > 0) b.write(a, 0, n);
    return b.toByteArray();
  }

  private void launchNetflix() {
    launchOptionalApp("com.netflix.ninja", "Netflix");
  }

  private void launchOptionalApp(String packageName, String label) {
    new AppShortcutLauncher(this, this::showLauncherDialog).open(packageName, label);
  }

  private void launchSwitch() {
    launchInput(
        playlist != null && playlist.selectedInputId != null ? playlist.selectedInputId : SONY_HW4);
  }

  private void openMovieLibrary() {
    revealTvInterface();
    if (movieLibrary != null) movieLibrary.openBrowser();
  }

  private void openRadio() {
    if (radio != null) radio.open();
  }

  private void launchInput(String input) {
    Uri channel = TvContract.buildChannelUriForPassthroughInput(input);
    Intent sony =
        new Intent(Intent.ACTION_VIEW)
            .setDataAndType(channel, TvContract.Channels.CONTENT_ITEM_TYPE)
            .setPackage("com.sony.dtv.tvx");
    try {
      startActivity(sony);
    } catch (Exception noSony) {
      try {
        startActivity(
            new Intent(Intent.ACTION_VIEW)
                .setDataAndType(channel, TvContract.Channels.CONTENT_ITEM_TYPE));
      } catch (Exception ignored) {
      }
    }
  }

  private void showLauncherDialog(AlertDialog dialog) {
    showLauncherDialog(dialog, null);
  }

  private void showLauncherDialog(AlertDialog dialog, Runnable onDismiss) {
    launcherDialogCount++;
    revealTvInterface();
    dialog.setOnDismissListener(
        ignored -> {
          launcherDialogCount = Math.max(0, launcherDialogCount - 1);
          updateLauncherInterfaceVisibility();
          if (onDismiss != null) onDismiss.run();
        });
    dialog.show();
  }

  private void showSettingsMenu() {
    if (tvSettings != null) tvSettings.showRoot();
  }

  private void updateStandaloneSettings(
      PlaylistStore.SettingsMutation mutation, TvSettingsController.Host.SaveResult result) {
    settingsWork.execute(
        () -> {
          boolean saved = false;
          PlaylistStore.Playlist updated = null;
          try {
            if (server != null) {
              updated = server.updateSettings(mutation);
              saved = true;
            }
          } catch (Exception unavailable) {
            android.util.Log.w("GlassTV", "Could not save standalone settings", unavailable);
          }
          final boolean completed = saved;
          final PlaylistStore.Playlist committed = updated;
          main.post(
              () -> {
                if (completed && committed != null) onPlaylistChanged(committed);
                result.complete(completed);
              });
        });
  }

  /** The launcher never reads or changes networks; Android owns its native Wi-Fi settings flow. */
  private void openSystemWifiSettings() {
    try {
      startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
    } catch (Exception unavailable) {
      startActivity(new Intent(Settings.ACTION_SETTINGS));
    }
  }

  private void openPrivacyPolicy() {
    try {
      startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://glasstv.xyz/privacy")));
    } catch (Exception unavailable) {
      showLauncherDialog(
          new AlertDialog.Builder(this)
              .setTitle("Privacy policy unavailable")
              .setMessage("A browser is not available on this TV.")
              .setPositiveButton("Close", null)
              .create());
    }
  }

  private void showPairing() {
    if (!ReceiverStartupPolicy.isReceiverEnabled(this)) {
      showLauncherDialog(
          new AlertDialog.Builder(this)
              .setTitle("Mac companion is stopped")
              .setMessage("Start the Mac companion receiver in Glass TV settings before pairing.")
              .setPositiveButton("Close", null)
              .create());
      return;
    }
    if (server == null) return;
    try {
      String code = server.openPairing();
      android.widget.LinearLayout content = new android.widget.LinearLayout(this);
      content.setOrientation(android.widget.LinearLayout.VERTICAL);
      int pad = (int) (24 * getResources().getDisplayMetrics().density);
      content.setPadding(pad, pad, pad, pad);
      TextView address = new TextView(this);
      address.setText("Connect your Mac to " + localIpv4Address() + ":8743");
      address.setTextColor(Color.WHITE);
      address.setContentDescription("pairing-address");
      content.addView(address);
      TextView instruction = new TextView(this);
      instruction.setText("Enter this code in Glass TV for Mac within 2 minutes");
      instruction.setTextColor(Color.WHITE);
      content.addView(instruction);
      TextView codeView = new TextView(this);
      codeView.setText(code);
      codeView.setTextSize(32);
      codeView.setTextColor(Color.WHITE);
      codeView.setContentDescription("pairing-code");
      content.addView(codeView);
      TextView label = new TextView(this);
      label.setText("TLS fingerprint");
      label.setTextColor(Color.WHITE);
      content.addView(label);
      TextView fingerprintView = new TextView(this);
      fingerprintView.setText(server.fingerprint());
      fingerprintView.setTextSize(13);
      fingerprintView.setTextColor(Color.WHITE);
      fingerprintView.setContentDescription("certificate-fingerprint");
      content.addView(fingerprintView);
      showLauncherDialog(
          new AlertDialog.Builder(this)
              .setTitle("Pair a Mac")
              .setView(content)
              .setPositiveButton("Close", null)
              .create());
    } catch (Exception e) {
      showLauncherDialog(
          new AlertDialog.Builder(this)
              .setTitle("Pairing unavailable")
              .setMessage("The local TLS identity could not be created.")
              .setPositiveButton("Close", null)
              .create());
    }
  }

  private void showInputPicker() {
    final List<TvInputInfo> inputs = passthroughInputs();
    String[] labels = new String[inputs.size()];
    for (int i = 0; i < inputs.size(); i++)
      labels[i] = String.valueOf(inputs.get(i).loadLabel(this));
    showLauncherDialog(
        new AlertDialog.Builder(this)
            .setTitle("TV input")
            .setItems(
                labels,
                (d, which) -> {
                  String id = inputs.get(which).getId();
                  selectInputLocally(id);
                  launchInput(id);
                })
            .setNegativeButton("Cancel", null)
            .create());
  }

  private void selectInputLocally(String id) {
    try {
      onPlaylistChanged(server.selectInput(id));
    } catch (Exception ignored) {
    }
  }

  private void showWeatherLocations() {
    List<WeatherController.Location> locations = weather.locations();
    if (locations.isEmpty()) return;
    String[] labels = new String[locations.size()];
    for (int i = 0; i < locations.size(); i++) labels[i] = locations.get(i).label();
    showLauncherDialog(
        new AlertDialog.Builder(this)
            .setTitle("Weather location")
            .setItems(labels, (dialog, which) -> weather.select(locations.get(which).id))
            .create());
  }

  private boolean googleHomeAvailable() {
    try {
      android.content.pm.ApplicationInfo info =
          getPackageManager().getApplicationInfo("com.google.android.apps.tv.launcherx", 0);
      return info.enabled;
    } catch (Exception unavailable) {
      return false;
    }
  }

  private void openGoogleHome() {
    Intent i =
        new Intent()
            .setComponent(
                new ComponentName(
                    "com.google.android.apps.tv.launcherx",
                    "com.google.android.apps.tv.launcherx.home.HomeActivity"));
    try {
      startActivity(i);
    } catch (Exception noGoogle) {
      try {
        startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME));
      } catch (Exception ignored) {
      }
    }
  }

  private String localIpv4Address() {
    try {
      Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
      while (interfaces.hasMoreElements()) {
        NetworkInterface network = interfaces.nextElement();
        if (!network.isUp() || network.isLoopback()) continue;
        Enumeration<InetAddress> addresses = network.getInetAddresses();
        while (addresses.hasMoreElements()) {
          InetAddress address = addresses.nextElement();
          if (address instanceof Inet4Address && address.isSiteLocalAddress())
            return address.getHostAddress();
        }
      }
    } catch (Exception ignored) {
    }
    return "TV local address";
  }

  @Override
  public JSONObject status() throws Exception {
    JSONObject root = ReceiverStatus.base(this, server);
    root.put("playback", playbackSnapshot());
    root.put("radio", radio == null ? new JSONObject() : radio.statusSnapshot());
    root.put("weatherStatus", weather.statusSnapshot());
    return root;
  }

  @Override
  public boolean isPassthroughInput(String id) {
    return ReceiverStatus.isPassthroughInput(this, id);
  }

  @Override
  public void onPlaylistChanged(final PlaylistStore.Playlist value) {
    final boolean wasEnabled = playlist == null || playlist.slideshowEnabled;
    playlist = value;
    main.post(
        new Runnable() {
          @Override
          public void run() {
            if (!launcherInitialized || !foreground) return;
            // Shortcut visibility is independent of ambient playback and must update even beneath
            // an overlay.
            layout.setApps(value.apps);
            layout.setLayout(value.layout);
            if (!value.apps.optBoolean("radioEnabled", true) && radio != null && radio.isOpen()) {
              radio.onHostStopped();
              ambientSuspendedForRadio = false;
            }
            if (ambientSuspendedForMovie || ambientSuspendedForRadio) {
              updateLauncherInterfaceVisibility();
              // Keep the latest playlist for restoration, but do not start an ambient decoder
              // behind a movie.
              return;
            }
            wallpaper.setFitMode("fit".equals(value.fitMode));
            updateVideoPresentation();
            failedVideos.clear();
            lastVideoFailure = null;
            lastVideoFailureAtMillis = 0;
            weather.setConfig(value.weather);
            rotationIndex = 0;
            updateLauncherInterfaceVisibility();
            if (!value.slideshowEnabled) {
              main.removeCallbacks(rotate);
              if (player != null
                  && activeMedia != null
                  && activeMedia.isVideo()
                  && "playing".equals(videoState)) {
                try {
                  player.pause();
                  player.setScreenOnWhilePlaying(false);
                  videoState = "paused";
                } catch (Exception ignored) {
                }
              }
              showPausedInitialMedia();
              updateLauncherInterfaceVisibility();
              return;
            }
            if (!wasEnabled) {
              if (player != null
                  && activeMedia != null
                  && activeMedia.isVideo()
                  && BackgroundPlaybackPolicy.canKeepVideo(value, activeMedia.id)
                  && "paused".equals(videoState)) {
                try {
                  player.setScreenOnWhilePlaying(true);
                  player.start();
                  videoState = "playing";
                } catch (Exception ignored) {
                  advanceWallpaper();
                }
              } else {
                advanceWallpaper();
              }
            } else if (reuseActiveVideo(value)) {
              // The completion listener reads the new playlist and advances to the next item.
            } else {
              advanceWallpaper();
            }
            scheduleRotation();
            updateLauncherInterfaceVisibility();
          }
        });
  }

  private boolean reuseActiveVideo(PlaylistStore.Playlist value) {
    if (player == null
        || activeMedia == null
        || !activeMedia.isVideo()
        || !BackgroundPlaybackPolicy.canKeepVideo(value, activeMedia.id)
        || !"playing".equals(videoState)) return false;
    int current = -1;
    for (int i = 0; i < value.images.size(); i++)
      if (activeMedia.id.equals(value.images.get(i).id)) {
        current = i;
        break;
      }
    if (current < 0) return false;
    try {
      player.setLooping("loop".equals(value.videoPlayback));
      if (value.muteVideos) player.setVolume(0f, 0f);
      else player.setVolume(1f, 1f);
      rotationIndex = (current + 1) % Math.max(1, value.images.size());
      return true;
    } catch (Exception ignored) {
      return false;
    }
  }

  private void updateVideoPresentation() {
    if (videoSurface == null || layout == null || playlist == null) return;
    boolean fit = "fit".equals(playlist.fitMode) && videoWidth > 0 && videoHeight > 0;
    if (videoMatte != null)
      videoMatte.setVisibility(
          fit && videoSurface.getVisibility() == View.VISIBLE ? View.VISIBLE : View.GONE);
    layout.requestLayout();
  }

  private void videoBounds(int screenWidth, int screenHeight, Rect out) {
    if (videoWidth <= 0 || videoHeight <= 0) {
      out.set(0, 0, screenWidth, screenHeight);
      return;
    }
    boolean fit = playlist != null && "fit".equals(playlist.fitMode);
    float scale =
        fit
            ? Math.min(screenWidth / (float) videoWidth, screenHeight / (float) videoHeight)
            : Math.max(screenWidth / (float) videoWidth, screenHeight / (float) videoHeight);
    int width = Math.round(videoWidth * scale);
    int height = Math.round(videoHeight * scale);
    int left = (screenWidth - width) / 2;
    int top = (screenHeight - height) / 2;
    out.set(left, top, left + width, top + height);
  }

  /**
   * MediaPlayer is thread-affine. Status calls arrive from the TLS worker, so read it on main with
   * a short bound.
   */
  private JSONObject playbackSnapshot() {
    if (Looper.myLooper() == Looper.getMainLooper()) return buildPlaybackSnapshot();
    AtomicReference<JSONObject> result = new AtomicReference<>();
    CountDownLatch complete = new CountDownLatch(1);
    main.post(
        () -> {
          result.set(buildPlaybackSnapshot());
          complete.countDown();
        });
    try {
      if (complete.await(150, TimeUnit.MILLISECONDS) && result.get() != null) return result.get();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
    try {
      return new JSONObject().put("state", "idle");
    } catch (Exception impossible) {
      return new JSONObject();
    }
  }

  private JSONObject buildPlaybackSnapshot() {
    try {
      JSONObject playback = new JSONObject();
      playback.put("activeMediaId", activeMedia == null ? JSONObject.NULL : activeMedia.id);
      playback.put("activeMediaKind", activeMedia == null ? JSONObject.NULL : activeMedia.kind);
      playback.put("state", videoState);
      playback.put("currentPositionMs", JSONObject.NULL);
      playback.put("durationMs", JSONObject.NULL);
      playback.put("isLooping", false);
      playback.put("videoWidth", JSONObject.NULL);
      playback.put("videoHeight", JSONObject.NULL);
      playback.put("pendingMediaId", pendingVideo == null ? JSONObject.NULL : pendingVideo.id);
      playback.put(
          "surfaceVisible", videoSurface != null && videoSurface.getVisibility() == View.VISIBLE);
      playback.put("surfaceReady", surfaceReady);
      playback.put("surfaceGeneration", surfaceGeneration);
      playback.put("requiredSurfaceGeneration", requiredSurfaceGeneration);
      playback.put("generation", playbackGeneration);
      playback.put("failedVideoIds", new JSONArray(failedVideos));
      playback.put("lastFailure", lastVideoFailure == null ? JSONObject.NULL : lastVideoFailure);
      playback.put(
          "lastFailureAtMillis",
          lastVideoFailureAtMillis == 0 ? JSONObject.NULL : lastVideoFailureAtMillis);
      if (player != null && ("playing".equals(videoState) || "paused".equals(videoState))) {
        try {
          playback.put("currentPositionMs", player.getCurrentPosition());
        } catch (Exception ignored) {
        }
        try {
          playback.put("durationMs", player.getDuration());
        } catch (Exception ignored) {
        }
        try {
          playback.put("isLooping", player.isLooping());
        } catch (Exception ignored) {
        }
        try {
          playback.put("videoWidth", player.getVideoWidth());
        } catch (Exception ignored) {
        }
        try {
          playback.put("videoHeight", player.getVideoHeight());
        } catch (Exception ignored) {
        }
      }
      return playback;
    } catch (Exception impossible) {
      return new JSONObject();
    }
  }

  private List<TvInputInfo> passthroughInputs() {
    return ReceiverStatus.passthroughInputs(this);
  }

  private static final class GlassLayout extends FrameLayout {
    private static final long NAVIGATION_GESTURE_WINDOW_MS = 350L;
    private static final Interpolator EASE_IN_OUT = new AccelerateDecelerateInterpolator();
    private final TextView clock, settings, wifi, previousPage, nextPage;
    private final ArrayList<LauncherShortcut> shortcuts = new ArrayList<>();
    /** The receiver's legacy shortcuts followed by the order from the bundled service catalog. */
    private final ArrayList<String> canonicalShortcutIds = new ArrayList<>();
    private final Handler clockHandler = new Handler();
    private final Paint nextShortcutPeekFade = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final MainActivity owner;
    private final NavigationSound navigationSound;
    private boolean chromeVisible = true;
    private boolean wifiEnabled = true;
    private float clockScale = 1f;
    private String hdmiStyle = "switch";
    private String shortcutStyle = ShortcutRow.MINIMAL;
    private float appIconScale = 1f;
    private float appSpacingScale = 1f;
    private int activeShortcutPage;
    private int carouselTransition;
    private boolean navigationSoundArmed;
    private long navigationSoundArmedAtMillis;
    private boolean shortcutPeekDrawn;

    private final class LauncherShortcut {
      final String id;
      final TextView button, label;
      final String minimalIcon, colorIcon, minimalFallback, colorFallback, colorBackground;
      final Runnable action;
      boolean enabled;

      LauncherShortcut(
          String id,
          String title,
          String minimalIcon,
          String colorIcon,
          String minimalFallback,
          String colorFallback,
          String colorBackground,
          Runnable action) {
        this.id = id;
        this.minimalIcon = minimalIcon;
        this.colorIcon = colorIcon;
        this.minimalFallback = minimalFallback;
        this.colorFallback = colorFallback;
        this.colorBackground = colorBackground;
        this.action = action;
        button = capsule(owner, "", title);
        // Reserve the selected extent in the view itself. Some TV renderers clip transformed
        // TextView display lists at their original lower edge, which would cut a scaled ring.
        button.setScaleX(shortcutUnfocusedScale());
        button.setScaleY(shortcutUnfocusedScale());
        label = label(owner, title);
        button.setId(View.generateViewId());
        button.setOnClickListener(ignored -> action.run());
        button.setOnFocusChangeListener(
            (view, hasFocus) -> {
              if (view.getBackground() instanceof CapsuleBackground)
                ((CapsuleBackground) view.getBackground()).setFocused(hasFocus);
              animateFocus(
                  view,
                  hasFocus,
                  1f,
                  shortcutFocusedContainerScale(this),
                  shortcutUnfocusedScale());
              if (hasFocus) playNavigationSoundForFocus();
            });
        button.setOnKeyListener(
            (view, keyCode, event) -> {
              if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
              if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) return moveShortcutPage(this, 1);
              if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return moveShortcutPage(this, -1);
              return false;
            });
        addView(button);
        addView(label);
      }

      void setTitle(String title) {
        button.setContentDescription(title);
        label.setText(title);
      }

      void updateBackground() {
        button.setBackground(shortcutBackground(this));
      }
    }

    GlassLayout(final MainActivity activity) {
      super(activity);
      owner = activity;
      navigationSound = new NavigationSound(activity);
      setWillNotDraw(false);
      // Focused tile scale and its outline may extend beyond its original bounds.
      setClipChildren(false);
      setClipToPadding(false);
      clock = text(activity, 56);
      settings = icon(activity, "settings");
      wifi = icon(activity, "wifi");
      previousPage = carouselArrow(activity, "arrow_left", "Previous shortcuts");
      nextPage = carouselArrow(activity, "arrow_right", "More shortcuts");
      clock.setTypeface(android.graphics.Typeface.create("sans-serif-thin", 0));
      addView(clock);
      addView(settings);
      addView(wifi);
      addView(previousPage);
      addView(nextPage);
      settings.setContentDescription("Settings");
      settings.setOnClickListener(v -> activity.showSettingsMenu());
      wifi.setContentDescription("Wi-Fi settings");
      wifi.setOnClickListener(v -> activity.openSystemWifiSettings());
      previousPage.setOnClickListener(
          ignored -> {
            armNavigationSound();
            changeShortcutPage(activeShortcutPage - 1, -1, null);
          });
      nextPage.setOnClickListener(
          ignored -> {
            armNavigationSound();
            changeShortcutPage(activeShortcutPage + 1, 1, null);
          });
      addBuiltInShortcuts();
      addCatalogShortcuts();
      View.OnFocusChangeListener focus =
          (view, hasFocus) -> {
            if (view.getBackground() instanceof IconBackground)
              ((IconBackground) view.getBackground()).setFocused(hasFocus);
            animateFocus(view, hasFocus, 1f, 1f);
            if (hasFocus) playNavigationSoundForFocus();
          };
      settings.setOnFocusChangeListener(focus);
      wifi.setOnFocusChangeListener(focus);
      View.OnFocusChangeListener carouselFocus =
          (view, hasFocus) -> {
            if (view.getBackground() instanceof CapsuleBackground)
              ((CapsuleBackground) view.getBackground()).setFocused(hasFocus);
            animateFocus(view, hasFocus, hasFocus ? 1f : .72f, 1f);
            if (hasFocus) playNavigationSoundForFocus();
          };
      previousPage.setOnFocusChangeListener(carouselFocus);
      nextPage.setOnFocusChangeListener(carouselFocus);
      post(() -> firstShortcutOrSettings().requestFocus());
    }

    private void addBuiltInShortcuts() {
      addShortcut("netflix", "Netflix", "brand_netflix", "brand_netflix_color", "netflix_logo", "netflix_logo", "#101010", owner::launchNetflix);
      addShortcut("radio", "Radio", "radio", "radio", "radio", "radio", "#20252B", owner::openRadio);
      addShortcut("prime_video", "Prime Video", "brand_prime_video", "brand_prime_video_color", "prime_video_logo", "prime_video_logo", "#101010", () -> owner.launchOptionalApp(PRIME_VIDEO_PACKAGE, "Prime Video"));
      addShortcut("spotify", "Spotify", "brand_spotify", "brand_spotify_color", "spotify_logo", "spotify_logo", "#101010", () -> owner.launchOptionalApp(SPOTIFY_PACKAGE, "Spotify"));
      addShortcut("twitch", "Twitch", "brand_twitch", "brand_twitch_color", "twitch_logo", "twitch_logo", "#101010", () -> owner.launchOptionalApp(TWITCH_PACKAGE, "Twitch"));
      addShortcut("hulu", "Hulu", "brand_hulu", "brand_hulu_color", "hulu_logo", "hulu_logo", "#101010", () -> owner.launchOptionalApp(HULU_PACKAGE, "Hulu"));
      addShortcut("movies", "Movies", "movies", "movies", "movies", "movies", "#20252B", owner::openMovieLibrary);
      addShortcut("hdmi", "Nintendo Switch", "switch_logo", "switch_logo", "switch_logo", "switch_logo", "#20252B", owner::launchSwitch);
    }

    private void addCatalogShortcuts() {
      for (AppShortcutCatalog.Service service : AppShortcutCatalog.load(owner)) {
        addShortcut(
            service.id,
            service.title,
            service.minimalIcon,
            service.colorIcon,
            null,
            null,
            service.colorBackground,
            () -> new AppShortcutLauncher(owner, owner::showLauncherDialog).open(service.packages, service.title));
      }
    }

    private void addShortcut(
        String id,
        String title,
        String minimalIcon,
        String colorIcon,
        String minimalFallback,
        String colorFallback,
        String colorBackground,
        Runnable action) {
      shortcuts.add(new LauncherShortcut(id, title, minimalIcon, colorIcon, minimalFallback, colorFallback, colorBackground, action));
      canonicalShortcutIds.add(id);
    }

    void addChrome(MainActivity ignored) {}

    void startClock() {
      ticker();
    }

    void stopClock() {
      clockHandler.removeCallbacksAndMessages(null);
    }

    void setApps(JSONObject apps) {
      clearNavigationSoundGesture();
      String focusedShortcutId = focusedShortcutId();
      navigationSound.configure(apps);
      shortcutStyle = apps.optString("shortcutStyle", ShortcutRow.MINIMAL);
      refreshShortcutFocusScale();
      Set<String> enabledServices = new HashSet<>();
      JSONArray values = apps.optJSONArray("enabledServices");
      if (values != null) for (int i = 0; i < values.length(); i++) enabledServices.add(values.optString(i));
      for (LauncherShortcut shortcut : shortcuts) {
        if (AppShortcutCatalog.NEW_SERVICE_IDS.contains(shortcut.id)) {
          shortcut.enabled = enabledServices.contains(shortcut.id);
        } else if ("netflix".equals(shortcut.id)) {
          shortcut.enabled = apps.optBoolean("netflixEnabled", true);
        } else if ("radio".equals(shortcut.id)) {
          shortcut.enabled = apps.optBoolean("radioEnabled", true);
        } else if ("hdmi".equals(shortcut.id)) {
          shortcut.enabled = apps.optBoolean("hdmiEnabled", true);
        } else if ("movies".equals(shortcut.id)) {
          shortcut.enabled = apps.optBoolean("moviesEnabled", false);
        } else if ("prime_video".equals(shortcut.id)) {
          shortcut.enabled = apps.optBoolean("primeVideoEnabled", false);
        } else {
          shortcut.enabled = apps.optBoolean(shortcut.id + "Enabled", false);
        }
        shortcut.updateBackground();
      }
      if (owner.movieLibrary != null) owner.movieLibrary.setEnabled(apps.optBoolean("moviesEnabled", false));
      hdmiStyle = apps.optString("hdmiStyle", "switch");
      String label =
          "switch".equals(hdmiStyle) ? "Nintendo Switch" : "ps5".equals(hdmiStyle) ? "PS5" : "HDMI";
      for (LauncherShortcut shortcut : shortcuts)
        if ("hdmi".equals(shortcut.id)) {
          shortcut.setTitle(label);
          shortcut.button.setBackground(shortcutBackground(shortcut));
        }
      applyShortcutOrder(apps.optJSONArray("shortcutOrder"));
      applyShortcutVisibility();
      requestLayout();
      post(
          () -> {
            if (!restoreShortcutFocus(focusedShortcutId)) {
              View focused = findFocus();
              if (focused == null || focused.getVisibility() != VISIBLE)
                firstShortcutOrSettings().requestFocus();
            }
          });
    }

    void setLayout(JSONObject value) {
      clearNavigationSoundGesture();
      String focusedShortcutId = focusedShortcutId();
      wifiEnabled = LauncherLayout.wifiEnabled(value);
      clockScale = LauncherLayout.clockScale(value);
      appIconScale = LauncherLayout.appIconScale(value);
      appSpacingScale = LauncherLayout.effectiveAppSpacingScale(value);
      layoutConfig = LauncherLayout.copy(value);
      refreshShortcutFocusScale();
      applyShortcutVisibility();
      wifi.setVisibility(chromeVisible && wifiEnabled ? VISIBLE : GONE);
      requestLayout();
      post(
          () -> {
            if (!restoreShortcutFocus(focusedShortcutId)) {
              View focused = findFocus();
              if (focused == wifi && wifi.getVisibility() != VISIBLE)
                firstShortcutOrSettings().requestFocus();
            }
          });
    }

    void setChromeVisible(boolean visible) {
      if (chromeVisible == visible) return;
      clearNavigationSoundGesture();
      chromeVisible = visible;
      int controlVisibility = visible ? VISIBLE : GONE;
      clock.setVisibility(controlVisibility);
      settings.setVisibility(controlVisibility);
      wifi.setVisibility(chromeVisible && wifiEnabled ? VISIBLE : GONE);
      applyShortcutVisibility();
      if (!visible) clearFocus();
      else
        post(
            () -> {
              // Retain an existing launcher or movie-overlay focus target when it remains valid.
              if (findFocus() == null) firstShortcutOrSettings().requestFocus();
            });
    }

    private void applyShortcutVisibility() {
      carouselTransition++;
      ArrayList<LauncherShortcut> visible = visibleShortcuts();
      int capacity = shortcutPageCapacity(visible.size());
      int pageCount = pageCount(visible);
      LauncherShortcut focused = shortcutForId(focusedShortcutId());
      int focusedIndex = focused == null ? -1 : visible.indexOf(focused);
      activeShortcutPage =
          focusedIndex >= 0 ? focusedIndex / capacity : Math.min(activeShortcutPage, pageCount - 1);
      int start = activeShortcutPage * capacity;
      int end = Math.min(visible.size(), start + capacity);
      boolean labels = chromeVisible && LauncherLayout.appsShowLabels(layoutConfig);
      for (LauncherShortcut shortcut : shortcuts) {
        cancelCarouselAnimation(shortcut);
        boolean onPage = chromeVisible && visible.subList(start, end).contains(shortcut);
        shortcut.button.setVisibility(onPage ? VISIBLE : GONE);
        shortcut.label.setVisibility(onPage && labels ? VISIBLE : GONE);
        shortcut.button.setAlpha(1f);
        shortcut.label.setAlpha(1f);
        shortcut.button.setTranslationX(0f);
        shortcut.label.setTranslationX(0f);
      }
      updateShortcutFocus();
      updateCarouselArrows(visible);
      invalidate();
    }

    void focusFirstShortcutOrSettings() {
      post(() -> firstShortcutOrSettings().requestFocus());
    }

    private View firstShortcutOrSettings() {
      for (LauncherShortcut shortcut : shortcuts)
        if (shortcut.button.getVisibility() == VISIBLE) return shortcut.button;
      return settings;
    }

    private void updateShortcutFocus() {
      for (LauncherShortcut shortcut : shortcuts) {
        shortcut.button.setNextFocusLeftId(View.NO_ID);
        shortcut.button.setNextFocusRightId(View.NO_ID);
      }
      LauncherShortcut first = null, previous = null;
      for (LauncherShortcut shortcut : visiblePage(visibleShortcuts(), activeShortcutPage)) {
        if (shortcut.button.getVisibility() != VISIBLE) continue;
        if (first == null) first = shortcut;
        if (previous != null) {
          previous.button.setNextFocusRightId(shortcut.button.getId());
          shortcut.button.setNextFocusLeftId(previous.button.getId());
        }
        previous = shortcut;
      }
      if (first != null) {
        first.button.setNextFocusLeftId(previous.button.getId());
        previous.button.setNextFocusRightId(first.button.getId());
      }
    }

    private float shortcutFocusScale() {
      return ShortcutRow.focusedScale(shortcutStyle, appIconScale, appSpacingScale);
    }

    private float shortcutUnfocusedScale() {
      return ShortcutRow.unfocusedContainerScale(shortcutFocusScale());
    }

    /** Page zero's leading tile pivots at the safe edge and grows only into the following gap. */
    private float shortcutFocusedContainerScale(LauncherShortcut shortcut) {
      if (!isLeadingFirstPageShortcut(shortcut)) return 1f;
      return ShortcutRow.leadingFocusedScale(shortcutStyle, appIconScale, appSpacingScale)
          / shortcutFocusScale();
    }

    private boolean isLeadingFirstPageShortcut(LauncherShortcut shortcut) {
      if (activeShortcutPage != 0) return false;
      ArrayList<LauncherShortcut> visible = visibleShortcuts();
      return pageCount(visible) > 1 && !visible.isEmpty() && visible.get(0) == shortcut;
    }

    /** Keep the resting and focused extents inside the configured spacing after a live layout sync. */
    private void refreshShortcutFocusScale() {
      float focusedScale = shortcutFocusScale();
      float unfocusedScale = ShortcutRow.unfocusedContainerScale(focusedScale);
      for (LauncherShortcut shortcut : shortcuts) {
        shortcut.button.animate().cancel();
        // The measured container already includes the focused extent, matching animateFocus().
        float scale =
            shortcut.button.hasFocus() ? shortcutFocusedContainerScale(shortcut) : unfocusedScale;
        shortcut.button.setScaleX(scale);
        shortcut.button.setScaleY(scale);
      }
    }

    private boolean moveShortcutPage(LauncherShortcut current, int direction) {
      ArrayList<LauncherShortcut> visible = visibleShortcuts();
      int index = visible.indexOf(current);
      if (index < 0) return false;
      int target = index + direction;
      if (target < 0 || target >= visible.size()) return true;
      int targetPage = target / shortcutPageCapacity(visible.size());
      if (targetPage != activeShortcutPage) {
        changeShortcutPage(targetPage, direction, visible.get(target));
        return true;
      }
      return false;
    }

    /** Slides one compact page at a time so a long shortcut list keeps its place on the TV. */
    private void changeShortcutPage(int targetPage, int direction, LauncherShortcut focusTarget) {
      ArrayList<LauncherShortcut> visible = visibleShortcuts();
      int pageCount = pageCount(visible);
      if (targetPage < 0 || targetPage >= pageCount || targetPage == activeShortcutPage) return;
      ArrayList<LauncherShortcut> oldPage = visiblePage(visible, activeShortcutPage);
      activeShortcutPage = targetPage;
      ArrayList<LauncherShortcut> newPage = visiblePage(visible, activeShortcutPage);
      final int transition = ++carouselTransition;
      boolean labels = chromeVisible && LauncherLayout.appsShowLabels(layoutConfig);
      float distance = Math.max(1, getWidth()) * .20f * direction;
      for (LauncherShortcut shortcut : shortcuts) {
        if (oldPage.contains(shortcut) || newPage.contains(shortcut)) continue;
        cancelCarouselAnimation(shortcut);
        shortcut.button.setVisibility(GONE);
        shortcut.label.setVisibility(GONE);
        shortcut.button.setAlpha(1f);
        shortcut.label.setAlpha(1f);
        shortcut.button.setTranslationX(0f);
        shortcut.label.setTranslationX(0f);
      }
      for (LauncherShortcut shortcut : newPage) {
        cancelCarouselAnimation(shortcut);
        shortcut.button.setVisibility(VISIBLE);
        shortcut.button.setAlpha(0f);
        shortcut.button.setTranslationX(distance);
        shortcut.button
            .animate()
            .alpha(1f)
            .translationX(0f)
            .setInterpolator(EASE_IN_OUT)
            .setDuration(190)
            .start();
        shortcut.label.setVisibility(labels ? VISIBLE : GONE);
        shortcut.label.setAlpha(0f);
        shortcut.label.setTranslationX(distance);
        shortcut.label
            .animate()
            .alpha(1f)
            .translationX(0f)
            .setInterpolator(EASE_IN_OUT)
            .setDuration(190)
            .start();
      }
      for (LauncherShortcut shortcut : oldPage) {
        if (newPage.contains(shortcut)) continue;
        cancelCarouselAnimation(shortcut);
        shortcut.button.animate().alpha(0f).translationX(-distance).setInterpolator(EASE_IN_OUT).setDuration(170)
            .withEndAction(() -> {
              if (transition != carouselTransition
                  || visiblePage(visibleShortcuts(), activeShortcutPage).contains(shortcut)) return;
              shortcut.button.setVisibility(GONE);
              shortcut.button.setAlpha(1f);
              shortcut.button.setTranslationX(0f);
            }).start();
        shortcut.label.animate().alpha(0f).translationX(-distance).setInterpolator(EASE_IN_OUT).setDuration(170)
            .withEndAction(() -> {
              if (transition != carouselTransition
                  || visiblePage(visibleShortcuts(), activeShortcutPage).contains(shortcut)) return;
              shortcut.label.setVisibility(GONE);
              shortcut.label.setAlpha(1f);
              shortcut.label.setTranslationX(0f);
            }).start();
      }
      updateShortcutFocus();
      updateCarouselArrows(visible);
      requestLayout();
      if (focusTarget != null) post(() -> focusTarget.button.requestFocus());
      else if (!newPage.isEmpty()) post(() -> newPage.get(0).button.requestFocus());
      else post(() -> settings.requestFocus());
    }

    private void cancelCarouselAnimation(LauncherShortcut shortcut) {
      shortcut.button.animate().cancel();
      shortcut.label.animate().cancel();
    }

    private CapsuleBackground shortcutBackground(LauncherShortcut shortcut) {
      boolean cards = ShortcutRow.CARDS.equals(shortcutStyle);
      if ("hdmi".equals(shortcut.id)) {
        return new CapsuleBackground(
            owner,
            "switch".equals(hdmiStyle)
                ? "switch_logo"
                : "ps5".equals(hdmiStyle) ? "controller" : "hdmi",
            null,
            cards,
            "#20252B",
            owner.frostedBackdrop);
      }
      return new CapsuleBackground(
          owner,
          cards ? shortcut.colorIcon : shortcut.minimalIcon,
          cards ? shortcut.colorFallback : shortcut.minimalFallback,
          cards,
          shortcut.colorBackground,
          owner.frostedBackdrop);
    }

    void armNavigationSound() {
      navigationSoundArmed = true;
      navigationSoundArmedAtMillis = SystemClock.uptimeMillis();
    }

    void stopNavigationSounds() {
      clearNavigationSoundGesture();
      navigationSound.stop();
    }

    /** Settings previews reuse the installed launcher cue without claiming audio focus. */
    void previewNavigationSound(JSONObject apps) {
      navigationSound.configure(apps);
      navigationSound.preview();
    }

    void destroyNavigationSounds() {
      clearNavigationSoundGesture();
      navigationSound.release();
    }

    private void clearNavigationSoundGesture() {
      navigationSoundArmed = false;
      navigationSoundArmedAtMillis = 0L;
    }

    private void playNavigationSoundForFocus() {
      if (!navigationSoundArmed) return;
      long elapsed = SystemClock.uptimeMillis() - navigationSoundArmedAtMillis;
      clearNavigationSoundGesture();
      if (elapsed >= 0L && elapsed <= NAVIGATION_GESTURE_WINDOW_MS) navigationSound.play();
    }

    private static void animateFocus(
        View view, boolean hasFocus, float unfocusedAlpha, float unfocusedScale) {
      animateFocus(view, hasFocus, unfocusedAlpha, 1f, unfocusedScale);
    }

    private static void animateFocus(
        View view,
        boolean hasFocus,
        float unfocusedAlpha,
        float focusedScale,
        float unfocusedScale) {
      view.setTranslationZ(hasFocus ? 2f : 0f);
      // ViewPropertyAnimator only replaces the scale/alpha properties below; leave a carousel
      // tile's translation running when focus lands on its entering page.
      view
          .animate()
          .alpha(hasFocus ? 1f : unfocusedAlpha)
          .scaleX(hasFocus ? focusedScale : unfocusedScale)
          .scaleY(hasFocus ? focusedScale : unfocusedScale)
          .setInterpolator(EASE_IN_OUT)
          .setDuration(180)
          .start();
    }

    private ArrayList<LauncherShortcut> visiblePage(
        ArrayList<LauncherShortcut> visible, int page) {
      int capacity = shortcutPageCapacity(visible.size());
      int start = page * capacity;
      int end = Math.min(visible.size(), start + capacity);
      return new ArrayList<>(visible.subList(start, end));
    }

    private void updateCarouselArrows(ArrayList<LauncherShortcut> visible) {
      int pageCount = pageCount(visible);
      previousPage.setVisibility(chromeVisible && activeShortcutPage > 0 ? VISIBLE : GONE);
      nextPage.setVisibility(chromeVisible && activeShortcutPage < pageCount - 1 ? VISIBLE : GONE);
    }

    private ArrayList<LauncherShortcut> visibleShortcuts() {
      ArrayList<LauncherShortcut> result = new ArrayList<>();
      for (LauncherShortcut shortcut : shortcuts) if (shortcut.enabled) result.add(shortcut);
      return result;
    }

    private int shortcutPageCapacity(int shortcutCount) {
      return ShortcutRow.capacityForCount(
          shortcutStyle,
          shortcutCount,
          appIconScale,
          appSpacingScale,
          LauncherLayout.safeAreaPadding(layoutConfig),
          LauncherLayout.aspectRatio(getWidth(), getHeight()));
    }

    private int pageCount(ArrayList<LauncherShortcut> visible) {
      int capacity = shortcutPageCapacity(visible.size());
      return Math.max(1, (visible.size() + capacity - 1) / capacity);
    }

    private String focusedShortcutId() {
      View focused = findFocus();
      for (LauncherShortcut shortcut : shortcuts)
        if (shortcut.button == focused) return shortcut.id;
      return null;
    }

    private LauncherShortcut shortcutForId(String id) {
      if (id == null) return null;
      for (LauncherShortcut shortcut : shortcuts) if (id.equals(shortcut.id)) return shortcut;
      return null;
    }

    private boolean restoreShortcutFocus(String id) {
      LauncherShortcut shortcut = shortcutForId(id);
      if (shortcut == null || shortcut.button.getVisibility() != VISIBLE) return false;
      shortcut.button.requestFocus();
      return true;
    }

    /** Reorders backing launcher entries, so labels, paging, focus, and click actions share one order. */
    private void applyShortcutOrder(JSONArray supplied) {
      ArrayList<String> orderedIds = new ArrayList<>();
      Set<String> known = new HashSet<>(canonicalShortcutIds);
      Set<String> seen = new HashSet<>();
      if (supplied != null) {
        for (int i = 0; i < supplied.length(); i++) {
          Object value = supplied.opt(i);
          if (value instanceof String && known.contains(value) && seen.add((String) value))
            orderedIds.add((String) value);
        }
      }
      for (String id : canonicalShortcutIds) if (seen.add(id)) orderedIds.add(id);
      final ArrayList<String> finalOrder = orderedIds;
      Collections.sort(
          shortcuts,
          (left, right) ->
              Integer.compare(finalOrder.indexOf(left.id), finalOrder.indexOf(right.id)));
    }

    private boolean isShortcutChild(View child) {
      if (child == previousPage || child == nextPage) return true;
      for (LauncherShortcut shortcut : shortcuts)
        if (child == shortcut.button || child == shortcut.label) return true;
      return false;
    }

    private void ticker() {
      clock.setText(
          new java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
              .format(new java.util.Date()));
      clockHandler.postDelayed(() -> ticker(), 1000);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
      int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec);
      setMeasuredDimension(width, height);
      clock.setTextSize(
          android.util.TypedValue.COMPLEX_UNIT_SP, 56f * clockScale);
      measureExact(clock, (int) (clockWidthFraction() * width), (int) (clockHeightFraction() * height));
      measureExact(settings, (int) (.04f * width), (int) (.06f * height));
      measureExact(wifi, (int) (.04f * width), (int) (.06f * height));
      measureExact(previousPage, (int) (.035f * width), (int) (.055f * height));
      measureExact(nextPage, (int) (.035f * width), (int) (.055f * height));
      int buttonWidth = (int) (ShortcutRow.width(shortcutStyle, appIconScale) * width),
          buttonHeight = (int) (ShortcutRow.height(shortcutStyle, appIconScale) * height),
          buttonContainerWidth =
              (int) Math.ceil(ShortcutRow.focusedContainerSize(buttonWidth, shortcutFocusScale())),
          buttonContainerHeight =
              (int) Math.ceil(ShortcutRow.focusedContainerSize(buttonHeight, shortcutFocusScale()));
      for (LauncherShortcut shortcut : shortcuts) {
        measureExact(shortcut.button, buttonContainerWidth, buttonContainerHeight);
        shortcut.label.setTextSize(
            android.util.TypedValue.COMPLEX_UNIT_SP, 14f * appIconScale);
        measureExact(shortcut.label, buttonWidth, (int) (ShortcutRow.labelHeight(appIconScale) * height));
      }
      for (int i = 0; i < getChildCount(); i++) {
        View child = getChildAt(i);
        if (child instanceof WeatherWidget) {
          WeatherWidget widget = (WeatherWidget) child;
          widget.setIconScale(LauncherLayout.weatherIconScale(layoutConfig));
          widget.setHorizontalSafeInset(
              LauncherLayout.horizontalSafeInset(
                  layoutConfig, LauncherLayout.aspectRatio(width, height)));
          measureExact(
              child,
              (int)
                  (widget.widthFraction(
                          LauncherLayout.aspectRatio(width, height))
                      * width),
              (int)
                  (widget.heightFraction(LauncherLayout.aspectRatio(width, height)) * height));
        } else if (child != clock && child != settings && child != wifi && !isShortcutChild(child)) {
          measureExact(child, width, height);
        }
      }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
      int width = right - left, height = bottom - top;
      float aspectRatio = LauncherLayout.aspectRatio(width, height);
      place(
          clock,
          (int) (LauncherLayout.x(layoutConfig, "clock", .045f, clockWidthFraction(), aspectRatio) * width),
          (int) (LauncherLayout.y(layoutConfig, "clock", .035f, clockHeightFraction(), aspectRatio) * height),
          (int) (clockWidthFraction() * width),
          (int) (clockHeightFraction() * height));
      place(
          settings,
          (int) (LauncherLayout.x(layoutConfig, "settings", .94f, .04f, aspectRatio) * width),
          (int) (LauncherLayout.y(layoutConfig, "settings", .052f, .06f, aspectRatio) * height),
          (int) (.04f * width),
          (int) (.06f * height));
      place(
          wifi,
          (int) (LauncherLayout.x(layoutConfig, "wifi", .88f, .04f, aspectRatio) * width),
          (int) (LauncherLayout.y(layoutConfig, "wifi", .052f, .06f, aspectRatio) * height),
          (int) (.04f * width),
          (int) (.06f * height));
      int buttonWidth = (int) (ShortcutRow.width(shortcutStyle, appIconScale) * width),
          buttonHeight = (int) (ShortcutRow.height(shortcutStyle, appIconScale) * height),
          buttonContainerWidth =
              (int) Math.ceil(ShortcutRow.focusedContainerSize(buttonWidth, shortcutFocusScale())),
          buttonContainerHeight =
              (int) Math.ceil(ShortcutRow.focusedContainerSize(buttonHeight, shortcutFocusScale()));
      // The configured apps anchor keeps a focused ring and, when present, labels safe. A label
      // already occupies the lower edge; without one the ring needs both edges.
      float appFocusOverflow =
          ShortcutRow.height(shortcutStyle, appIconScale) * (shortcutFocusScale() - 1f) / 2f;
      float appSafeHeight =
          LauncherLayout.appsHeight(layoutConfig, shortcutStyle, appIconScale)
              + (LauncherLayout.appsShowLabels(layoutConfig)
                  ? appFocusOverflow
                  : 2f * appFocusOverflow);
      int buttonY =
          (int)
              ((LauncherLayout.y(layoutConfig, "apps", .78f, appSafeHeight, aspectRatio)
                      + appFocusOverflow)
                  * height);
      ArrayList<LauncherShortcut> visible = visibleShortcuts();
      ArrayList<LauncherShortcut> page = visiblePage(visible, activeShortcutPage);
      float appWidth =
          ShortcutRow.pageWidth(
              shortcutStyle,
              visible.size(),
              appIconScale,
              appSpacingScale,
              LauncherLayout.safeAreaPadding(layoutConfig),
              aspectRatio);
      boolean paged = pageCount(visible) > 1;
      float carouselGutter =
          paged ? ShortcutRow.carouselGutter(shortcutStyle, appIconScale, appSpacingScale) : 0f;
      float leadingCarouselGutter =
          paged
              ? ShortcutRow.leadingCarouselGutter(
                  activeShortcutPage, shortcutStyle, appIconScale, appSpacingScale)
              : 0f;
      float appRowWidth =
          ShortcutRow.rowWidth(
              shortcutStyle,
              visible.size(),
              appIconScale,
              appSpacingScale,
              LauncherLayout.safeAreaPadding(layoutConfig),
              aspectRatio);
      // Clamp a paged row as one group so saved apps coordinates have the same meaning in every
      // renderer. A paged row occupies the safe-area viewport, but its cards and arrow gutters
      // retain their compact footprint at the leading edge.
      float carouselStart =
          LauncherLayout.x(
              layoutConfig,
              "apps",
              ShortcutRow.START_FRACTION,
              appRowWidth,
              aspectRatio);
      float appStart = carouselStart + leadingCarouselGutter;
      for (int i = 0; i < page.size(); i++) {
          LauncherShortcut shortcut = page.get(i);
          boolean leadingFirstPage = paged && activeShortcutPage == 0 && i == 0;
          int x =
              leadingFirstPage
                  ? (int)
                      Math.ceil(
                          (appStart
                                  + i
                                      * ShortcutRow.stride(
                                          shortcutStyle, appIconScale, appSpacingScale))
                              * width)
                  : (int)
                      ((appStart + i * ShortcutRow.stride(shortcutStyle, appIconScale, appSpacingScale))
                          * width);
          place(
              shortcut.button,
              leadingFirstPage ? x : x - (buttonContainerWidth - buttonWidth) / 2,
              buttonY - (buttonContainerHeight - buttonHeight) / 2,
              buttonContainerWidth,
              buttonContainerHeight);
          shortcut.button.setPivotX(leadingFirstPage ? 0f : buttonContainerWidth / 2f);
          shortcut.button.setPivotY(buttonContainerHeight / 2f);
          place(
              shortcut.label,
              x,
              buttonY + buttonHeight + (int) (ShortcutRow.labelGap(appIconScale) * height),
              buttonWidth,
              (int) (ShortcutRow.labelHeight(appIconScale) * height));
      }
      int arrowWidth = (int) (.035f * width), arrowHeight = (int) (.055f * height);
      int arrowY = buttonY + (buttonHeight - arrowHeight) / 2;
      place(
          previousPage,
          (int) (carouselStart * width),
          arrowY,
          arrowWidth,
          arrowHeight);
      place(
          nextPage,
          (int)
              ((carouselStart
                      + leadingCarouselGutter
                      + appWidth
                      + carouselGutter
                      - ShortcutRow.CAROUSEL_ARROW_WIDTH)
                  * width),
          arrowY,
          arrowWidth,
          arrowHeight);
      for (int i = 0; i < getChildCount(); i++) {
        View child = getChildAt(i);
        if (child == clock || child == settings || child == wifi || isShortcutChild(child))
          continue;
        if (child instanceof WeatherWidget) {
          WeatherWidget widget = (WeatherWidget) child;
          int childWidth = child.getMeasuredWidth(), childHeight = child.getMeasuredHeight();
          widget.setHorizontalSafeInset(LauncherLayout.horizontalSafeInset(layoutConfig, aspectRatio));
          float widgetWidth = widget.widthFraction(aspectRatio), widgetHeight = widget.heightFraction(aspectRatio);
          int widgetX =
                  (int)
                      (LauncherLayout.x(
                              layoutConfig,
                              "weather",
                              .98f - widgetWidth,
                              widgetWidth,
                              aspectRatio)
                          * width),
              widgetY =
                  (int)
                      (LauncherLayout.y(
                              layoutConfig, "weather", .125f, widgetHeight, aspectRatio)
                          * height);
          child.layout(widgetX, widgetY, widgetX + childWidth, widgetY + childHeight);
        } else if (child == owner.videoSurface) {
          android.graphics.Rect video = new android.graphics.Rect();
          owner.videoBounds(width, height, video);
          child.layout(video.left, video.top, video.right, video.bottom);
        } else child.layout(0, 0, width, height);
      }
    }

    /** Draws the next page's leading tile as a noninteractive, safe-area-clipped continuation. */
    private void drawNextShortcutPeek(Canvas canvas) {
      if (!chromeVisible || getWidth() <= 0 || getHeight() <= 0) return;
      ArrayList<LauncherShortcut> visible = visibleShortcuts();
      int capacity = shortcutPageCapacity(visible.size());
      int pageCount = pageCount(visible);
      if (pageCount <= 1 || activeShortcutPage >= pageCount - 1) return;
      int nextIndex = (activeShortcutPage + 1) * capacity;
      if (nextIndex < 0 || nextIndex >= visible.size()) return;

      int width = getWidth(), height = getHeight();
      float aspectRatio = LauncherLayout.aspectRatio(width, height);
      float safeInset = LauncherLayout.horizontalSafeInset(layoutConfig, aspectRatio);
      float safeLeft = safeInset * width, safeRight = (1f - safeInset) * width;
      float appSpacing = appSpacingScale;
      float carouselGutter = ShortcutRow.carouselGutter(shortcutStyle, appIconScale, appSpacing);
      float appRowWidth =
          ShortcutRow.rowWidth(
              shortcutStyle,
              visible.size(),
              appIconScale,
              appSpacing,
              LauncherLayout.safeAreaPadding(layoutConfig),
              aspectRatio);
      float carouselStart =
          LauncherLayout.x(
              layoutConfig, "apps", ShortcutRow.START_FRACTION, appRowWidth, aspectRatio);
      float appStart =
          carouselStart
              + ShortcutRow.leadingCarouselGutter(
                  activeShortcutPage, shortcutStyle, appIconScale, appSpacing);
      float peekLeft =
          (appStart
                  + ShortcutRow.nextPagePeekOffset(
                      shortcutStyle,
                      visible.size(),
                      appIconScale,
                      appSpacing,
                      LauncherLayout.safeAreaPadding(layoutConfig),
                      aspectRatio))
              * width;
      int buttonWidth = (int) (ShortcutRow.width(shortcutStyle, appIconScale) * width);
      int buttonHeight = (int) (ShortcutRow.height(shortcutStyle, appIconScale) * height);
      float appFocusOverflow =
          ShortcutRow.height(shortcutStyle, appIconScale) * (shortcutFocusScale() - 1f) / 2f;
      float appSafeHeight =
          LauncherLayout.appsHeight(layoutConfig, shortcutStyle, appIconScale)
              + (LauncherLayout.appsShowLabels(layoutConfig)
                  ? appFocusOverflow
                  : 2f * appFocusOverflow);
      int buttonY =
          (int)
              ((LauncherLayout.y(layoutConfig, "apps", .78f, appSafeHeight, aspectRatio)
                      + appFocusOverflow)
                  * height);
      boolean showLabel = LauncherLayout.appsShowLabels(layoutConfig);
      int labelY = buttonY + buttonHeight + (int) (ShortcutRow.labelGap(appIconScale) * height);
      int labelHeight = (int) (ShortcutRow.labelHeight(appIconScale) * height);
      int contentBottom = showLabel ? labelY + labelHeight : buttonY + buttonHeight;
      float visibleLeft = Math.max(safeLeft, peekLeft);
      float visibleRight = Math.min(safeRight, peekLeft + buttonWidth);
      if (visibleLeft >= visibleRight) return;

      int clip = canvas.save();
      canvas.clipRect(safeLeft, 0f, safeRight, height);
      int layer = canvas.saveLayer(visibleLeft, buttonY, visibleRight, contentBottom, null);
      LauncherShortcut nextShortcut = visible.get(nextIndex);
      CapsuleBackground background = shortcutBackground(nextShortcut);
      background.setBounds(
          Math.round(peekLeft), buttonY, Math.round(peekLeft) + buttonWidth, buttonY + buttonHeight);
      background.draw(canvas);
      if (showLabel) {
        Paint labelPaint = new Paint(nextShortcut.label.getPaint());
        labelPaint.setTextAlign(Paint.Align.CENTER);
        Paint.FontMetrics metrics = labelPaint.getFontMetrics();
        float baseline = labelY + (labelHeight - metrics.bottom + metrics.top) / 2f - metrics.top;
        canvas.drawText(
            nextShortcut.label.getText().toString(),
            peekLeft + buttonWidth / 2f,
            baseline,
            labelPaint);
      }
      nextShortcutPeekFade.setShader(
          new LinearGradient(
              visibleLeft,
              0f,
              visibleRight,
              0f,
              new int[] {0x61000000, 0x00000000},
              null,
              Shader.TileMode.CLAMP));
      nextShortcutPeekFade.setXfermode(
          new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN));
      canvas.drawRect(visibleLeft, buttonY, visibleRight, contentBottom, nextShortcutPeekFade);
      nextShortcutPeekFade.setXfermode(null);
      nextShortcutPeekFade.setShader(null);
      canvas.restoreToCount(layer);
      canvas.restoreToCount(clip);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
      shortcutPeekDrawn = false;
      super.dispatchDraw(canvas);
    }

    @Override
    protected boolean drawChild(Canvas canvas, View child, long drawingTime) {
      // Wallpaper, video matte, and video surface are earlier children. Draw the peek after those
      // backdrop layers and before any launcher chrome so it remains visible but noninteractive.
      if (!shortcutPeekDrawn && isLauncherChromeChild(child)) {
        drawNextShortcutPeek(canvas);
        shortcutPeekDrawn = true;
      }
      // Stable shortcut cards may use their reserved focus gutters. Only translated page-animation
      // frames need the hard viewport clip, so an edge card's focused outline stays intact.
      if (!isShortcutChild(child) || child.getTranslationX() == 0f)
        return super.drawChild(canvas, child, drawingTime);
      float inset =
          LauncherLayout.horizontalSafeInset(
                  layoutConfig, LauncherLayout.aspectRatio(getWidth(), getHeight()))
              * getWidth();
      int clip = canvas.save();
      canvas.clipRect(inset, 0f, getWidth() - inset, getHeight());
      boolean drawn = super.drawChild(canvas, child, drawingTime);
      canvas.restoreToCount(clip);
      return drawn;
    }

    private boolean isLauncherChromeChild(View child) {
      return child == clock || child == settings || child == wifi || isShortcutChild(child);
    }

    private JSONObject layoutConfig;

    private float clockWidthFraction() { return .28f * clockScale; }

    private float clockHeightFraction() { return .11f * clockScale; }

    @Override
    public View focusSearch(View focused, int direction) {
      View candidate = android.view.FocusFinder.getInstance().findNextFocus(this, focused, direction);
      if (candidate != null && candidate.getVisibility() == VISIBLE && candidate.isFocusable()) return candidate;
      // A customized layout can leave no geometric neighbor in one direction. Keep remote focus
      // on a visible launcher target instead of letting it escape behind ambient content.
      return firstShortcutOrSettings();
    }

    private static void measureExact(View view, int width, int height) {
      view.measure(
          MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
          MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }

    private static void place(View view, int x, int y, int width, int height) {
      view.layout(x, y, x + width, y + height);
    }

    private static TextView text(Context context, int sp) {
      TextView view = new TextView(context);
      view.setTextColor(Color.WHITE);
      view.setTextSize(sp);
      view.setGravity(android.view.Gravity.CENTER_VERTICAL);
      view.setIncludeFontPadding(false);
      return view;
    }

    private static TextView icon(Context context, String name) {
      TextView view = text(context, 12);
      view.setGravity(android.view.Gravity.CENTER);
      int id = DrawableResources.id(context, name);
      if (id != 0) {
        android.graphics.drawable.Drawable drawable = context.getResources().getDrawable(id);
        int size = (int) (30 * context.getResources().getDisplayMetrics().density);
        drawable.setBounds(0, 0, size, size);
        view.setCompoundDrawables(null, drawable, null, null);
      }
      view.setFocusable(true);
      view.setSoundEffectsEnabled(false);
      view.setBackground(new IconBackground());
      return view;
    }

    private static TextView capsule(Context context, String value, String description) {
      TextView view = text(context, 42);
      view.setText(value);
      view.setGravity(android.view.Gravity.CENTER);
      view.setContentDescription(description);
      view.setFocusable(true);
      view.setFocusableInTouchMode(true);
      view.setSoundEffectsEnabled(false);
      return view;
    }

    private static TextView carouselArrow(Context context, String icon, String description) {
      TextView view = capsule(context, "", description);
      view.setAlpha(.72f);
      view.setBackground(new CapsuleBackground(context, icon));
      return view;
    }

    private static TextView label(Context context, String value) {
      TextView view = text(context, 14);
      view.setText(value);
      view.setGravity(android.view.Gravity.CENTER);
      view.setVisibility(INVISIBLE);
      return view;
    }
  }

  private static final class CapsuleBackground extends android.graphics.drawable.Drawable {
    final Paint fill = new Paint(3);
    final Paint outline = new Paint(3);
    final String icon, fallbackIcon;
    final Context c;
    final boolean cards;
    final String colorBackground;
    final FrostedBackdrop frostedBackdrop;
    private boolean focused;

    CapsuleBackground(Context c, String icon) {
      this(c, icon, null, false, null);
    }

    CapsuleBackground(Context c, String icon, String fallbackIcon, boolean cards, String colorBackground) {
      this(c, icon, fallbackIcon, cards, colorBackground, null);
    }

    CapsuleBackground(
        Context c,
        String icon,
        String fallbackIcon,
        boolean cards,
        String colorBackground,
        FrostedBackdrop frostedBackdrop) {
      this.c = c;
      this.icon = icon;
      this.fallbackIcon = fallbackIcon;
      this.cards = cards;
      this.colorBackground = colorBackground;
      this.frostedBackdrop = frostedBackdrop;
    }

    void setFocused(boolean f) {
      focused = f;
      invalidateSelf();
    }

    public void draw(Canvas x) {
      Rect b = getBounds();
      RectF r = new RectF(b);
      float radius = cards ? b.height() * .12f : b.height() / 2f;
      fill.setStyle(Paint.Style.FILL);
      if (cards) fill.setColor(backgroundColor());
      else {
        if (frostedBackdrop != null && getCallback() instanceof View) {
          View view = (View) getCallback();
          frostedBackdrop.registerGlassView(view);
          frostedBackdrop.draw(x, view, r, radius);
        }
        // Color comes from the sampled media; the glass overlay remains neutral.
        fill.setShader(
            new LinearGradient(
                0,
                b.top,
                0,
                b.bottom,
                new int[] {0x2effffff, 0x26000000},
                null,
                Shader.TileMode.CLAMP));
      }
      x.drawRoundRect(r, radius, radius, fill);
      fill.setShader(null);
      if (!cards) {
        outline.setStyle(Paint.Style.STROKE);
        outline.setStrokeWidth(1f);
        outline.setColor(0x35ffffff);
        x.drawRoundRect(r, radius, radius, outline);
      }
      if (icon != null) {
        int id = DrawableResources.id(c, icon);
        if (id == 0 && fallbackIcon != null)
          id = DrawableResources.id(c, fallbackIcon);
        if (id != 0) {
          android.graphics.drawable.Drawable d = c.getResources().getDrawable(id);
          int sourceW = Math.max(1, d.getIntrinsicWidth()),
              sourceH = Math.max(1, d.getIntrinsicHeight());
          float scale = Math.min((b.width() * (cards ? .68f : .52f)) / sourceW, (b.height() * (cards ? .62f : .58f)) / sourceH);
          int w = Math.max(1, Math.round(sourceW * scale)),
              h = Math.max(1, Math.round(sourceH * scale));
          int cx = b.centerX(), cy = b.centerY();
          d.setBounds(cx - w / 2, cy - h / 2, cx + (w + 1) / 2, cy + (h + 1) / 2);
          d.draw(x);
        }
      }
      if (focused) {
        // Keep every stroke pixel inside the reserved focus container and above the logo.
        float strokeWidth = 3f;
        float inset = strokeWidth;
        RectF strokeBounds = new RectF(r);
        strokeBounds.inset(inset, inset);
        outline.setStyle(Paint.Style.STROKE);
        outline.setStrokeWidth(strokeWidth);
        outline.setColor(Color.WHITE);
        x.drawRoundRect(
            strokeBounds,
            Math.max(0f, radius - inset),
            Math.max(0f, radius - inset),
            outline);
      }
      outline.setStyle(Paint.Style.FILL);
    }

    private int backgroundColor() {
      if (colorBackground == null) return 0xff20252b;
      try { return Color.parseColor(colorBackground); }
      catch (IllegalArgumentException ignored) { return 0xff20252b; }
    }

    public void setAlpha(int a) {}

    public void setColorFilter(android.graphics.ColorFilter f) {}

    public int getOpacity() {
      return android.graphics.PixelFormat.TRANSLUCENT;
    }
  }

  private static final class IconBackground extends android.graphics.drawable.Drawable {
    private final Paint p = new Paint(3);
    private boolean focused;

    void setFocused(boolean value) {
      focused = value;
      invalidateSelf();
    }

    public void draw(Canvas c) {
      if (!focused) return;
      Rect b = getBounds();
      p.setStyle(Paint.Style.STROKE);
      p.setStrokeWidth(2f);
      p.setColor(Color.WHITE);
      c.drawRoundRect(
          new RectF(b.left + 2, b.top + 2, b.right - 2, b.bottom - 2),
          b.height() / 2f,
          b.height() / 2f,
          p);
      p.setStyle(Paint.Style.FILL);
    }

    public void setAlpha(int a) {}

    public void setColorFilter(android.graphics.ColorFilter f) {}

    public int getOpacity() {
      return android.graphics.PixelFormat.TRANSLUCENT;
    }
  }

  private static final class WallpaperView extends View {
    private Bitmap current, previous;
    private final Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
    private boolean foreground, fitMode;
    private android.animation.ValueAnimator animator;

    WallpaperView(Context c) {
      super(c);
      setBackgroundColor(Color.BLACK);
    }

    void setFitMode(boolean value) {
      if (fitMode != value) {
        fitMode = value;
        invalidate();
      }
    }

    void setForeground(boolean b) {
      foreground = b;
      if (!b && animator != null) animator.cancel();
    }

    void setBitmap(Bitmap b, boolean animate) {
      if (animator != null) animator.cancel();
      if (current == null || !foreground || !animate) {
        recycle(previous);
        recycle(current);
        current = b;
        previous = null;
        invalidate();
        return;
      }
      recycle(previous);
      previous = current;
      current = b;
      animator = android.animation.ValueAnimator.ofFloat(0, 1);
      animator.setDuration(650);
      animator.addUpdateListener(
          v -> {
            setAlphaFraction((Float) v.getAnimatedValue());
          });
      animator.addListener(
          new android.animation.AnimatorListenerAdapter() {
            public void onAnimationEnd(android.animation.Animator x) {
              if (x == animator) {
                recycle(previous);
                previous = null;
                invalidate();
              }
            }
          });
      animator.start();
    }

    float alpha;

    void setAlphaFraction(float f) {
      alpha = f;
      invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
      super.onDraw(c);
      if (previous != null) draw(c, previous, 255);
      if (current != null) draw(c, current, previous == null ? 255 : (int) (255 * alpha));
    }

    private void draw(Canvas c, Bitmap b, int a) {
      float s =
          (fitMode
              ? Math.min(getWidth() / (float) b.getWidth(), getHeight() / (float) b.getHeight())
              : Math.max(getWidth() / (float) b.getWidth(), getHeight() / (float) b.getHeight()));
      int w = (int) (b.getWidth() * s),
          h = (int) (b.getHeight() * s),
          l = (getWidth() - w) / 2,
          t = (getHeight() - h) / 2;
      p.setAlpha(a);
      c.drawBitmap(b, null, new Rect(l, t, l + w, t + h), p);
      p.setAlpha(255);
    }

    private static void recycle(Bitmap b) {
      if (b != null && !b.isRecycled()) b.recycle();
    }
  }
}
