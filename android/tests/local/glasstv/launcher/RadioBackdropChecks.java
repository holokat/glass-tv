package local.glasstv.launcher;

import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import java.io.File;
import java.io.FileOutputStream;
import java.util.List;

/**
 * Device-runnable rendering checks for Radio's self-contained ambient surface. It only draws a
 * Drawable into bitmaps: it never opens the activity, starts playback, or changes the foreground
 * application.
 */
public final class RadioBackdropChecks {
  private static final int WIDTH = 1920;
  private static final int HEIGHT = 1080;

  public static void main(String[] args) throws Exception {
    Looper.prepareMainLooper();
    File output = new File(args.length == 1 ? args[0] : "/data/local/tmp/glasstv-radio-backdrop");
    if (!output.exists() && !output.mkdirs()) throw new IllegalStateException("Could not create backdrop output directory");

    RadioDirectory directory = new RadioDirectory(applicationContext());
    List<RadioStationPolicy.Station> featured = directory.popular(new RadioDirectory.Cursor()).stations;
    check(featured.size() >= 2, "two curated stations are available for palette rendering");
    RadioStationPolicy.Station first = featured.get(0);
    RadioStationPolicy.Station second = featured.get(1);
    RadioStationPolicy.Station renamed = new RadioStationPolicy.Station(first.uuid, first.name + " refreshed",
        first.url, first.genre, first.country, first.bitrate, first.artworkUrl);
    check(RadioStationGradient.paletteFor(first).sameAs(RadioStationGradient.paletteFor(renamed)),
        "station UUID keeps art and backdrop palette stable across a refreshed display name");

    RadioBackdropDrawable backdrop = new RadioBackdropDrawable();
    Bitmap idle = render(backdrop, new File(output, "radio-backdrop-idle.png"));
    int idleTop = idle.getPixel(130, 70);
    int idleBottom = idle.getPixel(WIDTH / 2, HEIGHT - 40);
    check(android.graphics.Color.alpha(idleTop) == 255 && android.graphics.Color.alpha(idleBottom) == 255,
        "Radio backdrop is fully opaque over Home media");
    check(android.graphics.Color.green(idleTop) > android.graphics.Color.red(idleTop)
            && android.graphics.Color.blue(idleTop) > android.graphics.Color.red(idleTop),
        "idle backdrop begins with subdued teal and blue");
    check(brightness(idleBottom) < brightness(idleTop) && brightness(idleBottom) < 45,
        "idle backdrop falls away to black at the lower edge");

    backdrop.showStation(first, false);
    Bitmap firstStation = render(backdrop, new File(output, "radio-backdrop-station-a.png"));
    int firstColor = firstStation.getPixel(130, 70);
    check(firstColor != idleTop, "playing a station replaces the idle palette");

    backdrop.showStation(second, false);
    Bitmap secondStation = render(backdrop, new File(output, "radio-backdrop-station-b.png"));
    int secondColor = secondStation.getPixel(130, 70);
    check(secondColor != firstColor, "different curated stations receive distinct deterministic backdrop washes");

    // A rapid selection supersedes the transition source. Cancelling first cannot restore it later.
    backdrop.showStation(first, true);
    backdrop.cancelAnimation();
    backdrop.showStation(second, false);
    Bitmap latestStation = render(backdrop, new File(output, "radio-backdrop-latest.png"));
    check(latestStation.getPixel(130, 70) == secondColor,
        "latest station palette wins after an interrupted transition");

    // Closing performs this exact sequence after an animated stop. A same-target nonanimated idle
    // request must snap rather than preserve the cancelled transition's partial palette.
    backdrop.showStation(first, false);
    backdrop.showIdle(true);
    backdrop.cancelAnimation();
    backdrop.showIdle(false);
    Bitmap reset = render(backdrop, new File(output, "radio-backdrop-reset.png"));
    check(reset.getPixel(130, 70) == idleTop,
        "stopping playback restores the deterministic idle backdrop");
    recycle(idle, firstStation, secondStation, latestStation, reset);
    System.out.println("RadioBackdropChecks passed; rendered opaque idle and curated station palettes in " + output
        + " using " + first.name + " and " + second.name);
  }

  private static Context applicationContext() throws Exception {
    Class<?> activityThread = Class.forName("android.app.ActivityThread");
    Object thread = activityThread.getMethod("systemMain").invoke(null);
    Context system = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
    Context packageContext = system.createPackageContext("local.glasstv.launcher", 0);
    return new ContextWrapper(packageContext) {
      @Override public Context getApplicationContext() { return this; }
    };
  }

  private static Bitmap render(RadioBackdropDrawable drawable, File file) throws Exception {
    Bitmap bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
    drawable.setBounds(0, 0, WIDTH, HEIGHT);
    drawable.draw(new Canvas(bitmap));
    try (FileOutputStream stream = new FileOutputStream(file)) {
      if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) throw new IllegalStateException("Could not encode " + file);
    }
    return bitmap;
  }

  private static int brightness(int color) {
    return (android.graphics.Color.red(color) + android.graphics.Color.green(color)
        + android.graphics.Color.blue(color)) / 3;
  }

  private static void recycle(Bitmap... values) {
    for (Bitmap value : values) if (value != null && !value.isRecycled()) value.recycle();
  }

  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
