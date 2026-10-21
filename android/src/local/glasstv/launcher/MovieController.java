package local.glasstv.launcher;

import android.content.Context;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/** Full-screen, pinned movie session that lives over the launcher instead of becoming another Activity. */
final class MovieController {
    interface Host {
        boolean isMovieHostForeground();
        void suspendAmbientForMovie();
        void restoreAmbientAfterMovie();
        void onMovieStopped();
    }

    static final class MovieRequestException extends Exception {
        final int status; final String code;
        MovieRequestException(int status, String code, String message) { super(message); this.status = status; this.code = code; }
    }

    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final Pattern PIN = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern OPAQUE_PATH = Pattern.compile("/stream/[A-Za-z0-9_-]{1,180}");
    private static final Pattern IPV4 = Pattern.compile("(?:25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])(?:\\.(?:25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])){3}");
    private static final long UI_WAIT_MS = 300;
    private static final long SEEK_STEP_MS = 15_000L;

    private final Context context;
    private final Host host;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final FrameLayout overlay;
    private final SurfaceView surface;
    private final LinearLayout controls;
    private final TextView titleView, stateView, playPause, stop;
    private final AudioManager audio;
    private final Runnable hideControls = new Runnable() {
        @Override public void run() {
            if ("playing".equals(state) && overlay.getVisibility() == View.VISIBLE) controls.setVisibility(View.INVISIBLE);
        }
    };

    private int pendingReleases;
    private MediaPlayer player;
    private PinnedMovieDataSource source;
    private MovieRequest request;
    private String state = "idle";
    private String error;
    private long positionMs;
    private long durationMs;
    private int width;
    private int height;
    private boolean surfaceReady;
    private boolean preflightComplete;
    private int generation;
    private final AudioManager.OnAudioFocusChangeListener audioFocusListener = this::onAudioFocusChanged;

