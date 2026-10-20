#!/bin/bash
set -euo pipefail
SOURCE="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_HOME:?Set ANDROID_HOME to an Android SDK installation}"
export JAVA_HOME="${JAVA_HOME:?Set JAVA_HOME to a JDK 17 installation}"
export PATH="$JAVA_HOME/bin:$PATH"
TOOLS="$SDK/build-tools/36.0.0"
PLATFORM="$SDK/platforms/android-36/android.jar"
BUILD="${GLASS_TV_ANDROID_BUILD_DIR:-$SOURCE/build/android}"
KEYS="${GLASS_TV_ANDROID_SIGNING_DIR:-$BUILD/signing}"
SHORTCUT_CATALOG="$SOURCE/shared/app-shortcuts.json"
mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/dex" "$KEYS"
chmod 700 "$KEYS"
if [ ! -f "$SHORTCUT_CATALOG" ]; then
  echo "Missing shared app shortcut catalog: $SHORTCUT_CATALOG" >&2
  exit 1
fi
if [ ! -f "$KEYS/launcher.p12" ]; then
  umask 077
  openssl rand -hex 24 > "$KEYS/password"
  keytool -genkeypair -keystore "$KEYS/launcher.p12" -storetype PKCS12 -alias glass-tv -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=Glass TV local personal build' -storepass:file "$KEYS/password" -keypass:file "$KEYS/password" >/dev/null 2>&1
fi
"$TOOLS/aapt2" compile --dir "$SOURCE/android/res" -o "$BUILD/resources.zip"
"$TOOLS/aapt2" link -I "$PLATFORM" --manifest "$SOURCE/android/AndroidManifest.xml" --java "$BUILD/gen" -A "$SOURCE/android/assets" --min-sdk-version 26 --target-sdk-version 31 -o "$BUILD/base.apk" "$BUILD/resources.zip"
find "$SOURCE/android/src" "$BUILD/gen" -name '*.java' | sed 's/.*/"&"/' > "$BUILD/sources.txt"
javac -encoding UTF-8 --release 8 -classpath "$PLATFORM" -d "$BUILD/classes" @"$BUILD/sources.txt"
jar cf "$BUILD/classes.jar" -C "$BUILD/classes" .
"$TOOLS/d8" --min-api 26 --lib "$PLATFORM" --output "$BUILD/dex" "$BUILD/classes.jar"
cp "$BUILD/base.apk" "$BUILD/with-dex.apk"
(cd "$BUILD/dex" && zip -q -j "$BUILD/with-dex.apk" classes*.dex)
"$TOOLS/zipalign" -f 4 "$BUILD/with-dex.apk" "$BUILD/aligned.apk"
OUTPUT="${GLASS_TV_ANDROID_OUTPUT:-$BUILD/Glass TV.apk}"
"$TOOLS/apksigner" sign --ks "$KEYS/launcher.p12" --ks-key-alias glass-tv --ks-pass "file:$KEYS/password" --out "$OUTPUT" "$BUILD/aligned.apk"
"$TOOLS/apksigner" verify "$OUTPUT"
printf 'Built and verified %s\n' "$OUTPUT"
