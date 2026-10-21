# Linux companion porting plan

## Objective and scope

Build a separate Glass TV Linux desktop companion and distribute it as an AppImage. Keep the native SwiftUI/AppKit Mac application, its signing/build scripts, data and Keychain records intact. Reuse protocol version 1 and existing approved assets. Leave Android packaging, installed TV state and the website outside this work.

This plan records inspection on 21 October 2026. Items labeled proposed are implementation choices, not tested product capabilities. No Linux app has been built or run.

## Verified source boundaries

| Component | Evidence and implication |
| --- | --- |
| Mac UI and build | [`mac/build-mac-app.sh`](../mac/build-mac-app.sh) compiles Swift for `arm64-apple-macosx13.0` and links SwiftUI, AppKit, AVFoundation, ImageIO, CryptoKit, Security, Network and ServiceManagement. The current app cannot become a Linux app by changing its package extension. |
| Receiver client | [`mac/GlassTVClient.swift`](../mac/GlassTVClient.swift) validates an IPv4 address, uses HTTPS port 8743 and TLS 1.2, hashes the leaf certificate's DER bytes, pins authenticated requests, disables cookies and rejects redirects. |
| Connection state | [`mac/TVConnectionState.swift`](../mac/TVConnectionState.swift) requires authenticated status for a connected state, preserves manual disconnect and defines an 8-second status timeout, a 20-second healthy interval and 2/4/8/10-second retry delays. |
| Credentials and local state | [`mac/LibraryStore.swift`](../mac/LibraryStore.swift) stores credentials under Keychain service `local.glasstv.companion`. Local libraries and profiles live under the Mac Application Support `Glass TV` directory. Linux must own different paths and a different credential namespace. |
| Receiver pairing | [`android/.../LocalSyncServer.java`](../android/src/local/glasstv/launcher/LocalSyncServer.java) stores a single token hash on successful pairing. Pairing a second companion invalidates the previous token. Certificate discovery does not pair. |
| Settings reconciliation | [`mac/SettingsSyncPolicy.swift`](../mac/SettingsSyncPolicy.swift), [`mac/CompanionModel.swift`](../mac/CompanionModel.swift), and [`PROTOCOL.md`](../PROTOCOL.md) govern local drafts, receiver preferences and revision conflicts. Linux must preserve omitted optional settings and unrelated receiver changes. |
| Media and Movies | [`mac/ImageProcessor.swift`](../mac/ImageProcessor.swift), [`mac/VideoCompatibility.swift`](../mac/VideoCompatibility.swift), [`mac/MovieHTTPServer.swift`](../mac/MovieHTTPServer.swift) and [`mac/MovieTLSIdentity.swift`](../mac/MovieTLSIdentity.swift) include platform-specific image, TLS and streaming work. These require deliberate ports. |
| Shared assets | `shared/app-icon-master.png`, `shared/icons/`, `shared/app-shortcuts.json`, `shared/brands/` and `shared/navigation-sounds/` already exist. Source access does not establish public redistribution rights for every asset; preserve the public checkout's exclusions. |
| Project structure | No Linux implementation, Python package manifest, Cargo manifest, Electron manifest, Qt build manifest or package lockfile was found under `source/` during inspection. The public Git checkout is separately maintained in `release-source/`. |

## Verified local tooling

The inspected host is Darwin on arm64. Availability below is limited to the named checks; it does not prove that no other host or runtime exists.

| Tool or environment | Observed result |
| --- | --- |
| Docker | CLI 29.8.0 is installed. `docker info` fails because `/var/run/docker.sock` is absent. Only the default context is listed. |
| Lima and Colima | Lima 2.2.0 and Colima 0.10.3 are installed. Both report no VM instances. Nothing was started. |
| Remote Linux host | The inspected SSH configuration contains GitHub and a Colima include. No reusable Linux host was identified or contacted. |
| Python | Xcode Python 3.9.6, Homebrew Python 3.13.14 and the Codex bundled Python 3.12.14 are present. The latter two can run portable core checks without installing packages. |
| Python UI and packaging | PySide6, PyQt6, `keyring` and Nuitka were absent in all three checked interpreters. PyQt5 was also absent in the Xcode and Codex interpreters. |
| Swift | Apple Swift 6.4 is installed for the Mac target. No Linux Swift toolchain was identified. |
| JavaScript | Node 22.17.1, npm 11.4.2 and a bundled pnpm executable are present. Electron and the Tauri CLI are absent from PATH and npm's global package list. |
| Rust | Cargo/rustc 1.91.1 are present; installed targets are `aarch64-apple-darwin` and `wasm32-unknown-unknown`. |
| Native build tools | CMake 4.4.3, Ninja 1.13.2 and Apple's compiler tools are present. No Qt formula was listed by Homebrew; `qmake` and `qtpaths` were absent from PATH. |
| Media and TLS tools | Homebrew ffmpeg 9.0.1 and OpenSSL 3.6.4 are installed; `ffprobe` is available. These are Mac binaries, not Linux bundle payloads. |
| AppImage validation | `appimagetool`, `linuxdeploy`, `readelf`, `patchelf`, `desktop-file-validate` and `shellcheck` were absent from PATH. |

