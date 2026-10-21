package local.glasstv.launcher;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Low-cost Canvas weather card. Values and forecast labels always come from Open-Meteo readings. */
final class WeatherWidget extends View {
    interface Selector { void openLocationSelector(); }

    private static final float MINIMAL_WIDTH = 600f;
    private static final float MINIMAL_DAILY_HEIGHT = 384f;
    private static final float MINIMAL_HEIGHT = 264f;
    private static final float COMPACT_WIDTH = 520f;
    private static final float COMPACT_HEIGHT = 184f;
    private static final float SCENIC_WIDTH = 600f;
    private static final float SCENIC_HEIGHT = 338f; // 16:9
    // Inline typography scales by height; width is measured from the actual reading.
    private static final float ICON_HEIGHT = 62.4f;
    private static final int ARTWORK_MAX_WIDTH = 1200;
    private static final int ARTWORK_MAX_HEIGHT = 676;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Bitmap sun, moon, cloud, rain, locationIcon;
    private final AssetManager assets;
    private final Handler main = new Handler(Looper.getMainLooper());
    private ExecutorService artworkWorker = newArtworkWorker();
    private WeatherController weather;
    private Selector selector;
    private Bitmap scenicFallback;
    private Bitmap scenicArtwork;
    private String scenicArtworkKey;
    private final Map<String, Bitmap> portraitArtworks = new HashMap<>();
    private final Set<String> portraitArtworkRequests = new HashSet<>();
    private final ArtworkRequestGate artworkRequests = new ArtworkRequestGate();
    private ArtworkCatalog.LoadedCatalog artworkCatalog;
    private boolean artworkCatalogReady;
    private boolean artworkReleased;
    private int artworkLifecycleGeneration;
    private float scale = 1f;
    private float iconScale = 1f;
    private float horizontalSafeInset;
    private boolean interfaceVisible = true;

    WeatherWidget(Context context) {
        super(context);
        assets = context.getAssets();
        sun = loadDrawable(context, "weather_sun");
        moon = loadDrawable(context, "weather_moon");
        cloud = loadDrawable(context, "weather_cloud");
        rain = loadDrawable(context, "weather_rain");
        locationIcon = loadDrawable(context, "weather_location");
        setFocusable(true);
        setContentDescription("Weather and locations");
        loadArtworkCatalog();
        loadScenicFallback();
    }

    float widthFraction(float aspectRatio) {
        String style = style();
        if ("icon".equals(style)) return iconContentWidth() / ICON_HEIGHT * heightFraction() / aspectRatio;
        if (portraitEnabled()) return portraitGroupWidth(portraitLocationCount(), horizontalSafeInset);
        if ("compact".equals(style)) return .3523f;
        return .4069f;
    }

    float heightFraction() {
        return heightFraction(16f / 9f);
    }

    float heightFraction(float aspectRatio) {
        String style = style();
        if ("icon".equals(style)) return .06f * iconScale;
        if (portraitEnabled()) {
            float groupWidth = portraitGroupWidth(portraitLocationCount(), horizontalSafeInset);
            return portraitHeightFraction(
                portraitCardWidth(groupWidth, portraitLocationCount()), aspectRatio);
        }
        if ("compact".equals(style)) return .2210f;
        if ("scenic".equals(style)) return .4069f;
        return weather != null && weather.daily() ? .4628f : .3172f;
    }

    void setHorizontalSafeInset(float value) {
        float normalized = Math.max(0f, Math.min(.5f, value));
        if (horizontalSafeInset == normalized) return;
        horizontalSafeInset = normalized;
        requestLayout();
        invalidate();
    }

    static int portraitLocationCount(boolean portraitEnabled, boolean showAllLocations, int enabledLocationCount) {
        return portraitEnabled && showAllLocations
            ? Math.max(1, Math.min(6, enabledLocationCount))
            : 1;
    }

    static float portraitGroupWidth(int count, float horizontalSafeInset) {
        int cards = Math.max(1, Math.min(6, count));
        float safeWidth = 1f - 2f * Math.max(0f, Math.min(.5f, horizontalSafeInset));
        return Math.min(cards * .18f + (cards - 1) * .012f, safeWidth);
    }

    static float portraitCardWidth(float groupWidth, int count) {
        int cards = Math.max(1, Math.min(6, count));
        return (groupWidth - (cards - 1) * .012f) / cards;
    }

    static float portraitHeightFraction(float cardWidth, float aspectRatio) {
        float aspect = !Float.isNaN(aspectRatio) && !Float.isInfinite(aspectRatio) && aspectRatio > 0f
                ? aspectRatio : 16f / 9f;
        return cardWidth * aspect * 1.5f;
    }

    void setIconScale(float value) {
        if (iconScale == value) return;
        iconScale = value;
        invalidate();
    }

    void setWeather(WeatherController value) {
        weather = value;
        refreshScenicArtwork();
        refreshPortraitArtwork();
        updateVisibility();
        requestLayout();
        invalidate();
    }

