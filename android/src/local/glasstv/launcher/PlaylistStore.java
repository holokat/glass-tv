package local.glasstv.launcher;

import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Owns the small, durable playlist document. Media files are never removed here. */
final class PlaylistStore {
    static final int MIN_INTERVAL = 10, MAX_INTERVAL = 86400;
    static final class Item {
        final String id, name, kind;
        Item(String id, String name) { this(id, name, "photo"); }
        Item(String id, String name, String kind) { this.id = id; this.name = name; this.kind = "video".equals(kind) ? "video" : "photo"; }
        boolean isVideo() { return "video".equals(kind); }
    }
    static final class Playlist {
        long revision;
        int intervalSeconds = 300;
        boolean shuffle;
        String selectedInputId;
        String videoPlayback = "cycle";
        String loopVideoId;
        boolean muteVideos = true;
        boolean slideshowEnabled = true;
        /** Whether the launcher chrome remains continuously visible during ambient playback. */
        boolean showTVInterface = true;
        /** Explicit removal keeps an empty playlist blank instead of restoring bundled artwork. */
        boolean defaultBackgroundEnabled = true;
        String fitMode = "fill";
        JSONObject weather = WeatherSettings.defaults();
        JSONObject apps = AppsSettings.defaults();
        /** Optional so legacy playlists remain unchanged until the companion supplies a layout. */
        JSONObject layout;
        final ArrayList<Item> images = new ArrayList<>();
    }

    /**
     * A small, in-process settings transaction. Callers receive an isolated snapshot and may
     * change only launcher-owned settings (apps, weather, and layout). The store validates and
     * commits that snapshot as one revision so a standalone-TV edit cannot re-save a stale media
     * or playlist document.
     */
    interface SettingsMutation {
        void apply(Playlist playlist) throws Exception;
    }

    private final AtomicFile file;
    private Playlist current;

    PlaylistStore(File directory) { file = new AtomicFile(new File(directory, "playlist.json")); }

    synchronized Playlist get() throws IOExceptionLike {
        if (current == null) current = read();
        return copy(current);
    }

    synchronized Playlist replace(Playlist replacement) throws IOExceptionLike {
        write(replacement);
        current = copy(replacement);
        return copy(current);
    }

    /**
     * Commits just the launcher-owned settings sections from the latest playlist copy. All media,
     * playback, input and chrome fields are restored before validation so a standalone screen
     * cannot overwrite an unrelated receiver edit with stale state.
     */
    synchronized Playlist updateSettings(SettingsMutation mutation) throws Exception {
        Playlist current = get();
        Playlist next = get();
        mutation.apply(next);
        next.revision = current.revision;
        next.intervalSeconds = current.intervalSeconds;
        next.shuffle = current.shuffle;
        next.selectedInputId = current.selectedInputId;
        next.videoPlayback = current.videoPlayback;
        next.loopVideoId = current.loopVideoId;
        next.muteVideos = current.muteVideos;
        next.slideshowEnabled = current.slideshowEnabled;
        next.showTVInterface = current.showTVInterface;
        next.defaultBackgroundEnabled = current.defaultBackgroundEnabled;
        next.fitMode = current.fitMode;
        next.images.clear();
        next.images.addAll(current.images);
        next.apps = AppsSettings.validate(next.apps);
        next.weather = WeatherSettings.validate(next.weather);
        next.layout = next.layout == null ? null : LauncherLayout.validate(next.layout);
        next.revision++;
        return replace(next);
    }