Core unit tests, JSON/schema checks, Python syntax checks and local TLS test harnesses can run here. Linux desktop integration, keyring access, Linux TLS compatibility, X11/Wayland behavior and AppImage execution need a Linux environment.

## Framework recommendation

Approved for this first UI milestone: Python plus PySide6-Essentials Qt Widgets, with a Python standard-library protocol core. This fits the app's file selection, settings, lists and device controls without introducing a web renderer. Keeping the core independent of Qt allows useful work before installing a UI framework. Qt documents desktop widgets and its official Python binding. This is a project-specific recommendation, not a measured performance comparison. [Qt Widgets](https://doc.qt.io/qt-6/qtwidgets-index.html), [Qt for Python](https://doc.qt.io/qtforpython-6/gettingstarted.html).

| Option | Fit and tradeoff |
| --- | --- |
| PySide6 / Qt Widgets | Recommended starting point. A new production dependency and a packaged Python/Qt runtime are required. Build and test the AppImage early to expose plugin and library problems. |
| C++ / Qt Widgets | A reasonable compiled alternative. It uses the same desktop toolkit and avoids bundling Python, but adds a C++ implementation and does not reuse the current Swift UI. Qt still needs approval and Linux build tools. |
| Tauri | Rust and Node are installed locally, but no Linux target is ready. Its Linux development stack includes WebKitGTK, and AppImage compatibility depends on the build-system baseline. A web frontend adds another UI/runtime boundary without existing app UI to reuse. [Tauri prerequisites](https://v2.tauri.app/start/prerequisites/), [Tauri AppImage guidance](https://v2.tauri.app/distribute/appimage/). |
| Electron | Node alone is insufficient: Electron requires its own runtime download. AppImage tooling exists, but the project has no Electron UI or dependency tree to reuse. [Electron installation](https://www.electronjs.org/docs/latest/tutorial/installation), [AppImage packaging overview](https://docs.appimage.org/packaging-guide/overview.html). |
| Swift | Language portability does not remove the Apple-framework dependencies shown by this app's build script. Reusing isolated algorithms later is possible; direct UI compilation is not the first milestone. |

`PySide6-Essentials==6.12.0` is approved for the discovery-only Linux window. It requires Python 3.10 or newer and supplies the QtCore, QtGui and QtWidgets modules used here. The credential-storage adapter remains unresolved and must be approved separately. Keep build-only tools separate. Do not install Qt, Nuitka, a VM image or a second package manager as part of inspection.

## Ordered implementation

1. **Portable core and discovery, implemented.** `glasstv_linux` now validates strict numeric IPv4 targets, derives XDG-isolated storage paths, writes non-sensitive JSON atomically, resolves connection presentation, and discovers the exact TLS leaf certificate with no application-protocol bytes. Its developer CLI prints only an explicit target and SHA-256 fingerprint on success. Focused tests use an OpenSSL-generated local TLS server to verify the DER hash, no client HTTP data, redirect-payload indifference, timeouts, and settings failure preservation. Actual Linux OpenSSL compatibility remains a required CI check. [Python TLS documentation](https://docs.python.org/3/library/ssl.html).
2. **First GUI and Linux smoke build, implemented pending CI execution.** The native Qt window contains an address field, port, asynchronous Discover action, fingerprint display, and a read-only preview of the approved reef asset. It uses standard widgets plus the existing Central refresh SVG, and has a network-free offscreen `--smoke-test` path. It is pinned in one `requirements.txt`; no local dependency environment was installed. Ubuntu 24.04 x86_64 is the initial CI target.
3. **First AppImage candidate, recipe implemented pending Linux execution.** The discovery-only preview recipe pins and checksum-verifies `appimagetool` 1.9.1 and type-2 runtime 20251108, freezes a self-contained Python/Qt payload with Nuitka standalone mode, stages the reviewed reef preview and Central refresh icon, and writes candidates only to `builds/linux/`. It has static checks and an inactive Ubuntu 24.04 CI reference, but has not yet built or run a candidate on Linux.
4. **Pairing and authenticated status.** Implement explicit fingerprint confirmation, six-digit code entry, credential storage and read-only status. Validate the secret backend before pairing, then save the returned credential atomically and validate `apiVersion` and `deviceId`. A failed save must never be presented as successful pairing. Use a dedicated test receiver, or explicit approval to replace the current receiver's companion token. Confirm connection only after authenticated `/status`. Implement Disconnect, stale-response cancellation and existing retry policy.
5. **Photos and playlist settings.** Add local import, orientation correction, metadata stripping, hashing, bounded JPEG preparation and uploads. Preserve originals and local editing offline. Read the receiver revision before a write; retain unrelated settings, and handle `409` through refresh/reconciliation rather than blind retries. Start with photos before video conversion. Apply the receiver's actual size/storage limits.
6. **Apps, Weather and Layout.** Port their independent settings models and reconciliation behavior. Reuse the shared shortcut catalog and artwork identifiers. Keep Layout edits staged until Update TV. Weather must distinguish real data, loading, disabled and unavailable states. Match the current protocol's omission/default semantics and standalone TV edits.
7. **Video and Movies.** Port conversion/profile caches, then authenticated reverse HTTPS streaming, followed by catalog publication and TV-initiated requests. Select and validate Linux ffmpeg/ffprobe provisioning. Preserve byte-range behavior, stream token revocation, paired-peer restrictions, catalog generations and lease expiry. Keep Movies out of the first AppImage until its bidirectional security and cancellation checks pass.

Proposed modules follow `UI -> application state -> protocol/storage/media adapters`. State and protocol models must not import widgets. Share assets and the documented wire contract first; do not extract a cross-platform runtime from the working Mac app during this port.

## Security and compatibility requirements

- Treat the full leaf certificate's DER SHA-256 fingerprint as the pin. A public-key pin would change the current contract. Normalize display formatting separately from the comparison value.
- Discovery may accept the self-signed certificate only in its isolated, credential-free handshake. For every later connection, verify the confirmed pin before sending a code, bearer token or request body. A mismatch is a hard failure. Do not weaken process-wide certificate trust or third-party HTTPS validation.
- Use one explicit numeric IPv4 address and port 8743; reject URL syntax, credentials, paths and header injection. Do not add network scanning, proxy forwarding or redirects. Do not send the TV token to weather, artwork or metadata providers.
- Persist credentials only through an approved Linux desktop secret backend under a Linux-specific namespace, proposed `local.glasstv.companion.linux`. If no secure backend is available, keep the app unpaired until that condition is resolved. Do not fall back to plaintext settings or copy Mac credentials.
- Use proposed `glass-tv-linux` subdirectories under XDG config, data, state and cache roots, with standard fallbacks and atomic writes. Reject relative XDG environment paths. Never write user data into the read-only AppImage or the Mac Application Support directory. [XDG Base Directory Specification](https://specifications.freedesktop.org/basedir/latest/).
- The receiver currently has one token, not a client registry. Concurrent Mac and Linux pairing would require an architect decision on per-client credentials, revocation, migration and publisher ownership. Escalate through the coordinator; do not make receiver changes in this milestone.
- Retain complete-playlist revision semantics, optional-field preservation, bounded bodies/uploads and the absence of a media deletion endpoint. Unknown or malformed status must not mark the app connected.
- Reverse movie streaming must accept only the paired TV IP and temporary bearer token, use its own TLS identity, implement bounded GET/HEAD ranges and revoke replaced streams. The receiver validates the stream's numeric IPv4 source against the authenticated request peer. VM/container LAN routing must be tested before this feature is claimed.

## AppImage build plan

AppImage is the final container for Linux binaries and resources. It does not make Apple binaries portable. Build the payload inside Linux for each supported CPU architecture. Proposed initial architecture is x86_64; this remains an assumption until the user's Linux target is known. Add aarch64 only when it has its own build and execution checks.

1. Choose the oldest supported Linux baseline that also supports the pinned Python/Qt build. Record compiler, glibc, OpenSSL and Qt versions. The baseline sets compatibility limits; an AppImage is not a claim that every Linux distro is supported. [AppImage compatibility concepts](https://docs.appimage.org/introduction/concepts.html).
2. Use the pinned build-only `Nuitka==4.1.1` in standalone mode with its PySide6 plugin to create a directory payload. This is the direct deployment path documented by Qt, avoids deployment tools installing unpinned packages during the build, and creates a self-contained executable directory rather than an AppImage. [Qt deployment guidance](https://doc.qt.io/qtforpython-6/deployment/deployment-nuitka.html).
3. Stage the payload in `work/linux/<architecture>/appimage/GlassTV.AppDir`. Add a relocatable `AppRun`, one desktop entry, the app icon and `.DirIcon`. Put approved resources beneath the AppDir and resolve them relative to the bundle. Generate Linux icon sizes directly from the existing master. Validate the desktop entry. [AppDir specification](https://docs.appimage.org/reference/appdir.html).
4. Inspect the payload's ELF dependencies and bundle required Python/Qt libraries and plugins, including the tested X11/Wayland and TLS dependencies. Do not copy Mac executables, private credentials, signing files, entire caches or the whole source tree. Qt runtime and platform-plugin deployment need explicit checks. [Qt Linux deployment](https://doc.qt.io/qt-6/linux-deployment.html).
5. Run a pinned, checksum-verified `appimagetool` on the AppDir and write the candidate to `builds/linux/`. Record the artifact hash, tool versions and dependency/asset inventory. The tool turns an AppDir into an AppImage. [AppImage manual packaging](https://docs.appimage.org/packaging-guide/manual.html).
6. Test normal execution plus the selected runtime's extraction fallback where FUSE is unavailable. Use a real desktop session for visual and accessibility review; a headless container alone cannot establish that. Keep release artifacts under `distribution/linux/` only after acceptance and the existing release process. [AppImage FUSE guidance](https://docs.appimage.org/user-guide/troubleshooting/fuse.html).

The bundle size, supported distros, CPU architectures, build duration, secret-service coverage, multimedia codec support and performance have not been measured. No publication, signing or update-channel policy is established here.

## Shared background and visual assets

The bundled default TV background is being handled separately. Linux should consume the final canonical asset path from that work and display it only where the product calls for a TV/media preview or receiver background. Keep provenance and asset identity stable. Do not add an application-shell wallpaper, copy the Mac resource tree wholesale or duplicate every resolution. Existing Central SVG exports are the Linux UI icon source; keep private licensing material outside the build.

## Acceptance checks

| Gate | Required evidence |
| --- | --- |
| Core/discovery | Unit checks cover valid/invalid IPv4 input, grouped fingerprint formatting, connection-state transitions and XDG path isolation. A controlled local TLS test confirms discovery sends no HTTP credentials or pairing request, times out cleanly and reports the certificate's exact DER hash. No live TV mutation is needed. |
| Pinned transport | A changed certificate, redirects, malformed responses and protocol-version mismatch fail closed. A local server verifies that pin mismatch sends no code/token/body. Test cancellation and address/profile changes so old work cannot update a new session. |
| Pairing/status | Pair only against the approved test receiver. Secure persistence and restart work; storage failure is visible; tokens/codes never enter logs. Saved credentials alone do not produce a connected state. Manual Disconnect prevents automatic reconnect. |
| Linux GUI | Inspect keyboard traversal, accessible labels, system dialogs, sentence case, spacing, empty/error/disabled states, scaling and narrow-window behavior under the selected desktop environments. Show only real receiver data. |
| AppImage | Start with a clean user profile on the oldest supported Linux target and a newer target, under selected X11 and Wayland sessions. Check relocatability, icon/desktop entry, required plugins, offline launch, FUSE fallback, native file dialogs and no dependency on the developer's Python installation. |
| Media/settings | Verify limits, streamed transfer, atomic failure behavior, stale revisions, offline drafts and preservation of unrelated TV settings. Originals and existing local libraries remain intact. |
| Isolation | Changes stay in Linux source/build paths and approved shared assets. Existing Mac and Android build scripts, app identities, saved pairing and website remain untouched by Linux work. Inspect intended public changes before any separate `release-source/` port. |

## Unresolved decisions and worker handoff

The coordinator has selected Ubuntu 24.04 x86_64 as the initial CI target and approved PySide6-Essentials for this discovery window. The eventual secure credential-storage adapter remains unresolved. Concurrent Mac/Linux pairing is a separate architecture escalation if the user requires it; otherwise retain the existing single-companion behavior with clear pairing consequences.

Next bounded worker task: execute the pinned unit, offscreen Qt smoke, AppImage build and extraction-fallback checks on Ubuntu 24.04 x86_64, then inspect the actual window in a Linux desktop session. Do not call `/pair`, access Keychain, write TV settings, build Android, change the Mac app, or claim an AppImage exists before those checks pass.
