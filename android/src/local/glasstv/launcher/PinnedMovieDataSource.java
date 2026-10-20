package local.glasstv.launcher;

import android.media.MediaDataSource;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.net.URL;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Locale;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Read-only, range-backed movie source. TLS trust applies to these connections only and accepts
 * only the leaf certificate fingerprint supplied through the authenticated paired channel.
 */
final class PinnedMovieDataSource extends MediaDataSource {
    private static final int CACHE_BYTES = 2 * 1024 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 8_000;
    private static final int READ_TIMEOUT_MS = 12_000;

    private final URL url;
    private final String bearerToken;
    private final String certificatePin;
    private final Object lock = new Object();
    private volatile boolean closed;
    private volatile HttpsURLConnection activeConnection;
    private volatile long length = -1;
    private volatile String entityTag;
    private long cacheStart = -1;
    private byte[] cache = new byte[0];

    PinnedMovieDataSource(URL url, String bearerToken, String certificatePin) {
        this.url = url;
        this.bearerToken = bearerToken;
        this.certificatePin = certificatePin;
    }

    /** Performs the authenticated, pinned HEAD before MediaPlayer is handed this source. */
    void preflight() throws IOException {
        HttpsURLConnection connection = open("HEAD");
        setActive(connection);
        try {
            int code = connection.getResponseCode();
            verifyPeer(connection);
            checkOpen();
            if (code != HttpsURLConnection.HTTP_OK && code != HttpsURLConnection.HTTP_PARTIAL) {
                throw new IOException("Movie stream is unavailable");
            }
            String ranges = connection.getHeaderField("Accept-Ranges");
            if (ranges == null || !ranges.toLowerCase(Locale.US).contains("bytes")) {
                throw new IOException("Movie stream does not support byte ranges");
            }
            long declared = connection.getContentLengthLong();
            if (declared < 0) throw new IOException("Movie stream has no content length");
            length = declared;
            entityTag = connection.getHeaderField("ETag");
        } finally {
            clearActive(connection);
            disconnect(connection);
        }
    }

    @Override public int readAt(long position, byte[] target, int offset, int size) throws IOException {
        if (target == null || position < 0 || offset < 0 || size < 0 || offset > target.length || size > target.length - offset) {
            throw new IOException("Invalid movie read");
        }
        if (size == 0) return 0;
        if (closed) return -1;
        long knownLength = length;
        if (knownLength >= 0 && position >= knownLength) return -1;
        synchronized (lock) {
            if (closed) return -1;
            if (position < cacheStart || position >= cacheStart + cache.length) fillCache(position);
            if (cacheStart < 0 || cache.length == 0) return -1;
            int available = (int) Math.min((long) cache.length - (position - cacheStart), size);
            if (available <= 0) return -1;
            System.arraycopy(cache, (int) (position - cacheStart), target, offset, available);
            return available;
        }
    }

    @Override public long getSize() { return length; }

    /** Never waits on the cache lock: MediaPlayer may be blocked in a network read on it. */
    @Override public void close() {
        closed = true;
        HttpsURLConnection connection = activeConnection;
        if (connection != null) connection.disconnect();
        // Reads check closed before publishing cache data. Let the reading thread release lock.
        cache = new byte[0];
        cacheStart = -1;
    }

    private void fillCache(long position) throws IOException {
        checkOpen();
        long knownLength = length;
        long end = knownLength < 0 ? position + CACHE_BYTES - 1 : Math.min(knownLength - 1, position + CACHE_BYTES - 1);
        if (end < position) { cache = new byte[0]; cacheStart = position; return; }
        HttpsURLConnection connection = open("GET");
        setActive(connection);
        try {
            connection.setRequestProperty("Range", "bytes=" + position + "-" + end);
            if (entityTag != null && !entityTag.isEmpty()) connection.setRequestProperty("If-Range", entityTag);
            int code = connection.getResponseCode();
            verifyPeer(connection);
            checkOpen();
            if (code != HttpsURLConnection.HTTP_PARTIAL) {
                // A 200 response after If-Range means the representation changed, never mix it.
                throw new IOException("Movie stream returned an unexpected byte range");
            }
            ContentRange range = parseRange(connection.getHeaderField("Content-Range"));
            if (range.start != position || range.end != end || range.total != length) {
                throw new IOException("Movie stream returned an invalid byte range");
            }
            long expectedBytes = range.end - range.start + 1;
            long bodyLength = connection.getContentLengthLong();
            if (bodyLength != expectedBytes || bodyLength <= 0 || bodyLength > CACHE_BYTES || bodyLength > Integer.MAX_VALUE) {
                throw new IOException("Movie stream returned an invalid range length");
            }
            String responseTag = connection.getHeaderField("ETag");
            if (entityTag != null && responseTag != null && !entityTag.equals(responseTag)) {
                throw new IOException("Movie stream changed while playing");
            }
            if (entityTag == null && responseTag != null) entityTag = responseTag;
            byte[] loaded = new byte[(int) bodyLength];
            int used = 0;
            try (BufferedInputStream input = new BufferedInputStream(connection.getInputStream())) {
                while (used < loaded.length && !closed) {
                    int read = input.read(loaded, used, loaded.length - used);
                    if (read < 0) break;
                    used += read;
                }
            }
            checkOpen();
            if (used != loaded.length) throw new IOException("Movie stream ended early");
            cacheStart = position;
            cache = loaded;
        } finally {
            clearActive(connection);
            disconnect(connection);
        }
    }

