# Central interface icons

The compiled Glass TV app uses the Central icon set as part of the product. The raw Central SVG and PNG icon collection is not included in this repository.

To build the interface from source, run `CENTRAL_ICONS_MODULES=/path/to/node_modules CENTRAL_ICON_OUTPUT_ROOT="$PWD/build/central-assets" node scripts/export-central-icons.mjs` from the repository root. Then run `CENTRAL_ICON_ASSETS_ROOT="$PWD/build/central-assets" ./mac/build-mac-app.sh`. The script reads `shared/icons-manifest.json`, renders each licensed React icon in raw SVG mode, and creates the required macOS and Android PNG files. `sips` is required for rasterization. Do not add the raw collection to this repository.

For Android, set `CENTRAL_ICON_OUTPUT_ROOT="$PWD"` when exporting so the files are available under `android/res/drawable` before building. The radio heart uses the installed Central round-filled variant; other radio icons use the round-outlined variant. Both packages require the appropriate license. Follow `docs/android-release.md` for signed release packaging.