    private Playlist read() throws IOExceptionLike {
        if (!file.getBaseFile().exists()) return new Playlist();
        try (FileInputStream in = file.openRead()) {
            byte[] bytes = new byte[(int) Math.min(file.getBaseFile().length(), 65536)];
            int offset = 0, count;
            while (offset < bytes.length && (count = in.read(bytes, offset, bytes.length - offset)) > 0) offset += count;
            JSONObject json = new JSONObject(new String(bytes, 0, offset, StandardCharsets.UTF_8));
            Playlist out = new Playlist();
            out.revision = json.optLong("revision", 0);
            out.intervalSeconds = json.optInt("intervalSeconds", 300);
            out.shuffle = json.optBoolean("shuffle", false);
            out.selectedInputId = json.optString("selectedInputId", null);
            out.videoPlayback = "loop".equals(json.optString("videoPlayback", "cycle")) ? "loop" : "cycle";
            out.loopVideoId = json.optString("loopVideoId", null);
            out.muteVideos = json.optBoolean("muteVideos", true);
            out.slideshowEnabled = json.optBoolean("slideshowEnabled", true);
            out.showTVInterface = json.optBoolean("showTVInterface", true);
            out.defaultBackgroundEnabled = json.optBoolean("defaultBackgroundEnabled", true);
            out.fitMode = "fit".equals(json.optString("fitMode")) ? "fit" : "fill";
            if (json.optJSONObject("weather") != null) out.weather = WeatherSettings.validate(json.getJSONObject("weather"));
            if (json.optJSONObject("apps") != null) out.apps = AppsSettings.validate(json.getJSONObject("apps"));
            if (json.has("layout")) out.layout = LauncherLayout.validate(json.getJSONObject("layout"));
            JSONArray images = json.optJSONArray("images");
            if (images != null) for (int i = 0; i < images.length(); i++) {
                JSONObject image = images.getJSONObject(i);
                out.images.add(new Item(image.getString("id"), image.getString("name"), image.optString("kind", "photo")));
            }
            return out;
        } catch (Exception e) { throw new IOExceptionLike("Could not read playlist", e); }
    }

    private void write(Playlist value) throws IOExceptionLike {
        FileOutputStream stream = null;
        try {
            JSONObject json = new JSONObject();
            json.put("revision", value.revision);
            json.put("intervalSeconds", value.intervalSeconds);
            json.put("shuffle", value.shuffle);
            if (value.selectedInputId != null) json.put("selectedInputId", value.selectedInputId);
            json.put("videoPlayback", value.videoPlayback);
            if (value.loopVideoId != null) json.put("loopVideoId", value.loopVideoId);
            json.put("muteVideos", value.muteVideos);
            json.put("slideshowEnabled", value.slideshowEnabled);
            json.put("showTVInterface", value.showTVInterface);
            json.put("defaultBackgroundEnabled", value.defaultBackgroundEnabled);
            json.put("fitMode", value.fitMode);
            json.put("weather", value.weather);
            json.put("apps", value.apps);
            if (value.layout != null) json.put("layout", value.layout);
            JSONArray images = new JSONArray();
            for (Item item : value.images) images.put(new JSONObject().put("id", item.id).put("name", item.name).put("kind", item.kind));
            json.put("images", images);
            stream = file.startWrite();
            stream.write(json.toString().getBytes(StandardCharsets.UTF_8));
            file.finishWrite(stream);
        } catch (Exception e) {
            if (stream != null) file.failWrite(stream);
            throw new IOExceptionLike("Could not save playlist", e);
        }
    }

    private static Playlist copy(Playlist source) {
        Playlist out = new Playlist();
        out.revision = source.revision; out.intervalSeconds = source.intervalSeconds;
        out.shuffle = source.shuffle; out.selectedInputId = source.selectedInputId;
        out.videoPlayback = source.videoPlayback; out.loopVideoId = source.loopVideoId; out.muteVideos = source.muteVideos;
        out.slideshowEnabled = source.slideshowEnabled; out.showTVInterface = source.showTVInterface; out.fitMode = source.fitMode;
        out.defaultBackgroundEnabled = source.defaultBackgroundEnabled;
        out.weather = WeatherSettings.copy(source.weather);
        out.apps = AppsSettings.copy(source.apps);
        out.layout = LauncherLayout.copy(source.layout);
        out.images.addAll(source.images); return out;
    }

    static final class IOExceptionLike extends Exception {
        IOExceptionLike(String message, Throwable cause) { super(message, cause); }
    }
}
