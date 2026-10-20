package local.glasstv.launcher;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.SystemClock;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.json.JSONObject;

/**
 * Short launcher-navigation effects only. This deliberately requests no audio focus, so ambient
 * photos, video, and Radio playback keep their existing audio ownership.
 */
final class NavigationSound {
  private static final long REPEAT_COOLDOWN_MS = 75L;
  private final Context context;
  private final Map<String, Integer> loadedSounds = new HashMap<>();
  private final Set<Integer> readySamples = new HashSet<>();
  private SoundPool pool;
  private String sound = AppsSettings.NAVIGATION_SOUND_OFF;
  private float volume = AppsSettings.DEFAULT_NAVIGATION_SOUND_VOLUME;
  private long lastPlayedAtMillis;
  private int pendingPreviewSample;

  NavigationSound(Context context) { this.context = context.getApplicationContext(); }

  void configure(JSONObject apps) {
    String nextSound = AppsSettings.navigationSound(apps);
    if (!nextSound.equals(sound) || AppsSettings.NAVIGATION_SOUND_OFF.equals(nextSound)) stop();
    sound = nextSound;
    volume = AppsSettings.navigationSoundVolume(apps);
    if (!AppsSettings.NAVIGATION_SOUND_OFF.equals(sound)) load(sound);
  }

  /** Returns true only when a loaded cue actually began playing. */
  boolean play() {
    if (AppsSettings.NAVIGATION_SOUND_OFF.equals(sound)) return false;
    Integer sample = load(sound);
    if (sample == null || sample == 0 || pool == null || !readySamples.contains(sample)) return false;
    long now = SystemClock.uptimeMillis();
    if (now - lastPlayedAtMillis < REPEAT_COOLDOWN_MS) return false;
    int stream = pool.play(sample, volume, volume, 1, 0, 1f);
    if (stream == 0) return false;
    lastPlayedAtMillis = now;
    return true;
  }

  /** Plays the selected cue once it has finished loading, for the TV settings picker. */
  void preview() {
    if (AppsSettings.NAVIGATION_SOUND_OFF.equals(sound)) return;
    Integer sample = load(sound);
    if (sample == null || sample == 0) return;
    if (readySamples.contains(sample)) {
      play();
    } else {
      pendingPreviewSample = sample;
    }
  }

  void stop() {
    lastPlayedAtMillis = 0L;
    pendingPreviewSample = 0;
    if (pool != null) pool.autoPause();
  }

  void release() {
    loadedSounds.clear();
    readySamples.clear();
    if (pool != null) {
      pool.release();
      pool = null;
    }
  }

  private Integer load(String id) {
    Integer existing = loadedSounds.get(id);
    if (existing != null) return existing;
    try {
      if (pool == null) {
        pool =
            new SoundPool.Builder()
                .setMaxStreams(1)
                .setAudioAttributes(
                    new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                .build();
        pool.setOnLoadCompleteListener(
            (soundPool, sampleId, status) -> {
              if (status == 0) {
                readySamples.add(sampleId);
                if (pendingPreviewSample == sampleId) {
                  pendingPreviewSample = 0;
                  play();
                }
              }
            });
      }
      try (AssetFileDescriptor asset =
          context.getAssets().openFd("navigation-sounds/" + id + ".mp3")) {
        int sample = pool.load(asset, 1);
        loadedSounds.put(id, sample);
        return sample;
      }
    } catch (Exception unavailable) {
      // A missing optional bundled effect never affects navigation or media playback.
      loadedSounds.put(id, 0);
      return 0;
    }
  }
}
