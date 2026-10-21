# Glass TV

[Website](https://glasstv.xyz) · [Download for Mac](https://github.com/holokat/glass-tv/releases/latest/download/Glass-TV.dmg) · [Releases](https://github.com/holokat/glass-tv/releases)

## Install

Download the signed, Apple-notarized DMG and drag Glass TV to Applications. Requires an Apple silicon Mac running macOS 13 or later.

The [Android TV testing release](https://github.com/holokat/glass-tv/releases/tag/android-v1.0.1-rc.1) includes a signed universal APK and installation instructions. Connecting and syncing requires Glass TV on a compatible TV on the same local network. The Android app has not been submitted to Google Play.

Movie inspection and video conversion require FFmpeg. If you already use Homebrew, install it with `brew install ffmpeg`. FFmpeg is not included in the DMG.

## Source

Glass TV is a macOS companion for a compatible local-network receiver. This repository contains the macOS, Linux and Android source, build scripts, artwork, project documentation, protocol definitions, and CC0 navigation sounds. No private signing material is included.

## macOS build

On macOS 13 or later on Apple silicon, run:

```sh
./mac/build-mac-app.sh
```

The script writes `build/Glass TV.app` by default. Set `GLASS_TV_OUTPUT_DIR` to choose another output directory. It signs ad hoc unless `GLASS_TV_SIGNING_IDENTITY` names a local signing identity. A public release requires a valid Developer ID signature, hardened runtime, secure timestamp, notarization, and stapling.

The build uses the included app artwork and shortcut marks. It requires locally generated Central interface-icon assets; see `CENTRAL_ICONS.md`. Movie inspection, preview-frame extraction, and compatibility conversion look for `ffprobe` and `ffmpeg` in bundled `Resources/Tools`, `/opt/homebrew/bin`, `/usr/local/bin`, and `PATH`. They are not bundled.

## Linux companion

The separate Qt companion is in [`linux/`](linux/README.md). This is an early discovery preview, with an AppImage build recipe under development. Pairing, media management and settings sync are not yet implemented. The native Mac app remains separate.

## Android source

The `android/` directory contains source and resources required for review. Signed testing APKs are attached to [Android releases](https://github.com/holokat/glass-tv/releases/tag/android-v1.0.1-rc.1). Build and submission notes are in [`distribution-support/`](distribution-support/README.md). Private signing material is never included.

## Privacy and release scope

No local user libraries, pairing state, credentials, keys, build caches, device artifacts, or absolute local filesystem paths are included. The protocol supports local-network communication with a compatible receiver.

Read the [privacy policy](https://glasstv.xyz/privacy). See `CENTRAL_ICONS.md` for the icon prerequisite and `PROTOCOL.md` for the communication protocol.

## Website

The static release website is in `website/`. Populate its licensed UI icons using `website/scripts/prepare-licensed-icons.sh` as described in `website/STYLEGUIDE.md`, then serve the folder locally. Publish the runtime HTML, CSS, JavaScript, station-source JSON, robots.txt, sitemap.xml, and assets. Exclude tests, scripts and internal documentation from the static upload. The public source excludes loose Central icon exports.

## License

Glass TV code owned by 21 Media LLC is available under the [MIT license](LICENSE). Third-party assets retain their own licenses; see [license scope](LICENSE-SCOPE.md).
