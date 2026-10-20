package local.glasstv.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.spec.AlgorithmParameterSpec;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509KeyManager;
import javax.security.auth.x500.X500Principal;
import java.security.KeyPairGenerator;

/** Small LAN-only TLS API. It accepts no work after stop(), so HOME can restart it cleanly. */
final class LocalSyncServer {
    static final int PORT = 8743, MAX_IMAGE_BYTES = 8 * 1024 * 1024, MAX_TOTAL_BYTES = 1024 * 1024 * 1024;
    static final int MAX_VIDEO_BYTES = 1024 * 1024 * 1024;
    static final int MAX_IMAGES = 60, MAX_JSON = 64 * 1024;
    private static final String KEY_ALIAS = "glass-tv-tls-ec-v1", TOKEN_HASH = "pairedTokenSha256";
    private static final Pattern SHA = Pattern.compile("[0-9a-f]{64}");
    interface DeviceModel {
        JSONObject status() throws Exception;
        boolean isPassthroughInput(String id);
        void onPlaylistChanged(PlaylistStore.Playlist playlist);
        JSONObject movieStatus() throws MovieController.MovieRequestException;
        JSONObject playMovie(JSONObject request, InetAddress peer) throws MovieController.MovieRequestException;
        JSONObject controlMovie(JSONObject request) throws MovieController.MovieRequestException;
    }
    interface Listener {
        void onListenerStateChanged(boolean listening, String errorClass);
    }

    private final Context context;
    private final DeviceModel model;
    private final PlaylistStore playlists;
    private final MovieLibraryController movieLibrary;
    private final Listener listener;
    private final File photos, staging;
    private final android.content.SharedPreferences prefs;
    private final SecureRandom random = new SecureRandom();
    private final Object pairingLock = new Object();
    private final Object mutationLock = new Object();
    private final java.util.Set<SSLSocket> activeClients = java.util.Collections.synchronizedSet(new java.util.HashSet<SSLSocket>());
    private volatile boolean running;
    private volatile long generation;
    private volatile SSLServerSocket socket;
    private volatile ExecutorService clients;
    private final ScheduledExecutorService retries = new ScheduledThreadPoolExecutor(1);
    private ScheduledFuture<?> retry;
    private int retryAttempt;
    private volatile String pairCode;
    private volatile long pairingEndsAt;
    private volatile int pairingAttempts;
    private String deviceId;
    private String fingerprint;
    private X509Certificate certificate;

    LocalSyncServer(Context context, DeviceModel model, MovieLibraryController movieLibrary) {
        this(context, model, movieLibrary, null);
    }

    LocalSyncServer(Context context, DeviceModel model, MovieLibraryController movieLibrary, Listener listener) {
        this.context = context.getApplicationContext(); this.model = model; this.movieLibrary = movieLibrary;
        this.listener = listener;
        this.photos = new File(context.getFilesDir(), "photos"); this.staging = new File(context.getFilesDir(), "staging");
        this.photos.mkdirs(); this.staging.mkdirs();
        this.playlists = new PlaylistStore(context.getFilesDir());
        this.prefs = context.getSharedPreferences("glass-tv-private", Context.MODE_PRIVATE);
        this.deviceId = prefs.getString("deviceId", null);
        if (deviceId == null) { deviceId = java.util.UUID.randomUUID().toString(); prefs.edit().putString("deviceId", deviceId).apply(); }
    }

    /** Shows a fresh pair code; the token is never retained in plaintext. */
    String openPairing() throws Exception {
        ensureIdentity();
        synchronized (pairingLock) {
            pairCode = String.format(Locale.US, "%06d", random.nextInt(1_000_000));
            pairingAttempts = 0; pairingEndsAt = System.currentTimeMillis() + 120_000L;
            return pairCode;
        }
    }
    String fingerprint() throws Exception { ensureIdentity(); return fingerprint; }
    String deviceId() { return deviceId; }
    boolean isPairingOpen() { return pairCode != null && System.currentTimeMillis() < pairingEndsAt; }

    synchronized void start() {
        if (running) return;
        running = true;
        final long epoch = ++generation;
        retryAttempt = 0;
        launch(epoch);
    }

