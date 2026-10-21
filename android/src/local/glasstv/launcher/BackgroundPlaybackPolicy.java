package local.glasstv.launcher;

/** A new explicit loop/background selection must replace the previously playing video. */
final class BackgroundPlaybackPolicy {
  static boolean canKeepVideo(PlaylistStore.Playlist playlist, String activeId) {
    if (playlist == null || activeId == null) return false;
    if ("loop".equals(playlist.videoPlayback) && !activeId.equals(playlist.loopVideoId)) return false;
    for (PlaylistStore.Item item : playlist.images)
      if (item.isVideo() && activeId.equals(item.id)) return true;
    return false;
  }
}
