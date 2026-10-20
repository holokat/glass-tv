package local.glasstv.launcher;

import org.json.JSONArray;
import org.json.JSONObject;

/** Contract-level validation checks; they do not need a TV activity or paired connection. */
public final class MovieLibraryChecks {
  private static final String SHA = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
  private static final String UUID = "123e4567-e89b-12d3-a456-426614174000";
  public static void main(String[] args) throws Exception {
    MovieLibraryController.validateForChecks(valid());
    rejects(valid().put("unexpected", true), "unknown library field");
    JSONObject duplicate = valid(); duplicate.getJSONArray("items").put(item());
    rejects(duplicate, "duplicate movie identifier");
    JSONObject nonBoolean = valid(); nonBoolean.getJSONArray("items").getJSONObject(0).put("playable", "true");
    rejects(nonBoolean, "non-boolean playback field");
    JSONObject badPoster = valid(); badPoster.getJSONArray("items").getJSONObject(0).put("posterId", "outside-cache");
    rejects(badPoster, "unsafe poster identifier");
    JSONObject tooLong = valid(); tooLong.getJSONArray("items").getJSONObject(0).put("title", repeat('x', 241));
    rejects(tooLong, "oversize title");
    check(MovieLibraryController.leaseLive(15_001L, 15_000L), "monotonic lease is live before its deadline");
    check(!MovieLibraryController.leaseLive(15_000L, 15_000L), "monotonic lease expires at its deadline");
    check(MovieLibraryController.requestExpired("queued", 0L, 15_000L, 15_001L), "queued request expires after 15 seconds");
    check(!MovieLibraryController.requestExpired("preparing", 0L, 900_000L, 600_000L), "preparing request remains live during long preparation with its publisher lease");
    check(MovieLibraryController.requestExpired("preparing", 0L, 599_999L, 600_000L), "preparing request cancels when its publisher lease expires");
    check(MovieLibraryController.ackCanAdvance("queued", "preparing"), "queued request accepts preparing acknowledgement");
    check(!MovieLibraryController.ackCanAdvance("cancelled", "started"), "cancelled request rejects late acknowledgement");
    check(!MovieLibraryController.ackCanAdvance("preparing", "preparing"), "stale acknowledgement cannot regress preparation");
    check(MovieLibraryController.posterFitsCache(0L, 0L, 256 * 1024L), "poster uploaded before catalogue publication stays within the dedicated cache");
    check(!MovieLibraryController.posterFitsCache(128L * 1024L * 1024L, 0L, 1L), "poster cache rejects overflow without deleting pending artwork");
    System.out.println("MovieLibraryChecks passed");
  }
  private static JSONObject valid() throws Exception { return new JSONObject().put("apiVersion", 1).put("publisherId", UUID).put("libraryId", SHA).put("items", new JSONArray().put(item())); }
  private static JSONObject item() throws Exception { return new JSONObject().put("id", SHA).put("title", "A real movie").put("year", 2026).put("durationMs", 90000).put("favorite", false).put("watched", false).put("playable", true).put("posterId", SHA); }
  private static void rejects(JSONObject value, String label) throws Exception { boolean rejected=false; try { MovieLibraryController.validateForChecks(value); } catch (MovieLibraryController.RequestException expected) { rejected=true; } if(!rejected) throw new AssertionError(label); }
  private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); }
  private static String repeat(char value, int count) { StringBuilder b=new StringBuilder(count); for(int i=0;i<count;i++)b.append(value); return b.toString(); }
}
