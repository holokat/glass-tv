package local.glasstv.launcher;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import java.util.ArrayList;
import java.util.HashMap;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Decodes one 2x2 category-atlas tile at a time. It never expands a complete source atlas on the
 * UI thread, coalesces duplicate visible-card requests, and keeps only a small bitmap cache.
 */
final class RadioCategoryArtwork {
  interface Callback { void onArtwork(int artworkIndex, Bitmap bitmap); }

  private static final int ATLAS_COLUMNS = 2;
  private static final int CACHE_BYTES = 8 * 1024 * 1024;
  private final Context context;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final Object lock = new Object();
  private final Map<String, List<Request>> pending = new HashMap<>();
  private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(CACHE_BYTES) {
    @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
  };
  private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 15, TimeUnit.SECONDS,
      new LinkedBlockingQueue<Runnable>(8), new ThreadPoolExecutor.AbortPolicy());
  private volatile boolean closed;

  RadioCategoryArtwork(Context context) { this.context = context.getApplicationContext(); }

  void load(final int artworkIndex, final int targetPixels, final Callback callback) {
    if (callback == null || closed || artworkIndex < 0) return;
    final int target = Math.max(1, targetPixels);
    final String key = artworkIndex + ":" + target;
    Bitmap cached = cache.get(key);
    if (cached != null) {
      main.post(() -> { if (!closed) callback.onArtwork(artworkIndex, cached); });
      return;
    }
    synchronized (lock) {
      if (closed) return;
      List<Request> callbacks = pending.get(key);
      if (callbacks != null) {
        callbacks.add(new Request(artworkIndex, callback));
        return;
      }
      callbacks = new ArrayList<>();
      callbacks.add(new Request(artworkIndex, callback));
      pending.put(key, callbacks);
    }
    try {
      worker.execute(() -> complete(key, decode(artworkIndex, target)));
    } catch (RuntimeException rejected) {
      // A rejected task completes explicitly, so a later visible-card pass can retry rather than
      // leaving a request permanently pending.
      complete(key, null);
    }
  }

  void clearMemoryCache() { cache.evictAll(); }

  void close() {
    closed = true;
    worker.shutdownNow();
    synchronized (lock) { pending.clear(); }
    cache.evictAll();
  }

  private void complete(String key, Bitmap bitmap) {
    List<Request> callbacks;
    synchronized (lock) {
      callbacks = pending.remove(key);
      if (!closed && bitmap != null) cache.put(key, bitmap);
    }
    if (callbacks == null || closed) {
      if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
      return;
    }
    for (Request request : callbacks) {
      main.post(() -> {
        if (!closed) request.callback.onArtwork(request.artworkIndex, bitmap);
      });
    }
  }

  private Bitmap decode(int artworkIndex, int targetPixels) {
    String filename = String.format(java.util.Locale.US, "radio-artwork/category-atlas-%02d.webp",
        artworkIndex / 4);
    AssetManager assets = context.getAssets();
    try (InputStream input = assets.open(filename)) {
      BitmapRegionDecoder decoder = BitmapRegionDecoder.newInstance(input, false);
      if (decoder == null) return null;
      try {
        int atlasWidth = decoder.getWidth();
        int atlasHeight = decoder.getHeight();
        int cellWidth = atlasWidth / ATLAS_COLUMNS;
        int cellHeight = atlasHeight / ATLAS_COLUMNS;
        if (cellWidth <= 0 || cellHeight <= 0) return null;
        int slot = artworkIndex % 4;
        Rect region = new Rect((slot % ATLAS_COLUMNS) * cellWidth,
            (slot / ATLAS_COLUMNS) * cellHeight,
            ((slot % ATLAS_COLUMNS) + 1) * cellWidth,
            ((slot / ATLAS_COLUMNS) + 1) * cellHeight);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        options.inSampleSize = sampleFor(cellWidth, cellHeight, targetPixels);
        return decoder.decodeRegion(region, options);
      } finally {
        decoder.recycle();
      }
    } catch (Exception ignored) {
      return null;
    }
  }

  private static int sampleFor(int width, int height, int target) {
    int sample = 1;
    while (width / (sample * 2) >= target && height / (sample * 2) >= target) sample *= 2;
    return sample;
  }

  private static final class Request {
    final int artworkIndex;
    final Callback callback;
    Request(int artworkIndex, Callback callback) {
      this.artworkIndex = artworkIndex;
      this.callback = callback;
    }
  }
}
