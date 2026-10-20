package local.glasstv.launcher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Pure station eligibility and ordering rules, shared by the directory and executable checks. */
final class RadioStationPolicy {
  static final int MAX_REASONABLE_BITRATE = 10_000;

  static final class Station {
    final String uuid, name, url, genre, country;
    final int bitrate;

    Station(String uuid, String name, String url, String genre, String country, int bitrate) {
      this.uuid = uuid;
      this.name = name;
      this.url = url;
      this.genre = genre;
      this.country = country;
      this.bitrate = bitrate;
    }
  }

  static boolean qualifies(
      String uuid,
      String name,
      String url,
      boolean lastCheckKnownGood,
      int bitrate,
      boolean secureUrl) {
    return lastCheckKnownGood
        && bitrate >= 128
        && bitrate <= MAX_REASONABLE_BITRATE
        && secureUrl
        && uuid != null
        && uuid.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        && label(name, 140);
  }

  static int rank(int bitrate) {
    return bitrate == 320 ? 0 : bitrate > 320 ? 1 : bitrate == 256 ? 2 : 3;
  }

  static List<Station> uniqueAndRank(List<Station> source, int limit) {
    ArrayList<Station> result = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (Station station : source) if (seen.add(station.uuid)) result.add(station);
    Collections.sort(
        result,
        new Comparator<Station>() {
          @Override
          public int compare(Station left, Station right) {
            int leftRank = rank(left.bitrate), rightRank = rank(right.bitrate);
            if (leftRank != rightRank) return leftRank - rightRank;
            if (leftRank == 1) return left.bitrate - right.bitrate;
            if (leftRank == 3) return right.bitrate - left.bitrate;
            return left.name.compareToIgnoreCase(right.name);
          }
        });
    return result.size() > limit ? new ArrayList<>(result.subList(0, limit)) : result;
  }

  private static boolean label(String text, int maximum) {
    return text != null
        && text.length() > 0
        && text.length() <= maximum
        && text.indexOf('\n') < 0
        && text.indexOf('\r') < 0;
  }
}
