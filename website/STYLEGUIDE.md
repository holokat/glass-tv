# Glass TV website

## Direction

The release page is a dark, low-density product page led by a working reconstruction of the Mac companion's Layout screen. It uses the same destinations, sidebar hierarchy, charcoal surfaces, connected control column, TV preview, shortcut styles, and generated weather artwork recorded in the source project. The hero preview follows the split hierarchy studied in [Supahero](https://supahero.io/); [Dark.design](https://www.dark.design/) informed surface contrast and restrained accent use; [60fps](https://60fps.design/) informed short control transitions. It retains system typography from the actual app. Fontastic was unavailable to the browsing tool, so no external typeface is introduced.

The generated LG-style frame is an illustrative source asset, not a statement of device compatibility. The small London and Tokyo weather readings are mock data inside the one clearly labeled interactive demo, as authorized for the release site. Weather imagery is labeled AI-generated artwork.

Shader Effects was reviewed but is not used. The complete UI works with CSS and static assets, and adding the unapproved 2.5 MB package would not support this restrained interface.

## Tokens and components

- Background `#090b0c`, panels `#15191b`, primary text `#f4f6f5`, secondary text `#9fa8a5`, single accent `#c6e6d7`.
- System font stack: `-apple-system, BlinkMacSystemFont, "SF Pro Display", "Helvetica Neue", sans-serif`; headings use tight tracking and sentence case.
- Release page width is 1480px with 20–58px insets. It becomes one column below 900px and uses a 95px app sidebar below 600px.
- Borders and surface contrast establish groups. No shadows. Buttons have 46px release targets and 0.96 press scale; demo controls use concise 160–280ms transitions.
- The Mac window is the canonical interactive demo. Its sidebar uses keyboard-operable tabs; layout sliders, snapping, Wi-Fi visibility, reset, shortcut style, and TV update feedback change the preview. App visibility, weather style, weather visibility, forecast visibility, locations, and the draggable layout samples also work locally.
- Section reveals use opacity and a 16px rise. `prefers-reduced-motion` shows content immediately and removes movement; no-JavaScript content remains visible.

## Assets

Only generated artwork, the project app icon, and supplied service-identification marks are stored as public files. The locally deployed build also uses eight required Central PNG exports for the app reconstruction. `assets/ui-icons/` is ignored by Git so the public source never contains a loose Central Icons collection; populate it from an owned licensed native export with `scripts/prepare-licensed-icons.sh` and `CENTRAL_ICON_SOURCE`. Provider and weather provenance stays in the source project at `shared/brands/README.md` and `mac/Resources/weather-artwork/provenance.json`.

Release links are centralized in `app.js`: `https://github.com/holokat/glass-tv` and its `Glass-TV.dmg` release path. The only availability message is “TV app coming separately.”
