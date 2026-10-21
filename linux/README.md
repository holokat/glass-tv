# Glass TV for Linux

This directory starts a separate Linux companion, with AppImage as the intended distribution format. The existing native Mac companion remains in `../mac/`; Android receiver packaging remains in `../build-android.sh`.

Current status: a first discovery-only Python core and Qt Widgets window are implemented here. The window accepts an explicit numeric IPv4 address, reads only the receiver's TLS leaf certificate asynchronously, and displays its SHA-256 fingerprint for the user to compare on the TV. It has no pairing, credential storage, authenticated request, media, or settings-sync behavior. A pinned AppImage preview recipe is implemented, but no AppImage candidate has been built or run yet.

The approved initial UI dependency is `PySide6-Essentials==6.12.0`, using Python 3.10 or newer. Qt provides desktop controls while the protocol and local state remain small Python modules independent of the UI. See [the porting plan](PORTING.md) for alternatives, evidence and acceptance checks.

The core validates a canonical numeric IPv4 address and port, uses app-isolated XDG directories, and atomically writes non-sensitive local JSON settings. Discovery performs only a TLS 1.2 handshake. It sends no HTTP request, cannot call `/pair`, and persists neither a fingerprint nor a pairing.

## Run the first desktop window

Create an isolated environment outside the source tree, install the pinned requirements, then run the module from this directory. With no arguments it opens the native Qt window:

```sh
export GLASS_TV_LINUX_VENV="${XDG_DATA_HOME:-$HOME/.local/share}/glass-tv-linux/venv"
mkdir -p "$(dirname "$GLASS_TV_LINUX_VENV")"
python3 -m venv "$GLASS_TV_LINUX_VENV"
"$GLASS_TV_LINUX_VENV/bin/python" -m pip install -r requirements.txt
"$GLASS_TV_LINUX_VENV/bin/python" -m glasstv_linux
```

For the developer-only command-line discovery flow, supply the target yourself:

```sh
"$GLASS_TV_LINUX_VENV/bin/python" -m glasstv_linux 192.168.1.20
```

On success, that command prints only the target and grouped certificate fingerprint. It never performs pairing or sends credentials. For a network-free Qt CI launch check, run:

```sh
QT_QPA_PLATFORM=offscreen "$GLASS_TV_LINUX_VENV/bin/python" -m glasstv_linux --smoke-test --smoke-screenshot "${XDG_RUNTIME_DIR:-/tmp}/glasstv-linux-smoke.png"
```

The screenshot option is only for a CI artifact path whose parent already exists. The smoke path shows, captures, and closes the actual window without resolving an address, opening a socket, reading settings, or touching credentials.

The host used for this work has no reusable PySide6 installation, and no dependency environment was installed by this milestone. The [AppImage recipe](packaging/README.md) pins its build-only tools, validates its inputs locally and supplies an inactive CI reference. A Linux Ubuntu 24.04 run must still build and validate the first candidate.

The receiver currently accepts one companion token. Pairing Linux with a TV already paired to the Mac replaces the Mac's token. Concurrent companion pairing requires a separate receiver protocol decision; this port must not silently change that behavior or copy Mac Keychain credentials.

Use [the local protocol](../PROTOCOL.md) as the wire contract and [the source style guide](../STYLEGUIDE.md) for product behavior, assets and content rules. Mac-specific SwiftUI/AppKit styling stays with the Mac app. Linux should use standard desktop controls, system typography and the existing licensed Central SVG exports.

No command in this document depends on a developer-specific path. In the full
workspace, planned locations are relative to its root. In a public checkout,
the same `work/`, `builds/` and `distribution/` locations are relative to the
checkout root:

| Purpose | Location |
| --- | --- |
| Linux implementation and packaging recipe | `source/linux/` in the full workspace, `linux/` in a public checkout |
| Linux build output and local AppImage candidates | `builds/linux/` |
| Linux toolchain/build intermediates | `work/linux/` |
| Reviewed Linux release artifacts | `distribution/linux/` |

The output directories are created only by an explicit local or CI build. Public source changes must be copied deliberately into `release-source/`, preserving its existing asset and secret exclusions.
