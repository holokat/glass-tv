package local.glasstv.launcher;

import java.io.File;
import org.json.JSONObject;

/** Isolated app_process regression checks for standalone Apps/Weather transactions. */
public final class PlaylistStoreTransactionChecks {
  public static void main(String[] args) throws Exception {
    File directory = new File(System.getProperty("java.io.tmpdir"), "glass-tv-settings-" + System.nanoTime());
    check(directory.mkdirs(), "creates an isolated playlist directory");
    try {
      PlaylistStore store = new PlaylistStore(directory);
      PlaylistStore.Playlist initial = store.get();
      check(initial.defaultBackgroundEnabled, "fresh installs use bundled background");
      check(!BackgroundPlaybackPolicy.canKeepVideo(initial, "video-1"), "removed video cannot keep playing");
      initial.defaultBackgroundEnabled = false;
      initial.revision = 41;
      initial.intervalSeconds = 600;
      initial.shuffle = true;
      initial.selectedInputId = "hdmi-4";
      initial.videoPlayback = "loop";
      initial.loopVideoId = "video-1";
      initial.muteVideos = false;
      initial.slideshowEnabled = false;
      initial.showTVInterface = false;
      initial.fitMode = "fit";
      initial.images.add(new PlaylistStore.Item("image-1", "Original photo"));
      initial.images.add(new PlaylistStore.Item("video-1", "Original video", "video"));
      initial.apps = AppsSettings.validate(new JSONObject().put("netflixEnabled", true));
      initial.weather = WeatherSettings.validate(new JSONObject().put("enabled", false));
      store.replace(initial);
      check(BackgroundPlaybackPolicy.canKeepVideo(initial, "video-1"), "selected video may keep playing");
      PlaylistStore.Playlist changedLoop = store.get();
      changedLoop.images.add(new PlaylistStore.Item("video-2", "Another video", "video"));
      changedLoop.loopVideoId = "video-2";
      check(!BackgroundPlaybackPolicy.canKeepVideo(changedLoop, "video-1"), "new loop replaces previous video even if retained in rotation");
      check(BackgroundPlaybackPolicy.canKeepVideo(changedLoop, "video-2"), "new loop is playable");

      PlaylistStore.Playlist appsCommitted =
          store.updateSettings(
              next -> {
                next.apps = AppsSettings.validate(new JSONObject().put("netflixEnabled", false));
                // A caller attempting stale unrelated changes cannot alter them.
                next.defaultBackgroundEnabled = true;
                next.intervalSeconds = 10;
                next.images.clear();
                next.selectedInputId = "different-input";
              });
      check(appsCommitted.revision == 42, "Apps change increments revision exactly once");
      check(!appsCommitted.apps.getBoolean("netflixEnabled"), "Apps change commits");
      check(!appsCommitted.weather.getBoolean("enabled"), "Apps change preserves Weather");
      assertUnrelatedState(appsCommitted);

      PlaylistStore.Playlist weatherCommitted =
          store.updateSettings(
              next -> next.weather = WeatherSettings.validate(new JSONObject().put("enabled", true).put("style", "compact")));
      check(weatherCommitted.revision == 43, "Weather change increments the next revision once");
      check(weatherCommitted.weather.getBoolean("enabled"), "Weather change commits");
      check("compact".equals(weatherCommitted.weather.getString("style")), "Weather style commits");
      check(!weatherCommitted.apps.getBoolean("netflixEnabled"), "Weather change preserves Apps");
      assertUnrelatedState(weatherCommitted);

      boolean rejected = false;
      try {
        store.updateSettings(next -> next.apps = new JSONObject().put("unknownSetting", true));
      } catch (IllegalArgumentException expected) {
        rejected = true;
      }
      check(rejected, "invalid Apps transaction is rejected");
      PlaylistStore.Playlist afterRejected = store.get();
      check(afterRejected.revision == 43, "rejected transaction does not increment revision");
      check(!afterRejected.apps.getBoolean("netflixEnabled"), "rejected transaction leaves Apps unchanged");
      check(afterRejected.weather.getBoolean("enabled"), "rejected transaction leaves Weather unchanged");
      assertUnrelatedState(afterRejected);
      assertUnrelatedState(new PlaylistStore(directory).get());
      System.out.println("PlaylistStoreTransactionChecks passed");
    } finally {
      delete(directory);
    }
  }

  private static void assertUnrelatedState(PlaylistStore.Playlist playlist) {
    check(!playlist.defaultBackgroundEnabled, "explicit background removal survives copies, settings changes and restart");
    check(playlist.intervalSeconds == 600, "transaction preserves interval");
    check(playlist.shuffle, "transaction preserves shuffle");
    check("hdmi-4".equals(playlist.selectedInputId), "transaction preserves selected input");
    check("loop".equals(playlist.videoPlayback) && "video-1".equals(playlist.loopVideoId), "transaction preserves video settings");
    check(!playlist.muteVideos && !playlist.slideshowEnabled && !playlist.showTVInterface, "transaction preserves playback and chrome settings");
    check("fit".equals(playlist.fitMode), "transaction preserves screen fit");
    check(playlist.images.size() == 2 && "Original photo".equals(playlist.images.get(0).name), "transaction preserves media");
  }

  private static void delete(File value) {
    File[] children = value.listFiles();
    if (children != null) for (File child : children) child.delete();
    value.delete();
  }

  private static void check(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
