package local.glasstv.launcher;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.view.ContextThemeWrapper;
import java.io.File;
import java.io.FileOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Headless 1920×1080 regression pass for the standalone settings pages. It never opens an
 * Activity, contacts a provider, or writes receiver preferences. PNGs are intentionally emitted
 * only to the caller's requested temporary directory for visual review.
 */
public final class TvSettingsUiChecks {
  private static final int WIDTH = 1920;
  private static final int HEIGHT = 1080;

  public static void main(String[] args) throws Exception {
    Looper.prepareMainLooper();
    Context system = systemContext();
    Context app = system.createPackageContext("local.glasstv.launcher", 0);
    Context theme = new ContextThemeWrapper(app, android.R.style.Theme_Material);
    File output = new File(args.length == 1 ? args[0] : "/data/local/tmp/glasstv-settings-ui");
    if (!output.exists() && !output.mkdirs()) throw new IllegalStateException("Could not create screenshot directory");
    FakeHost host = new FakeHost();
    TvSettingsController controller = new TvSettingsController(theme, host);

    LinearLayout root = controller.buildRootPage();
    render(root, new File(output, "settings-root.png"));
    step("root rendered");
    Button apps = button(root, "Apps");
    check(apps != null, "Root exposes Apps");
    apps.requestFocus();
    step("apps focused");
    Button weatherTarget = button(root, "Weather");
    check(weatherTarget != null && weatherTarget.isFocusable(), "Root keeps the next directional target focusable");
    step("root focus targets ready");
    LinearLayout appsPage = controller.buildAppsPage();
    step("apps built without a headless dialog");
    check(button(appsPage, "Shortcuts") != null, "Apps starts with the dedicated shortcut manager");
    check(button(appsPage, "Shortcut order") != null && button(appsPage, "Navigation sound") != null,
        "Apps keeps order and sound controls in the top-level page");
    render(appsPage, new File(output, "settings-apps.png"));
    step("apps rendered");

    Button weather = button(root, "Weather");
    check(weather != null, "Root exposes Weather");
    LinearLayout weatherPage = controller.buildWeatherPage();
    step("weather built without a headless dialog");
    check(button(weatherPage, "Show temperature") != null && button(weatherPage, "Content size") != null,
        "Weather exposes readable content and temperature choices");
    render(weatherPage, new File(output, "settings-weather.png"));
    step("weather rendered");
    LinearLayout locationsPage = controller.buildLocationsPage();
    step("locations built without a headless dialog");
    check(button(locationsPage, "Add location") != null, "Locations page offers a city search below the six-city cap");
    render(locationsPage, new File(output, "settings-locations.png"));
    step("locations rendered");
    // EditText and dialog navigation require an attached Activity; verify them in the live UI.
    controller.destroy();
    System.out.println("TvSettingsUiChecks passed; screenshots in " + output);
  }

  private static Context systemContext() throws Exception {
    Class<?> activityThread = Class.forName("android.app.ActivityThread");
    Object thread = activityThread.getMethod("systemMain").invoke(null);
    if (android.graphics.Typeface.DEFAULT == null) {
      java.lang.reflect.Method fonts = android.graphics.Typeface.class.getDeclaredMethod("loadPreinstalledSystemFontMap");
      fonts.setAccessible(true);
      fonts.invoke(null);
    }
    return (Context) activityThread.getMethod("getSystemContext").invoke(thread);
  }

  private static void render(LinearLayout page, File file) throws Exception {
    if (page.getParent() instanceof android.view.ViewGroup)
      ((android.view.ViewGroup) page.getParent()).removeView(page);
    FrameLayout shell = new FrameLayout(page.getContext());
    shell.setBackgroundColor(0xff090c10);
    ScrollView card = new ScrollView(page.getContext());
    card.setFillViewport(true);
    card.setBackgroundColor(0xee11171d);
    card.addView(page);
    FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(Math.round(WIDTH * .8f), HEIGHT - 144);
    params.leftMargin = Math.round(WIDTH * .1f);
    params.topMargin = 72;
    shell.addView(card, params);
    int specWidth = View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY);
    int specHeight = View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY);
    shell.measure(specWidth, specHeight);
    shell.layout(0, 0, WIDTH, HEIGHT);
    Bitmap bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
    shell.draw(new Canvas(bitmap));
    try (FileOutputStream stream = new FileOutputStream(file)) {
      bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
    }
    bitmap.recycle();
  }

  private static Button button(View root, String contentDescription) {
    if (root instanceof Button && contentDescription.contentEquals(root.getContentDescription())) return (Button) root;
    if (!(root instanceof android.view.ViewGroup)) return null;
    android.view.ViewGroup group = (android.view.ViewGroup) root;
    for (int index = 0; index < group.getChildCount(); index++) {
      Button found = button(group.getChildAt(index), contentDescription);
      if (found != null) return found;
    }
    return null;
  }

  private static void check(boolean value, String description) {
    if (!value) throw new AssertionError(description);
  }

  private static void step(String value) { System.out.println("TvSettingsUiChecks: " + value); }

  private static final class FakeHost implements TvSettingsController.Host {
    JSONObject apps = AppsSettings.defaults();
    JSONObject weather;

    FakeHost() {
      try {
        weather = WeatherSettings.validate(new JSONObject().put("locations", new JSONArray()));
      } catch (Exception impossible) {
        weather = WeatherSettings.defaults();
      }
    }

    @Override public JSONObject apps() { return AppsSettings.copy(apps); }
    @Override public JSONObject weather() { return WeatherSettings.copy(weather); }
    @Override public void saveApps(JSONObject value, SaveResult result) { apps = AppsSettings.copy(value); result.complete(true); }
    @Override public void saveWeather(JSONObject value, SaveResult result) { weather = WeatherSettings.copy(value); result.complete(true); }
    @Override public void refreshWeather() { }
    @Override public void previewNavigationSound(JSONObject value) { }
    @Override public void showDialog(AlertDialog dialog, Runnable onDismiss) { }
    @Override public void pairMac() { }
    @Override public void chooseInput() { }
    @Override public void openSystemSettings() { }
  }
}
