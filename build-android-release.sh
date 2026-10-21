#!/usr/bin/env bash
# Builds the signed Glass TV 1.0.0 Play artifacts without changing the local-development build.
set -euo pipefail
umask 077

SOURCE="$(cd "$(dirname "$0")" && pwd)"
TASK="$(cd "$SOURCE/.." && pwd)"

readonly VERSION_NAME="1.0.0"
readonly VERSION_CODE="1"
readonly MIN_SDK="26"
readonly TARGET_SDK="34"
readonly COMPILE_SDK="36"
readonly R_NAMESPACE="local.glasstv.launcher"
readonly BUILD_TOOLS_VERSION="36.0.0"
readonly BUNDLETOOL_VERSION="1.18.3"
readonly BUNDLETOOL_SHA256="a099cfa1543f55593bc2ed16a70a7c67fe54b1747bb7301f37fdfd6d91028e29"
readonly MAVEN_AAPT2_VERSION="9.4.1-15978811"
readonly MAVEN_AAPT2_SHA256="eb93ce9d0fa121333f12395b3ab30822c5cdb9155737f20d342fe71809757059"

readonly TOOL_DIR="$TASK/work/android-release/tools"
readonly BUNDLETOOL="$TOOL_DIR/bundletool-all-$BUNDLETOOL_VERSION.jar"
readonly MAVEN_AAPT2_JAR="$TOOL_DIR/aapt2-$MAVEN_AAPT2_VERSION-osx.jar"
readonly AAPT2="$TOOL_DIR/aapt2-$MAVEN_AAPT2_VERSION-osx/aapt2"
readonly OUTPUT_DIR="$TASK/distribution/android/$VERSION_NAME"
readonly WORK_ROOT="$TASK/work/android-release"
readonly SDK_ROOT="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
readonly JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
readonly BUILD_TOOLS="$SDK_ROOT/build-tools/$BUILD_TOOLS_VERSION"
readonly PLATFORM="$SDK_ROOT/platforms/android-$COMPILE_SDK/android.jar"
readonly TV_BANNER="$SOURCE/android/res/drawable-xhdpi/tv_banner.png"
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

usage() {
  cat <<'USAGE'
Usage: GLASSTV_RELEASE_APPLICATION_ID=xyz.glasstv.launcher \
       GLASSTV_RELEASE_KEYSTORE=/absolute/path/glasstv-release.p12 \
       GLASSTV_RELEASE_KEY_ALIAS=glasstv-release \
       GLASSTV_RELEASE_STORE_PASSWORD_FILE=/absolute/path/store-password \
       GLASSTV_RELEASE_KEY_PASSWORD_FILE=/absolute/path/key-password \
       ./build-android-release.sh [--check]

Builds Glass TV 1.0.0 (version code 1) for min SDK 26, target SDK 34 and
compile SDK 36. The release key must be explicitly supplied; this script never
uses or creates the development key used by build-android.sh.

--check validates the supplied release configuration and required tooling without
compiling, signing, or creating artifacts.
USAGE
}

die() {
  printf 'Release build error: %s\n' "$*" >&2
  exit 1
}

require_file() {
  local label="$1"
  local path="$2"
  [[ -n "$path" ]] || die "$label is required"
  [[ -f "$path" && -r "$path" ]] || die "$label is not a readable file: $path"
}

require_executable() {
  local path="$1"
  [[ -x "$path" ]] || die "Required executable is unavailable: $path"
}

scan_staged_json() {
  local staged_assets="$1"
  if find "$staged_assets" -type f -name '*.json' -print0 | \
      xargs -0 grep -IlE '/Users/|/var/folders/|-----BEGIN [A-Z ]*PRIVATE KEY-----|private[_ -]?key|(^|[^0-9])(10\.[0-9]{1,3}\.|192\.168\.|172\.(1[6-9]|2[0-9]|3[0-1])\.)' | \
      grep -q .; then
    die "Staged JSON assets contain a forbidden local path, private IP address, or private-key marker"
  fi
}

