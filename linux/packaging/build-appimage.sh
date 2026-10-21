#!/bin/sh
# Build a local, discovery-only x86_64 AppImage candidate on Ubuntu 24.04.
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
linux_dir=$(CDPATH= cd -- "$script_dir/.." && pwd)
source_dir=$(CDPATH= cd -- "$linux_dir/.." && pwd)
# In the full private workspace, source/ has a sibling release-source/.git and
# build products belong at that workspace root. In a public checkout, linux/
# and shared/ live directly below the Git root, so keep products in that clone.
if [ -d "$source_dir/.git" ]; then
    workspace_dir=$source_dir
elif [ "$(basename "$source_dir")" = "source" ] && [ -d "$source_dir/../release-source/.git" ]; then
    workspace_dir=$(CDPATH= cd -- "$source_dir/.." && pwd)
else
    workspace_dir=$source_dir
fi

architecture=${GLASS_TV_APPIMAGE_ARCH:-x86_64}
version=${GLASS_TV_APPIMAGE_VERSION:-0.1.0-preview}
python_bin=${PYTHON_BIN:-python3}
work_root=${GLASS_TV_APPIMAGE_WORK_ROOT:-"$workspace_dir/work/linux/$architecture/appimage"}
output_root=${GLASS_TV_APPIMAGE_OUTPUT_ROOT:-"$workspace_dir/builds/linux"}
tool_root=${GLASS_TV_APPIMAGE_TOOL_ROOT:-"$workspace_dir/work/linux/tools"}

appimagetool_version=1.9.1
appimagetool_file="appimagetool-$architecture.AppImage"
appimagetool_url="https://github.com/AppImage/appimagetool/releases/download/$appimagetool_version/$appimagetool_file"
appimagetool_sha256_x86_64=ed4ce84f0d9caff66f50bcca6ff6f35aae54ce8135408b3fa33abfc3cb384eb0
runtime_version=20251108
runtime_file="runtime-$architecture"
runtime_url="https://github.com/AppImage/type2-runtime/releases/download/$runtime_version/$runtime_file"
runtime_sha256_x86_64=2fca8b443c92510f1483a883f60061ad09b46b978b2631c807cd873a47ec260d

usage() {
    cat <<'EOF'
Usage: packaging/build-appimage.sh [--help]

Builds the discovery-only Glass TV Linux preview as an x86_64 AppImage.
Run on Ubuntu 24.04 x86_64 with Python 3.10–3.15, a C compiler, readelf,
patchelf, ImageMagick, curl, and sha256sum. Install ../requirements.txt and
packaging/requirements-build.txt into the selected virtual environment first.

Optional environment variables:
  PYTHON_BIN                     Python from the prepared virtual environment
  GLASS_TV_APPIMAGE_VERSION      AppImage version label (default: 0.1.0-preview)
  GLASS_TV_APPIMAGE_WORK_ROOT    Parent for unique intermediates under work/linux/
  GLASS_TV_APPIMAGE_OUTPUT_ROOT  Candidate output directory under builds/linux/
  GLASS_TV_APPIMAGE_TOOL_ROOT    Download cache under work/linux/tools/
EOF
}

case "${1:-}" in
    --help|-h) usage; exit 0 ;;
    "") ;;
    *) echo "Unknown argument: $1" >&2; usage >&2; exit 64 ;;
esac

if [ "$(uname -s)" != "Linux" ]; then
    echo "AppImage candidates must be built on Linux; current host is $(uname -s)." >&2
    exit 1
fi
if [ "$architecture" != "x86_64" ] || [ "$(uname -m)" != "x86_64" ]; then
    echo "This preview recipe supports only x86_64 Linux. Build aarch64 after its own execution check." >&2
    exit 1
fi
for command in "$python_bin" convert curl sha256sum readelf patchelf mktemp; do
    if ! command -v "$command" >/dev/null 2>&1; then
        echo "Required command is unavailable: $command" >&2
        exit 1
    fi
done
if ! "$python_bin" -c 'import nuitka, PySide6.QtWidgets' >/dev/null 2>&1; then
    echo "Install requirements.txt and packaging/requirements-build.txt in PYTHON_BIN's environment first." >&2
    exit 1
