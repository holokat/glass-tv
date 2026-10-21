package local.glasstv.launcher;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.View;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A small, background-only source for the launcher glass. It never copies the window, which keeps
 * foreground controls out of its own blur. Sources are first composed at TV-safe resolution and
 * blurred on one worker; the resulting bitmap is then sampled in root coordinates by each glass
 * surface.
 */
final class FrostedBackdrop {
  private static final int MAX_EDGE = 384;
  private static final int BLUR_RADIUS = 6;
  private static final long VIDEO_REFRESH_MS = 1000L;

  private final View root;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final Set<View> registered =
      Collections.newSetFromMap(new WeakHashMap<View, Boolean>());
  private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
  private final Path clipPath = new Path();
  private final Rect sourceRect = new Rect();
  private final RectF rootDestination = new RectF();

  private Bitmap wallpaperSource;
  private Bitmap blurred;
  private SurfaceView videoSurface;
  private boolean active;
  private boolean videoSource;
  private boolean capturePending;
  private boolean closed;
  private int generation;
  private long lastVideoCaptureAt;

  private final Runnable scheduledVideoCapture =
      new Runnable() {
        @Override
        public void run() {
          captureVideoAtCadence();
        }
      };

  FrostedBackdrop(View root) { this.root = root; }

  void setActive(boolean value) {
    active = value;
    if (!active) main.removeCallbacks(scheduledVideoCapture);
    else if (videoSource) scheduleVideoCapture(0L);
  }

  void registerGlassView(View view) {
    if (view == null || closed) return;
    registered.add(view);
    if (videoSource && active) scheduleVideoCapture(0L);
  }

  void unregisterGlassView(View view) {
    if (view != null) registered.remove(view);
  }

  /** Composes the supplied photo into the root's low-resolution coordinate space. */
  void setWallpaperSource(Bitmap bitmap, boolean fitMode) {
    if (closed || bitmap == null || bitmap.isRecycled()) return;
    Bitmap composed = composePhoto(bitmap, fitMode);
    if (composed == null) return;
    recycle(wallpaperSource);
    wallpaperSource = composed;
    videoSource = false;
    videoSurface = null;
    main.removeCallbacks(scheduledVideoCapture);
    queueBlur(copyOf(composed));
  }

  /** Restores the latest photo source after a video surface is released. */
  void useWallpaperSource() {
    videoSource = false;
    videoSurface = null;
    main.removeCallbacks(scheduledVideoCapture);
    if (wallpaperSource != null) queueBlur(copyOf(wallpaperSource));
  }

  /**
   * Samples only the video SurfaceView, then composites it into a root-sized low-res source. The
   * request is capped at one per second and is useful both for moving launcher video and the
   * paused frame retained below Radio.
   */
  void setVideoSurface(SurfaceView surface) {
    if (closed) return;
    videoSource = surface != null;
    videoSurface = surface;
    if (videoSource && active) scheduleVideoCapture(0L);
  }

  /** Captures the retained video frame immediately before an overlay is shown. */
  void captureVideoNow() { captureVideo(true); }

  private void captureVideoAtCadence() { captureVideo(false); }

  private void captureVideo(boolean force) {
    if (closed || !active || !videoSource || capturePending || registered.isEmpty()) return;
    final SurfaceView surface = videoSurface;
    if (surface == null || surface.getWidth() <= 0 || surface.getHeight() <= 0 || !surface.isShown()) {
      scheduleVideoCapture(VIDEO_REFRESH_MS);
      return;
    }
    final long now = android.os.SystemClock.uptimeMillis();
    long remaining = VIDEO_REFRESH_MS - (now - lastVideoCaptureAt);
    if (!force && remaining > 0L) {
      scheduleVideoCapture(remaining);
      return;
    }
    int[] dimensions = lowResolutionDimensions();
    if (dimensions == null) return;
    final int rootWidth = root.getWidth();
    final int rootHeight = root.getHeight();
    final int outputWidth = dimensions[0];
    final int outputHeight = dimensions[1];
    final Bitmap frame =
        Bitmap.createBitmap(
            Math.max(1, Math.round(surface.getWidth() * outputWidth / (float) rootWidth)),
            Math.max(1, Math.round(surface.getHeight() * outputHeight / (float) rootHeight)),
            Bitmap.Config.ARGB_8888);
    capturePending = true;
    try {
      PixelCopy.request(
          surface,
          frame,
          result -> {
            capturePending = false;
            lastVideoCaptureAt = android.os.SystemClock.uptimeMillis();
            if (closed || !videoSource || surface != videoSurface || result != PixelCopy.SUCCESS) {
              recycle(frame);
              if (!closed && videoSource) scheduleVideoCapture(VIDEO_REFRESH_MS);
              return;
            }
            Bitmap composed = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(composed);
            canvas.drawColor(Color.BLACK);
            int left = Math.round(surface.getLeft() * outputWidth / (float) rootWidth);
            int top = Math.round(surface.getTop() * outputHeight / (float) rootHeight);
            int right = Math.round(surface.getRight() * outputWidth / (float) rootWidth);
            int bottom = Math.round(surface.getBottom() * outputHeight / (float) rootHeight);
            canvas.drawBitmap(frame, null, new Rect(left, top, right, bottom), bitmapPaint);
            recycle(frame);
            queueBlur(composed);
            scheduleVideoCapture(VIDEO_REFRESH_MS);
          },
          main);
    } catch (RuntimeException failure) {
      capturePending = false;
      recycle(frame);
      if (wallpaperSource != null) queueBlur(copyOf(wallpaperSource));
      scheduleVideoCapture(VIDEO_REFRESH_MS);
    }
  }