validate_application_id() {
  [[ "$GLASSTV_RELEASE_APPLICATION_ID" =~ ^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$ ]] || \
    die "GLASSTV_RELEASE_APPLICATION_ID must be a lowercase Android application ID"
}

check_bundletool() {
  require_file "Pinned bundletool $BUNDLETOOL_VERSION" "$BUNDLETOOL"
  local actual_hash
  actual_hash="$(shasum -a 256 "$BUNDLETOOL" | awk '{print $1}')"
  [[ "$actual_hash" == "$BUNDLETOOL_SHA256" ]] || \
    die "bundletool checksum does not match the pinned 1.18.3 artifact"
}

check_maven_aapt2() {
  require_file "Pinned Maven AAPT2 $MAVEN_AAPT2_VERSION" "$MAVEN_AAPT2_JAR"
  require_executable "$AAPT2"
  local actual_hash
  actual_hash="$(shasum -a 256 "$MAVEN_AAPT2_JAR" | awk '{print $1}')"
  [[ "$actual_hash" == "$MAVEN_AAPT2_SHA256" ]] || \
    die "Maven AAPT2 checksum does not match the pinned 9.4.1 artifact"
}

check_release_configuration() {
  : "${GLASSTV_RELEASE_APPLICATION_ID:?Set GLASSTV_RELEASE_APPLICATION_ID}"
  : "${GLASSTV_RELEASE_KEYSTORE:?Set GLASSTV_RELEASE_KEYSTORE}"
  : "${GLASSTV_RELEASE_KEY_ALIAS:?Set GLASSTV_RELEASE_KEY_ALIAS}"
  : "${GLASSTV_RELEASE_STORE_PASSWORD_FILE:?Set GLASSTV_RELEASE_STORE_PASSWORD_FILE}"
  : "${GLASSTV_RELEASE_KEY_PASSWORD_FILE:?Set GLASSTV_RELEASE_KEY_PASSWORD_FILE}"

  validate_application_id
  require_file "Release keystore" "$GLASSTV_RELEASE_KEYSTORE"
  require_file "Release keystore password file" "$GLASSTV_RELEASE_STORE_PASSWORD_FILE"
  require_file "Release key password file" "$GLASSTV_RELEASE_KEY_PASSWORD_FILE"
  require_file "Android platform $COMPILE_SDK" "$PLATFORM"
  require_executable "$JAVA_HOME/bin/java"
  require_executable "$JAVA_HOME/bin/javac"
  require_executable "$JAVA_HOME/bin/jar"
  require_executable "$JAVA_HOME/bin/jarsigner"
  require_executable "$JAVA_HOME/bin/keytool"
  require_executable "$BUILD_TOOLS/d8"
  require_executable "$BUILD_TOOLS/apksigner"
  require_executable "$BUILD_TOOLS/zipalign"
  command -v openssl >/dev/null 2>&1 || die "Required executable is unavailable: openssl"
  check_bundletool
  check_maven_aapt2

  [[ -f "$SOURCE/android/AndroidManifest.xml" ]] || die "Missing Android manifest"
  [[ -d "$SOURCE/android/res" ]] || die "Missing Android resources"
  require_file "Android TV banner" "$TV_BANNER"
  [[ -d "$SOURCE/android/src" ]] || die "Missing Android source"
  [[ -d "$SOURCE/android/assets" ]] || die "Missing Android assets"
  [[ -f "$SOURCE/shared/app-shortcuts.json" ]] || die "Missing shared shortcut catalog"
  cmp -s "$SOURCE/shared/app-shortcuts.json" "$SOURCE/android/assets/app-shortcuts.json" || \
    die "Android shortcut catalog differs from shared/app-shortcuts.json"
  if rg -n 'android:name="\.' "$SOURCE/android/AndroidManifest.xml" >/dev/null; then
    die "Release manifest must use fully qualified component class names when the application ID is renamed"
  fi
}

mode="build"
case "${1:-}" in
  "") ;;
  --check) mode="check" ;;
  --help|-h) usage; exit 0 ;;
  *) usage >&2; exit 2 ;;
esac

