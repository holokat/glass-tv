# Android 1.0.1 candidate verification

Validated local candidate: Glass TV 1.0.1 (2), `xyz.glasstv.launcher`.

- AAB bundletool validation passed.
- APK v2/v3 signature and ZIP alignment passed.
- APK and AAB public certificate fingerprints match.
- Package ID, version, min SDK 26 and target SDK 34 verified.
- The public application ID is `xyz.glasstv.launcher`; the compiled drawable resource namespace remains `local.glasstv.launcher`. Dynamic drawable lookup uses that compiled namespace, and the release build checks it.
- Source provenance metadata containing local file paths was excluded from both artifacts. The staged text and JSON scan passed.
- No native shared libraries or signing keystores are in either archive.
- Independent SHA-256 verification passed for final artifacts.
- Installer fixture checks passed: read-only default, exact selected ADB serial, explicit install/update and launch, corrupt APK rejection before device access. No TV was changed by those checks.

The signed APK passed the documented Sony API 31 checks in `DEVICE-TEST.md`. Android 14+ and the remaining pairing/media/reboot cases have not been verified. Foreground-service review for the long-lived `dataSync` receiver, developer-account setup, verified signing backup, Data safety, Open-Meteo free-tier eligibility, and third-party distribution rights remain open gates. The privacy policy is published at `https://glasstv.xyz/privacy` and was verified over HTTPS on 21 October 2026.

Build evidence is retained privately under `work/android-release/build-20261021T101850Z`. The release signing key is intentionally absent from this kit.
