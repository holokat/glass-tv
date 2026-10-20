package local.glasstv.launcher;

/** Pure launcher-overlay visibility rule, kept independent from rendering and remote handling. */
final class TvInterfaceVisibility {
    private TvInterfaceVisibility() { }

    static boolean shouldHide(boolean showTVInterface, boolean slideshowEnabled, boolean hasActiveAmbientMedia,
            boolean movieActive, boolean dialogOpen, boolean foreground, boolean screenAwake, boolean revealed) {
        return !showTVInterface && slideshowEnabled && hasActiveAmbientMedia && !movieActive
                && !dialogOpen && foreground && screenAwake && !revealed;
    }
}
