# Install Glass TV on your Android TV

This kit contains a signed testing prerelease. It has not been submitted to Google Play. Use the APK for direct installation. The AAB is for Google Play and cannot be installed directly.

Requires Android TV 8.0 (API 26) or later, Python 3 on your computer, and Google's [Android SDK platform-tools](https://developer.android.com/tools/releases/platform-tools). Use the APK, `release.json`, and installer from the same trusted release folder. The checksum detects mismatched or damaged files; a checksum supplied alongside an untrusted download does not establish publisher identity. Android verifies the APK signature during installation.

## Instructions to give your agent

> Help me install the Glass TV APK from this release folder onto the Android TV I select. First inspect `release.json` and run `install-tv.py` in its default read-only mode. Ask me to identify the TV if the device is ambiguous. Use only its exact ADB serial; do not scan my network. If debugging or pairing is needed, guide me through approving it on the TV. After checks succeed, install with `--install` on that selected device. Launch it only when I am ready. Do not uninstall existing apps, clear app data, disable Play Protect, change the default launcher, or alter other devices. If an existing installation has a different signing certificate, stop and explain the conflict instead of uninstalling it.

## Commands

Enable developer options and USB or wireless debugging on the TV using its settings. Approve the computer on the TV. For wireless debugging, use only the pairing/connect address and ports shown on that TV. `adb pair` prompts for the displayed pairing code. Older TVs may expose a different vendor-specific debugging flow.

List already connected devices:

```sh
adb devices -l
```

Replace `YOUR_TV_SERIAL` below with the selected device's exact serial. Run from the release folder:

```sh
python3 install-tv.py --serial YOUR_TV_SERIAL
python3 install-tv.py --serial YOUR_TV_SERIAL --install
```

Add `--launch` to the install command to open Glass TV immediately. Supply `--adb /absolute/path/to/adb` if platform-tools is not on your PATH. The helper uses `adb install -r` to preserve data on a compatible update. It never uses downgrade, uninstall, or permission-grant flags.

## Existing development installations

The public package is `xyz.glasstv.launcher`. The development package `local.glasstv.launcher` is a separate app. Installing this package does not transfer development preferences or pairing. Two companion receivers may compete for the same port; close or stop the development app before pairing the public app, with the owner's permission. Do not delete the development app or its data automatically.

Choosing Glass TV as the default home app is a separate, optional action in TV settings where supported. Installation alone does not change the default launcher. Disable debugging afterward if you no longer need it.
