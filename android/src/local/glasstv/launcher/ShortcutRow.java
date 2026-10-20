package local.glasstv.launcher;

/** Fractional layout for the launcher shortcut row, independent of Android view state. */
final class ShortcutRow {
  static final float START_FRACTION = .04f;
  static final String MINIMAL = "minimal";
  static final String CARDS = "cards";
  static final float MINIMAL_WIDTH = .09f;
  static final float MINIMAL_STRIDE = .11f;
  static final float CARD_WIDTH = .17f;
  static final float CARD_STRIDE = .18f;
  static final float MINIMAL_HEIGHT = .12f;
  static final float CARD_HEIGHT = .17f;
  /** The selected shortcut grows from its center, so adjacent carousel controls need this room. */
  static final float FOCUSED_SCALE = 1.06f;
  /** A shortcut view reserves its focused extent and animates from this scale within it. */
  static final float UNFOCUSED_CONTAINER_SCALE = 1f / FOCUSED_SCALE;
  static final float LABEL_SPACE = .047f;
  static final float LABEL_GAP = .012f;
  static final float LABEL_HEIGHT = .035f;
  static final float CAROUSEL_ARROW_WIDTH = .035f;
  static final float CAROUSEL_FOCUS_GAP = .001f;
  static final int MINIMAL_PAGE_SIZE = 8;
  static final int CARD_PAGE_SIZE = 5;
  // Kept for source compatibility with the existing layout checks.
  static final float BUTTON_WIDTH_FRACTION = MINIMAL_WIDTH;
  static final float BUTTON_STRIDE_FRACTION = MINIMAL_STRIDE;

  private ShortcutRow() {}

  static int packedIndex(boolean[] visible, int index) {
    if (index < 0 || index >= visible.length || !visible[index]) return -1;
    int packed = 0;
    for (int i = 0; i < index; i++) if (visible[i]) packed++;
    return packed;
  }

  static float leftFraction(boolean[] visible, int index) {
    int packed = packedIndex(visible, index);
    return packed < 0 ? Float.NaN : START_FRACTION + packed * BUTTON_STRIDE_FRACTION;
  }

  static boolean fitsInRow(int shortcutCount) {
    return fitsInPage(MINIMAL, shortcutCount);
  }

  static boolean fitsInPage(String style, int shortcutCount) {
    return fitsInPage(style, shortcutCount, 1f, LauncherLayout.DEFAULT_SAFE_AREA_PADDING, 16f / 9f);
  }

  static boolean fitsInPage(
      String style, int shortcutCount, float appIconScale, float safeAreaPadding, float aspectRatio) {
    return fitsInPage(style, shortcutCount, appIconScale, 1f, safeAreaPadding, aspectRatio);
  }

  static boolean fitsInPage(
      String style,
      int shortcutCount,
      float appIconScale,
      float appSpacingScale,
      float safeAreaPadding,
      float aspectRatio) {
    if (shortcutCount <= 0) return true;
    if (shortcutCount
        > capacityForCount(
            style, shortcutCount, appIconScale, appSpacingScale, safeAreaPadding, aspectRatio))
      return false;
    return pageWidth(style, shortcutCount, appIconScale, appSpacingScale, safeAreaPadding, aspectRatio)
        <= availableWidth(style, safeAreaPadding, aspectRatio);
  }

  static int pageSize(String style) { return CARDS.equals(style) ? CARD_PAGE_SIZE : MINIMAL_PAGE_SIZE; }

  static float width(String style) { return CARDS.equals(style) ? CARD_WIDTH : MINIMAL_WIDTH; }

  static float width(String style, float appIconScale) { return width(style) * scale(appIconScale); }

  /** Fraction extending past either horizontal edge while the selected shortcut is enlarged. */
  static float focusedOverflow(String style, float appIconScale) {
    return focusedOverflow(style, appIconScale, 1f);
  }

  /**
   * Restricts focus growth to the actual gap so adjacent shortcuts never overlap at zero spacing.
   */
  static float focusedOverflow(String style, float appIconScale, float appSpacingScale) {
    float visualSize = width(style, appIconScale);
    return visualSize * (focusedScale(style, appIconScale, appSpacingScale) - 1f) / 2f;
  }

  static float focusedEdgeOverflow(float visualSize) {
    return visualSize * (FOCUSED_SCALE - 1f) / 2f;
  }

  static float focusedContainerSize(float visualSize) { return visualSize * FOCUSED_SCALE; }

  static float focusedContainerSize(float visualSize, float focusedScale) {
    return visualSize * focusedScale;
  }

  static float unfocusedContainerScale(float focusedScale) { return 1f / focusedScale; }

  static float focusedScale(String style, float appIconScale, float appSpacingScale) {
    float visualSize = width(style, appIconScale);
    if (visualSize <= 0f) return 1f;
    return Math.min(FOCUSED_SCALE, 1f + 2f * gap(style, appIconScale, appSpacingScale) / visualSize);
  }

