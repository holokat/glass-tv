package local.glasstv.launcher;

import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * Stable, locally rendered artwork for Radio stations. Provider artwork deliberately stays out of
 * Radio so card, search, and transport presentation remain consistent while offline.
 */
final class RadioStationGradient extends Drawable {
  /** Shared deterministic colors for station art and the immersive Radio background. */
  static final class Palette {
    final int first;
    final int second;

    Palette(int first, int second) { this.first = first; this.second = second; }

    boolean sameAs(Palette other) {
      return other != null && first == other.first && second == other.second;
    }

    static Palette blend(Palette first, Palette second, float amount) {
      float mix = Math.max(0f, Math.min(1f, amount));
      return new Palette(blendColor(first.first, second.first, mix),
          blendColor(first.second, second.second, mix));
    }

    private static int blendColor(int from, int to, float amount) {
      return Color.rgb(
          Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * amount),
          Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * amount),
          Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * amount));
    }
  }

  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Palette palette;
  private final float cornerRadius;

  private RadioStationGradient(String stableKey, float cornerRadius) {
    palette = paletteForKey(stableKey);
    this.cornerRadius = cornerRadius;
  }

  static Drawable forStation(RadioStationPolicy.Station station) {
    return forStation(station, 0f);
  }

  static Drawable forStation(RadioStationPolicy.Station station, float cornerRadius) {
    return new RadioStationGradient(keyFor(station), cornerRadius);
  }

  static Palette paletteFor(RadioStationPolicy.Station station) { return paletteForKey(keyFor(station)); }

  static Palette idlePalette() { return new Palette(0xff0d5263, 0xff0b1e38); }

  private static String keyFor(RadioStationPolicy.Station station) {
    // Radio Browser can return a refreshed display name during its pre-play check. UUID keeps
    // the card, player, and background palette matched to the same station across that rename.
    if (station == null) return "radio";
    return station.uuid == null || station.uuid.trim().isEmpty() ? station.name : station.uuid;
  }

  private static Palette paletteForKey(String stableKey) {
    int hash = stableKey == null ? 0 : stableKey.hashCode();
    float hue = (hash & 0x7fffffff) % 360;
    float accentHue = (hue + 36f + ((hash >>> 9) & 31)) % 360f;
    return new Palette(Color.HSVToColor(new float[] {hue, .62f, .79f}),
        Color.HSVToColor(new float[] {accentHue, .58f, .36f}));
  }

  @Override public void draw(android.graphics.Canvas canvas) {
    Rect bounds = getBounds();
    if (bounds.isEmpty()) return;
    RectF area = new RectF(bounds);
    paint.setShader(new LinearGradient(area.left, area.top, area.right, area.bottom,
        palette.first, palette.second, Shader.TileMode.CLAMP));
    canvas.drawRect(area, paint);
    // A restrained white band gives the two-color field a glassy, metallic finish without text or
    // fake station branding. It is deterministic because its position derives only from the key.
    paint.setShader(new LinearGradient(area.left, area.top, area.right, area.bottom,
        0x66ffffff, 0x00ffffff, Shader.TileMode.CLAMP));
    canvas.drawRect(area, paint);
    paint.setShader(new LinearGradient(area.left, area.bottom, area.right, area.top,
        0x24000000, 0x00000000, Shader.TileMode.CLAMP));
    canvas.drawRect(area, paint);
    paint.setShader(null);
  }

  @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
  @Override public void setColorFilter(android.graphics.ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
  @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
  @Override public void getOutline(Outline outline) {
    if (cornerRadius > 0f) outline.setRoundRect(getBounds(), cornerRadius);
    else outline.setRect(getBounds());
  }
}
