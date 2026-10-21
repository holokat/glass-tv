package local.glasstv.launcher;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Live directory smoke checks. No UI, audio, saved preferences or stream playback. */
public final class RadioDirectoryChecks {
    public static void main(String[] args) throws Exception {
        RadioDirectory directory = new RadioDirectory();
        RadioDirectory.Page popular = directory.popular(new RadioDirectory.Cursor());
        require(!popular.stations.isEmpty(), "popular stations return eligible results");
        validate(popular.stations, new HashSet<String>());
        RadioDirectory.Cursor cursor = new RadioDirectory.Cursor();
        Set<String> ids = new HashSet<>();
        RadioDirectory.Page first = directory.browseGenre("jazz", cursor);
        require(!first.stations.isEmpty(), "live jazz page must contain eligible stations");
        require(first.stations.get(0).bitrate == 320, "320 kbps comes first");
        validate(first.stations, ids);
        if (first.hasMore) validate(directory.browseGenre("jazz", cursor).stations, ids);
        RadioDirectory.Page search = directory.searchByName("Classic", new RadioDirectory.Cursor());
        require(!search.stations.isEmpty(), "live name search returns stations");
        validate(search.stations, new HashSet<String>());
        List<String> favoriteIds = new ArrayList<>();
        favoriteIds.add(first.stations.get(0).uuid);
        require(directory.favorites(favoriteIds).size() == 1, "favorite UUID revalidation");
        require(directory.checkedStation(favoriteIds.get(0)).size() == 1, "pre-play UUID revalidation");
        require(!RadioDirectory.safeHttpsUrl("http://example.com/audio"), "reject HTTP");
        require(!RadioDirectory.safePlaybackUrl("https://127.0.0.1/audio"), "reject loopback playback");
        System.out.println("RadioDirectoryChecks passed: genre pagination, 320-first, search, favorites, playback recheck, HTTPS policy. No audio played.");
    }
    private static void validate(List<RadioStationPolicy.Station> stations, Set<String> ids) {
        for (RadioStationPolicy.Station station : stations) {
            require(station.bitrate >= 128 && station.bitrate <= 10000, "bitrate boundary");
            require(station.url.startsWith("https://"), "HTTPS stream");
            require(ids.add(station.uuid), "no duplicate across pages");
        }
    }
    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
