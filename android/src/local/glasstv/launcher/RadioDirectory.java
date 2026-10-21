package local.glasstv.launcher;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import javax.net.ssl.HttpsURLConnection;

/** HTTPS directory access, with filtering and pagination separate from the TV UI. */
final class RadioDirectory {
    static final int PAGE_SIZE = 12;
    private static final int[][] BITRATE_BUCKETS = {{320, 320}, {321, 10_000}, {128, 319}};
    private static final String DISCOVERY_HOST = "all.api.radio-browser.info";
    private static final String USER_AGENT = "GlassTV/1.0 (Android TV radio)";
    private static final int CONNECT_TIMEOUT_MS = 5_000, READ_TIMEOUT_MS = 7_000;
    private static final int BODY_DEADLINE_MS = 8_000, MAX_MIRRORS = 3, MAX_MIRROR_ATTEMPTS = 2;
    private static final int MAX_SCAN_REQUESTS = 4;
    private List<String> mirrors = Collections.emptyList();

    static final class Cursor {
        int bucket;
        final int[] offsets = new int[BITRATE_BUCKETS.length];
    }

    static final class Page {
        final List<RadioStationPolicy.Station> stations;
        final boolean hasMore;
        Page(List<RadioStationPolicy.Station> stations, boolean hasMore) {
            this.stations = stations;
            this.hasMore = hasMore;
        }
    }

    List<String> musicGenres() {
        return Arrays.asList("rock", "pop", "jazz", "classical", "electronic", "hip hop");
    }

    Page browseGenre(String genre, Cursor cursor) throws Exception {
        return search("tag=" + encode(genre), cursor);
    }

    Page searchByName(String name, Cursor cursor) throws Exception {
        return search("name=" + encode(name), cursor);
    }

    Page searchByTag(String tag, Cursor cursor) throws Exception {
        return search("tag=" + encode(tag), cursor);
    }

    /** Radio Browser's click-count ranking. This is popularity, not an editorial trend claim. */
    Page popular(Cursor cursor) throws Exception {
        return search("", cursor);
    }

    List<RadioStationPolicy.Station> favorites(List<String> uuids) throws Exception {
        if (uuids.isEmpty()) return Collections.emptyList();
        String joined = String.join(",", uuids);
        JSONArray rows = request("/json/stations/byuuid?uuids=" + encode(joined)
                + "&hidebroken=true&limit=" + uuids.size());
        return RadioStationPolicy.uniqueAndRank(parse(rows), uuids.size());
    }

    List<RadioStationPolicy.Station> checkedStation(String uuid) throws Exception {
        return RadioStationPolicy.uniqueAndRank(parse(request("/json/stations/byuuid/" + encode(uuid))), 1);
    }

    /** Non-overlapping buckets guarantee 320 first without discarding another page's results. */
    private Page search(String criteria, Cursor cursor) throws Exception {
        ArrayList<RadioStationPolicy.Station> page = new ArrayList<>();
        int bucketIndex = cursor.bucket;
        int[] offsets = cursor.offsets.clone();
        for (int scan = 0; scan < MAX_SCAN_REQUESTS && bucketIndex < BITRATE_BUCKETS.length
                && page.size() < PAGE_SIZE; scan++) {
            int[] bucket = BITRATE_BUCKETS[bucketIndex];
            // Request only remaining capacity. Otherwise a partially filled page would
            // discard valid rows while advancing the raw-result offset past them.
            int capacity = PAGE_SIZE - page.size();
            String path = "/json/stations/search?" + criteria + (criteria.length() == 0 ? "" : "&") + "hidebroken=true"
                    + "&bitrateMin=" + bucket[0] + "&bitrateMax=" + bucket[1]
                    + "&order=clickcount&reverse=true&limit=" + capacity
                    + "&offset=" + offsets[bucketIndex];
            JSONArray raw = request(path);
            offsets[bucketIndex] += raw.length();
            for (RadioStationPolicy.Station station : parse(raw)) {
                if (!contains(page, station.uuid)) page.add(station);
            }
            if (raw.length() < capacity) bucketIndex++;
        }
        // Commit the cursor only after all requests succeed. Failures cannot skip rows.
        cursor.bucket = bucketIndex;
        System.arraycopy(offsets, 0, cursor.offsets, 0, offsets.length);
        return new Page(RadioStationPolicy.uniqueAndRank(page, PAGE_SIZE), bucketIndex < BITRATE_BUCKETS.length);
    }

