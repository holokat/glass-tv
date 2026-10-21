package local.glasstv.launcher;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.animation.DecelerateInterpolator;

/**
 * Radio's self-contained ambient surface. It starts as a subdued teal/blue field and crossfades
 * to the same deterministic two-color palette used by the selected station's artwork.
 */
final class RadioBackdropDrawable extends Drawable {
  private static final long TRANSITION_MS = 780L;
  private final Paint basePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint sourcePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint targetPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint fadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private RadioStationGradient.Palette source = RadioStationGradient.idlePalette();
  private RadioStationGradient.Palette target = source;
  private ValueAnimator transition;
  private float progress = 1f;
  private int cachedWidth = -1;
  private int cachedHeight = -1;
  private int alpha = 255;
  private ColorFilter colorFilter;

  RadioBackdropDrawable() {
    basePaint.setColor(0xff020609);
  }

  void showStation(RadioStationPolicy.Station station, boolean animate) {
    showPalette(RadioStationGradient.paletteFor(station), animate);
  }

  void showIdle(boolean animate) { showPalette(RadioStationGradient.idlePalette(), animate); }

  void cancelAnimation() {
    ValueAnimator old = transition;
    transition = null;
    if (old != null) old.cancel();
  }

  private void showPalette(RadioStationGradient.Palette next, boolean animate) {
    if (next == null) return;
    if (next.sameAs(target)) {
      // Close/release asks for a nonanimated idle state even if an earlier transition was stopped
      // mid-frame. Snap it fully so reopening cannot inherit a partially blended old palette.
      if (!animate && (transition != null || progress != 1f || !source.sameAs(target))) {
        cancelAnimation();
        source = target;
        progress = 1f;
        rebuildShaders();
        invalidateSelf();
      }
      return;
    }
    RadioStationGradient.Palette visible = RadioStationGradient.Palette.blend(source, target, progress);
    cancelAnimation();
    source = visible;
    target = next;
    progress = 0f;
    rebuildShaders();
    if (!animate || !animatorsEnabled()) {
      source = target;
      progress = 1f;
      rebuildShaders();
      invalidateSelf();
      return;
    }
    final ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
    transition = animator;
    animator.setDuration(TRANSITION_MS);
    animator.setInterpolator(new DecelerateInterpolator());
    animator.addUpdateListener(value -> {
      if (transition != animator) return;
      progress = (Float) value.getAnimatedValue();
      invalidateSelf();
    });
    animator.addListener(new AnimatorListenerAdapter() {
      @Override public void onAnimationEnd(Animator value) {
        if (transition == animator) {
          progress = 1f;
          transition = null;
          invalidateSelf();
        }
      }
    });
    animator.start();
  }

  private static boolean animatorsEnabled() {
    try { return ValueAnimator.areAnimatorsEnabled(); }
    catch (Exception ignored) { return false; }
  }

  @Override public void draw(Canvas canvas) {
    Rect bounds = getBounds();
    if (bounds.isEmpty()) return;
    if (cachedWidth != bounds.width() || cachedHeight != bounds.height()) rebuildShaders();
    basePaint.setAlpha(alpha);
    canvas.drawRect(bounds, basePaint);
    // Draw the old opaque wash first, then reveal the new wash. Scaling both alpha values would
    // expose the base layer and create a distracting dark dip halfway through a station switch.
    sourcePaint.setAlpha(alpha);
    targetPaint.setAlpha(Math.round(alpha * progress));
    canvas.drawRect(bounds, sourcePaint);
    canvas.drawRect(bounds, targetPaint);
    fadePaint.setAlpha(alpha);
    canvas.drawRect(bounds, fadePaint);
  }

  @Override protected void onBoundsChange(Rect ignored) { rebuildShaders(); }

  private void rebuildShaders() {
    Rect bounds = getBounds();
    int width = bounds.width(), height = bounds.height();
    if (width <= 0 || height <= 0) return;
    cachedWidth = width;
    cachedHeight = height;
    sourcePaint.setShader(wash(source, bounds));
    targetPaint.setShader(wash(target, bounds));
    fadePaint.setShader(new LinearGradient(bounds.left, bounds.top, bounds.left, bounds.bottom,
        new int[] {0x12000000, 0x8f000000, 0xe8000000}, new float[] {0f, .58f, 1f}, Shader.TileMode.CLAMP));
    sourcePaint.setColorFilter(colorFilter);
    targetPaint.setColorFilter(colorFilter);
    fadePaint.setColorFilter(colorFilter);
  }

  private static Shader wash(RadioStationGradient.Palette palette, Rect bounds) {
    float radius = Math.max(bounds.width(), bounds.height()) * 1.10f;
    return new RadialGradient(bounds.left + bounds.width() * .13f, bounds.top + bounds.height() * .04f,
        radius,
        new int[] {darken(palette.first, .43f), darken(palette.second, .31f), 0xff020609},
        new float[] {0f, .48f, 1f}, Shader.TileMode.CLAMP);
  }

  private static int darken(int color, float amount) {
    return Color.rgb(Math.round(Color.red(color) * amount), Math.round(Color.green(color) * amount),
        Math.round(Color.blue(color) * amount));
  }

  @Override public void setAlpha(int value) { alpha = Math.max(0, Math.min(255, value)); invalidateSelf(); }
  @Override public void setColorFilter(ColorFilter filter) { colorFilter = filter; rebuildShaders(); invalidateSelf(); }
  @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
