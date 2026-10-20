#!/bin/zsh
set -euo pipefail
script_dir="${0:A:h}"
master="$script_dir/shared/app-icon-master.png"
iconset="$script_dir/mac/Resources/AppIcon.iconset"
mkdir -p "$iconset"
# Always resample directly from the original, never from a smaller rendition.
for size in 16 32 128 256 512; do
  sips -z "$size" "$size" "$master" --out "$iconset/icon_${size}x${size}.png" >/dev/null
  retina=$((size * 2))
  sips -z "$retina" "$retina" "$master" --out "$iconset/icon_${size}x${size}@2x.png" >/dev/null
done
iconutil -c icns "$iconset" -o "$script_dir/mac/Resources/AppIcon.icns"
sips -z 512 512 "$master" --out "$script_dir/android/res/drawable/app_icon.png" >/dev/null
for entry in ldpi:36 mdpi:48 hdpi:72 xhdpi:96 xxhdpi:144 xxxhdpi:192; do
  density="${entry%%:*}"
  size="${entry##*:}"
  destination="$script_dir/android/res/drawable-$density"
  mkdir -p "$destination"
  sips -z "$size" "$size" "$master" --out "$destination/app_icon.png" >/dev/null
done
