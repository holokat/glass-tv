package local.glasstv.launcher;

import android.content.Context;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.os.Build;
import android.provider.Settings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Stable receiver fields that remain truthful with or without an attached launcher activity. */
final class ReceiverStatus {
  static final String DEFAULT_INPUT_ID =
      "com.sony.dtv.tvinput.external/.ExternalTvInputService/HW4";

  private ReceiverStatus() {}

  static JSONObject base(Context context, LocalSyncServer server) throws Exception {
    PlaylistStore.Playlist playlist = server.playlist();
    JSONObject root = new JSONObject();
    root.put("apiVersion", 1);
    root.put("deviceId", server.deviceId());
    String manufacturer = identityPart(Build.MANUFACTURER);
    String model = identityPart(Build.MODEL);
    String deviceName = deviceName(configuredDeviceName(context), manufacturer, model);
    if (deviceName != null) root.put("deviceName", deviceName);
    if (manufacturer != null) root.put("manufacturer", manufacturer);
    if (model != null) root.put("model", model);
    root.put("revision", playlist.revision);
    root.put("playlist", playlistJson(playlist));
    root.put("storedImages", server.storedImagesJson());
    root.put("storage", server.storageJson());
    JSONObject limits = new JSONObject();
    limits.put("maxImages", LocalSyncServer.MAX_IMAGES);
    limits.put("maxImageBytes", LocalSyncServer.MAX_IMAGE_BYTES);
    limits.put("maxVideoBytes", LocalSyncServer.MAX_VIDEO_BYTES);
    limits.put("minIntervalSeconds", PlaylistStore.MIN_INTERVAL);
    limits.put("maxIntervalSeconds", PlaylistStore.MAX_INTERVAL);
    root.put("limits", limits);
    TvInputManager manager = (TvInputManager) context.getSystemService(Context.TV_INPUT_SERVICE);
    JSONArray inputs = new JSONArray();
    for (TvInputInfo info : passthroughInputs(context)) {
      int state = manager == null ? TvInputManager.INPUT_STATE_DISCONNECTED : manager.getInputState(info.getId());
      String name = state == TvInputManager.INPUT_STATE_CONNECTED ? "connected"
          : state == TvInputManager.INPUT_STATE_CONNECTED_STANDBY ? "standby" : "disconnected";
      inputs.put(new JSONObject().put("id", info.getId()).put("parentId", JSONObject.NULL)
          .put("label", String.valueOf(info.loadLabel(context))).put("customLabel", JSONObject.NULL).put("state", name));
    }
    root.put("inputs", inputs);
    root.put("selectedInputId", playlist.selectedInputId == null ? DEFAULT_INPUT_ID : playlist.selectedInputId);
    return root;
  }

  /** Returns the local name first, then a readable Build identity without repeating its maker. */
  static String deviceName(String configuredName, String manufacturer, String model) {
    String configured = identityPart(configuredName);
    if (configured != null) return configured;
    String maker = identityPart(manufacturer);
    String deviceModel = identityPart(model);
    if (maker == null) return deviceModel;
    if (deviceModel == null) return maker;
    if (startsWithIgnoreCase(deviceModel, maker)) return deviceModel;
    return maker + " " + deviceModel;
  }

  static String identityPart(String value) {
    if (value == null) return null;
    String trimmed = value.trim();
    if (trimmed.length() == 0) return null;
    for (int i = 0; i < trimmed.length(); i++)
      if (Character.isISOControl(trimmed.charAt(i))) return null;
    return trimmed;
  }

  private static String configuredDeviceName(Context context) {
    try {
      return Settings.Global.getString(context.getContentResolver(), Settings.Global.DEVICE_NAME);
    } catch (SecurityException unavailable) {
      return null;
    }
  }

  private static boolean startsWithIgnoreCase(String value, String prefix) {
    return value.length() >= prefix.length()
        && value.regionMatches(true, 0, prefix, 0, prefix.length());
  }

  static boolean isPassthroughInput(Context context, String id) {
    for (TvInputInfo info : passthroughInputs(context)) if (info.getId().equals(id)) return true;
    return false;
  }

  static List<TvInputInfo> passthroughInputs(final Context context) {
    TvInputManager manager = (TvInputManager) context.getSystemService(Context.TV_INPUT_SERVICE);
    if (manager == null) return Collections.emptyList();
    ArrayList<TvInputInfo> result = new ArrayList<>();
    for (TvInputInfo info : manager.getTvInputList()) if (info.isPassthroughInput()) result.add(info);
    Collections.sort(result, new Comparator<TvInputInfo>() {
      @Override public int compare(TvInputInfo a, TvInputInfo b) {
        return String.valueOf(a.loadLabel(context)).compareToIgnoreCase(String.valueOf(b.loadLabel(context)));
      }
    });
    return result;
  }

  private static JSONObject playlistJson(PlaylistStore.Playlist playlist) throws Exception {
    JSONObject value = new JSONObject();
    value.put("intervalSeconds", playlist.intervalSeconds);
    value.put("shuffle", playlist.shuffle);
    value.put("videoPlayback", playlist.videoPlayback);
    value.put("muteVideos", playlist.muteVideos);
    value.put("slideshowEnabled", playlist.slideshowEnabled);
    value.put("showTVInterface", playlist.showTVInterface);
    value.put("fitMode", playlist.fitMode);
    if (playlist.loopVideoId != null) value.put("loopVideoId", playlist.loopVideoId);
    JSONArray images = new JSONArray();
    for (PlaylistStore.Item item : playlist.images)
      images.put(new JSONObject().put("id", item.id).put("name", item.name).put("kind", item.kind));
    value.put("images", images);
    value.put("weather", playlist.weather);
    value.put("apps", playlist.apps);
    if (playlist.layout != null) value.put("layout", playlist.layout);
    return value;
  }
}