    private synchronized void launch(final long epoch) {
        if (!running || generation != epoch) return;
        final ExecutorService pool = new ThreadPoolExecutor(1, 3, 15, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(8), new ThreadPoolExecutor.AbortPolicy());
        clients = pool;
        Thread acceptor = new Thread(new Runnable() { public void run() { acceptLoop(epoch, pool); } }, "GlassTV-TLS");
        acceptor.setDaemon(true); acceptor.start();
    }
    synchronized void stop() {
        running = false;
        generation++;
        retryAttempt = 0;
        if (retry != null) { retry.cancel(false); retry = null; }
        SSLServerSocket old = socket;
        socket = null;
        if (old != null) try { old.close(); } catch (IOException ignored) { }
        final SSLSocket[] closing;
        synchronized (activeClients) {
            closing = activeClients.toArray(new SSLSocket[0]);
            activeClients.clear();
        }
        // Conscrypt sends TLS close_notify from close(). Activity lifecycle callbacks run
        // on main, so close accepted clients on a worker without blocking navigation.
        if (closing.length > 0) {
            Thread cleanup = new Thread(() -> {
                for (SSLSocket client : closing) try { client.close(); } catch (Exception ignored) { }
            }, "GlassTV-TLS-close");
            cleanup.setDaemon(true);
            cleanup.start();
        }
        if (clients != null) { clients.shutdownNow(); clients = null; }
        synchronized (pairingLock) { pairCode = null; pairingEndsAt = 0; }
        notifyListener(false, null);
    }

    void destroy() { stop(); retries.shutdownNow(); }

    private void acceptLoop(long epoch, ExecutorService pool) {
        SSLServerSocket opened = null;
        try {
            SSLContext tls = tlsContext();
            opened = (SSLServerSocket) tls.getServerSocketFactory().createServerSocket();
            opened.setReuseAddress(true);
            opened.setEnabledProtocols(new String[] { "TLSv1.2" });
            java.util.ArrayList<String> suites = new java.util.ArrayList<>();
            for (String suite : opened.getSupportedCipherSuites()) if (suite.startsWith("TLS_ECDHE_ECDSA_WITH_AES_") && suite.contains("GCM")) suites.add(suite);
            opened.setEnabledCipherSuites(suites.toArray(new String[0]));
            synchronized (this) {
                if (!running || generation != epoch) return;
                opened.bind(new java.net.InetSocketAddress(PORT), 12);
                socket = opened;
                retryAttempt = 0;
            }
            notifyListener(true, null);
            while (running && generation == epoch) {
                final SSLSocket client = (SSLSocket) opened.accept();
                client.setSoTimeout(12_000);
                synchronized (this) {
                    if (!running || generation != epoch) { client.close(); break; }
                    activeClients.add(client);
                }
                try { pool.execute(new Runnable() { public void run() { serve(client, epoch); } }); }
                catch (RuntimeException full) { activeClients.remove(client); try { client.close(); } catch (IOException ignored) { } }
            }
        } catch (Exception error) {
            android.util.Log.w("GlassTV", "Local receiver unavailable: " + error.getClass().getSimpleName());
            notifyListener(false, error.getClass().getSimpleName());
        } finally {
            if (opened != null) try { opened.close(); } catch (IOException ignored) { }
            synchronized (this) {
                if (generation == epoch) {
                    socket = null;
                    if (running) scheduleRetry(epoch);
                }
            }
            pool.shutdownNow();
        }
    }