    MovieController(Context context, Host host, FrameLayout parent) {
        this.context = context;
        this.host = host;
        this.audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        overlay = new FrameLayout(context);
        overlay.setBackgroundColor(Color.BLACK);
        overlay.setVisibility(View.GONE);
        overlay.setFocusable(true);
        surface = new SurfaceView(context);
        overlay.addView(surface, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        surface.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder holder) { surfaceReady = holder.getSurface().isValid(); startPreparedSource(); }
            @Override public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) { surfaceReady = holder.getSurface().isValid(); startPreparedSource(); }
            @Override public void surfaceDestroyed(SurfaceHolder holder) { surfaceReady = false; }
        });
        controls = new LinearLayout(context);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setPadding(dp(38), dp(18), dp(38), dp(30));
        controls.setBackgroundColor(0x9b000000);
        titleView = text(20); titleView.setMaxLines(1); titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        stateView = text(15); stateView.setTextColor(0xd9ffffff);
        playPause = control("pause", "Pause movie");
        stop = control("stop", "Stop movie");
        controls.addView(titleView, new LinearLayout.LayoutParams(0, dp(58), 1f));
        controls.addView(stateView, new LinearLayout.LayoutParams(dp(170), dp(58)));
        controls.addView(playPause, new LinearLayout.LayoutParams(dp(82), dp(58)));
        controls.addView(stop, new LinearLayout.LayoutParams(dp(82), dp(58)));
        FrameLayout.LayoutParams controlParams = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        overlay.addView(controls, controlParams);
        playPause.setOnClickListener(v -> toggle());
        stop.setOnClickListener(v -> stopInternal(true));
        parent.addView(overlay, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    JSONObject play(JSONObject json, InetAddress peer) throws MovieRequestException {
        MovieRequest value = parse(json, peer);
        return onMain(() -> {
            if (isActive() && request != null && request.sessionId.equals(value.sessionId)) {
                if (request.sameSessionRequest(value)) return snapshotOnMain();
                throw new MovieRequestException(409, "movie_conflict", "This movie session has different playback details");
            }
            stopInternal(false);
            request = value;
            state = "preparing";
            error = null;
            positionMs = value.resumePositionMs;
            durationMs = 0;
            width = 0;
            height = 0;
            preflightComplete = false;
            final int current = ++generation;
            resetSurfaceFill();
            overlay.setVisibility(View.VISIBLE);
            overlay.requestFocus();
            updateControls();
            host.suspendAmbientForMovie();
            final PinnedMovieDataSource pendingSource = new PinnedMovieDataSource(value.url, value.token, value.pin);
            source = pendingSource;
            network.execute(() -> {
                try {
                    pendingSource.preflight();
                    main.post(() -> {
                        if (current == generation && source == pendingSource && "preparing".equals(state)) { preflightComplete = true; startPreparedSource(); }
                    });
                } catch (Exception failure) {
                    main.post(() -> { if (current == generation) fail("Movie stream could not be reached"); });
                }
            });
            return snapshotOnMain();
        });
    }

    JSONObject control(JSONObject json) throws MovieRequestException {
        if (json.optInt("apiVersion", 0) != 1) throw new MovieRequestException(400, "invalid", "Invalid movie control");
        final String session = json.optString("sessionId", "");
        final String action = json.optString("action", "");
        final boolean hasPosition = json.has("positionMs");
        final long wanted = json.optLong("positionMs", -1);
        return onMain(() -> {
            if (request == null || !request.sessionId.equals(session)) throw new MovieRequestException(409, "movie_session", "Movie session is no longer active");
            if ("stop".equals(action)) { stopInternal(true); return snapshotOnMain(); }
            if ("pause".equals(action)) { pauseInternal(); return snapshotOnMain(); }
            if ("resume".equals(action)) { resumeInternal(); return snapshotOnMain(); }
            if ("seek".equals(action)) {
                if (!hasPosition || wanted < 0) throw new MovieRequestException(400, "invalid", "A nonnegative movie position is required");
                seekInternal(wanted);
                return snapshotOnMain();
            }
            throw new MovieRequestException(400, "invalid", "Unknown movie control");
        });
    }

    /** A busy main thread is retriable; never fabricate an idle state for an active stream. */
    JSONObject status() throws MovieRequestException { return onMain(this::snapshotOnMain); }

    boolean handleKey(int keyCode) {
        if (!isActive() && !"error".equals(state)) return false;
        showControls();
        if (keyCode == KeyEvent.KEYCODE_BACK) { stopInternal(true); return true; }
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
            if (stop.hasFocus()) stopInternal(true); else toggle();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) { seekRelative(-SEEK_STEP_MS); return true; }
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) { seekRelative(SEEK_STEP_MS); return true; }
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) { playPause.requestFocus(); return true; }
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) { stop.requestFocus(); return true; }
        return false;
    }

    void onHostStopped() { onMainUnchecked(() -> stopInternal(true)); }
    void destroy() { onMainUnchecked(() -> stopInternal(false)); network.shutdownNow(); }

    private void startPreparedSource() {
        if (pendingReleases > 0 || player != null || !surfaceReady || !surface.getHolder().getSurface().isValid() || !preflightComplete || !"preparing".equals(state) || source == null || !host.isMovieHostForeground()) return;
        final int current = generation;
        try {
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build());
            player.setDataSource(source);
            player.setDisplay(surface.getHolder());
            player.setScreenOnWhilePlaying(true);
            player.setOnPreparedListener(ready -> {
                if (current != generation || player != ready) return;
                if (!host.isMovieHostForeground()) {
                    detachAndReleaseResources();
                    return;
                }
                durationMs = safeDuration(ready);
                if (positionMs > 0 && durationMs > 0) ready.seekTo((int) Math.min(positionMs, durationMs));
                requestAudioFocus();
                state = "playing";
                ready.start();
                updateControls();
            });
            player.setOnVideoSizeChangedListener((ignored, w, h) -> {
                width = w;
                height = h;
                resizeSurfaceForVideo();
            });
            player.setOnCompletionListener(done -> {
                if (current == generation && player == done) {
                    positionMs = durationMs;
                    state = "completed";
                    final int completedGeneration = ++generation;
                    detachAndReleaseResources(() -> {
                        if (generation == completedGeneration && "completed".equals(state)) hideOverlayAndRestoreAmbient();
                    });
                }
            });
            player.setOnErrorListener((failed, what, extra) -> {
                if (current == generation && player == failed) fail("This TV cannot play this movie stream");
                return true;
            });
            player.prepareAsync();
        } catch (Exception failure) {
            android.util.Log.w("GlassTVMovie", "Preparation failure: " + failure.getClass().getSimpleName());
            fail("This TV could not prepare this movie");
        }
    }

    private void pauseInternal() {
        if (!"playing".equals(state) || player == null) return;
        try { positionMs = safePosition(player); player.pause(); player.setScreenOnWhilePlaying(false); abandonAudioFocus(); state = "paused"; updateControls(); }
        catch (Exception failure) { fail("Movie playback could not be paused"); }
    }

    private void resumeInternal() {
        if (!"paused".equals(state) || player == null) return;
        try { requestAudioFocus(); player.setScreenOnWhilePlaying(true); player.start(); state = "playing"; updateControls(); }
        catch (Exception failure) { fail("Movie playback could not resume"); }
    }

    private void seekRelative(long delta) {
        if (durationMs <= 0) return;
        try {
            seekInternal(Math.max(0, Math.min(durationMs, currentPosition() + delta)));
        } catch (MovieRequestException ignored) { }
    }

    private void seekInternal(long target) throws MovieRequestException {
        if (durationMs <= 0 || target > durationMs) throw new MovieRequestException(400, "invalid", "Movie position is outside its duration");
        positionMs = target;
        if (player != null && ("playing".equals(state) || "paused".equals(state))) {
            try { player.seekTo((int) target); } catch (Exception failure) { throw new MovieRequestException(400, "invalid", "Movie seek failed"); }
        }
    }

    private void toggle() { if ("playing".equals(state)) pauseInternal(); else if ("paused".equals(state)) resumeInternal(); }

    private void resetSurfaceFill() {
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) surface.getLayoutParams();
        params.width = FrameLayout.LayoutParams.MATCH_PARENT;
        params.height = FrameLayout.LayoutParams.MATCH_PARENT;
        params.gravity = Gravity.NO_GRAVITY;
        surface.setLayoutParams(params);
    }

    /** Keep non-16:9 movie frames at their actual aspect ratio against the opaque black overlay. */
    private void resizeSurfaceForVideo() {
        int parentWidth = overlay.getWidth(), parentHeight = overlay.getHeight();
        if (width <= 0 || height <= 0 || parentWidth <= 0 || parentHeight <= 0) return;
        float scale = Math.min(parentWidth / (float) width, parentHeight / (float) height);
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) surface.getLayoutParams();
        params.width = Math.round(width * scale);
        params.height = Math.round(height * scale);
        params.gravity = Gravity.CENTER;
        surface.setLayoutParams(params);
    }

    private void fail(String message) {
        error = message;
        state = "error";
        final int failedGeneration = ++generation;
        detachAndReleaseResources(() -> {
            if (generation == failedGeneration && "error".equals(state)) updateControls();
        });
    }

    private void stopInternal(boolean resetState) {
        final int stoppedGeneration = ++generation;
        preflightComplete = false;
        if (resetState) {
            request = null;
            state = "idle";
            error = null;
            positionMs = durationMs = 0;
            width = height = 0;
            detachAndReleaseResources(() -> {
                if (generation == stoppedGeneration && "idle".equals(state)) hideOverlayAndRestoreAmbient();
            });
        } else {
            // A replacement play keeps the overlay visible and must not revive ambient content.
            detachAndReleaseResources();
        }
    }

    /** Detach on main, then cancel source I/O before releasing its MediaPlayer off main. */
    private void detachAndReleaseResources() { detachAndReleaseResources(null); }
    private void detachAndReleaseResources(final Runnable afterCleanup) {
        abandonAudioFocus();
        MediaPlayer oldPlayer = player;
        PinnedMovieDataSource oldSource = source;
        player = null;
        source = null;
        releaseDetachedAsync(oldPlayer, oldSource, afterCleanup);
    }

    private void releaseDetachedAsync(final MediaPlayer oldPlayer, final PinnedMovieDataSource oldSource, final Runnable afterCleanup) {
        if (oldPlayer == null && oldSource == null) {
            if (afterCleanup != null) main.post(afterCleanup);
            return;
        }
        pendingReleases++;
        Thread cleanup = new Thread(() -> {
            // Disconnect first: MediaPlayer may otherwise remain blocked in MediaDataSource.readAt.
            if (oldSource != null) oldSource.close();
            if (oldPlayer != null) {
                try {
                    oldPlayer.setOnPreparedListener(null);
                    oldPlayer.setOnCompletionListener(null);
                    oldPlayer.setOnErrorListener(null);
                    oldPlayer.setScreenOnWhilePlaying(false);
                    oldPlayer.reset();
                    oldPlayer.release();
                } catch (Exception ignored) { }
            }
            main.post(() -> {
                pendingReleases = Math.max(0, pendingReleases - 1);
                if (afterCleanup != null) afterCleanup.run();
                startPreparedSource();
            });
        }, "GlassTV-Movie-cleanup");
        cleanup.setDaemon(true);
        cleanup.start();
    }

    private void hideOverlayAndRestoreAmbient() {
        main.removeCallbacks(hideControls);
        overlay.setVisibility(View.GONE);
        updateControls();
        if (host.isMovieHostForeground()) {
            host.restoreAmbientAfterMovie();
            host.onMovieStopped();
        }
    }

    private void requestAudioFocus() { if (audio != null) audio.requestAudioFocus(audioFocusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN); }
    private void abandonAudioFocus() { if (audio != null) audio.abandonAudioFocus(audioFocusListener); }
    private void onAudioFocusChanged(int focusChange) {
        if (focusChange < 0) {
            main.post(() -> { if ("playing".equals(state)) pauseInternal(); });
        }
    }
    private boolean isActive() { return "preparing".equals(state) || "playing".equals(state) || "paused".equals(state); }
    private long currentPosition() { return player != null && ("playing".equals(state) || "paused".equals(state)) ? safePosition(player) : positionMs; }
    private static long safePosition(MediaPlayer value) { try { return Math.max(0, value.getCurrentPosition()); } catch (Exception ignored) { return 0; } }
    private static long safeDuration(MediaPlayer value) { try { return Math.max(0, value.getDuration()); } catch (Exception ignored) { return 0; } }

    private JSONObject snapshotOnMain() {
        try {
            JSONObject out = new JSONObject();
            out.put("supported", true);
            if (request != null) { out.put("sessionId", request.sessionId); out.put("movieId", request.movieId); out.put("title", request.title); }
            out.put("state", state);
            out.put("positionMs", currentPosition());
            out.put("durationMs", durationMs);
            out.put("width", width);
            out.put("height", height);
            if (error != null) out.put("error", error);
            return out;
        } catch (Exception impossible) { return new JSONObject(); }
    }

    private void updateControls() {
        titleView.setText(request == null ? "" : request.title);
        stateView.setText(error != null ? error : ("playing".equals(state) ? "Playing" : "paused".equals(state) ? "Paused" : "preparing".equals(state) ? "Preparing" : state));
        setControlIcon(playPause, "playing".equals(state) ? "pause" : "play");
        playPause.setContentDescription("playing".equals(state) ? "Pause movie" : "Play movie");
        showControls();
    }

    private void showControls() {
        main.removeCallbacks(hideControls);
        controls.setVisibility(View.VISIBLE);
        if ("playing".equals(state)) main.postDelayed(hideControls, 4_000L);
    }

    private TextView control(String icon, String description) {
        TextView view = text(0);
        view.setGravity(Gravity.CENTER);
        view.setFocusable(true);
        view.setClickable(true);
        view.setContentDescription(description);
        setControlIcon(view, icon);
        return view;
    }
    private void setControlIcon(TextView view, String name) {
        int id = DrawableResources.id(context, name);
        if (id != 0) {
            android.graphics.drawable.Drawable drawable = context.getResources().getDrawable(id);
            int size = dp(34); drawable.setBounds(0, 0, size, size); view.setCompoundDrawables(null, drawable, null, null);
        } else view.setText("play".equals(name) ? "Play" : "Stop".equals(name) ? "Stop" : "Pause");
    }
    private TextView text(int sp) { TextView view = new TextView(context); view.setTextColor(Color.WHITE); if (sp > 0) view.setTextSize(sp); view.setGravity(Gravity.CENTER_VERTICAL); return view; }
    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }

    private MovieRequest parse(JSONObject json, InetAddress peer) throws MovieRequestException {
        if (json.optInt("apiVersion", 0) != 1 || !(peer instanceof Inet4Address)) throw new MovieRequestException(400, "invalid", "Invalid movie request");
        String session = json.optString("sessionId", "");
        String movie = json.optString("movieId", "");
        String title = json.optString("title", "");
        String rawUrl = json.optString("url", "");
        String token = json.optString("token", "");
        String pin = json.optString("certificatePin", "");
        long resume = json.optLong("resumePositionMs", -1);
        try { UUID.fromString(session); } catch (Exception invalid) { throw new MovieRequestException(400, "invalid", "Invalid movie session"); }
        if (movie.length() < 1 || movie.length() > 255 || title.length() < 1 || title.length() > 255 || resume < 0 || !TOKEN.matcher(token).matches() || !PIN.matcher(pin).matches()) throw new MovieRequestException(400, "invalid", "Invalid movie request");
        try {
            URI uri = new URI(rawUrl);
            if (!"https".equals(uri.getScheme()) || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getHost() == null || !IPV4.matcher(uri.getHost()).matches() || uri.getPort() < 1 || uri.getPort() > 65535 || !OPAQUE_PATH.matcher(uri.getRawPath()).matches()) throw new Exception();
            InetAddress source = InetAddress.getByName(uri.getHost());
            if (!(source instanceof Inet4Address) || !source.getHostAddress().equals(peer.getHostAddress())) throw new Exception();
            return new MovieRequest(session, movie, title, uri.toURL(), token, pin, resume);
        } catch (Exception invalid) { throw new MovieRequestException(400, "invalid_source", "Movie source must be the paired Mac IPv4 HTTPS stream"); }
    }

    private interface MainCall<T> { T run() throws MovieRequestException; }
    private <T> T onMain(MainCall<T> call) throws MovieRequestException {
        if (Looper.myLooper() == Looper.getMainLooper()) return call.run();
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<MovieRequestException> failure = new AtomicReference<>();
        AtomicBoolean cancelled = new AtomicBoolean();
        CountDownLatch done = new CountDownLatch(1);
        main.post(() -> {
            if (cancelled.get()) { done.countDown(); return; }
            try { result.set(call.run()); } catch (MovieRequestException e) { failure.set(e); } finally { done.countDown(); }
        });
        try {
            if (!done.await(UI_WAIT_MS, TimeUnit.MILLISECONDS)) {
                cancelled.set(true);
                throw new MovieRequestException(503, "busy", "Movie player is busy");
            }
        } catch (InterruptedException interrupted) {
            cancelled.set(true);
            Thread.currentThread().interrupt();
            throw new MovieRequestException(503, "busy", "Movie player is busy");
        }
        if (failure.get() != null) throw failure.get();
        return result.get();
    }
    private void onMainUnchecked(Runnable action) { if (Looper.myLooper() == Looper.getMainLooper()) action.run(); else main.post(action); }

    private static final class MovieRequest {
        final String sessionId, movieId, title, token, pin; final URL url; final long resumePositionMs;
        MovieRequest(String sessionId, String movieId, String title, URL url, String token, String pin, long resumePositionMs) { this.sessionId=sessionId;this.movieId=movieId;this.title=title;this.url=url;this.token=token;this.pin=pin;this.resumePositionMs=resumePositionMs; }
        boolean sameSessionRequest(MovieRequest other) {
            return movieId.equals(other.movieId) && title.equals(other.title) && url.toExternalForm().equals(other.url.toExternalForm())
                    && token.equals(other.token) && pin.equals(other.pin) && resumePositionMs == other.resumePositionMs;
        }
    }
}
