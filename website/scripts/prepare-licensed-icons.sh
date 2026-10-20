#!/usr/bin/env bash
set -euo pipefail

: "${CENTRAL_ICON_SOURCE:?Set CENTRAL_ICON_SOURCE to your licensed Central PNG export directory.}"
mkdir -p assets/ui-icons
for icon in apps image movies settings tv weather_sun wifi more_horizontal; do
  cp "$CENTRAL_ICON_SOURCE/$icon.png" "assets/ui-icons/$icon.png"
done