    private void scheduleRetry(final long epoch) {
        if (retry != null && !retry.isDone()) return;
        final long delay = retryDelayMillis(retryAttempt++);
        retry = retries.schedule(new Runnable() {
            @Override public void run() {
                synchronized (LocalSyncServer.this) {
                    retry = null;
                    launch(epoch);
                }
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    static long retryDelayMillis(int attempt) {
        int shift = Math.max(0, Math.min(attempt, 5));
        return Math.min(30_000L, 1_000L << shift);
    }

    private void notifyListener(boolean listening, String errorClass) {
        if (listener == null) return;
        try { listener.onListenerStateChanged(listening, errorClass); } catch (Exception ignored) { }
    }

    private void serve(SSLSocket client, long epoch) {
        try {
            if (!running || generation != epoch) return;
            try { client.startHandshake(); } catch (Exception handshake) {
                android.util.Log.e("GlassTV", "TLS handshake failed", handshake);
                return;
            }
            Request request = Request.read(new BufferedInputStream(client.getInputStream()));
            if (!running || generation != epoch) return;
            Response response = route(request, client.getInetAddress());
            response.write(client.getOutputStream());
        } catch (MovieController.MovieRequestException e) {
            try { Response.error(e.status, e.code, e.getMessage()).write(client.getOutputStream()); } catch (Exception ignored) { }
        } catch (MovieLibraryController.RequestException e) {
            try { Response.error(e.status, e.code, e.getMessage()).write(client.getOutputStream()); } catch (Exception ignored) { }
        } catch (HttpError e) {
            try { Response.error(e.status, e.code, e.getMessage()).write(client.getOutputStream()); } catch (Exception ignored) { }
        } catch (Exception e) {
            try { Response.error(400, "invalid", "Invalid request").write(client.getOutputStream()); } catch (Exception ignored) { }
        } finally { activeClients.remove(client); try { client.close(); } catch (IOException ignored) { } }
    }

    private Response route(Request r, InetAddress peer) throws Exception {
        if ("POST".equals(r.method) && "/pair".equals(r.path)) return pair(r);
        if (!authorised(r)) throw new HttpError(401, "unauthorized", "Authorization is required");
        if ("GET".equals(r.method) && "/status".equals(r.path)) return Response.json(200, model.status());
        if ("GET".equals(r.method) && "/movies/status".equals(r.path)) return Response.json(200, model.movieStatus());
        if ("POST".equals(r.method) && "/movies/play".equals(r.path)) return Response.json(202, model.playMovie(r.json(), peer));
        if ("POST".equals(r.method) && "/movies/control".equals(r.path)) return Response.json(202, model.controlMovie(r.json()));
        if ("PUT".equals(r.method) && "/movies/library".equals(r.path)) { synchronized (mutationLock) { return Response.json(200, movieLibrary.replaceLibrary(r.json(MovieLibraryController.MAX_LIBRARY_BYTES))); } }
        if ("POST".equals(r.method) && "/movies/library/poll".equals(r.path)) return Response.json(200, movieLibrary.poll(r.json()));
        if ("PUT".equals(r.method) && r.path.startsWith("/movies/library/posters/") && r.path.endsWith(".jpg")) { synchronized (mutationLock) { return uploadMoviePoster(r); } }
        if ("PUT".equals(r.method) && "/playlist".equals(r.path)) { synchronized (mutationLock) { return updatePlaylist(r); } }
        if ("PUT".equals(r.method) && ((r.path.startsWith("/images/") && r.path.endsWith(".jpg")) || (r.path.startsWith("/videos/") && r.path.endsWith(".mp4")))) { synchronized (mutationLock) { return upload(r); } }
        throw new HttpError(400, "invalid", "Unknown endpoint");
    }

    private Response pair(Request r) throws Exception {
        JSONObject json = r.json();
        if (json.optInt("apiVersion", 0) != 1 || !json.has("code") || !json.has("clientName")) throw new HttpError(400, "invalid", "Invalid pairing request");
        synchronized (pairingLock) {
            if (pairCode == null || System.currentTimeMillis() >= pairingEndsAt) throw new HttpError(403, "pairing_closed", "Pairing is closed");
            if (pairingAttempts >= 5) { pairCode = null; throw new HttpError(429, "pairing_limited", "Pairing attempt limit reached"); }
            if (!constantEquals(pairCode, json.getString("code"))) { pairingAttempts++; if (pairingAttempts >= 5) pairCode = null; throw new HttpError(403, "pairing_closed", "Invalid pairing code"); }
            byte[] token = new byte[32]; random.nextBytes(token);
            String encoded = Base64.encodeToString(token, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
            String tokenHash = hex(MessageDigest.getInstance("SHA-256").digest(encoded.getBytes(StandardCharsets.UTF_8)));
            if (!prefs.edit().putString(TOKEN_HASH, tokenHash).commit()) throw new HttpError(507, "storage_full", "Could not save pairing");
            pairCode = null; pairingEndsAt = 0; pairingAttempts = 0;
            JSONObject out = new JSONObject(); out.put("apiVersion", 1); out.put("deviceId", deviceId); out.put("token", encoded); out.put("tokenType", "Bearer");
            return Response.json(201, out);
        }
    }

    private boolean authorised(Request r) throws Exception {
        String header = r.headers.get("authorization");
        if (header == null || !header.startsWith("Bearer ")) return false;
        String stored = prefs.getString(TOKEN_HASH, null);
        if (stored == null) return false;
        String actual = hex(MessageDigest.getInstance("SHA-256").digest(header.substring(7).getBytes(StandardCharsets.UTF_8)));
        return constantEquals(stored, actual);
    }

    private Response uploadMoviePoster(Request r) throws Exception {
        String id = r.path.substring("/movies/library/posters/".length(), r.path.length() - 4);
        movieLibrary.uploadPoster(id, r.body, r.contentLength, r.headers.get("content-type"));
        return Response.json(200, new JSONObject().put("supported", true));
    }

    private Response upload(Request r) throws Exception {
        boolean video = r.path.startsWith("/videos/");
        String prefix = video ? "/videos/" : "/images/";
        String extension = video ? ".mp4" : ".jpg";
        String id = r.path.substring(prefix.length(), r.path.length() - extension.length());
        if (!SHA.matcher(id).matches()) throw new HttpError(400, "invalid", "Invalid media identifier");
        if (!(video ? "video/mp4" : "image/jpeg").equalsIgnoreCase(r.headers.get("content-type"))) throw new HttpError(415, "invalid_media", "Unexpected media content type");
        int limit = video ? MAX_VIDEO_BYTES : MAX_IMAGE_BYTES;
        if (r.contentLength < 1 || r.contentLength > limit) throw new HttpError(413, "media_too_large", "Media file exceeds upload limit");
        File finalFile = new File(photos, id + extension);
        if (finalFile.exists()) {
            MessageDigest duplicateDigest = MessageDigest.getInstance("SHA-256");
            copyExact(r.body, new OutputStream() { public void write(int b) { } public void write(byte[] b, int o, int n) { } }, r.contentLength, duplicateDigest);
            if (!constantEquals(id, hex(duplicateDigest.digest()))) throw new HttpError(400, "invalid", "Media hash does not match content");
            return Response.json(200, mediaInfo(id, finalFile, false));
        }
        Storage storage = storage();
        if (storage.count >= MAX_IMAGES || storage.bytes + r.contentLength > MAX_TOTAL_BYTES) throw new HttpError(507, "storage_full", "TV media storage is full");
        if (photos.getUsableSpace() < r.contentLength + 256L * 1024 * 1024) throw new HttpError(507, "storage_full", "Not enough free TV storage; at least 256 MiB must remain free");
        File temporary = File.createTempFile("upload-", extension, staging);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (FileOutputStream out = new FileOutputStream(temporary)) { copyExact(r.body, out, r.contentLength, digest); out.getFD().sync(); }
            if (!constantEquals(id, hex(digest.digest()))) throw new HttpError(400, "invalid", "Media hash does not match content");
            JSONObject info = mediaInfo(id, temporary, true);
            try { Files.move(temporary.toPath(), finalFile.toPath(), StandardCopyOption.ATOMIC_MOVE); }
            catch (java.nio.file.FileAlreadyExistsException alreadyThere) { return Response.json(200, mediaInfo(id, finalFile, false)); }
            catch (java.nio.file.AtomicMoveNotSupportedException noAtomicMove) { throw new HttpError(400, "invalid", "Storage does not support atomic upload"); }
            return Response.json(201, info);
        } finally { if (temporary.exists()) temporary.delete(); }
    }

    private JSONObject mediaInfo(String id, File file, boolean validate) throws Exception {
        if (!file.getName().endsWith(".mp4")) return imageInfo(id, file, validate).put("kind", "photo");
        android.media.MediaMetadataRetriever metadata = new android.media.MediaMetadataRetriever();
        android.media.MediaExtractor extractor = null;
        try {
            if (validate) {
                byte[] header = new byte[12];
                try (FileInputStream in = new FileInputStream(file)) { if (in.read(header) < 12 || header[4]!='f' || header[5]!='t' || header[6]!='y' || header[7]!='p') throw new HttpError(415, "invalid_media", "Use an MP4 video file"); }
            }
            metadata.setDataSource(file.getAbsolutePath());
            String hasVideo = metadata.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO);
            int width = Integer.parseInt(metadata.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            int height = Integer.parseInt(metadata.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            long duration = Long.parseLong(metadata.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION));
            if (!"yes".equals(hasVideo) || width < 1 || height < 1 || duration < 1 || width > 3840 || height > 3840 || (long)width * height > 8_294_400L) throw new HttpError(415, "invalid_media", "Video must be a playable MP4 up to 4K");
            if (validate) {
                extractor = new android.media.MediaExtractor(); extractor.setDataSource(file.getAbsolutePath());
                boolean compatible = false;
                android.media.MediaCodecList codecs = new android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS);
                for (int i=0; i<extractor.getTrackCount(); i++) {
                    android.media.MediaFormat format = extractor.getTrackFormat(i);
                    String mime = format.getString(android.media.MediaFormat.KEY_MIME);
                    if (("video/avc".equals(mime) || "video/hevc".equals(mime)) && codecs.findDecoderForFormat(format) != null) compatible = true;
                }
                if (!compatible) throw new HttpError(415, "invalid_media", "This TV cannot decode the video. Use H.264 or HEVC in an MP4 container");
            }
            return new JSONObject().put("id",id).put("kind","video").put("bytes",file.length()).put("width",width).put("height",height).put("durationSeconds",duration/1000.0);
        } catch (HttpError e) { throw e; }
        catch (Exception e) { throw new HttpError(415, "invalid_media", "Video metadata could not be read. Use an H.264 or HEVC MP4"); }
        finally { metadata.release(); if (extractor != null) extractor.release(); }
    }

    private JSONObject imageInfo(String id, File image) throws Exception { return imageInfo(id, image, false); }
    private JSONObject imageInfo(String id, File image, boolean validateDecode) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(image.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outMimeType == null || !"image/jpeg".equalsIgnoreCase(bounds.outMimeType)) throw new HttpError(415, "invalid_image", "Not a valid JPEG");
        if (bounds.outWidth > 3840 || bounds.outHeight > 3840 || (long) bounds.outWidth * bounds.outHeight > 8_294_400L) throw new HttpError(415, "invalid_image", "Image dimensions exceed limit");
        if (validateDecode) {
        BitmapFactory.Options decode = new BitmapFactory.Options(); decode.inSampleSize = 2;
        Bitmap decoded = BitmapFactory.decodeFile(image.getAbsolutePath(), decode);
        if (decoded == null) throw new HttpError(415, "invalid_image", "JPEG cannot be decoded");
        decoded.recycle();
        }
        JSONObject out = new JSONObject(); out.put("id", id); out.put("bytes", image.length()); out.put("width", bounds.outWidth); out.put("height", bounds.outHeight); return out;
    }

    private Response updatePlaylist(Request r) throws Exception {
        JSONObject json = r.json();
        if (json.optInt("apiVersion", 0) != 1 || !json.has("baseRevision")) throw new HttpError(400, "invalid", "Invalid playlist");
        synchronized (playlists) {
            PlaylistStore.Playlist old = playlists.get();
            if (json.getLong("baseRevision") != old.revision) throw new HttpError(409, "revision_conflict", "Playlist changed on TV");
            int interval = json.getInt("intervalSeconds");
            if (interval < PlaylistStore.MIN_INTERVAL || interval > PlaylistStore.MAX_INTERVAL) throw new HttpError(400, "invalid", "Interval is outside allowed range");
            PlaylistStore.Playlist next = new PlaylistStore.Playlist(); next.revision = old.revision + 1; next.intervalSeconds = interval; next.shuffle = json.optBoolean("shuffle", false);
            try {
                next.weather = json.has("weather") ? WeatherSettings.validate(json.getJSONObject("weather")) : WeatherSettings.copy(old.weather);
            } catch (Exception invalidWeather) { throw new HttpError(400, "invalid_weather", invalidWeather.getMessage()); }
            try {
                next.apps = json.has("apps") ? AppsSettings.validate(json.getJSONObject("apps")) : AppsSettings.copy(old.apps);
            } catch (Exception invalidApps) { throw new HttpError(400, "invalid_apps", invalidApps.getMessage()); }
            try {
                next.layout =
                    json.has("layout")
                        ? LauncherLayout.validate(json.getJSONObject("layout"))
                        : LauncherLayout.copy(old.layout);
            } catch (Exception invalidLayout) {
                throw new HttpError(400, "invalid_layout", invalidLayout.getMessage());
            }
            next.videoPlayback = json.optString("videoPlayback", "cycle"); next.muteVideos = json.optBoolean("muteVideos", true);
            next.slideshowEnabled = json.optBoolean("slideshowEnabled", old.slideshowEnabled);
            if (json.has("showTVInterface") && !(json.opt("showTVInterface") instanceof Boolean)) {
                throw new HttpError(400, "invalid", "showTVInterface must be a boolean");
            }
            next.showTVInterface = json.has("showTVInterface") ? json.getBoolean("showTVInterface") : old.showTVInterface;
            next.fitMode = json.optString("fitMode", old.fitMode);
            if (!"fill".equals(next.fitMode) && !"fit".equals(next.fitMode)) throw new HttpError(400, "invalid", "Unknown screen fit mode");
            next.loopVideoId = json.isNull("loopVideoId") ? null : json.optString("loopVideoId", null);
            if (!"cycle".equals(next.videoPlayback) && !"loop".equals(next.videoPlayback)) throw new HttpError(400, "invalid", "Unknown video playback mode");
            String input = json.optString("selectedInputId", null); if (input != null && input.length() == 0) input = null;
            if (input != null && !model.isPassthroughInput(input)) throw new HttpError(400, "invalid", "Unknown TV input"); next.selectedInputId = input;
            JSONArray list = json.optJSONArray("images"); if (list == null) throw new HttpError(400, "invalid", "Images are required");
            Set<String> seen = new HashSet<>(), missing = new HashSet<>();
            if (list.length() > MAX_IMAGES) throw new HttpError(400, "invalid", "Too many playlist images");
            for (int i = 0; i < list.length(); i++) {
                JSONObject item = list.getJSONObject(i); String id = item.optString("id", ""); String name = item.optString("name", "");
                if (!SHA.matcher(id).matches() || utf8Length(name) > 255 || !seen.add(id)) throw new HttpError(400, "invalid", "Invalid playlist image");
                String kind = item.optString("kind", "photo");
                if (!"photo".equals(kind) && !"video".equals(kind)) throw new HttpError(400, "invalid", "Unknown media kind");
                if (!new File(photos, id + ("video".equals(kind) ? ".mp4" : ".jpg")).isFile()) missing.add(id);
                next.images.add(new PlaylistStore.Item(id, name, kind));
            }
            if (!missing.isEmpty()) { JSONObject extra = new JSONObject(); extra.put("missingImages", new JSONArray(missing)); return Response.error(409, "missing_images", "Upload all playlist images first", extra); }
            if ("loop".equals(next.videoPlayback)) { boolean found = false; for (PlaylistStore.Item item : next.images) if ("video".equals(item.kind) && item.id.equals(next.loopVideoId)) found = true; if (!found) throw new HttpError(400, "invalid", "Choose a video in this playlist to loop"); }
            playlists.replace(next); model.onPlaylistChanged(next);
            JSONObject out = new JSONObject(); out.put("revision", next.revision); return Response.json(200, out);
        }
    }

    PlaylistStore.Playlist playlist() throws PlaylistStore.IOExceptionLike { return playlists.get(); }
    void replaceLocalPlaylist(PlaylistStore.Playlist next) throws PlaylistStore.IOExceptionLike { synchronized (mutationLock) { playlists.replace(next); } }
    /**
     * Atomically persists a standalone launcher settings edit. The callback starts with a copy
     * of the latest committed playlist, so it must mutate only apps, weather, or layout; all
     * media, playback, pairing and input fields remain the current values. Validation happens
     * before the single revision increment and the launcher is notified only after persistence.
     */
    PlaylistStore.Playlist updateSettings(PlaylistStore.SettingsMutation mutation) throws Exception {
        synchronized (mutationLock) {
            PlaylistStore.Playlist next = playlists.updateSettings(mutation);
            model.onPlaylistChanged(next);
            return next;
        }
    }
    PlaylistStore.Playlist selectInput(String id) throws Exception {
        synchronized (mutationLock) {
            if (!model.isPassthroughInput(id)) throw new HttpError(400, "invalid", "Unknown TV input");
            PlaylistStore.Playlist next = playlists.get(); next.revision++; next.selectedInputId = id;
            playlists.replace(next); return next;
        }
    }
    File photoFile(String id) { return new File(photos, id + ".jpg"); }
    File videoFile(String id) { return new File(photos, id + ".mp4"); }
    private Storage storage() {
        long bytes = 0; int count = 0; File[] files = photos.listFiles(); if (files != null) for (File file : files) if (file.isFile() && (file.getName().endsWith(".jpg") || file.getName().endsWith(".mp4"))) { count++; bytes += file.length(); } return new Storage(bytes, count);
    }
    JSONObject storageJson() throws Exception { Storage s = storage(); JSONObject out = new JSONObject(); out.put("usedBytes", s.bytes); out.put("maxBytes", MAX_TOTAL_BYTES); return out; }
    JSONArray storedImagesJson() throws Exception { JSONArray out = new JSONArray(); File[] files = photos.listFiles(); if (files != null) for (File file : files) if ((file.getName().endsWith(".jpg") || file.getName().endsWith(".mp4"))) { String id = file.getName().substring(0, file.getName().length()-4); if (SHA.matcher(id).matches()) out.put(mediaInfo(id, file, false)); } return out; }
    private static final class Storage { final long bytes; final int count; Storage(long bytes, int count) { this.bytes=bytes;this.count=count; } }

    private SSLContext tlsContext() throws Exception {
        ensureIdentity(); KeyStore keys = KeyStore.getInstance("AndroidKeyStore"); keys.load(null);
        KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); factory.init(keys, null);
        KeyManager[] managers = factory.getKeyManagers();
        for (int i=0; i<managers.length; i++) if (managers[i] instanceof X509KeyManager) managers[i] = new AliasKeyManager((X509KeyManager) managers[i], KEY_ALIAS);
        SSLContext context = SSLContext.getInstance("TLS"); context.init(managers, null, random); return context;
    }
    private synchronized void ensureIdentity() throws Exception {
        if (certificate != null) return;
        KeyStore keys = KeyStore.getInstance("AndroidKeyStore"); keys.load(null);
        if (!keys.containsAlias(KEY_ALIAS)) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");
            AlgorithmParameterSpec spec = new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                    .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                    .setAlgorithmParameterSpec(new java.security.spec.ECGenParameterSpec("secp256r1"))
                    .setCertificateSubject(new X500Principal("CN=Glass TV Local Sync"))
                    .setCertificateSerialNumber(BigInteger.valueOf(System.currentTimeMillis()))
                    .setCertificateNotBefore(new java.util.Date(System.currentTimeMillis() - 60_000L))
                    .setCertificateNotAfter(new java.util.Date(System.currentTimeMillis() + 3650L * 24L * 60L * 60L * 1000L)).build();
            generator.initialize(spec); generator.generateKeyPair();
        }
        certificate = (X509Certificate) keys.getCertificate(KEY_ALIAS);
        fingerprint = grouped(hex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded())));
    }

    private static final class AliasKeyManager extends X509ExtendedKeyManager {
        private final X509KeyManager delegate; private final String alias;
        AliasKeyManager(X509KeyManager delegate, String alias) { this.delegate=delegate; this.alias=alias; }
        public String[] getClientAliases(String t, java.security.Principal[] i) { return delegate.getClientAliases(t,i); }
        public String chooseClientAlias(String[] t, java.security.Principal[] i, java.net.Socket s) { return delegate.chooseClientAlias(t,i,s); }
        public String[] getServerAliases(String t, java.security.Principal[] i) { return t != null && t.startsWith("EC") ? new String[] { alias } : null; }
        public String chooseServerAlias(String t, java.security.Principal[] i, java.net.Socket s) { return t != null && t.startsWith("EC") ? alias : null; }
        public java.security.PrivateKey getPrivateKey(String a) { return delegate.getPrivateKey(a); }
        public X509Certificate[] getCertificateChain(String a) { return delegate.getCertificateChain(a); }
        public String chooseEngineServerAlias(String t, java.security.Principal[] i, javax.net.ssl.SSLEngine e) { return t != null && t.startsWith("EC") ? alias : null; }
    }

    private static void copyExact(InputStream in, OutputStream out, int bytes, MessageDigest digest) throws Exception { byte[] buffer=new byte[32768]; int remaining=bytes; while(remaining>0){int n=in.read(buffer,0,Math.min(buffer.length,remaining));if(n<0)throw new HttpError(400,"invalid","Truncated request body");out.write(buffer,0,n);digest.update(buffer,0,n);remaining-=n;} }
    private static int utf8Length(String value) { return value.getBytes(StandardCharsets.UTF_8).length; }
    private static String hex(byte[] data) { StringBuilder s=new StringBuilder(data.length*2); for(byte b:data)s.append(String.format(Locale.US,"%02x",b&255)); return s.toString(); }
    private static String grouped(String hex) { StringBuilder s=new StringBuilder();for(int i=0;i<hex.length();i++){if(i>0&&i%4==0)s.append(' ');s.append(hex.charAt(i));}return s.toString(); }
    private static boolean constantEquals(String a,String b){if(a==null||b==null)return false;byte[] x=a.getBytes(StandardCharsets.UTF_8),y=b.getBytes(StandardCharsets.UTF_8);return MessageDigest.isEqual(x,y);}

    private static final class Request {
        final String method,path; final java.util.Map<String,String> headers; final InputStream body; final int contentLength;
        Request(String method,String path,java.util.Map<String,String> headers,InputStream body,int length){this.method=method;this.path=path;this.headers=headers;this.body=body;this.contentLength=length;}
        static Request read(InputStream input) throws Exception { String first=line(input,4096);if(first==null)throw new HttpError(400,"invalid","Missing request");String[] p=first.split(" ");if(p.length!=3||!p[2].startsWith("HTTP/1."))throw new HttpError(400,"invalid","Malformed request");java.util.Map<String,String> h=new java.util.HashMap<>();int total=first.length();String l;while((l=line(input,8192))!=null&&!l.isEmpty()){total+=l.length();if(total>16384)throw new HttpError(400,"invalid","Headers too large");int colon=l.indexOf(':');if(colon<1)throw new HttpError(400,"invalid","Malformed header");h.put(l.substring(0,colon).toLowerCase(Locale.US),l.substring(colon+1).trim());}if(h.containsKey("transfer-encoding"))throw new HttpError(400,"invalid","Transfer encoding is unsupported");int len=0;if(h.containsKey("content-length"))try{len=Integer.parseInt(h.get("content-length"));}catch(Exception e){throw new HttpError(400,"invalid","Invalid content length");}if(len<0||len>MAX_VIDEO_BYTES)throw new HttpError(413,"image_too_large","Request body too large");return new Request(p[0],p[1],h,input,len); }
        JSONObject json() throws Exception { return json(MAX_JSON); }
        JSONObject json(int maximum) throws Exception { if(contentLength<1||contentLength>maximum)throw new HttpError(400,"invalid","Invalid JSON body");byte[] b=new byte[contentLength];int o=0,n;while(o<b.length&&(n=body.read(b,o,b.length-o))>0)o+=n;if(o!=b.length)throw new HttpError(400,"invalid","Truncated JSON");try{return new JSONObject(new String(b,StandardCharsets.UTF_8));}catch(Exception e){throw new HttpError(400,"invalid","Invalid JSON");} }
        static String line(InputStream in,int max)throws IOException,HttpError{java.io.ByteArrayOutputStream b=new java.io.ByteArrayOutputStream();int c;while((c=in.read())!=-1){if(c=='\n')break;if(c!='\r')b.write(c);if(b.size()>max)throw new HttpError(400,"invalid","Line too long");}if(c==-1&&b.size()==0)return null;return b.toString("US-ASCII");}
    }
    private static final class Response { final int status;final byte[] body;Response(int s,byte[]b){status=s;body=b;}static Response json(int s,JSONObject b){return new Response(s,b.toString().getBytes(StandardCharsets.UTF_8));}static Response error(int s,String c,String m){return error(s,c,m,null);}static Response error(int s,String c,String m,JSONObject x){try{JSONObject e=new JSONObject().put("code",c).put("message",m);if(x!=null)for(java.util.Iterator<String>i=x.keys();i.hasNext();){String k=i.next();e.put(k,x.get(k));}return json(s,new JSONObject().put("error",e));}catch(Exception impossible){return new Response(500,new byte[0]);}}void write(OutputStream out)throws IOException{String statusText=status==200?"OK":status==201?"Created":status==202?"Accepted":status==401?"Unauthorized":status==403?"Forbidden":status==409?"Conflict":status==413?"Payload Too Large":status==415?"Unsupported Media Type":status==429?"Too Many Requests":status==503?"Service Unavailable":status==507?"Insufficient Storage":"Bad Request";out.write(("HTTP/1.1 "+status+" "+statusText+"\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(body);out.flush();}}
    private static final class HttpError extends Exception { final int status;final String code;HttpError(int s,String c,String m){super(m);status=s;code=c;} }
}
