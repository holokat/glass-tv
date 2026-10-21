package local.glasstv.launcher;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.PixelCopy;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.text.Editable;
import android.text.TextWatcher;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * A transient, native Spotlight-style radio search surface. It deliberately owns neither playback
 * nor saved state: callers receive one eligible Radio Browser station and decide what to do with it.
 */
final class RadioSearchOverlay {
  interface Callback {
    void onSelect(RadioStationPolicy.Station station);
  }

  private static final int SEARCH_DEBOUNCE_MS = 250;
  private static final int MAX_RESULTS = 12;
  private static final int BLUR_MAX_EDGE = 480;

  private final Context context;
  private final FrameLayout parent;
  private final RadioDirectory directory;
  private final Callback callback;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ThreadPoolExecutor worker = new ThreadPoolExecutor(
      1, 1, 15, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(2),
      new ThreadPoolExecutor.DiscardOldestPolicy());
  private final FrameLayout overlay;
  private final ImageView blurredBackdrop;
  private final EditText query;
  private final ImageView close;
  private final LinearLayout resultRows;
  private final TextView state;
  private Runnable scheduledSearch;
  private int searchGeneration;
  private int snapshotGeneration;
  private boolean keyboardRequested;
  private boolean destroyed;
  private Bitmap backdropBitmap;