  /** Space on either side of a paged row for an arrow and the selected tile's expansion. */
  static float carouselGutter(String style, float appIconScale) {
    return carouselGutter(style, appIconScale, 1f);
  }

  static float carouselGutter(String style, float appIconScale, float appSpacingScale) {
    return CAROUSEL_ARROW_WIDTH
        + focusedOverflow(style, appIconScale, appSpacingScale)
        + CAROUSEL_FOCUS_GAP;
  }

  /** Page zero has no previous arrow, so its first tile starts directly at the safe edge. */
  static float leadingCarouselGutter(
      int page, String style, float appIconScale, float appSpacingScale) {
    return page > 0 ? carouselGutter(style, appIconScale, appSpacingScale) : 0f;
  }

  /** A left-pivoted first tile may grow only into its following gap. */
  static float leadingFocusedScale(String style, float appIconScale, float appSpacingScale) {
    float visualSize = width(style, appIconScale);
    if (visualSize <= 0f) return 1f;
    return Math.min(
        focusedScale(style, appIconScale, appSpacingScale),
        1f + gap(style, appIconScale, appSpacingScale) / visualSize);
  }

  static float stride(String style) { return CARDS.equals(style) ? CARD_STRIDE : MINIMAL_STRIDE; }

  static float stride(String style, float appIconScale) { return stride(style, appIconScale, 1f); }

  /** Tile dimensions remain unchanged; spacing only changes the gap between their edges. */
  static float stride(String style, float appIconScale, float appSpacingScale) {
    return width(style, appIconScale) + gap(style, appIconScale, appSpacingScale);
  }

  static float gap(String style, float appIconScale) {
    return gap(style, appIconScale, 1f);
  }

  static float gap(String style, float appIconScale, float appSpacingScale) {
    return (CARDS.equals(style) ? .01f : .02f)
        * scale(appIconScale)
        * spacingScale(appSpacingScale);
  }

  static float height(String style) { return CARDS.equals(style) ? CARD_HEIGHT : MINIMAL_HEIGHT; }

  static float height(String style, float appIconScale) { return height(style) * scale(appIconScale); }

  static float heightWithLabel(String style, boolean labels) {
    return heightWithLabel(style, labels, 1f);
  }

  static float heightWithLabel(String style, boolean labels, float appIconScale) {
    return (height(style) + (labels ? LABEL_SPACE : 0f)) * scale(appIconScale);
  }

  static float pageWidth(String style, int shortcutCount) {
    return pageWidth(
        style, shortcutCount, 1f, 1f, LauncherLayout.DEFAULT_SAFE_AREA_PADDING, 16f / 9f);
  }

  static float pageWidth(
      String style, int shortcutCount, float appIconScale, float safeAreaPadding, float aspectRatio) {
    return pageWidth(style, shortcutCount, appIconScale, 1f, safeAreaPadding, aspectRatio);
  }

  static float pageWidth(
      String style,
      int shortcutCount,
      float appIconScale,
      float appSpacingScale,
      float safeAreaPadding,
      float aspectRatio) {
    int count =
        Math.min(
            Math.max(1, shortcutCount),
            capacityForCount(
                style, shortcutCount, appIconScale, appSpacingScale, safeAreaPadding, aspectRatio));
    return rowWidth(style, count, appIconScale, appSpacingScale);
  }

  /** Width of a specific shortcut row without applying pagination capacity. */
  static float rowWidth(String style, int shortcutCount, float appIconScale, float appSpacingScale) {
    int count = Math.max(1, shortcutCount);
    return count * width(style, appIconScale)
        + (count - 1) * gap(style, appIconScale, appSpacingScale);
  }

  /**
   * A paged row owns the complete safe-area viewport so the following card can be clipped at its
   * edge. A one-page row preserves its compact, positionable width.
   */
  static float rowWidth(
      String style,
      int shortcutCount,
      float appIconScale,
      float appSpacingScale,
      float safeAreaPadding,
      float aspectRatio) {
    return isPaged(style, shortcutCount, appIconScale, appSpacingScale, safeAreaPadding, aspectRatio)
        ? safeWidth(safeAreaPadding, aspectRatio)
        : pageWidth(style, shortcutCount, appIconScale, appSpacingScale, safeAreaPadding, aspectRatio);
  }

  /** The cards and arrows retain their historical footprint within the paged viewport. */
  static float coreRowWidth(
      String style,
      int shortcutCount,
      float appIconScale,
      float appSpacingScale,
      float safeAreaPadding,
      float aspectRatio) {
    return coreRowWidth(
        1,
        style,
        shortcutCount,
        appIconScale,
        appSpacingScale,
        safeAreaPadding,
        aspectRatio);
  }