    private List<String> mirrors() throws Exception {
        if (!mirrors.isEmpty()) return mirrors;
        ArrayList<String> values = new ArrayList<>();
        for (InetAddress address : InetAddress.getAllByName(DISCOVERY_HOST)) {
            String name = address.getCanonicalHostName().toLowerCase(Locale.US);
            if ((name.equals(DISCOVERY_HOST) || name.endsWith(".api.radio-browser.info"))
                    && !values.contains(name)) values.add(name);
            if (values.size() == MAX_MIRRORS) break;
        }
        if (values.isEmpty()) throw new IllegalStateException("Radio directory DNS discovery failed");
        mirrors = values;
        return mirrors;
    }

    private JSONArray request(String path) throws Exception {
        Exception last = null;
        List<String> endpoints = mirrors();
        for (int index = 0; index < endpoints.size() && index < MAX_MIRROR_ATTEMPTS; index++) {
            try {
                HttpsURLConnection connection = (HttpsURLConnection) new URL("https", endpoints.get(index), path).openConnection();
                connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
                connection.setReadTimeout(READ_TIMEOUT_MS);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("User-Agent", USER_AGENT);
                connection.setRequestProperty("Accept", "application/json");
                try {
                    if (connection.getResponseCode() == 200) {
                        try (InputStream body = connection.getInputStream()) {
                            return new JSONArray(readBounded(body));
                        }
                    }
                } finally { connection.disconnect(); }
            } catch (Exception failure) { last = failure; }
        }
        mirrors = Collections.emptyList(); // Rediscover after mirror failures.
        throw last == null ? new IllegalStateException("Radio directory unavailable") : last;
    }

    private static List<RadioStationPolicy.Station> parse(JSONArray rows) {
        ArrayList<RadioStationPolicy.Station> result = new ArrayList<>();
        for (int index = 0; index < rows.length(); index++) {
            JSONObject row = rows.optJSONObject(index);
            if (row == null) continue;
            String uuid = row.optString("stationuuid", "").toLowerCase(Locale.US);
            String name = row.optString("name", "").trim();
            String url = row.optString("url_resolved", "").trim();
            int bitrate = row.optInt("bitrate", 0);
            boolean checked = row.has("lastcheckok") && row.optInt("lastcheckok", 0) == 1;
            if (RadioStationPolicy.qualifies(uuid, name, url, checked, bitrate, safeHttpsUrl(url))) {
                result.add(new RadioStationPolicy.Station(uuid, name, url,
                        trim(row.optString("tags", ""), 80), trim(row.optString("country", ""), 48), bitrate,
                        trim(row.optString("favicon", ""), 2048)));
            }
        }
        return result;
    }

    static boolean safeHttpsUrl(String value) {
        try {
            URI uri = new URI(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getUserInfo() == null
                    && uri.getHost() != null && value.length() <= 2048
                    && IDN.toASCII(uri.getHost()).length() <= 253
                    && uri.getPort() >= -1 && uri.getPort() <= 65535;
        } catch (Exception invalid) { return false; }
    }

    static boolean safePlaybackUrl(String value) {
        return safePublicHttpsUrl(value);
    }

    /** HTTPS URL with a resolved public destination, suitable for stream or artwork retrieval. */
    static boolean safePublicHttpsUrl(String value) {
        if (!safeHttpsUrl(value)) return false;
        try {
            for (InetAddress address : InetAddress.getAllByName(new URI(value).getHost())) {
                byte[] bytes = address.getAddress();
                boolean uniqueLocalV6 = bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
                if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                        || address.isSiteLocalAddress() || address.isMulticastAddress() || uniqueLocalV6) return false;
            }
            return true;
        } catch (Exception invalid) { return false; }
    }

    private static String readBounded(InputStream input) throws Exception {
        long deadline = System.nanoTime() + BODY_DEADLINE_MS * 1_000_000L;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (System.nanoTime() > deadline || output.size() + count > 512 * 1024)
                throw new IllegalStateException("Radio response limit exceeded");
            output.write(buffer, 0, count);
        }
        return output.toString("UTF-8");
    }

    private static boolean contains(List<RadioStationPolicy.Station> values, String uuid) {
        for (RadioStationPolicy.Station value : values) if (value.uuid.equals(uuid)) return true;
        return false;
    }

    private static String trim(String value, int max) {
        value = value == null ? "" : value.trim();
        return value.length() > max ? value.substring(0, max) : value;
    }

    private static String encode(String value) {
        try { return URLEncoder.encode(value, "UTF-8").replace("+", "%20"); }
        catch (Exception impossible) { return ""; }
    }
}