    void setSelector(Selector value) { selector = value; }
    void setInterfaceVisible(boolean value) { interfaceVisible = value; updateVisibility(); }
    private void updateVisibility() { setVisibility(interfaceVisible && weather != null && weather.enabled() ? VISIBLE : GONE); }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!artworkReleased) return;
        artworkReleased = false;
        artworkWorker = newArtworkWorker();
        artworkCatalog = null;
        artworkCatalogReady = false;
        scenicArtworkKey = null;
        loadArtworkCatalog();
        loadScenicFallback();
        refreshScenicArtwork();
        refreshPortraitArtwork();
    }

    @Override protected void onDetachedFromWindow() {
        artworkReleased = true;
        artworkLifecycleGeneration++;
        artworkRequests.cancel();
        artworkWorker.shutdownNow();
        recycle(scenicArtwork);
        recycle(scenicFallback);
        recyclePortraitArtworks();
        scenicArtwork = null;
        scenicFallback = null;
        super.onDetachedFromWindow();
    }

    @Override public boolean onKeyDown(int key, KeyEvent event) {
        // Directional keys belong to launcher focus navigation. Location selection is opened
        // with OK, so weather can never trap focus away from Wi-Fi or Settings.
        if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) {
            if (selector != null) selector.openLocationSelector();
            return true;
        }
        return super.onKeyDown(key, event);
    }

    @Override protected void onFocusChanged(boolean focused, int direction, android.graphics.Rect previous) {
        super.onFocusChanged(focused, direction, previous);
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (weather == null || !weather.enabled()) return;
        String style = style();
        if (portraitEnabled()) {
            drawPortrait(canvas);
            return;
        }
        float nominalWidth = "icon".equals(style) ? iconContentWidth() : "compact".equals(style) ? COMPACT_WIDTH : SCENIC_WIDTH;
        float nominalHeight = "icon".equals(style) ? ICON_HEIGHT : "compact".equals(style) ? COMPACT_HEIGHT
                : "scenic".equals(style) ? SCENIC_HEIGHT
                : weather.daily() ? MINIMAL_DAILY_HEIGHT : MINIMAL_HEIGHT;
        scale = Math.min(getWidth() / nominalWidth, getHeight() / nominalHeight);
        canvas.save();
        canvas.scale(scale, scale);
        if ("icon".equals(style)) drawIconTemperature(canvas);
        else if ("scenic".equals(style)) drawScenic(canvas);
        else if ("compact".equals(style)) drawCompact(canvas);
        else drawMinimal(canvas);
        canvas.restore();
    }

    private float iconContentWidth() {
        JSONObject data = data(weather == null ? null : weather.reading());
        JSONObject current = data == null ? null : data.optJSONObject("current");
        double value = current == null ? Double.NaN : current.optDouble("temperature_2m", Double.NaN);
        int code = current == null ? -1 : current.optInt("weather_code", -1);
        return measureIconContentWidth(paint, value, code, weather == null ? "celsius" : weather.unit());
    }

    static float measureIconContentWidth(Paint measurement, double value, int code, String unit) {
        measurement.setTextSize(40f);
        measurement.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL));
        // Same text origin as drawing, plus a small trailing inset for the focus outline.
        return (code >= 0 ? 56f : 8f) + measurement.measureText(iconTemperature(value, unit)) + 8f;
    }

    /** Former detailed payloads are rendered as the migrated minimal style. */
    private String style() {
        String current = weather == null ? "minimal" : weather.style();
        return "compact".equals(current) || "scenic".equals(current) || "icon".equals(current)
                ? current : "minimal";
    }

    /** Draws only the actual condition icon and actual current temperature, with no card treatment. */
    private void drawIconTemperature(Canvas canvas) {
        WeatherController.Reading reading = weather.reading();
        JSONObject data = data(reading);
        JSONObject current = data == null ? null : data.optJSONObject("current");
        double value = current == null ? Double.NaN : current.optDouble("temperature_2m", Double.NaN);
        int code = current == null ? -1 : current.optInt("weather_code", -1);
        boolean day = current == null || current.optInt("is_day", 1) == 1;
        updateIconContentDescription(value, code);
        // A missing condition code must not become a cloud. The dash is the complete, truthful
        // unavailable state; a known temperature can remain visible without a guessed icon.
        if (code >= 0) drawCondition(canvas, code, day, 27, ICON_HEIGHT / 2f, 22);
        text(canvas, iconTemperature(value, weather.unit()), 40, code >= 0 ? 56 : 8, 45, Color.WHITE);
        if (isFocused()) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2f);
            paint.setColor(0xfaffffff);
            canvas.drawRoundRect(new RectF(1f, 1f, iconContentWidth() - 1f, ICON_HEIGHT - 1f), 6f, 6f, paint);
            paint.setStyle(Paint.Style.FILL);
        }
    }

    private void updateIconContentDescription(double value, int code) {
        String description = iconContentDescription(value, code, weather.unit());
        CharSequence current = getContentDescription();
        if (!description.contentEquals(current == null ? "" : current)) setContentDescription(description);
    }

    private void drawMinimal(Canvas canvas) {
        float height = weather.daily() ? MINIMAL_DAILY_HEIGHT : MINIMAL_HEIGHT;
        float content = contentScale();
        MinimalLayout layout = minimalLayout(paint, content, weather.daily());
        RectF card = new RectF(0, 0, MINIMAL_WIDTH, height);
        drawGlass(canvas, card);
        WeatherController.Location location = weather.selected();
        WeatherController.Reading reading = weather.reading();
        if (location == null) { unavailable(canvas, "Weather unavailable", 34, 76); return; }
        drawHeaderAt(canvas, location.name, 32, layout.header.baseline, layout.headerRight,
                layout.header.size / 26f);
        JSONObject data = data(reading);
        if (data == null) { unavailable(canvas, unavailableText(reading), 34, 112); return; }
        JSONObject current = data.optJSONObject("current");
        double value = current == null ? Double.NaN : current.optDouble("temperature_2m", Double.NaN);
        int code = current == null ? -1 : current.optInt("weather_code", -1);
        boolean day = current == null || current.optInt("is_day", 1) == 1;
        float currentX = 88 + layout.conditionRadius + 16;
        drawCondition(canvas, code, day, 88, layout.temperature.center(), layout.conditionRadius);
        if (weather.temperature()) text(canvas, temperature(value), layout.temperature.size, currentX,
                layout.temperature.baseline, Color.WHITE);
        // The condition and range share the temperature column, matching the reference card hierarchy.
        textFitted(canvas, condition(code), layout.condition.size, currentX, MINIMAL_WIDTH - 30,
                layout.condition.baseline, 0xf1ffffff);
        if (weather.temperature()) textFitted(canvas, highLow(data), layout.highLow.size, currentX,
                MINIMAL_WIDTH - 30, layout.highLow.baseline, 0xeaffffff);
        if (!weather.daily()) {
            attribution(canvas, MINIMAL_WIDTH - 30, layout.attribution.baseline);
            return;
        }
        divider(canvas, 250, MINIMAL_WIDTH);
        drawDaily(canvas, data, 250, MINIMAL_WIDTH, content);
        attribution(canvas, MINIMAL_WIDTH - 30, layout.attribution.baseline);
    }

    private void drawCompact(Canvas canvas) {
        float content = contentScale();
        CompactLayout layout = compactLayout(paint, content);
        RectF card = new RectF(0, 0, COMPACT_WIDTH, COMPACT_HEIGHT);
        drawGlass(canvas, card);
        WeatherController.Location location = weather.selected();
        WeatherController.Reading reading = weather.reading();
        if (location == null) { unavailable(canvas, "Weather unavailable", 28, 70); return; }
        float attributionWidth = measureTextWidth(paint, "Open-Meteo", 11);
        float attributionLeft = COMPACT_WIDTH - 28 - attributionWidth;
        drawHeaderAt(canvas, location.name, 28, layout.header.baseline, attributionLeft - 12,
                layout.header.size / 26f);
        JSONObject data = data(reading);
        if (data == null) { unavailable(canvas, unavailableText(reading), 28, 98); return; }
        JSONObject current = data.optJSONObject("current");
        double value = current == null ? Double.NaN : current.optDouble("temperature_2m", Double.NaN);
        int code = current == null ? -1 : current.optInt("weather_code", -1);
        boolean day = current == null || current.optInt("is_day", 1) == 1;
        float currentX = 66 + layout.conditionRadius + 14;
        drawCondition(canvas, code, day, 66, layout.temperature.center(), layout.conditionRadius);
        if (weather.temperature()) text(canvas, temperature(value), layout.temperature.size, currentX,
                layout.temperature.baseline, Color.WHITE);
        String line = condition(code);
        String highLow = weather.temperature() ? highLow(data) : "";
        float footerMiddle = COMPACT_WIDTH * .53f;
        textFitted(canvas, line, layout.footer.size, 28, footerMiddle - 8,
                layout.footer.baseline, 0xf1ffffff);
        if (!highLow.isEmpty()) textRightFitted(canvas, highLow, layout.footer.size,
                footerMiddle + 8, COMPACT_WIDTH - 28, layout.footer.baseline, 0xeaffffff);
        attribution(canvas, COMPACT_WIDTH - 28, layout.attribution.baseline);
    }

    private void drawScenic(Canvas canvas) {
        float content = contentScale();
        ScenicLayout layout = scenicLayout(paint, content);
        RectF card = new RectF(0, 0, SCENIC_WIDTH, SCENIC_HEIGHT);
        drawScenicSurface(canvas, card);
        WeatherController.Location location = weather.selected();
        WeatherController.Reading reading = weather.reading();
        if (location == null) { unavailable(canvas, "Weather unavailable", 28, 72); return; }
        textFitted(canvas, location.name, layout.city.size, 30, layout.cityRight,
                layout.city.baseline, Color.WHITE);
        // The image is destination artwork, never a live weather photograph.
        text(canvas, "Destination artwork", layout.artworkInfo.size, 30,
                layout.artworkInfo.baseline, 0xcaffffff);
        JSONObject data = data(reading);
        if (data == null) { unavailable(canvas, unavailableText(reading), 30, 152); return; }
        JSONObject current = data.optJSONObject("current");
        double value = current == null ? Double.NaN : current.optDouble("temperature_2m", Double.NaN);
        int code = current == null ? -1 : current.optInt("weather_code", -1);
        if (weather.temperature()) text(canvas, temperature(value), layout.temperature.size, 30,
                layout.temperature.baseline, Color.WHITE);
        float conditionX = scenicConditionLabelX(content);
        drawCondition(canvas, code, current == null || current.optInt("is_day", 1) == 1, 44,
                SCENIC_HEIGHT - 37, 13 * content);
        textFitted(canvas, condition(code), 21 * content, conditionX, SCENIC_WIDTH * .56f,
                SCENIC_HEIGHT - 30, Color.WHITE);
        if (weather.temperature()) textRightFitted(canvas, highLow(data), 18 * content,
                SCENIC_WIDTH * .58f, SCENIC_WIDTH - 28, SCENIC_HEIGHT - 57, 0xf2ffffff);
        long localTime = current == null ? 0 : current.optLong("time", 0) * 1000L;
        if (localTime > 0) textRight(canvas, "Updated " + time(data, localTime, "MMM d, h:mm a"), 15, SCENIC_WIDTH - 28, SCENIC_HEIGHT - 29, 0xeaffffff);
        attribution(canvas, SCENIC_WIDTH - 28, layout.attribution.baseline);
    }

    /**
     * Portrait cards remain one focus target, while every card uses that location's own cached
     * Open-Meteo reading and verified destination artwork.  The selected city is used only when
     * the raw all-locations preference is off.
     */
    private void drawPortrait(Canvas canvas) {
        List<WeatherController.Location> locations = portraitLocations();
        int count = portraitLocationCount();
        float groupFraction = portraitGroupWidth(count, horizontalSafeInset);
        float viewportWidth = groupFraction > 0f ? getWidth() / groupFraction : getWidth();
        float gap = viewportWidth * .012f;
        float cardWidth = (getWidth() - (count - 1) * gap) / count;
        for (int i = 0; i < count; i++) {
            WeatherController.Location location = i < locations.size() ? locations.get(i) : null;
            RectF card = new RectF(i * (cardWidth + gap), 0, i * (cardWidth + gap) + cardWidth, getHeight());
            drawPortraitCard(canvas, card, location);
        }
        if (isFocused()) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2.6f);
            paint.setColor(0xfaffffff);
            canvas.drawRoundRect(new RectF(1.3f, 1.3f, getWidth() - 1.3f, getHeight() - 1.3f), 18f, 18f, paint);
            paint.setStyle(Paint.Style.FILL);
        }
    }

    private void drawPortraitCard(Canvas canvas, RectF card, WeatherController.Location location) {
        String treatment = style();
        if ("scenic".equals(treatment)) {
            Bitmap artwork = portraitArtwork(location);
            drawScenicSurface(canvas, card, artwork == null ? scenicFallback : artwork, false);
        } else {
            drawGlass(canvas, card, false);
        }
        // Each city owns its contents. A long localized city name must not bleed into its neighbor.
        canvas.save();
        canvas.clipRect(card);
        if (location == null) {
            portraitText(canvas, "Weather unavailable", card, .09f, .15f, .115f, 0xeeffffff);
            canvas.restore();
            return;
        }
        portraitHeader(canvas, location.name, card, contentScale());
        WeatherController.Reading reading = weather.readingFor(location);
        JSONObject data = data(reading);
        if (data == null) {
            portraitText(canvas, portraitUnavailableText(reading), card, .09f, .48f, .11f, 0xeeffffff);
            canvas.restore();
            return;
        }
        JSONObject current = data.optJSONObject("current");
        double value = current == null ? Double.NaN : current.optDouble("temperature_2m", Double.NaN);
        int code = current == null ? -1 : current.optInt("weather_code", -1);
        boolean day = current == null || current.optInt("is_day", 1) == 1;
        if ("compact".equals(treatment)) {
            drawPortraitCompact(canvas, card, value, code, day, data);
        } else if ("minimal".equals(treatment)) {
            drawPortraitMinimal(canvas, card, value, code, day, data);
        } else {
            drawPortraitScenic(canvas, card, value, code, day, data);
        }
        canvas.restore();
    }

    /** Preserves Compact's dense, one-line status treatment inside the shared tall card. */
    private void drawPortraitCompact(Canvas canvas, RectF card, double value, int code, boolean day, JSONObject data) {
        float content = contentScale();
        if (weather.temperature()) portraitText(canvas, temperature(value), card, .09f, .48f,
                .235f * content, Color.WHITE);
        float footerY = card.bottom - card.width() * .29f;
        drawCondition(canvas, code, day, card.left + card.width() * .16f, footerY,
                card.width() * .09f * content);
        float conditionX = .16f + .09f * content + .035f;
        portraitTextFitted(canvas, condition(code), card, conditionX, .75f, .09f * content,
                .91f - conditionX, 0xf1ffffff);
        if (weather.temperature()) portraitTextRightFitted(canvas, highLow(data), card, .91f,
                .89f, .075f * content, .53f, 0xeaffffff);
    }

    /** Preserves Minimal's larger current reading and separated high/low line. */
    private void drawPortraitMinimal(Canvas canvas, RectF card, double value, int code, boolean day, JSONObject data) {
        float content = contentScale();
        if (weather.temperature()) portraitText(canvas, temperature(value), card, .09f, .53f,
                .27f * content, Color.WHITE);
        float footerY = card.bottom - card.width() * .24f;
        drawCondition(canvas, code, day, card.left + card.width() * .16f, footerY,
                card.width() * .105f * content);
        float conditionX = .16f + .105f * content + .035f;
        portraitTextFitted(canvas, condition(code), card, conditionX, .772f, .105f * content,
                .91f - conditionX, 0xf1ffffff);
        if (weather.temperature()) portraitTextFitted(canvas, highLow(data), card, .09f,
                .91f, .08f * content, .82f, 0xeaffffff);
    }

    /** Scenic retains cropped destination artwork with its lower status treatment. */
    private void drawPortraitScenic(Canvas canvas, RectF card, double value, int code, boolean day, JSONObject data) {
        float content = contentScale();
        if (weather.temperature()) portraitText(canvas, temperature(value), card, .09f, .55f,
                .265f * content, Color.WHITE);
        float footerY = card.bottom - card.width() * .15f;
        drawCondition(canvas, code, day, card.left + card.width() * .15f, footerY,
                card.width() * .09f * content);
        float conditionX = .15f + .09f * content + .035f;
        portraitTextFitted(canvas, condition(code), card, conditionX, .87f, .09f * content,
                .86f - conditionX, Color.WHITE);
        if (weather.temperature()) portraitTextRightFitted(canvas, highLow(data), card, .91f,
                .82f, .075f * content, .48f, 0xf2ffffff);
    }

    private void portraitText(Canvas canvas, String value, RectF card, float x, float y, float size, int color) {
        text(canvas, value, card.width() * size, card.left + card.width() * x, card.top + card.height() * y, color);
    }

    private void portraitTextRight(Canvas canvas, String value, RectF card, float x, float y, float size, int color) {
        textRight(canvas, value, card.width() * size, card.left + card.width() * x, card.top + card.height() * y, color);
    }

    private void portraitHeader(Canvas canvas, String value, RectF card, float content) {
        float size = card.width() * .15f * content;
        float baseline = Math.max(card.top + card.height() * .12f,
                card.top + card.width() * .07f + size * .92f);
        textFitted(canvas, value, size, card.left + card.width() * .09f,
                card.right - card.width() * .09f, baseline, Color.WHITE);
    }

    private void portraitTextFitted(Canvas canvas, String value, RectF card, float x, float y,
            float size, float widthFraction, int color) {
        textFitted(canvas, value, card.width() * size, card.left + card.width() * x,
                card.left + card.width() * (x + widthFraction), card.top + card.height() * y, color);
    }

    private void portraitTextRightFitted(Canvas canvas, String value, RectF card, float x, float y,
            float size, float widthFraction, int color) {
        textRightFitted(canvas, value, card.width() * size,
                card.left + card.width() * (x - widthFraction), card.left + card.width() * x,
                card.top + card.height() * y, color);
    }

    private JSONObject data(WeatherController.Reading reading) { return reading == null ? null : reading.data; }
    /** Icon & temperature owns its independent launcher-size control, so it ignores contentScale. */
    private float contentScale() {
        return weather == null ? (float) WeatherSettings.DEFAULT_CONTENT_SCALE
                : (float) weather.contentScale();
    }
    private static String unavailableText(WeatherController.Reading reading) { return reading != null && reading.offline ? "Offline" : "Weather unavailable"; }
    private static String portraitUnavailableText(WeatherController.Reading reading) {
        return reading == null ? "Loading forecast" : unavailableText(reading);
    }

    private void drawHeaderAt(Canvas canvas, String label, float x, float baseline, float right,
            float content) {
        float iconSize = 18 * content;
        drawIcon(canvas, locationIcon, x + iconSize * .55f, baseline - iconSize * .45f,
                iconSize, 0xeeffffff);
        textFitted(canvas, label, 26 * content, x + iconSize * 1.4f, right, baseline, Color.WHITE);
    }

    private void unavailable(Canvas canvas, String value, float x, float baseline) { text(canvas, value, 22, x, baseline, 0xeeffffff); }

    private void drawGlass(Canvas canvas, RectF bounds) {
        drawGlass(canvas, bounds, isFocused());
    }

    private void drawGlass(Canvas canvas, RectF bounds, boolean focused) {
        final float radius = 26f;
        paint.setShader(new LinearGradient(bounds.left, bounds.top, bounds.right, bounds.bottom,
                new int[] { 0xa59caec9, 0x7a536174, 0x94526071 }, null, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(bounds, radius, radius, paint);
        paint.setShader(new LinearGradient(bounds.left, bounds.top, bounds.left, bounds.top + 95,
                0x40ffffff, 0x00ffffff, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(bounds, radius, radius, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(focused ? 2.6f : 1.2f);
        paint.setColor(focused ? 0xfaffffff : 0x92ffffff);
        canvas.drawRoundRect(new RectF(bounds.left + .6f, bounds.top + .6f, bounds.right - .6f, bounds.bottom - .6f), radius, radius, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawScenicSurface(Canvas canvas, RectF bounds) {
        drawScenicSurface(canvas, bounds, scenicArtwork == null ? scenicFallback : scenicArtwork, isFocused());
    }

    private void drawScenicSurface(Canvas canvas, RectF bounds, Bitmap image, boolean focused) {
        final float radius = 26f;
        Path clip = new Path();
        clip.addRoundRect(bounds, radius, radius, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(clip);
        if (image != null) drawCover(canvas, image, bounds);
        else canvas.drawColor(0xff1d2c37);
        // Left text and bottom status remain legible over every verified destination image.
        paint.setShader(new LinearGradient(bounds.left, bounds.top, bounds.right, bounds.bottom,
                new int[] { 0x990b1720, 0x220b1720, 0x7d0b1720 }, null, Shader.TileMode.CLAMP));
        canvas.drawRect(bounds, paint);
        paint.setShader(null);
        canvas.restore();
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(focused ? 2.6f : 1.2f);
        paint.setColor(focused ? 0xfaffffff : 0x8fffffff);
        canvas.drawRoundRect(new RectF(bounds.left + .6f, bounds.top + .6f, bounds.right - .6f, bounds.bottom - .6f), radius, radius, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void divider(Canvas canvas, float y, float width) {
        paint.setColor(0x55ffffff);
        canvas.drawRect(0, y, width, y + 1, paint);
    }

    private void drawDaily(Canvas canvas, JSONObject data, float divider, float width, float content) {
        DailyLayout layout = dailyLayout(paint, content, divider);
        JSONObject daily = data.optJSONObject("daily");
        JSONArray times = daily == null ? null : daily.optJSONArray("time");
        JSONArray high = daily == null ? null : daily.optJSONArray("temperature_2m_max");
        JSONArray low = daily == null ? null : daily.optJSONArray("temperature_2m_min");
        JSONArray codes = daily == null ? null : daily.optJSONArray("weather_code");
        if (times == null) return;
        float column = width / 5f;
        for (int i = 0; i < 5 && i < times.length(); i++) {
            float center = column * i + column / 2f;
            String label = i == 0 ? "Today" : day(data, times.optLong(i));
            centeredTextFitted(canvas, label, layout.label.size, center, column - 10f,
                    layout.label.baseline, 0xeaffffff);
            drawCondition(canvas, codes == null ? -1 : codes.optInt(i, -1), true, center,
                    layout.iconCenterY, layout.iconRadius);
            if (weather.temperature()) {
                String value = temperature(high == null ? Double.NaN : high.optDouble(i, Double.NaN)) + " "
                        + temperature(low == null ? Double.NaN : low.optDouble(i, Double.NaN));
                centeredTextFitted(canvas, value, layout.temperature.size, center, column - 10f,
                        layout.temperature.baseline, Color.WHITE);
            }
        }
    }

    private String highLow(JSONObject data) {
        JSONObject daily = data.optJSONObject("daily");
        JSONArray high = daily == null ? null : daily.optJSONArray("temperature_2m_max");
        JSONArray low = daily == null ? null : daily.optJSONArray("temperature_2m_min");
        if (high == null || low == null || high.length() == 0 || low.length() == 0) return "";
        return "H " + temperature(high.optDouble(0, Double.NaN)) + "  L " + temperature(low.optDouble(0, Double.NaN));
    }

    private void attribution(Canvas canvas, float right, float baseline) {
        textRight(canvas, "Open-Meteo", 11, right, baseline, 0xcaffffff);
    }

    private void drawCondition(Canvas canvas, int code, boolean daytime, float centerX, float centerY, float radius) {
        Bitmap icon;
        if (code == 0 || code == 1) icon = daytime ? sun : moon;
        else if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82) || code >= 95) icon = rain;
        else icon = cloud;
        if (icon != null) canvas.drawBitmap(icon, null, new RectF(centerX - radius, centerY - radius, centerX + radius, centerY + radius), paint);
    }

    private void refreshScenicArtwork() {
        if (artworkReleased) return;
        WeatherController.Location location = weather == null || !"scenic".equals(style()) ? null : weather.selected();
        ArtworkReference reference = artworkReference(location);
        String key = reference == null ? null : reference.cacheKey;
        if (key != null && (key.equals(scenicArtworkKey) || key.equals(artworkRequests.pendingKey()))) return;
        final int generation = artworkRequests.request(key);
        if (reference == null) {
            replaceScenicArtwork(null, null);
            return;
        }
        artworkWorker.execute(() -> {
            Bitmap bitmap = reference.assetPath == null ? null
                    : loadBoundedAsset(assets, reference.assetPath, ARTWORK_MAX_WIDTH, ARTWORK_MAX_HEIGHT);
            if (bitmap == null && reference.legacyFallbackPath != null) {
                bitmap = loadBoundedAsset(assets, reference.legacyFallbackPath, ARTWORK_MAX_WIDTH, ARTWORK_MAX_HEIGHT);
            }
            final Bitmap decoded = bitmap;
            main.post(() -> {
                if (artworkReleased || !artworkRequests.isCurrent(generation)) {
                    recycle(decoded);
                    return;
                }
                artworkRequests.complete(generation);
                replaceScenicArtwork(decoded, reference.cacheKey);
            });
        });
    }

    /** Loads only the artwork visible in portrait mode and releases images no longer represented. */
    private void refreshPortraitArtwork() {
        if (artworkReleased) return;
        if (!portraitEnabled()) {
            recyclePortraitArtworks();
            portraitArtworkRequests.clear();
            return;
        }
        Set<String> desired = new HashSet<>();
        for (WeatherController.Location location : portraitLocations()) {
            ArtworkReference reference = artworkReference(location);
            if (reference == null || reference.cacheKey == null) continue;
            desired.add(reference.cacheKey);
            if (portraitArtworks.containsKey(reference.cacheKey) || !portraitArtworkRequests.add(reference.cacheKey)) continue;
            loadPortraitArtwork(reference);
        }
        java.util.Iterator<Map.Entry<String, Bitmap>> entries = portraitArtworks.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<String, Bitmap> entry = entries.next();
            if (desired.contains(entry.getKey())) continue;
            recycle(entry.getValue());
            entries.remove();
        }
    }

    private void loadPortraitArtwork(final ArtworkReference reference) {
        final int lifecycle = artworkLifecycleGeneration;
        artworkWorker.execute(() -> {
            Bitmap bitmap = reference.assetPath == null ? null
                    : loadBoundedAsset(assets, reference.assetPath, ARTWORK_MAX_WIDTH, ARTWORK_MAX_HEIGHT);
            if (bitmap == null && reference.legacyFallbackPath != null) {
                bitmap = loadBoundedAsset(assets, reference.legacyFallbackPath, ARTWORK_MAX_WIDTH, ARTWORK_MAX_HEIGHT);
            }
            final Bitmap decoded = bitmap;
            main.post(() -> {
                portraitArtworkRequests.remove(reference.cacheKey);
                if (artworkReleased || lifecycle != artworkLifecycleGeneration || !portraitArtworkStillNeeded(reference.cacheKey)) {
                    recycle(decoded);
                    return;
                }
                Bitmap previous = portraitArtworks.put(reference.cacheKey, decoded);
                if (previous != decoded) recycle(previous);
                invalidate();
            });
        });
    }

    private boolean portraitArtworkStillNeeded(String key) {
        if (!portraitEnabled()) return false;
        for (WeatherController.Location location : portraitLocations()) {
            ArtworkReference reference = artworkReference(location);
            if (reference != null && key.equals(reference.cacheKey)) return true;
        }
        return false;
    }

    private List<WeatherController.Location> portraitLocations() {
        if (weather == null) return Collections.emptyList();
        if (portraitEnabled() && weather.showAllLocations()) return weather.locations();
        WeatherController.Location selected = weather.selected();
        return selected == null ? Collections.<WeatherController.Location>emptyList()
                : Collections.singletonList(selected);
    }

    private int portraitLocationCount() {
        return portraitLocationCount(portraitEnabled(), weather != null && weather.showAllLocations(),
                weather == null ? 0 : weather.locations().size());
    }

    private boolean portraitEnabled() { return weather != null && weather.portraitEnabled(); }

    private Bitmap portraitArtwork(WeatherController.Location location) {
        ArtworkReference reference = artworkReference(location);
        return reference == null ? null : portraitArtworks.get(reference.cacheKey);
    }

    private void recyclePortraitArtworks() {
        for (Bitmap artwork : portraitArtworks.values()) recycle(artwork);
        portraitArtworks.clear();
        portraitArtworkRequests.clear();
    }

    /** Starts off-main-thread asset work so remote configuration never blocks the launcher frame. */
    private void loadScenicFallback() {
        final int lifecycle = artworkLifecycleGeneration;
        artworkWorker.execute(() -> {
            Bitmap bitmap = loadBoundedAsset(assets, "wallpaper.jpg", ARTWORK_MAX_WIDTH, ARTWORK_MAX_HEIGHT);
            main.post(() -> {
                if (artworkReleased || lifecycle != artworkLifecycleGeneration) {
                    recycle(bitmap);
                    return;
                }
                recycle(scenicFallback);
                scenicFallback = bitmap;
                invalidate();
            });
        });
    }

    private void loadArtworkCatalog() {
        final int lifecycle = artworkLifecycleGeneration;
        artworkWorker.execute(() -> {
            ArtworkCatalog.LoadedCatalog loaded = ArtworkCatalog.load(assets);
            main.post(() -> {
                if (artworkReleased || lifecycle != artworkLifecycleGeneration) return;
                artworkCatalog = loaded;
                artworkCatalogReady = true;
                refreshScenicArtwork();
                refreshPortraitArtwork();
            });
        });
    }

    private ArtworkReference artworkReference(WeatherController.Location location) {
        if (location == null || !artworkCatalogReady) return null;
        if (artworkCatalog != null && artworkCatalog.available) return artworkCatalog.referenceFor(location);
        String legacyKey = legacyArtworkKey(location);
        return legacyKey == null ? null : new ArtworkReference("legacy:" + legacyKey, "weather-artwork/" + legacyKey + ".png");
    }

    /** Used only by older app bundles that do not include the shared catalog. */
    private static String legacyArtworkKey(WeatherController.Location location) {
        if (location == null) return null;
        String name = normalize(location.name);
        if (matches(location, name, new String[] { "tokyo", "東京", "東京都" }, new String[] { "geo-1850147" }, 35.6895, 139.69171, 40)) return "tokyo";
        if (matches(location, name, new String[] { "denver" }, new String[] { "geo-5419384" }, 39.73915, -104.9847, 35)) return "denver";
        if (matches(location, name, new String[] { "maui" }, new String[] { "geo-5850862" }, 20.77024, -156.26816, 60)) return "maui";
        if (matches(location, name, new String[] { "reykjavik" }, new String[] { "geo-3413829" }, 64.13548, -21.89541, 30)) return "reykjavik";
        if (matches(location, name, new String[] { "singapore", "singapore island", "新加坡" }, new String[] { "geo-1880252", "geo-1880251", "geo-1880249" }, 1.28967, 103.85007, 40)) return "singapore";
        if (matches(location, name, new String[] { "paris" }, new String[] { "geo-2988507" }, 48.85341, 2.3488, 30)) return "paris";
        if (matches(location, name, new String[] { "new york", "new york city", "nyc" }, new String[] { "geo-5128581" }, 40.71427, -74.00597, 35)) return "new-york";
        if (matches(location, name, new String[] { "london" }, new String[] { "geo-2643743" }, 51.50853, -0.12574, 40)) return "london";
        return null;
    }

    private static boolean matches(WeatherController.Location location, String name, String[] names, String[] ids, double lat, double lon, double radiusKm) {
        boolean known = false;
        for (String candidate : names) if (normalize(candidate).equals(name)) { known = true; break; }
        if (!known) for (String id : ids) if (id.equals(location.id)) { known = true; break; }
        return known && distanceKm(location.latitude, location.longitude, lat, lon) <= radiusKm;
    }

    private static double distanceKm(double lat1, double lon1, double lat2, double lon2) {
        if (Double.isNaN(lat1) || Double.isNaN(lon1) || lat1 < -90 || lat1 > 90 || lon1 < -180 || lon1 > 180) return Double.POSITIVE_INFINITY;
        double radians = Math.PI / 180d;
        double dLat = (lat2 - lat1) * radians, dLon = (lon2 - lon1) * radians;
        double a = Math.pow(Math.sin(dLat / 2), 2) + Math.cos(lat1 * radians) * Math.cos(lat2 * radians) * Math.pow(Math.sin(dLon / 2), 2);
        return 6371d * 2d * Math.atan2(Math.sqrt(Math.max(0d, a)), Math.sqrt(Math.max(0d, 1d - a)));
    }

    private void replaceScenicArtwork(Bitmap next, String key) {
        if (scenicArtwork != next) recycle(scenicArtwork);
        scenicArtwork = next;
        scenicArtworkKey = key;
        invalidate();
    }

    private static void recycle(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
    }

    private static ExecutorService newArtworkWorker() { return Executors.newSingleThreadExecutor(); }

    static final class ArtworkReference {
        final String cacheKey, assetPath, legacyFallbackPath;

        ArtworkReference(String cacheKey, String assetPath) {
            this(cacheKey, assetPath, null);
        }

        ArtworkReference(String cacheKey, String assetPath, String legacyFallbackPath) {
            this.cacheKey = cacheKey;
            this.assetPath = assetPath;
            this.legacyFallbackPath = legacyFallbackPath;
        }
    }

    /** Main-thread request token that prevents a late decode replacing a newer scene selection. */
    static final class ArtworkRequestGate {
        private int generation;
        private String pendingKey;

        int request(String key) {
            pendingKey = key;
            return ++generation;
        }

        boolean isCurrent(int candidate) { return candidate == generation; }
        String pendingKey() { return pendingKey; }

        void complete(int candidate) {
            if (isCurrent(candidate)) pendingKey = null;
        }

        void cancel() {
            pendingKey = null;
            generation++;
        }
    }

    /**
     * The catalog is shared with the Mac app. A catalog that exists but cannot be parsed resolves to
     * neutral artwork; the legacy PNG table is used only when a pre-catalog bundle lacks the file.
     */
    static final class ArtworkCatalog {
        private static final int MAX_CATALOG_BYTES = 131072;
        private final List<Destination> destinations;

        ArtworkCatalog(List<Destination> destinations) { this.destinations = destinations; }

        static final class LoadedCatalog {
            final boolean available;
            final ArtworkCatalog catalog;

            LoadedCatalog(boolean available, ArtworkCatalog catalog) {
                this.available = available;
                this.catalog = catalog;
            }

            ArtworkReference referenceFor(WeatherController.Location location) {
                if (catalog == null) return null;
                for (Destination destination : catalog.destinations) {
                    if (!destination.matches(location)) continue;
                    String image = destination.imageFor(location.artworkVariant);
                    String legacy = location.artworkVariant == 0
                            && destination.key.equals(legacyArtworkKey(location)) ? destination.key : null;
                    if (image == null && legacy == null) return null;
                    return new ArtworkReference(destination.key + ":" + location.artworkVariant + ":"
                                    + (image == null ? "unavailable" : image),
                            image == null ? null : "weather-artwork/" + image,
                            legacy == null ? null : "weather-artwork/" + legacy + ".png");
                }
                return null;
            }
        }

        static LoadedCatalog load(AssetManager assets) {
            try (InputStream input = assets.open("weather-artwork/catalog.json")) {
                try {
                    return new LoadedCatalog(true, parse(readUtf8(input)));
                } catch (Exception invalidCatalog) {
                    return new LoadedCatalog(true, new ArtworkCatalog(Collections.<Destination>emptyList()));
                }
            } catch (Exception missingCatalog) {
                return new LoadedCatalog(false, new ArtworkCatalog(Collections.<Destination>emptyList()));
            }
        }

        static ArtworkCatalog parse(String source) throws Exception {
            JSONObject root = new JSONObject(source);
            if (root.optInt("version", -1) != 1) throw new IllegalArgumentException("Unsupported artwork catalog");
            JSONArray values = root.optJSONArray("destinations");
            if (values == null) throw new IllegalArgumentException("Missing artwork destinations");
            ArrayList<Destination> destinations = new ArrayList<>();
            for (int i = 0; i < values.length(); i++) {
                JSONObject value = values.optJSONObject(i);
                if (value == null) continue;
                String key = value.optString("key", "").trim();
                double latitude = value.optDouble("latitude", Double.NaN);
                double longitude = value.optDouble("longitude", Double.NaN);
                double radius = value.optDouble("radiusKM", Double.NaN);
                if (!key.matches("[a-z0-9][a-z0-9-]{0,79}") || !finite(latitude) || !finite(longitude)
                        || latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180
                        || !finite(radius) || radius <= 0 || radius > 500) continue;
                destinations.add(new Destination(key, strings(value.optJSONArray("names"), true),
                        strings(value.optJSONArray("geoIDs"), false), latitude, longitude, radius,
                        imageNames(value.optJSONArray("images"))));
            }
            return new ArtworkCatalog(Collections.unmodifiableList(destinations));
        }

        private static List<String> strings(JSONArray source, boolean normalized) {
            if (source == null) return Collections.emptyList();
            ArrayList<String> values = new ArrayList<>();
            for (int i = 0; i < source.length(); i++) {
                Object value = source.opt(i);
                if (!(value instanceof String)) continue;
                String text = normalized ? normalize((String) value) : ((String) value).trim();
                if (!text.isEmpty() && text.length() <= 100 && !values.contains(text)) values.add(text);
            }
            return Collections.unmodifiableList(values);
        }

        private static List<String> imageNames(JSONArray source) {
            if (source == null) return Collections.emptyList();
            ArrayList<String> values = new ArrayList<>();
            for (int i = 0; i < source.length() && i < 5; i++) {
                Object value = source.opt(i);
                String image = null;
                if (value instanceof String) {
                    String file = ((String) value).trim();
                    String lower = file.toLowerCase(Locale.US);
                    if (file.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,159}")
                            && (lower.endsWith(".jpg") || lower.endsWith(".jpeg"))) image = file;
                }
                // Preserve the catalog index: scene 2 must not silently become scene 1.
                values.add(image);
            }
            return Collections.unmodifiableList(values);
        }

        private static String readUtf8(InputStream input) throws Exception {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) > 0) {
                if (output.size() + count > MAX_CATALOG_BYTES) throw new IllegalArgumentException("Artwork catalog too large");
                output.write(buffer, 0, count);
            }
            return output.toString("UTF-8");
        }

        private static final class Destination {
            final String key;
            final List<String> names, geoIDs, images;
            final double latitude, longitude, radiusKM;

            Destination(String key, List<String> names, List<String> geoIDs, double latitude,
                    double longitude, double radiusKM, List<String> images) {
                this.key = key;
                this.names = names;
                this.geoIDs = geoIDs;
                this.latitude = latitude;
                this.longitude = longitude;
                this.radiusKM = radiusKM;
                this.images = images;
            }

            boolean matches(WeatherController.Location location) {
                if (location == null || !finite(location.latitude) || !finite(location.longitude)
                        || location.latitude < -90 || location.latitude > 90
                        || location.longitude < -180 || location.longitude > 180) return false;
                if (!names.contains(normalize(location.name)) && !geoIDs.contains(location.id)) return false;
                return distanceKm(location.latitude, location.longitude, latitude, longitude) <= radiusKM;
            }

            String imageFor(int variant) {
                return variant >= 0 && variant < images.size() ? images.get(variant) : null;
            }
        }
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").trim().toLowerCase(Locale.US);
    }

    private static boolean finite(double value) { return !Double.isNaN(value) && !Double.isInfinite(value); }

    private void drawIcon(Canvas canvas, Bitmap icon, float centerX, float centerY, float size, int color) {
        if (icon == null) return;
        paint.setColorFilter(new android.graphics.PorterDuffColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN));
        canvas.drawBitmap(icon, null, new RectF(centerX - size / 2f, centerY - size / 2f, centerX + size / 2f, centerY + size / 2f), paint);
        paint.setColorFilter(null);
    }

    private static void drawCover(Canvas canvas, Bitmap bitmap, RectF destination) {
        float factor = Math.max(destination.width() / bitmap.getWidth(), destination.height() / bitmap.getHeight());
        float width = bitmap.getWidth() * factor, height = bitmap.getHeight() * factor;
        canvas.drawBitmap(bitmap, null, new RectF(destination.centerX() - width / 2f, destination.centerY() - height / 2f,
                destination.centerX() + width / 2f, destination.centerY() + height / 2f), null);
    }

    private static Bitmap loadDrawable(Context context, String name) {
        int id = DrawableResources.id(context, name);
        return id == 0 ? null : BitmapFactory.decodeResource(context.getResources(), id);
    }

    private static Bitmap loadBoundedAsset(AssetManager assets, String name, int maxWidth, int maxHeight) {
        try (InputStream bounds = assets.open(name)) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(bounds, null, options);
            if (options.outWidth <= 0 || options.outHeight <= 0) return null;
            int sample = 1;
            while (options.outWidth / sample > maxWidth || options.outHeight / sample > maxHeight) sample *= 2;
            options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            try (InputStream input = assets.open(name)) {
                return BitmapFactory.decodeStream(input, null, options);
            }
        } catch (Exception ignored) { return null; }
    }

    private String temperature(double value) { return temperature(value, weather.unit()); }
    private static String temperature(double value, String unit) { return Double.isNaN(value) ? "—" : Math.round(value) + ("fahrenheit".equals(unit) ? "°F" : "°"); }
    /** Icon is an inline launcher reading, so it always names the selected temperature unit. */
    private static String iconTemperature(double value, String unit) {
        return Double.isNaN(value) ? "—" : Math.round(value) + ("fahrenheit".equals(unit) ? "°F" : "°C");
    }
    static String iconContentDescription(double value, int code, String unit) {
        if (Double.isNaN(value)) return "Forecast unavailable";
        String reading = "Temperature " + iconTemperature(value, unit);
        return code < 0 ? "Forecast unavailable; " + reading : "Weather " + condition(code) + "; " + reading;
    }
    private static String condition(int code) {
        if (code < 0) return "Unavailable";
        if (code == 0) return "Clear";
        if (code == 1) return "Mainly clear";
        if (code == 2) return "Partly cloudy";
        if (code == 3) return "Overcast";
        if (code == 45 || code == 48) return "Fog";
        if (code >= 51 && code <= 57) return "Drizzle";
        if (code >= 61 && code <= 67) return "Rain";
        if (code >= 71 && code <= 77) return "Snow";
        if (code >= 80 && code <= 82) return "Showers";
        if (code >= 85 && code <= 86) return "Snow showers";
        if (code >= 95 && code <= 99) return "Thunderstorm";
        return "Unavailable";
    }
    private static String day(JSONObject data, long seconds) { return time(data, seconds * 1000L, "EEE"); }
    private static String time(JSONObject data, long millis, String format) {
        SimpleDateFormat formatter = new SimpleDateFormat(format, Locale.getDefault());
        formatter.setTimeZone(TimeZone.getTimeZone(data.optString("timezone", "UTC")));
        return formatter.format(new Date(millis));
    }
    private void centeredText(Canvas canvas, String value, float size, float center, float baseline, int color) {
        paint.setTextSize(size);
        text(canvas, value, size, center - paint.measureText(value) / 2f, baseline, color);
    }
    private void centeredTextFitted(Canvas canvas, String value, float size, float center,
            float maxWidth, float baseline, int color) {
        paint.setTextSize(size);
        paint.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL));
        String fitted = fittedLabel(paint, value, maxWidth);
        text(canvas, fitted, size, center - paint.measureText(fitted) / 2f, baseline, color);
    }
    private void textFitted(Canvas canvas, String value, float size, float left, float right,
            float baseline, int color) {
        paint.setTextSize(size);
        paint.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL));
        text(canvas, fittedLabel(paint, value, Math.max(0f, right - left)), size, left, baseline, color);
    }
    private void textRightFitted(Canvas canvas, String value, float size, float left, float right,
            float baseline, int color) {
        paint.setTextSize(size);
        paint.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL));
        String fitted = fittedLabel(paint, value, Math.max(0f, right - left));
        text(canvas, fitted, size, right - paint.measureText(fitted), baseline, color);
    }
    /** Ellipsizing creates a deliberate boundary for localized names rather than canvas clipping. */
    static String fittedLabel(Paint measurement, String value, float maxWidth) {
        if (value == null || value.isEmpty() || maxWidth <= 0f) return "";
        return android.text.TextUtils.ellipsize(value, new android.text.TextPaint(measurement), maxWidth,
                android.text.TextUtils.TruncateAt.END).toString();
    }

    /** Fixed Compact geometry keeps all scaled text bands inside the unchanged 184-unit card. */
    static final class CompactLayout {
        final TextBand header, temperature, footer, attribution;
        final float headerRight, attributionLeft, conditionRadius;

        CompactLayout(TextBand header, TextBand temperature, TextBand footer, TextBand attribution,
                float headerRight, float attributionLeft, float conditionRadius) {
            this.header = header;
            this.temperature = temperature;
            this.footer = footer;
            this.attribution = attribution;
            this.headerRight = headerRight;
            this.attributionLeft = attributionLeft;
            this.conditionRadius = conditionRadius;
        }
    }

    static final class TextBand {
        final float size, top, baseline, bottom;

        TextBand(float size, float top, float baseline, float bottom) {
            this.size = size;
            this.top = top;
            this.baseline = baseline;
            this.bottom = bottom;
        }

        float center() { return (top + bottom) / 2f; }
    }

    static CompactLayout compactLayout(Paint measurement, float contentScale) {
        float content = (float) Math.max(WeatherSettings.MIN_CONTENT_SCALE,
                Math.min(WeatherSettings.MAX_CONTENT_SCALE, contentScale));
        TextBand header = textBandAtTop(measurement, 26f * content, 8f);
        // Attribution moves into the otherwise unused header-right corner at enlarged sizes.
        TextBand attribution = textBandAtTop(measurement, 11f, 8f);
        TextBand footer = textBandAtBottom(measurement, 19f * content, COMPACT_HEIGHT - 8f);
        float temperatureTop = header.bottom + 8f;
        float temperatureBottom = footer.top - 8f;
        float temperatureSize = fittedTextSize(measurement, 56f * content,
                temperatureTop, temperatureBottom);
        TextBand temperature = textBandAtTop(measurement, temperatureSize, temperatureTop);
        float attributionLeft = COMPACT_WIDTH - 28f - measureTextWidth(measurement, "Open-Meteo", 11f);
        return new CompactLayout(header, temperature, footer, attribution,
                attributionLeft - 12f, attributionLeft,
                Math.min(30f * content, (temperature.bottom - temperature.top) / 2f));
    }

    static float scenicConditionLabelX(float contentScale) {
        float content = (float) Math.max(WeatherSettings.MIN_CONTENT_SCALE,
                Math.min(WeatherSettings.MAX_CONTENT_SCALE, contentScale));
        return 44f + 13f * content + 8f;
    }

    static final class ScenicLayout {
        final TextBand city, artworkInfo, temperature, attribution;
        final float cityRight, attributionLeft;

        ScenicLayout(TextBand city, TextBand artworkInfo, TextBand temperature,
                TextBand attribution, float cityRight, float attributionLeft) {
            this.city = city;
            this.artworkInfo = artworkInfo;
            this.temperature = temperature;
            this.attribution = attribution;
            this.cityRight = cityRight;
            this.attributionLeft = attributionLeft;
        }
    }

    static ScenicLayout scenicLayout(Paint measurement, float contentScale) {
        float content = (float) Math.max(WeatherSettings.MIN_CONTENT_SCALE,
                Math.min(WeatherSettings.MAX_CONTENT_SCALE, contentScale));
        TextBand city = textBandAtTop(measurement, 27f * content, 8f);
        TextBand attribution = textBandAtTop(measurement, 11f, 8f);
        TextBand artworkInfo = textBandAtTop(measurement, 11f, city.bottom + 4f);
        float temperatureTop = Math.max(90f, artworkInfo.bottom + 7f);
        float temperatureSize = fittedTextSize(measurement, 72f * content, temperatureTop, 244f);
        TextBand temperature = textBandAtTop(measurement, temperatureSize, temperatureTop);
        float attributionLeft = SCENIC_WIDTH - 28f - measureTextWidth(measurement, "Open-Meteo", 11f);
        return new ScenicLayout(city, artworkInfo, temperature, attribution,
                attributionLeft - 12f, attributionLeft);
    }

    static final class MinimalLayout {
        final TextBand header, temperature, condition, highLow, attribution;
        final float headerRight, attributionLeft, conditionRadius;

        MinimalLayout(TextBand header, TextBand temperature, TextBand condition, TextBand highLow,
                TextBand attribution, float headerRight, float attributionLeft, float conditionRadius) {
            this.header = header;
            this.temperature = temperature;
            this.condition = condition;
            this.highLow = highLow;
            this.attribution = attribution;
            this.headerRight = headerRight;
            this.attributionLeft = attributionLeft;
            this.conditionRadius = conditionRadius;
        }
    }

    static MinimalLayout minimalLayout(Paint measurement, float contentScale, boolean daily) {
        float content = (float) Math.max(WeatherSettings.MIN_CONTENT_SCALE,
                Math.min(WeatherSettings.MAX_CONTENT_SCALE, contentScale));
        TextBand header = textBandAtTop(measurement, 26f * content, 8f);
        TextBand attribution = textBandAtTop(measurement, 11f, 8f);
        float bodyBottom = daily ? 244f : MINIMAL_HEIGHT - 8f;
        TextBand highLow = textBandAtBottom(measurement, 20f * content, bodyBottom);
        TextBand condition = textBandAtBottom(measurement, 22f * content, highLow.top - 6f);
        float temperatureTop = header.bottom + 8f;
        float temperatureSize = fittedTextSize(measurement, 72f * content,
                temperatureTop, condition.top - 8f);
        TextBand temperature = textBandAtTop(measurement, temperatureSize, temperatureTop);
        float attributionLeft = MINIMAL_WIDTH - 30f - measureTextWidth(measurement, "Open-Meteo", 11f);
        return new MinimalLayout(header, temperature, condition, highLow, attribution,
                attributionLeft - 12f, attributionLeft,
                Math.min(42f * content, (temperature.bottom - temperature.top) / 2f));
    }

    static final class DailyLayout {
        final TextBand label, temperature;
        final float iconRadius, iconCenterY;

        DailyLayout(TextBand label, TextBand temperature, float iconRadius, float iconCenterY) {
            this.label = label;
            this.temperature = temperature;
            this.iconRadius = iconRadius;
            this.iconCenterY = iconCenterY;
        }
    }

    static DailyLayout dailyLayout(Paint measurement, float contentScale, float divider) {
        float content = (float) Math.max(WeatherSettings.MIN_CONTENT_SCALE,
                Math.min(WeatherSettings.MAX_CONTENT_SCALE, contentScale));
        float availableTop = divider + 7f;
        float availableBottom = MINIMAL_DAILY_HEIGHT - 7f;
        float labelRequested = 20f * content;
        float temperatureRequested = 19f * content;
        float iconRequested = 18f * content;
        prepareText(measurement, labelRequested);
        float labelHeight = measurement.getFontMetrics().descent - measurement.getFontMetrics().ascent;
        prepareText(measurement, temperatureRequested);
        float temperatureHeight = measurement.getFontMetrics().descent - measurement.getFontMetrics().ascent;
        float required = labelHeight + temperatureHeight + 2f * iconRequested + 12f;
        float factor = Math.min(1f, Math.max(0f, availableBottom - availableTop - 1f) / required);
        TextBand label = textBandAtTop(measurement, labelRequested * factor, availableTop);
        TextBand temperature = textBandAtBottom(measurement, temperatureRequested * factor, availableBottom);
        float iconRadius = Math.min(iconRequested * factor,
                Math.max(0f, (temperature.top - label.bottom - 12f) / 2f));
        float iconCenterY = label.bottom + 6f + iconRadius;
        return new DailyLayout(label, temperature, iconRadius, iconCenterY);
    }

    private static float fittedTextSize(Paint measurement, float requested, float top, float bottom) {
        prepareText(measurement, requested);
        Paint.FontMetrics metrics = measurement.getFontMetrics();
        float available = Math.max(0f, bottom - top - .5f);
        float height = metrics.descent - metrics.ascent;
        return height <= available || height == 0f ? requested : requested * available / height;
    }

    private static TextBand textBandAtTop(Paint measurement, float size, float top) {
        prepareText(measurement, size);
        Paint.FontMetrics metrics = measurement.getFontMetrics();
        float baseline = top - metrics.ascent;
        return new TextBand(size, top, baseline, baseline + metrics.descent);
    }

    private static TextBand textBandAtBottom(Paint measurement, float size, float bottom) {
        prepareText(measurement, size);
        Paint.FontMetrics metrics = measurement.getFontMetrics();
        float baseline = bottom - metrics.descent;
        return new TextBand(size, baseline + metrics.ascent, baseline, bottom);
    }

    private static float measureTextWidth(Paint measurement, String value, float size) {
        prepareText(measurement, size);
        return measurement.measureText(value);
    }

    private static void prepareText(Paint measurement, float size) {
        measurement.setTextSize(size);
        measurement.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL));
    }
    private void textRight(Canvas canvas, String value, float size, float right, float baseline, int color) {
        paint.setTextSize(size);
        text(canvas, value, size, right - paint.measureText(value), baseline, color);
    }
    private void text(Canvas canvas, String value, float size, float x, float baseline, int color) {
        paint.setColor(color);
        paint.setTextSize(size);
        paint.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL));
        canvas.drawText(value, x, baseline, paint);
    }
}