  RadioSearchOverlay(Context context, FrameLayout parent, RadioDirectory directory, Callback onStationSelected) {
    this.context = context;
    this.parent = parent;
    this.directory = directory;
    this.callback = onStationSelected;

    overlay = new FrameLayout(context);
    overlay.setVisibility(View.GONE);
    overlay.setFocusable(true);
    blurredBackdrop = new ImageView(context);
    blurredBackdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
    blurredBackdrop.setAlpha(1f);
    overlay.addView(blurredBackdrop, new FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    View contrastScrim = new View(context);
    contrastScrim.setBackgroundColor(0x1f000000);
    overlay.addView(contrastScrim, new FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

    LinearLayout panel = new LinearLayout(context);
    panel.setOrientation(LinearLayout.VERTICAL);
    FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(dp(620),
        ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
    panelParams.topMargin = dp(82);
    overlay.addView(panel, panelParams);

    FrameLayout inputShell = new FrameLayout(context);
    inputShell.setBackground(new WhiteSurface(dp(24), 0x42ffffff));
    panel.addView(inputShell, new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, dp(58)));
    query = new EditText(context);
    query.setSingleLine(true);
    query.setTextSize(18);
    query.setTextColor(Color.WHITE);
    query.setHintTextColor(0xbfffffff);
    query.setHint("Search stations, genres, or moods");
    query.setBackgroundColor(Color.TRANSPARENT);
    query.setHighlightColor(0x40ffffff);
    if (Build.VERSION.SDK_INT >= 29) {
      android.graphics.drawable.GradientDrawable cursor = new android.graphics.drawable.GradientDrawable();
      cursor.setColor(Color.WHITE);
      cursor.setSize(dp(2), dp(24));
      query.setTextCursorDrawable(cursor);
    }
    query.setPadding(dp(19), 0, dp(58), 0);
    query.setContentDescription("Search stations, genres, or moods");
    setIcon(query, "search", 22);
    inputShell.addView(query, new FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    close = new ImageView(context);
    close.setContentDescription("Close search");
    close.setPadding(dp(12), dp(12), dp(12), dp(12));
    close.setFocusable(true);
    close.setBackground(new WhiteSurface(dp(18), Color.TRANSPARENT));
    setImageIcon(close, "close");
    close.setOnClickListener(view -> dismissImmediately());
    FrameLayout.LayoutParams closeParams = new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.RIGHT | Gravity.CENTER_VERTICAL);
    closeParams.rightMargin = dp(5);
    inputShell.addView(close, closeParams);

    ScrollView scroll = new ScrollView(context);
    scroll.setFillViewport(false);
    scroll.setClipToPadding(false);
    LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, dp(350));
    scrollParams.topMargin = dp(12);
    panel.addView(scroll, scrollParams);
    FrameLayout resultsHost = new FrameLayout(context);
    scroll.addView(resultsHost, new ScrollView.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    resultRows = new LinearLayout(context);
    resultRows.setOrientation(LinearLayout.VERTICAL);
    resultsHost.addView(resultRows, new FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    state = new TextView(context);
    state.setTextColor(0xd9ffffff);
    state.setTextSize(15);
    state.setGravity(Gravity.CENTER);
    state.setIncludeFontPadding(false);
    state.setVisibility(View.GONE);
    resultsHost.addView(state, new FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, dp(76)));

    query.addTextChangedListener(new TextWatcher() {
      @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
      @Override public void onTextChanged(CharSequence value, int start, int before, int count) { scheduleSearch(value.toString()); }
      @Override public void afterTextChanged(Editable value) { }
    });
    query.setOnFocusChangeListener((view, focused) -> { if (!focused) keyboardRequested = false; });
    query.setOnKeyListener((view, key, event) -> {
      if (event.getAction() == KeyEvent.ACTION_DOWN && key == KeyEvent.KEYCODE_DPAD_DOWN
          && resultRows.getChildCount() > 0) {
        resultRows.getChildAt(0).requestFocus();
        return true;
      }
      return false;
    });
    parent.addView(overlay, new FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
  }

  void show() {
    if (destroyed) return;
    if (isOpen()) {
      query.requestFocus();
      showKeyboard();
      return;
    }
    query.setText("");
    resultRows.removeAllViews();
    state.setVisibility(View.GONE);
    captureBackdrop(++snapshotGeneration);
  }

  boolean isOpen() { return overlay.getVisibility() == View.VISIBLE; }

  /** The first Back press dismisses the native keyboard when the input owns focus; the next closes. */
  void close() {
    if (!isOpen()) return;
    if (query.hasFocus() && keyboardRequested) {
      hideKeyboard();
      query.clearFocus();
      overlay.requestFocus();
      keyboardRequested = false;
      return;
    }
    dismiss();
  }

  void destroy() {
    if (destroyed) return;
    destroyed = true;
    dismissImmediately();
    worker.shutdownNow();
    parent.removeView(overlay);
  }

  /** Closes the surface immediately for Radio exit and selection, without a keyboard-only pass. */
  void dismissImmediately() { dismiss(); }

  private void dismiss() {
    ++searchGeneration;
    ++snapshotGeneration;
    if (scheduledSearch != null) main.removeCallbacks(scheduledSearch);
    scheduledSearch = null;
    hideKeyboard();
    keyboardRequested = false;
    overlay.setVisibility(View.GONE);
    resultRows.removeAllViews();
    state.setVisibility(View.GONE);
    blurredBackdrop.setImageDrawable(null);
    if (backdropBitmap != null) {
      backdropBitmap.recycle();
      backdropBitmap = null;
    }
  }

  private void scheduleSearch(String raw) {
    if (!isOpen() || destroyed) return;
    final String value = raw.trim();
    final int generation = ++searchGeneration;
    if (scheduledSearch != null) main.removeCallbacks(scheduledSearch);
    if (value.length() == 0) {
      resultRows.removeAllViews();
      state.setVisibility(View.GONE);
      return;
    }
    resultRows.removeAllViews();
    showState("Searching…");
    scheduledSearch = () -> executeSearch(value, generation);
    main.postDelayed(scheduledSearch, SEARCH_DEBOUNCE_MS);
  }

  private void executeSearch(final String value, final int generation) {
    if (destroyed || generation != searchGeneration) return;
    try {
      worker.getQueue().clear();
      worker.execute(() -> {
        try {
          if (destroyed || generation != searchGeneration) return;
          List<RadioStationPolicy.Station> matches = directory.searchCatalog(value);
          main.post(() -> {
            if (currentSearch(generation, value)) showResults(matches);
          });
        } catch (Exception failure) {
          main.post(() -> {
            if (currentSearch(generation, value)) showState("Search unavailable");
          });
        }
      });
    } catch (RuntimeException ignored) {
      if (currentSearch(generation, value)) showState("Searching…");
    }
  }

  private boolean currentSearch(int generation, String value) {
    return !destroyed && isOpen() && generation == searchGeneration && value.equals(query.getText().toString().trim());
  }

  private void showResults(List<RadioStationPolicy.Station> values) {
    resultRows.removeAllViews();
    state.setVisibility(View.GONE);
    if (values.isEmpty()) {
      showState("No matching stations");
      return;
    }
    for (int index = 0; index < values.size(); index++) addResult(values.get(index), index == 0);
  }

  private void addResult(final RadioStationPolicy.Station station, boolean first) {
    LinearLayout row = new LinearLayout(context);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setFocusable(true);
    row.setFocusableInTouchMode(true);
    row.setContentDescription("Play " + station.name);
    WhiteSurface surface = new WhiteSurface(dp(17), 0x30ffffff);
    row.setBackground(surface);
    row.setOnFocusChangeListener((view, focused) -> surface.setFocused(focused));
    row.setOnClickListener(view -> {
      dismissImmediately();
      if (callback != null) callback.onSelect(station);
    });
    ImageView logo = new ImageView(context);
    logo.setScaleType(ImageView.ScaleType.CENTER_CROP);
    logo.setBackground(new WhiteSurface(dp(10), 0x40ffffff));
    logo.setImageDrawable(RadioStationGradient.forStation(station, dp(10)));
    logo.setClipToOutline(true);
    logo.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
    logo.setAlpha(1f);
    LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(dp(58), dp(58));
    logoParams.leftMargin = dp(8);
    row.addView(logo, logoParams);
    TextView name = new TextView(context);
    name.setText(station.name);
    name.setTextColor(Color.WHITE);
    name.setTextSize(16);
    name.setSingleLine(true);
    name.setEllipsize(android.text.TextUtils.TruncateAt.END);
    name.setIncludeFontPadding(false);
    LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(0,
        ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    nameParams.leftMargin = dp(16);
    nameParams.rightMargin = dp(18);
    row.addView(name, nameParams);
    LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, dp(74));
    if (!first) rowParams.topMargin = dp(8);
    resultRows.addView(row, rowParams);
  }

  private void showState(String value) {
    resultRows.removeAllViews();
    state.setText(value);
    state.setVisibility(View.VISIBLE);
  }

  private void captureBackdrop(final int generation) {
    clearBackdrop();
    Activity activity = activityContext(context);
    if (activity == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O || parent.getWidth() <= 0 || parent.getHeight() <= 0) {
      captureParentBackdrop(generation);
      return;
    }
    Window window = activity.getWindow();
    int width = Math.min(BLUR_MAX_EDGE, parent.getWidth());
    int height = Math.max(1, Math.round(parent.getHeight() * (width / (float) parent.getWidth())));
    Bitmap sampled = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    PixelCopy.request(window, sampled, result -> {
      if (result == PixelCopy.SUCCESS) {
        blurOffUi(sampled, generation);
        reveal(generation);
      }
      else {
        sampled.recycle();
        captureParentBackdrop(generation);
      }
    }, main);
  }

  private void captureParentBackdrop(int generation) {
    if (destroyed || generation != snapshotGeneration || parent.getWidth() <= 0 || parent.getHeight() <= 0) return;
    int width = Math.min(BLUR_MAX_EDGE, Math.max(1, parent.getWidth()));
    int height = Math.max(1, Math.round(parent.getHeight() * (width / (float) parent.getWidth())));
    Bitmap snapshot = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(snapshot);
    canvas.scale(width / (float) parent.getWidth(), height / (float) parent.getHeight());
    parent.draw(canvas);
    blurOffUi(snapshot, generation);
    reveal(generation);
  }

  private void blurOffUi(final Bitmap source, final int generation) {
    try {
      worker.execute(() -> {
        Bitmap blurred = null;
        try { blurred = blur(source); }
        finally { source.recycle(); }
        final Bitmap value = blurred;
        main.post(() -> {
          if (destroyed || generation != snapshotGeneration) {
            if (value != null) value.recycle();
            return;
          }
          clearBackdrop();
          backdropBitmap = value;
          blurredBackdrop.setImageBitmap(value);
        });
      });
    } catch (RuntimeException rejected) {
      source.recycle();
    }
  }

  /** Revealing only after capture prevents the search input and IME from entering the snapshot. */
  private void reveal(int generation) {
    if (destroyed || generation != snapshotGeneration) return;
    overlay.setVisibility(View.VISIBLE);
    query.requestFocus();
    keyboardRequested = true;
    showKeyboard();
  }

  private Bitmap blur(Bitmap source) {
    int maximum = Math.max(source.getWidth(), source.getHeight());
    float scale = maximum <= BLUR_MAX_EDGE ? 1f : (float) BLUR_MAX_EDGE / maximum;
    int width = Math.max(1, Math.round(source.getWidth() * scale));
    int height = Math.max(1, Math.round(source.getHeight() * scale));
    // createScaledBitmap may return source unchanged at small sizes; this bitmap must remain
    // independently owned because the full PixelCopy image is released after this task.
    Bitmap reduced = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    new Canvas(reduced).drawBitmap(source, null, new Rect(0, 0, width, height), null);
    int[] pixels = new int[width * height];
    int[] scratch = new int[pixels.length];
    reduced.getPixels(pixels, 0, width, 0, 0, width, height);
    blurHorizontal(pixels, scratch, width, height, 7);
    blurVertical(scratch, pixels, width, height, 7);
    reduced.setPixels(pixels, 0, width, 0, 0, width, height);
    return reduced;
  }

  private static void blurHorizontal(int[] input, int[] output, int width, int height, int radius) {
    for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
      int count = 0, a = 0, r = 0, g = 0, b = 0;
      for (int offset = -radius; offset <= radius; offset++) {
        int color = input[y * width + Math.max(0, Math.min(width - 1, x + offset))];
        a += Color.alpha(color); r += Color.red(color); g += Color.green(color); b += Color.blue(color); count++;
      }
      output[y * width + x] = Color.argb(a / count, r / count, g / count, b / count);
    }
  }

  private static void blurVertical(int[] input, int[] output, int width, int height, int radius) {
    for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
      int count = 0, a = 0, r = 0, g = 0, b = 0;
      for (int offset = -radius; offset <= radius; offset++) {
        int color = input[Math.max(0, Math.min(height - 1, y + offset)) * width + x];
        a += Color.alpha(color); r += Color.red(color); g += Color.green(color); b += Color.blue(color); count++;
      }
      output[y * width + x] = Color.argb(a / count, r / count, g / count, b / count);
    }
  }

  private void clearBackdrop() {
    blurredBackdrop.setImageDrawable(null);
    if (backdropBitmap != null) {
      backdropBitmap.recycle();
      backdropBitmap = null;
    }
  }

  private void showKeyboard() {
    query.post(() -> ((InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(query,
        InputMethodManager.SHOW_IMPLICIT));
  }

  private void hideKeyboard() {
    ((InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(
        query.getWindowToken(), 0);
  }

  private static Activity activityContext(Context value) {
    while (value instanceof ContextWrapper) {
      if (value instanceof Activity) return (Activity) value;
      Context next = ((ContextWrapper) value).getBaseContext();
      if (next == value) break;
      value = next;
    }
    return value instanceof Activity ? (Activity) value : null;
  }

  private void setIcon(TextView view, String name, int sizeDp) {
    int id = DrawableResources.id(context, name);
    if (id == 0) return;
    Drawable icon = context.getResources().getDrawable(id).mutate();
    icon.setColorFilter(Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN);
    int size = dp(sizeDp);
    icon.setBounds(0, 0, size, size);
    view.setCompoundDrawables(icon, null, null, null);
    view.setCompoundDrawablePadding(dp(10));
  }

  private void setImageIcon(ImageView view, String name) {
    int id = DrawableResources.id(context, name);
    if (id == 0) return;
    view.setImageResource(id);
    view.setColorFilter(Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN);
    view.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
  }

  private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }

  private static final class WhiteSurface extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float radius;
    private final int fill;
    private boolean focused;

    WhiteSurface(float radius, int fill) { this.radius = radius; this.fill = fill; }
    void setFocused(boolean value) { focused = value; invalidateSelf(); }
    @Override public void draw(Canvas canvas) {
      Rect bounds = getBounds();
      paint.setStyle(Paint.Style.FILL);
      paint.setColor(fill);
      canvas.drawRoundRect(new RectF(bounds), radius, radius, paint);
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeWidth(focused ? 2.5f : 1f);
      paint.setColor(focused ? Color.WHITE : 0x55ffffff);
      canvas.drawRoundRect(new RectF(bounds.left + .5f, bounds.top + .5f, bounds.right - .5f, bounds.bottom - .5f), radius, radius, paint);
    }
    @Override public void setAlpha(int alpha) { }
    @Override public void setColorFilter(android.graphics.ColorFilter filter) { }
    @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
  }
}