  static float coreRowWidth(
      int page,
      String style,
      int shortcutCount,
      float appIconScale,
      float appSpacingScale,
      float safeAreaPadding,
      float aspectRatio) {
    boolean paged =
        isPaged(style, shortcutCount, appIconScale, appSpacingScale, safeAreaPadding, aspectRatio);
    return pageWidth(style, shortcutCount, appIconScale, appSpacingScale, safeAreaPadding, aspectRatio)
        + (paged
            ? leadingCarouselGutter(page, style, appIconScale, appSpacingScale)
                + carouselGutter(style, appIconScale, appSpacingScale)
            : 0f);
  }

  /** Leading-edge offset of the first next-page tile, measured from the card area. */
  static float nextPagePeekOffset(
      String style,
      int shortcutCount,
      float appIconScale,
      float appSpacingScale,
      float safeAreaPadding,
      float aspectRatio) {
    return capacityForCount(
            style, shortcutCount, appIconScale, appSpacingScale, safeAreaPadding, aspectRatio)
        * stride(style, appIconScale, appSpacingScale);
  }

  static float carouselWidth(
      String style, int shortcutCount, float appIconScale, float safeAreaPadding, float aspectRatio) {
    return carouselWidth(style, shortcutCount, appIconScale, 1f, safeAreaPadding, aspectRatio);
  }

  static float carouselWidth(
      String style,
      int shortcutCount,
      float appIconScale,
      float appSpacingScale,
      float safeAreaPadding,
      float aspectRatio) {
    return pageWidth(style, shortcutCount, appIconScale, appSpacingScale, safeAreaPadding, aspectRatio)
        + 2f * carouselGutter(style, appIconScale, appSpacingScale);
  }

  static int capacity(String style, float appIconScale, float safeAreaPadding, float aspectRatio) {
    return capacity(style, appIconScale, 1f, safeAreaPadding, aspectRatio);
  }

  static int capacity(
      String style,
      float appIconScale,
      float appSpacingScale,
      float safeAreaPadding,
      float aspectRatio) {
    return capacity(style, appIconScale, appSpacingScale, safeAreaPadding, aspectRatio, false);
  }

  /**
   * Paged rows reserve identical arrow gutters before finding their card count. A one-page row
   * retains its historical capacity and position because it has no arrows to draw.
   */
  static int capacityForCount(
      String style,
      int shortcutCount,
      float appIconScale,
      float safeAreaPadding,
      float aspectRatio) {
    return capacityForCount(style, shortcutCount, appIconScale, 1f, safeAreaPadding, aspectRatio);
  }

  static int capacityForCount(
      String style,
      int shortcutCount,
      float appIconScale,
      float appSpacingScale,
      float safeAreaPadding,
      float aspectRatio) {
    int unpaged = capacity(style, appIconScale, appSpacingScale, safeAreaPadding, aspectRatio, false);
    return shortcutCount > unpaged
        ? capacity(style, appIconScale, appSpacingScale, safeAreaPadding, aspectRatio, true)
        : unpaged;
  }

  static boolean isPaged(
      String style,
      int shortcutCount,
      float appIconScale,
      float appSpacingScale,
      float safeAreaPadding,
      float aspectRatio) {
    return shortcutCount
        > capacity(style, appIconScale, appSpacingScale, safeAreaPadding, aspectRatio, false);
  }

  private static int capacity(
      String style,
      float appIconScale,
      float appSpacingScale,
      float safeAreaPadding,
      float aspectRatio,
      boolean reserveCarouselGutters) {
    // Keep the specified epsilon after the division in double precision. Float rounding at the
    // historic .89/.18 card boundary otherwise turns the five-card default into four cards.
    double available = availableWidth(style, safeAreaPadding, aspectRatio);
    if (reserveCarouselGutters)
      available -= 2d * carouselGutter(style, appIconScale, appSpacingScale);
    return Math.max(
        1,
        (int)
            Math.floor(
                (available + (double) gap(style, appIconScale, appSpacingScale))
                        / (double) stride(style, appIconScale, appSpacingScale)
                    + .000001d));
  }

  static float labelGap(float appIconScale) { return LABEL_GAP * scale(appIconScale); }

  static float labelHeight(float appIconScale) { return LABEL_HEIGHT * scale(appIconScale); }

  private static float availableWidth(String style, float safeAreaPadding, float aspectRatio) {
    return Math.min(CARDS.equals(style) ? .89f : .86f, safeWidth(safeAreaPadding, aspectRatio));
  }

  private static float safeWidth(float safeAreaPadding, float aspectRatio) {
    float ratio = Float.isFinite(aspectRatio) && aspectRatio > 0f ? aspectRatio : 16f / 9f;
    float padding = Math.max(0f, Math.min(.10f, safeAreaPadding));
    return 1f - 2f * padding * Math.min(1f, 1f / ratio);
  }

  private static float scale(float value) { return Math.max(.5f, Math.min(1.5f, value)); }

  private static float spacingScale(float value) { return Math.max(0f, Math.min(3f, value)); }
}