  /** Draws the cached root background behind a local rounded glass surface. */
  void draw(Canvas canvas, View view, RectF localBounds, float cornerRadius) {
    Bitmap value = blurred;
    if (value == null || value.isRecycled() || view == null || root.getWidth() <= 0 || root.getHeight() <= 0)
      return;
    int[] rootLocation = new int[2];
    int[] viewLocation = new int[2];
    root.getLocationOnScreen(rootLocation);
    view.getLocationOnScreen(viewLocation);
    float left = viewLocation[0] - rootLocation[0];
    float top = viewLocation[1] - rootLocation[1];
    sourceRect.set(0, 0, value.getWidth(), value.getHeight());
    rootDestination.set(-left, -top, root.getWidth() - left, root.getHeight() - top);
    int saved = canvas.save();
    if (cornerRadius > 0f) {
      clipPath.reset();
      clipPath.addRoundRect(localBounds, cornerRadius, cornerRadius, Path.Direction.CW);
      canvas.clipPath(clipPath);
    } else canvas.clipRect(localBounds);
    canvas.drawBitmap(value, sourceRect, rootDestination, bitmapPaint);
    canvas.restoreToCount(saved);
  }

  void close() {
    if (closed) return;
    closed = true;
    main.removeCallbacks(scheduledVideoCapture);
    registered.clear();
    recycle(wallpaperSource);
    wallpaperSource = null;
    recycle(blurred);
    blurred = null;
    worker.shutdownNow();
  }

  private void scheduleVideoCapture(long delay) {
    if (closed || !active || !videoSource || registered.isEmpty()) return;
    main.removeCallbacks(scheduledVideoCapture);
    main.postDelayed(scheduledVideoCapture, Math.max(0L, delay));
  }

  private Bitmap composePhoto(Bitmap source, boolean fitMode) {
    int[] dimensions = lowResolutionDimensions();
    if (dimensions == null) return null;
    int width = dimensions[0], height = dimensions[1];
    Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(result);
    canvas.drawColor(Color.BLACK);
    float scale =
        fitMode
            ? Math.min(width / (float) source.getWidth(), height / (float) source.getHeight())
            : Math.max(width / (float) source.getWidth(), height / (float) source.getHeight());
    int drawnWidth = Math.round(source.getWidth() * scale);
    int drawnHeight = Math.round(source.getHeight() * scale);
    int left = (width - drawnWidth) / 2;
    int top = (height - drawnHeight) / 2;
    canvas.drawBitmap(source, null, new Rect(left, top, left + drawnWidth, top + drawnHeight), bitmapPaint);
    return result;
  }

  private int[] lowResolutionDimensions() {
    int width = root.getWidth(), height = root.getHeight();
    if (width <= 0 || height <= 0) return null;
    float scale = Math.min(1f, MAX_EDGE / (float) Math.max(width, height));
    return new int[] {Math.max(1, Math.round(width * scale)), Math.max(1, Math.round(height * scale))};
  }

  private void queueBlur(final Bitmap source) {
    if (source == null || closed) {
      recycle(source);
      return;
    }
    final int request = ++generation;
    try {
      worker.execute(
          () -> {
            Bitmap value = null;
            try {
              value = blur(source);
            } finally {
              recycle(source);
            }
            final Bitmap result = value;
            main.post(
                () -> {
                  if (closed || request != generation) {
                    recycle(result);
                    return;
                  }
                  recycle(blurred);
                  blurred = result;
                  for (View view : registered) if (view != null) view.invalidate();
                });
          });
    } catch (RuntimeException rejected) {
      recycle(source);
    }
  }

  private static Bitmap blur(Bitmap source) {
    int width = source.getWidth(), height = source.getHeight();
    Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    int[] pixels = new int[width * height];
    int[] scratch = new int[pixels.length];
    source.getPixels(pixels, 0, width, 0, 0, width, height);
    blurHorizontal(pixels, scratch, width, height);
    blurVertical(scratch, pixels, width, height);
    result.setPixels(pixels, 0, width, 0, 0, width, height);
    return result;
  }

  private static void blurHorizontal(int[] input, int[] output, int width, int height) {
    for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
      int a = 0, r = 0, g = 0, b = 0, count = 0;
      for (int offset = -BLUR_RADIUS; offset <= BLUR_RADIUS; offset++) {
        int color = input[y * width + Math.max(0, Math.min(width - 1, x + offset))];
        a += Color.alpha(color); r += Color.red(color); g += Color.green(color); b += Color.blue(color); count++;
      }
      output[y * width + x] = Color.argb(a / count, r / count, g / count, b / count);
    }
  }

  private static void blurVertical(int[] input, int[] output, int width, int height) {
    for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
      int a = 0, r = 0, g = 0, b = 0, count = 0;
      for (int offset = -BLUR_RADIUS; offset <= BLUR_RADIUS; offset++) {
        int color = input[Math.max(0, Math.min(height - 1, y + offset)) * width + x];
        a += Color.alpha(color); r += Color.red(color); g += Color.green(color); b += Color.blue(color); count++;
      }
      output[y * width + x] = Color.argb(a / count, r / count, g / count, b / count);
    }
  }

  private static Bitmap copyOf(Bitmap source) {
    return source == null || source.isRecycled() ? null : source.copy(Bitmap.Config.ARGB_8888, false);
  }

  private static void recycle(Bitmap bitmap) {
    if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
  }
}
