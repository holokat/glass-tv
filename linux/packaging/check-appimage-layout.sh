#!/bin/sh
# Fast, network-free checks for committed AppImage recipe inputs.
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
linux_dir=$(CDPATH= cd -- "$script_dir/.." && pwd)
source_dir=$(CDPATH= cd -- "$linux_dir/.." && pwd)

for file in AppRun build-appimage.sh glasstv-linux.desktop requirements-build.txt entrypoint.py; do
    test -f "$script_dir/$file"
done
for file in "$source_dir/shared/app-icon-master.png" \
            "$source_dir/shared/backgrounds/fluorescent-anemone-reef.jpg"; do
    test -f "$file"
done
sh -n "$script_dir/AppRun"
sh -n "$script_dir/build-appimage.sh"
grep -qx 'Type=Application' "$script_dir/glasstv-linux.desktop"
grep -qx 'Exec=glasstv-linux' "$script_dir/glasstv-linux.desktop"
grep -qx 'Icon=glass-tv' "$script_dir/glasstv-linux.desktop"
grep -qx 'Nuitka==4.1.1' "$script_dir/requirements-build.txt"
grep -Fqx 'appimagetool_version=1.9.1' "$script_dir/build-appimage.sh"
grep -Fqx 'appimagetool_sha256_x86_64=ed4ce84f0d9caff66f50bcca6ff6f35aae54ce8135408b3fa33abfc3cb384eb0' "$script_dir/build-appimage.sh"
grep -Fqx 'runtime_version=20251108' "$script_dir/build-appimage.sh"
grep -Fqx 'runtime_sha256_x86_64=2fca8b443c92510f1483a883f60061ad09b46b978b2631c807cd873a47ec260d' "$script_dir/build-appimage.sh"
grep -Fq -- '--runtime-file="$runtime_path"' "$script_dir/build-appimage.sh"
grep -Fq 'shared/app-icon-master.png" -resize 256x256' "$script_dir/build-appimage.sh"
grep -Fq 'shared/app-icon-master.png" -resize 512x512' "$script_dir/build-appimage.sh"
grep -Fq 'if [ -f "$source_dir/shared/icons/refresh.svg" ]; then' "$script_dir/build-appimage.sh"
grep -Fq 'PYTHONPATH="$linux_dir${PYTHONPATH:+:$PYTHONPATH}" "$python_bin" -m nuitka' "$script_dir/build-appimage.sh"
grep -Fq 'build_root=$(mktemp -d "$work_root/build.XXXXXXXX")' "$script_dir/build-appimage.sh"
grep -Fq 'Candidate output already exists' "$script_dir/build-appimage.sh"
if grep -Eq '(^|[[:space:]])rm[[:space:]]' "$script_dir/build-appimage.sh"; then
    echo "The AppImage build recipe must preserve existing files." >&2
    exit 1
fi
grep -Fq 'GLASS_TV_RESOURCE_ROOT' "$script_dir/AppRun"
printf 'AppImage recipe inputs are structurally valid.\n'