    private void setActive(HttpsURLConnection connection) throws IOException {
        if (closed) { disconnect(connection); throw new IOException("Movie stream is closed"); }
        activeConnection = connection;
        if (closed) { clearActive(connection); disconnect(connection); throw new IOException("Movie stream is closed"); }
    }

    private void clearActive(HttpsURLConnection connection) {
        if (activeConnection == connection) activeConnection = null;
    }

    private void checkOpen() throws IOException {
        if (closed) throw new IOException("Movie stream is closed");
    }

    private HttpsURLConnection open(String method) throws IOException {
        checkOpen();
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestMethod(method);
        connection.setRequestProperty("Authorization", "Bearer " + bearerToken);
        connection.setRequestProperty("Accept-Encoding", "identity");
        connection.setSSLSocketFactory(pinnedContext().getSocketFactory());
        // The exact leaf pin authenticates this numeric IP endpoint; this verifier is instance-only.
        connection.setHostnameVerifier(new HostnameVerifier() {
            @Override public boolean verify(String ignored, javax.net.ssl.SSLSession session) { return true; }
        });
        return connection;
    }

    private SSLContext pinnedContext() throws IOException {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[] { new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] chain, String authType) { }
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                @Override public void checkServerTrusted(X509Certificate[] chain, String authType) throws java.security.cert.CertificateException {
                    if (chain == null || chain.length == 0) throw new java.security.cert.CertificateException("Missing movie certificate");
                    try {
                        String actual = hex(MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded()));
                        if (!MessageDigest.isEqual(actual.getBytes("US-ASCII"), certificatePin.getBytes("US-ASCII"))) {
                            throw new java.security.cert.CertificateException("Movie certificate pin did not match");
                        }
                    } catch (java.security.cert.CertificateException bad) { throw bad; }
                    catch (Exception failure) { throw new java.security.cert.CertificateException("Movie certificate could not be verified", failure); }
                }
            } }, new SecureRandom());
            return context;
        } catch (Exception error) {
            throw new IOException("Movie TLS could not be configured", error);
        }
    }

    private void verifyPeer(HttpsURLConnection connection) throws IOException {
        try {
            java.security.cert.Certificate[] certificates = connection.getServerCertificates();
            if (certificates.length == 0 || !(certificates[0] instanceof X509Certificate)) throw new IOException("Missing movie certificate");
            String actual = hex(MessageDigest.getInstance("SHA-256").digest(certificates[0].getEncoded()));
            if (!MessageDigest.isEqual(actual.getBytes("US-ASCII"), certificatePin.getBytes("US-ASCII"))) throw new IOException("Movie certificate pin did not match");
        } catch (SSLPeerUnverifiedException peer) {
            throw new IOException("Movie certificate was not verified", peer);
        } catch (IOException io) { throw io; }
        catch (Exception error) { throw new IOException("Movie certificate could not be verified", error); }
    }

    private ContentRange parseRange(String header) throws IOException {
        if (header == null || !header.startsWith("bytes ")) throw new IOException("Movie stream returned no range");
        int dash = header.indexOf('-', 6), slash = header.indexOf('/', dash + 1);
        if (dash < 7 || slash < dash + 2 || slash == header.length() - 1) throw new IOException("Movie stream returned an invalid range");
        try {
            long start = Long.parseLong(header.substring(6, dash));
            long end = Long.parseLong(header.substring(dash + 1, slash));
            long total = Long.parseLong(header.substring(slash + 1));
            if (start < 0 || end < start || total <= end) throw new NumberFormatException();
            return new ContentRange(start, end, total);
        } catch (NumberFormatException bad) {
            throw new IOException("Movie stream returned an invalid range", bad);
        }
    }

    private static final class ContentRange {
        final long start, end, total;
        ContentRange(long start, long end, long total) { this.start = start; this.end = end; this.total = total; }
    }
    private static void disconnect(HttpsURLConnection connection) { if (connection != null) connection.disconnect(); }
    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(Locale.US, "%02x", value & 0xff));
        return result.toString();
    }
}