check_release_configuration

if [[ "$mode" == "check" ]]; then
  printf 'Release configuration is ready for %s (version %s, code %s).\n' \
    "$GLASSTV_RELEASE_APPLICATION_ID" "$VERSION_NAME" "$VERSION_CODE"
  exit 0
fi

BUILD="$WORK_ROOT/build-$(date -u +%Y%m%dT%H%M%SZ)"
MODULE_DIR="$BUILD/base"
PROTO_DIR="$BUILD/proto"
ASSET_STAGE="$BUILD/assets"
OUTPUT_APK="$OUTPUT_DIR/Glass-TV-$VERSION_NAME-universal.apk"
OUTPUT_AAB="$OUTPUT_DIR/Glass-TV-$VERSION_NAME.aab"
OUTPUT_SUMS="$OUTPUT_DIR/SHA256SUMS"
OUTPUT_CERTIFICATE="$OUTPUT_DIR/publiccert.pem"
OUTPUT_METADATA="$OUTPUT_DIR/release.json"

for artifact in "$OUTPUT_APK" "$OUTPUT_AAB" "$OUTPUT_SUMS" "$OUTPUT_CERTIFICATE" "$OUTPUT_METADATA"; do
  [[ ! -e "$artifact" ]] || die "Refusing to overwrite an existing release artifact: $artifact"
done
mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/dex" "$MODULE_DIR/manifest" "$MODULE_DIR/dex" "$ASSET_STAGE" "$OUTPUT_DIR"
tar -C "$SOURCE/android/assets" --exclude=README.md --exclude=provenance.json --exclude=atlas-provenance.json -cf - . | tar -C "$ASSET_STAGE" -xf -
scan_staged_json "$ASSET_STAGE"

"$AAPT2" compile --dir "$SOURCE/android/res" -o "$BUILD/resources.zip"
"$AAPT2" link --proto-format -I "$PLATFORM" \
  --manifest "$SOURCE/android/AndroidManifest.xml" \
  --java "$BUILD/gen" \
  --custom-package "$R_NAMESPACE" \
  --rename-manifest-package "$GLASSTV_RELEASE_APPLICATION_ID" \
  --min-sdk-version "$MIN_SDK" \
  --target-sdk-version "$TARGET_SDK" \
  --version-code "$VERSION_CODE" \
  --version-name "$VERSION_NAME" \
  --replace-version \
  -A "$ASSET_STAGE" \
  -o "$BUILD/base-proto.apk" "$BUILD/resources.zip"

find "$SOURCE/android/src" "$BUILD/gen" -name '*.java' -print | sort > "$BUILD/sources.txt"
"$JAVA_HOME/bin/javac" -encoding UTF-8 --release 8 -classpath "$PLATFORM" -d "$BUILD/classes" @"$BUILD/sources.txt"
"$JAVA_HOME/bin/jar" cf "$BUILD/classes.jar" -C "$BUILD/classes" .
"$BUILD_TOOLS/d8" --min-api "$MIN_SDK" --lib "$PLATFORM" --output "$BUILD/dex" "$BUILD/classes.jar"

unzip -q "$BUILD/base-proto.apk" -d "$PROTO_DIR"
mv "$PROTO_DIR/AndroidManifest.xml" "$MODULE_DIR/manifest/AndroidManifest.xml"
for entry in assets res resources.pb; do
  [[ -e "$PROTO_DIR/$entry" ]] || die "aapt2 proto output is missing $entry"
  mv "$PROTO_DIR/$entry" "$MODULE_DIR/$entry"
done
cp "$BUILD/dex"/classes*.dex "$MODULE_DIR/dex/"
(cd "$MODULE_DIR" && zip -q -X -r "$BUILD/base.zip" .)

"$JAVA_HOME/bin/java" -jar "$BUNDLETOOL" build-bundle \
  --modules="$BUILD/base.zip" --output="$BUILD/Glass-TV-$VERSION_NAME-unsigned.aab" --overwrite

