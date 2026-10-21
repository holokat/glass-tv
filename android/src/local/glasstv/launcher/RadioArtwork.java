package local.glasstv.launcher;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.HttpsURLConnection;

/**
 * Bounded favicon retrieval for Radio Browser station metadata. This class intentionally returns
 * null when a favicon cannot be trusted or decoded; the caller owns an honest empty placeholder.
 */
final class RadioArtwork {
  interface Callback {
    /** Called on the main looper with the original station and its favicon, or null. */
    void onArtwork(RadioStationPolicy.Station station, Bitmap bitmap);
  }

  private static final int CONNECT_TIMEOUT_MS = 4_000;
  private static final int READ_TIMEOUT_MS = 5_000;
  private static final int BODY_DEADLINE_MS = 6_000;
  private static final int MAX_BODY_BYTES = 512 * 1024;
  private static final int MAX_REDIRECTS = 3;
  private static final int MAX_DIMENSION = 256;
  private static final int CACHE_BYTES = 4 * 1024 * 1024;
  private static final String USER_AGENT = "GlassTV/1.0 (Android TV radio artwork)";

  private final Handler main = new Handler(Looper.getMainLooper());
  private final Object lock = new Object();
  private final Map<String, List<Request>> pending = new HashMap<>();
  private final LruCache<String, Bitmap> cache =
      new LruCache<String, Bitmap>(CACHE_BYTES) {
        @Override
        protected int sizeOf(String key, Bitmap bitmap) {
          return bitmap.getByteCount();
        }
      };
  private final ThreadPoolExecutor worker =
      new ThreadPoolExecutor(
          1,
          1,
          15,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<Runnable>(12),
          new ThreadPoolExecutor.AbortPolicy());
  private boolean closed;

  void load(final RadioStationPolicy.Station station, final Callback callback) {
    if (callback == null) return;
    if (station == null || !RadioDirectory.safeHttpsUrl(station.artworkUrl)) {
      deliver(station, null, callback);
      return;
    }
    final String url = station.artworkUrl;
    Bitmap cached = cache.get(url);
    if (cached != null) {
      deliver(station, cached, callback);
      return;
    }
    synchronized (lock) {
      if (closed) {
        deliver(station, null, callback);
        return;
      }
      List<Request> callbacks = pending.get(url);
      if (callbacks != null) {
        callbacks.add(new Request(station, callback));
        return;
      }
      callbacks = new ArrayList<>();
      callbacks.add(new Request(station, callback));
      pending.put(url, callbacks);
    }
    try {
      worker.execute(
          () -> {
            Bitmap result = loadBitmap(url);
            complete(url, result);
          });
    } catch (RuntimeException rejected) {
      complete(url, null);
    }
  }

  void clearMemoryCache() {
    cache.evictAll();
  }

  void close() {
    List<Request> orphaned = new ArrayList<>();
    synchronized (lock) {
      if (closed) return;
      closed = true;
      for (List<Request> requests : pending.values()) orphaned.addAll(requests);
      pending.clear();
    }
    worker.shutdownNow();
    cache.evictAll();
    for (Request request : orphaned) deliver(request.station, null, request.callback);
  }

  private void complete(String url, Bitmap bitmap) {
    List<Request> callbacks;
    synchronized (lock) {
      callbacks = pending.remove(url);
      if (!closed && bitmap != null) cache.put(url, bitmap);
    }
    if (callbacks == null) return;
    for (Request request : callbacks) deliver(request.station, bitmap, request.callback);
  }

  private void deliver(final RadioStationPolicy.Station station, final Bitmap bitmap, final Callback callback) {
    main.post(() -> callback.onArtwork(station, bitmap));
  }

  private static Bitmap loadBitmap(String initialUrl) {
    String next = initialUrl;
    for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
      if (!RadioDirectory.safePublicHttpsUrl(next)) return null;
      HttpsURLConnection connection = null;
      try {
        connection = (HttpsURLConnection) new URL(next).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestProperty("Accept", "image/avif,image/webp,image/png,image/jpeg,image/*;q=0.8");
        int response = connection.getResponseCode();
        if (response >= 300 && response < 400) {
          String location = connection.getHeaderField("Location");
          if (location == null || redirect == MAX_REDIRECTS) return null;
          next = new URI(next).resolve(location).toString();
          continue;
        }
        if (response != 200) return null;
        String type = connection.getContentType();
        if (type != null && !type.toLowerCase(Locale.US).startsWith("image/")) return null;
        try (InputStream input = connection.getInputStream()) {
          return decode(readBounded(input));
        }
      } catch (Exception ignored) {
        return null;
      } finally {
        if (connection != null) connection.disconnect();
      }
    }
    return null;
  }

  private static byte[] readBounded(InputStream input) throws Exception {
    long deadline = System.nanoTime() + BODY_DEADLINE_MS * 1_000_000L;
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    int count;
    while ((count = input.read(buffer)) != -1) {
      if (System.nanoTime() > deadline || output.size() + count > MAX_BODY_BYTES)
        throw new IllegalStateException("Radio artwork response limit exceeded");
      output.write(buffer, 0, count);
    }
    return output.toByteArray();
  }

  private static Bitmap decode(byte[] bytes) {
    BitmapFactory.Options bounds = new BitmapFactory.Options();
    bounds.inJustDecodeBounds = true;
    BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
    int sample = 1;
    while (bounds.outWidth / sample > MAX_DIMENSION || bounds.outHeight / sample > MAX_DIMENSION)
      sample *= 2;
    BitmapFactory.Options options = new BitmapFactory.Options();
    options.inSampleSize = sample;
    options.inPreferredConfig = Bitmap.Config.ARGB_8888;
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
  }

  private static final class Request {
    final RadioStationPolicy.Station station;
    final Callback callback;

    Request(RadioStationPolicy.Station station, Callback callback) {
      this.station = station;
      this.callback = callback;
    }
  }
}
