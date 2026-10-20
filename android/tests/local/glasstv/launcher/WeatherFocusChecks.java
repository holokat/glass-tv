package local.glasstv.launcher;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.graphics.Paint;
import android.os.Looper;
import android.view.FocusFinder;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import org.json.JSONObject;

/** Device regression checks using explicit test readings, without modifying receiver preferences. */
public final class WeatherFocusChecks {
    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Object thread = activityThread.getMethod("systemMain").invoke(null);
        Context system = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
        // app_process does not inherit Zygote's preloaded fonts.
        if (android.graphics.Typeface.DEFAULT == null) {
            java.lang.reflect.Method fonts = android.graphics.Typeface.class
                    .getDeclaredMethod("loadPreinstalledSystemFontMap");
            fonts.setAccessible(true);
            fonts.invoke(null);
        }
        Context app = system.createPackageContext("local.glasstv.launcher", 0);
        Context context = new ContextWrapper(app) {
            @Override public SharedPreferences getSharedPreferences(String name, int mode) { return null; }
        };
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        float single = WeatherWidget.measureIconContentWidth(paint, 4, 0, "celsius");
        float doubleDigit = WeatherWidget.measureIconContentWidth(paint, 24, 0, "celsius");
        float negative = WeatherWidget.measureIconContentWidth(paint, -24, 0, "celsius");
        float fahrenheit = WeatherWidget.measureIconContentWidth(paint, 104, 0, "fahrenheit");
        float missing = WeatherWidget.measureIconContentWidth(paint, Double.NaN, -1, "celsius");
        check(single < doubleDigit && doubleDigit < negative, "bounds follow the reading length");
        check(doubleDigit < 200 && fahrenheit > doubleDigit, "no fixed 240px width; Fahrenheit fits");
        check(missing < single, "unavailable reading has no empty icon reservation");

        WeatherController controller = new WeatherController(context, ignored -> {});
        controller.setConfig(new JSONObject().put("contentScale", 1.6d));
        check(Math.abs(controller.contentScale() - 1.6d) < .0001d,
                "controller exposes the selected card content scale");
        controller.setConfig(new JSONObject().put("contentScale", -2d));
        check(Math.abs(controller.contentScale() - 1d) < .0001d,
                "controller clamps an unvalidated transient card scale");
        controller.setConfig(new JSONObject().put("style", "icon"));
        WeatherWidget widget = new WeatherWidget(context);
        widget.setWeather(controller);
        widget.setInterfaceVisible(true);
        widget.setVisibility(View.VISIBLE);
        float width = widget.widthFraction(16f / 9f);
        widget.setIconScale(2f);
        check(Math.abs(widget.widthFraction(16f / 9f) - width * 2f) < .0001f,
                "resizing scales measured bounds proportionally");
        for (int key : new int[] { KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT }) {
            check(!widget.onKeyDown(key, new KeyEvent(KeyEvent.ACTION_DOWN, key)),
                    "weather releases directional keys to focus navigation");
        }
        final boolean[] opened = {false};
        widget.setSelector(() -> opened[0] = true);
        check(widget.onKeyDown(KeyEvent.KEYCODE_DPAD_CENTER,
                new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)) && opened[0],
                "OK still opens the location chooser");

        FrameLayout root = new FrameLayout(context);
        View wifi = new View(context), settings = new View(context);
        wifi.setFocusable(true);
        settings.setFocusable(true);
        root.addView(widget);
        root.addView(wifi);
        root.addView(settings);
        root.layout(0, 0, 1920, 1080);
        widget.layout(1400, 50, 1400 + (int) doubleDigit, 113);
        wifi.layout(1650, 50, 1727, 115);
        settings.layout(1780, 50, 1857, 115);
        FocusFinder finder = FocusFinder.getInstance();
        check(finder.findNextFocus(root, widget, View.FOCUS_RIGHT) == wifi, "Right reaches Wi-Fi");
        check(finder.findNextFocus(root, wifi, View.FOCUS_RIGHT) == settings, "Right reaches Settings");
        check(finder.findNextFocus(root, settings, View.FOCUS_LEFT) == wifi, "Left returns to Wi-Fi");
        check(finder.findNextFocus(root, wifi, View.FOCUS_LEFT) == widget, "Left returns to weather");
        controller.destroy();
        System.out.println("WeatherFocusChecks passed");
        System.exit(0);
    }

    private static void check(boolean value, String description) {
        if (!value) throw new AssertionError(description);
        System.out.println("PASS " + description);
    }
}