"$JAVA_HOME/bin/jarsigner" \
  -keystore "$GLASSTV_RELEASE_KEYSTORE" \
  -storepass:file "$GLASSTV_RELEASE_STORE_PASSWORD_FILE" \
  -keypass:file "$GLASSTV_RELEASE_KEY_PASSWORD_FILE" \
  "$BUILD/Glass-TV-$VERSION_NAME-unsigned.aab" "$GLASSTV_RELEASE_KEY_ALIAS"
"$JAVA_HOME/bin/jarsigner" -verify -verbose -certs "$BUILD/Glass-TV-$VERSION_NAME-unsigned.aab" > "$BUILD/aab-signature-verification.txt"

"$JAVA_HOME/bin/java" -jar "$BUNDLETOOL" validate --bundle="$BUILD/Glass-TV-$VERSION_NAME-unsigned.aab" > "$BUILD/aab-validation.txt"
"$JAVA_HOME/bin/java" -jar "$BUNDLETOOL" dump manifest --bundle="$BUILD/Glass-TV-$VERSION_NAME-unsigned.aab" > "$BUILD/aab-manifest.xml"

rg -F "package=\"$GLASSTV_RELEASE_APPLICATION_ID\"" "$BUILD/aab-manifest.xml" >/dev/null || die "AAB manifest package verification failed"
rg -F "android:versionCode=\"$VERSION_CODE\"" "$BUILD/aab-manifest.xml" >/dev/null || die "AAB manifest version code verification failed"
rg -F "android:versionName=\"$VERSION_NAME\"" "$BUILD/aab-manifest.xml" >/dev/null || die "AAB manifest version name verification failed"
rg -F "android:minSdkVersion=\"$MIN_SDK\"" "$BUILD/aab-manifest.xml" >/dev/null || die "AAB manifest minimum SDK verification failed"
rg -F "android:targetSdkVersion=\"$TARGET_SDK\"" "$BUILD/aab-manifest.xml" >/dev/null || die "AAB manifest target SDK verification failed"

mv "$BUILD/Glass-TV-$VERSION_NAME-unsigned.aab" "$OUTPUT_AAB"

"$JAVA_HOME/bin/java" -jar "$BUNDLETOOL" build-apks \
  --bundle="$OUTPUT_AAB" \
  --output="$BUILD/Glass-TV-$VERSION_NAME-universal.apks" \
  --mode=universal \
  --aapt2="$AAPT2" \
  --ks="$GLASSTV_RELEASE_KEYSTORE" \
  --ks-key-alias="$GLASSTV_RELEASE_KEY_ALIAS" \
  --ks-pass="file:$GLASSTV_RELEASE_STORE_PASSWORD_FILE" \
  --key-pass="file:$GLASSTV_RELEASE_KEY_PASSWORD_FILE" \
  --overwrite

mkdir -p "$BUILD/universal"
unzip -q -j "$BUILD/Glass-TV-$VERSION_NAME-universal.apks" universal.apk -d "$BUILD/universal"
mv "$BUILD/universal/universal.apk" "$OUTPUT_APK"

"$BUILD_TOOLS/apksigner" verify --verbose --print-certs "$OUTPUT_APK" > "$BUILD/apk-signature-verification.txt"
"$BUILD_TOOLS/zipalign" -c -v 4 "$OUTPUT_APK" > "$BUILD/apk-zipalign-verification.txt"
"$AAPT2" dump badging "$OUTPUT_APK" > "$BUILD/apk-badging.txt"
rg -F "package: name='$GLASSTV_RELEASE_APPLICATION_ID' versionCode='$VERSION_CODE' versionName='$VERSION_NAME'" "$BUILD/apk-badging.txt" >/dev/null || die "APK package/version verification failed"
rg -Fx "minSdkVersion:'$MIN_SDK'" "$BUILD/apk-badging.txt" >/dev/null || die "APK minimum SDK verification failed"
rg -Fx "targetSdkVersion:'$TARGET_SDK'" "$BUILD/apk-badging.txt" >/dev/null || die "APK target SDK verification failed"

