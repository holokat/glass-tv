#!/bin/zsh
set -euo pipefail

script_dir="${0:A:h}"
source_root="${script_dir:h}"
output_dir="${GLASS_TV_OUTPUT_DIR:-${source_root}/build}"
app="${output_dir}/Glass TV.app"
scratch="$(mktemp -d "${TMPDIR:-/tmp}/glass-tv-build.XXXXXX")"
icon_assets_root="${CENTRAL_ICON_ASSETS_ROOT:-${source_root}}"

mkdir -p "$app/Contents/MacOS" "$app/Contents/Resources"

missing_icons=()
while IFS= read -r icon; do
  [[ -f "$icon_assets_root/mac/Resources/icons/$icon.png" ]] || missing_icons+=("$icon")
done < <(node -e 'const manifest = require(process.argv[1]); Object.keys(manifest.icons).forEach((icon) => console.log(icon))' "$source_root/shared/icons-manifest.json")
if (( ${#missing_icons[@]} )); then
  print -u2 "Missing Central icon PNGs: ${(j:, :)missing_icons}"
  print -u2 "Run scripts/export-central-icons.mjs with CENTRAL_ICONS_MODULES set to a licensed node_modules directory, then set CENTRAL_ICON_ASSETS_ROOT to its output directory."
  exit 1
fi

xcrun swiftc -swift-version 5 -target arm64-apple-macosx13.0 -O \
  -framework SwiftUI -framework AppKit -framework AVFoundation -framework ImageIO \
  -framework CryptoKit -framework Security -framework Network -framework ServiceManagement \
  "$script_dir"/*.swift -o "$scratch/Glass TV"

cp "$scratch/Glass TV" "$app/Contents/MacOS/Glass TV"
cp "$script_dir/Info.plist" "$app/Contents/Info.plist"

[[ -f "$source_root/shared/app-shortcuts.json" ]] && cp "$source_root/shared/app-shortcuts.json" "$app/Contents/Resources/app-shortcuts.json"
[[ -d "$script_dir/Resources/navigation-sounds" ]] && cp -R "$script_dir/Resources/navigation-sounds" "$app/Contents/Resources/"

for resource in wallpaper.jpg sony-preview.png AppIcon.icns; do
  [[ -f "$script_dir/Resources/$resource" ]] && cp "$script_dir/Resources/$resource" "$app/Contents/Resources/$resource"
done

for directory in layout-frames weather-artwork Tools; do
  [[ -d "$script_dir/Resources/$directory" ]] && cp -R "$script_dir/Resources/$directory" "$app/Contents/Resources/"
done
cp -R "$icon_assets_root/mac/Resources/icons" "$app/Contents/Resources/"

chmod +x "$app/Contents/MacOS/Glass TV"

if [[ -n "${GLASS_TV_SIGNING_IDENTITY:-}" ]]; then
  codesign --force --options runtime --timestamp --sign "$GLASS_TV_SIGNING_IDENTITY" "$app"
else
  codesign --force --sign - "$app"
fi

echo "Built $app"
