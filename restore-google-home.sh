#!/bin/bash
set -euo pipefail
if [ "$#" -ne 2 ]; then
  printf 'Usage: %s /path/to/adb host:port\n' "$0" >&2
  exit 64
fi
ADB="$1"
DEVICE="$2"
if [ ! -x "$ADB" ]; then
  printf 'adb is not executable: %s\n' "$ADB" >&2
  exit 66
fi
PRODUCT="$($ADB -P 5038 -s "$DEVICE" shell getprop ro.product.name | tr -d '\r')"
if [ "$PRODUCT" != BRAVIA_VH21_JP ]; then
  printf 'Unexpected device: %s\n' "$PRODUCT" >&2
  exit 1
fi
"$ADB" -P 5038 -s "$DEVICE" shell cmd package install-existing --user 0 com.google.android.apps.tv.launcherx
"$ADB" -P 5038 -s "$DEVICE" shell pm default-state --user 0 com.google.android.apps.tv.launcherx
"$ADB" -P 5038 -s "$DEVICE" shell pm default-state --user 0 com.google.android.tungsten.setupwraith
"$ADB" -P 5038 -s "$DEVICE" shell cmd role add-role-holder --user 0 android.app.role.HOME com.google.android.apps.tv.launcherx 0
"$ADB" -P 5038 -s "$DEVICE" shell cmd package set-home-activity --user 0 com.google.android.apps.tv.launcherx/.home.HomeActivity
"$ADB" -P 5038 -s "$DEVICE" shell am start -n com.google.android.apps.tv.launcherx/.home.HomeActivity
printf 'Google TV Home restored. Glass TV photos and app are retained.\n'