fi
case "$work_root" in
    "$workspace_dir"/work/linux/*) ;;
    *) echo "GLASS_TV_APPIMAGE_WORK_ROOT must stay under $workspace_dir/work/linux/." >&2; exit 1 ;;
esac
case "$output_root" in
    "$workspace_dir"/builds/linux) ;;
    *) echo "GLASS_TV_APPIMAGE_OUTPUT_ROOT must be $workspace_dir/builds/linux." >&2; exit 1 ;;
esac
case "$tool_root" in
    "$workspace_dir"/work/linux/tools) ;;
    *) echo "GLASS_TV_APPIMAGE_TOOL_ROOT must be $workspace_dir/work/linux/tools." >&2; exit 1 ;;
esac

mkdir -p "$work_root" "$output_root" "$tool_root"
build_root=$(mktemp -d "$work_root/build.XXXXXXXX")
stage_root="$build_root/stage"
compile_root="$build_root/compile"
appdir="$build_root/GlassTV.AppDir"
mkdir -p "$stage_root/resources/backgrounds" "$compile_root"

# Stage the reviewed, minimal resource set. No macOS tree, credentials, caches,
# or unreviewed shared content is copied into the AppImage.
cp "$source_dir/shared/backgrounds/fluorescent-anemone-reef.jpg" "$stage_root/resources/backgrounds/"
if [ -f "$source_dir/shared/icons/refresh.svg" ]; then
    mkdir -p "$stage_root/resources/icons"
    cp "$source_dir/shared/icons/refresh.svg" "$stage_root/resources/icons/"
fi

PYTHONPATH="$linux_dir${PYTHONPATH:+:$PYTHONPATH}" "$python_bin" -m nuitka \
    --standalone \
    --enable-plugin=pyside6 \
    --assume-yes-for-downloads \
    --output-dir="$compile_root" \
    --output-filename=glasstv-linux \
    --include-package=glasstv_linux \
    --include-data-dir="$stage_root/resources=resources" \
    "$script_dir/entrypoint.py"

payload="$compile_root/entrypoint.dist"
if [ ! -x "$payload/glasstv-linux" ]; then
    echo "Nuitka did not create the expected standalone payload: $payload/glasstv-linux" >&2
    exit 1
fi

mkdir -p "$appdir/usr/bin" "$appdir/usr/lib/glasstv-linux" \
    "$appdir/usr/share/applications" "$appdir/usr/share/icons/hicolor/256x256/apps" \
    "$appdir/usr/share/icons/hicolor/512x512/apps"
cp -R "$payload/." "$appdir/usr/lib/glasstv-linux/"
cp "$script_dir/AppRun" "$appdir/AppRun"
chmod 0755 "$appdir/AppRun"
cp "$script_dir/glasstv-linux.desktop" "$appdir/usr/share/applications/glasstv-linux.desktop"
ln -s usr/share/applications/glasstv-linux.desktop "$appdir/glasstv-linux.desktop"
# Generate conventional Linux icon sizes directly from the owner-supplied
# master. Never resample one generated size to produce another.
convert "$source_dir/shared/app-icon-master.png" -resize 256x256 "$appdir/usr/share/icons/hicolor/256x256/apps/glass-tv.png"
convert "$source_dir/shared/app-icon-master.png" -resize 512x512 "$appdir/usr/share/icons/hicolor/512x512/apps/glass-tv.png"
ln -s usr/share/icons/hicolor/512x512/apps/glass-tv.png "$appdir/glass-tv.png"
ln -s glass-tv.png "$appdir/.DirIcon"
ln -s ../lib/glasstv-linux/glasstv-linux "$appdir/usr/bin/glasstv-linux"

if command -v desktop-file-validate >/dev/null 2>&1; then
    desktop-file-validate "$appdir/glasstv-linux.desktop"
fi

tool_path="$tool_root/$appimagetool_file"
if [ ! -f "$tool_path" ]; then
    curl --fail --location --retry 3 --output "$tool_path" "$appimagetool_url"
fi
actual_sha256=$(sha256sum "$tool_path" | awk '{print $1}')
if [ "$actual_sha256" != "$appimagetool_sha256_x86_64" ]; then
    echo "appimagetool checksum mismatch for $tool_path" >&2
    exit 1
fi
chmod 0755 "$tool_path"

runtime_path="$tool_root/$runtime_file-$runtime_version"
if [ ! -f "$runtime_path" ]; then
    curl --fail --location --retry 3 --output "$runtime_path" "$runtime_url"
fi
actual_runtime_sha256=$(sha256sum "$runtime_path" | awk '{print $1}')
if [ "$actual_runtime_sha256" != "$runtime_sha256_x86_64" ]; then
    echo "AppImage runtime checksum mismatch for $runtime_path" >&2
    exit 1
fi

artifact="$output_root/GlassTV-$version-$architecture.AppImage"
if [ -e "$artifact" ] || [ -e "$artifact.sha256" ]; then
    echo "Candidate output already exists; choose GLASS_TV_APPIMAGE_VERSION or preserve the existing artifact: $artifact" >&2
    exit 1
fi
ARCH="$architecture" VERSION="$version" APPIMAGE_EXTRACT_AND_RUN=1 \
    "$tool_path" --runtime-file="$runtime_path" "$appdir" "$artifact"
sha256sum "$artifact" > "$artifact.sha256"
printf 'Built discovery-only preview candidate: %s\n' "$artifact"
