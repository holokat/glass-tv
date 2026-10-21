# AppImage preview recipe

This is a reproducible build path for the **discovery-only preview**. It
contains the certificate-discovery window, its approved reef preview and the
Central refresh icon. It has no pairing, credential persistence, authenticated
receiver requests, media, settings synchronization or Android packaging.

The first target is x86_64 Ubuntu 24.04. Its glibc 2.39 meets the
`manylinux_2_34_x86_64` platform tag published for `PySide6-Essentials 6.12.0`.
That is a dependency floor, not a compatibility claim for every distribution.
Build aarch64 only after it receives its own Linux execution check.

## CI smoke check

Use Python 3.10 through 3.15 and an isolated virtual environment. The approved
runtime set is pinned in `../requirements.txt`.

```sh
python3 -m venv .venv
.venv/bin/python -m pip install --upgrade pip
.venv/bin/python -m pip install -r requirements.txt
QT_QPA_PLATFORM=offscreen .venv/bin/python -m glasstv_linux --smoke-test --smoke-screenshot "$PWD/glasstv-linux-smoke.png"
.venv/bin/python -m unittest discover -s tests -t . -v
```

The smoke test creates, renders, captures, and closes the Qt window without
resolving an address, opening a socket, reading settings, or touching
credentials. It is suitable for an offscreen CI artifact. A desktop-session run
remains required before claiming keyboard, scaling, native file-dialog,
accessibility, X11, or Wayland support.

## Build a candidate on Ubuntu 24.04 x86_64

Use an isolated Python environment. `Nuitka==4.1.1` is build-only tooling;
`PySide6-Essentials==6.12.0` remains the approved runtime dependency.

```sh
python3 -m venv .venv
.venv/bin/python -m pip install --upgrade pip
.venv/bin/python -m pip install -r requirements.txt -r packaging/requirements-build.txt
sudo apt-get update
sudo apt-get install --yes patchelf desktop-file-utils imagemagick
PYTHON_BIN="$PWD/.venv/bin/python" packaging/build-appimage.sh
```

The script creates its temporary AppDir and compiler output under
`work/linux/x86_64/appimage/`, caches its toolchain under `work/linux/tools/`,
and writes a candidate plus SHA-256 file under `builds/linux/`. It downloads
only the pinned 15 MB x86_64 `appimagetool` 1.9.1 release and the pinned 0.9 MB
type-2 runtime 20251108 release. It verifies the GitHub release digests
`ed4ce84f0d9caff66f50bcca6ff6f35aae54ce8135408b3fa33abfc3cb384eb0` and
`2fca8b443c92510f1483a883f60061ad09b46b978b2631c807cd873a47ec260d` before
using them. Passing the verified runtime through `--runtime-file` prevents
`appimagetool` from fetching an unpinned runtime. The build does not create
`distribution/linux/` artifacts.

`build-appimage.sh` stages only the minimum reviewed resources and freezes the
program in Nuitka standalone mode with Qt's PySide6 plugin. ImageMagick derives
256 px and 512 px icons directly from the owner-supplied icon master. The result
is a conforming AppDir with one root desktop entry, `AppRun`, a root icon and
`.DirIcon`; the launcher sets `GLASS_TV_RESOURCE_ROOT` so the frozen executable
does not look back into the source checkout.

Run `packaging/check-appimage-layout.sh` for a network-free recipe check. CI
also runs the artifact using `APPIMAGE_EXTRACT_AND_RUN=1`, which exercises the
runtime's extraction path where FUSE is unavailable. A real desktop-session
test remains necessary before claiming native desktop, accessibility, X11 or
Wayland support.

References: [AppDir specification](https://docs.appimage.org/reference/appdir.html),
[appimagetool 1.9.1 release](https://github.com/AppImage/appimagetool/releases/tag/1.9.1),
and [Qt's Nuitka deployment guidance](https://doc.qt.io/qtforpython-6/deployment/deployment-nuitka.html).