(cd "$OUTPUT_DIR" && shasum -a 256 "$(basename "$OUTPUT_AAB")" "$(basename "$OUTPUT_APK")") > "$OUTPUT_SUMS"
"$JAVA_HOME/bin/keytool" -exportcert -rfc \
  -keystore "$GLASSTV_RELEASE_KEYSTORE" \
  -storepass:file "$GLASSTV_RELEASE_STORE_PASSWORD_FILE" \
  -alias "$GLASSTV_RELEASE_KEY_ALIAS" \
  -file "$OUTPUT_CERTIFICATE"
certificate_sha256="$(openssl x509 -in "$OUTPUT_CERTIFICATE" -noout -fingerprint -sha256 | sed 's/^[^=]*=//' | tr -d ':' | tr '[:upper:]' '[:lower:]')"
banner_sha256="$(shasum -a 256 "$TV_BANNER" | awk '{print $1}')"
[[ "$certificate_sha256" =~ ^[0-9a-f]{64}$ ]] || die "Public release certificate fingerprint verification failed"
[[ "$banner_sha256" =~ ^[0-9a-f]{64}$ ]] || die "Android TV banner checksum capture failed"
aab_sha256="$(awk -v name="$(basename "$OUTPUT_AAB")" '$2 == name { print $1 }' "$OUTPUT_SUMS")"
apk_sha256="$(awk -v name="$(basename "$OUTPUT_APK")" '$2 == name { print $1 }' "$OUTPUT_SUMS")"
apk_signer_sha256="$(awk -F': ' '/Signer #1 certificate SHA-256 digest:/ { print $2 }' "$BUILD/apk-signature-verification.txt")"
[[ "$aab_sha256" =~ ^[0-9a-f]{64}$ ]] || die "AAB checksum capture failed"
[[ "$apk_sha256" =~ ^[0-9a-f]{64}$ ]] || die "APK checksum capture failed"
[[ "$apk_signer_sha256" =~ ^[0-9a-f]{64}$ ]] || die "APK signer fingerprint capture failed"
[[ "$apk_signer_sha256" == "$certificate_sha256" ]] || die "AAB certificate does not match the APK signer"
cat > "$OUTPUT_METADATA" <<EOF
{
  "applicationId": "$GLASSTV_RELEASE_APPLICATION_ID",
  "versionName": "$VERSION_NAME",
  "versionCode": $VERSION_CODE,
  "minSdk": $MIN_SDK,
  "targetSdk": $TARGET_SDK,
  "bannerSha256": "$banner_sha256",
  "apk": {
    "file": "$(basename "$OUTPUT_APK")",
    "sha256": "$apk_sha256",
    "signerSha256": "$apk_signer_sha256"
  },
  "aab": {
    "file": "$(basename "$OUTPUT_AAB")",
    "sha256": "$aab_sha256"
  },
  "status": "candidate-not-published"
}
EOF
cat > "$BUILD/release-summary.txt" <<EOF
applicationId=$GLASSTV_RELEASE_APPLICATION_ID
versionName=$VERSION_NAME
versionCode=$VERSION_CODE
minSdk=$MIN_SDK
targetSdk=$TARGET_SDK
compileSdk=$COMPILE_SDK
bundletool=$BUNDLETOOL_VERSION
bundletoolSha256=$BUNDLETOOL_SHA256
mavenAapt2=$MAVEN_AAPT2_VERSION
mavenAapt2Sha256=$MAVEN_AAPT2_SHA256
certificateSha256=$certificate_sha256
apkSignerSha256=$apk_signer_sha256
bannerSha256=$banner_sha256
EOF
chmod 644 "$OUTPUT_AAB" "$OUTPUT_APK" "$OUTPUT_CERTIFICATE" "$OUTPUT_SUMS" "$OUTPUT_METADATA"

printf 'Built and verified:\n  %s\n  %s\n  %s\n  %s\n  %s\n' "$OUTPUT_AAB" "$OUTPUT_APK" "$OUTPUT_CERTIFICATE" "$OUTPUT_SUMS" "$OUTPUT_METADATA"
printf 'Build evidence: %s\n' "$BUILD"
