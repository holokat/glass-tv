# Glass TV 1.0.1 Android release candidate

Package ID: `xyz.glasstv.launcher` • Version code: `2` • Android TV 8.0 or later

This is a signed testing prerelease for direct installation. The AAB has not been submitted to Google Play.

| File | Purpose |
| --- | --- |
| `Glass-TV-1.0.1.aab` | Signed Google Play upload bundle |
| `Glass-TV-1.0.1-universal.apk` | Signed package for direct installation |
| `release.json` | Version, package ID, hashes and certificate fingerprint |
| `SHA256SUMS` | SHA-256 checksums for the APK and AAB |
| `publiccert.pem` | Public signing certificate, safe to share |
| `LICENSE` and `LICENSE-SCOPE.md` | MIT license for owned code; third-party exclusions |
| `install-tv.py` | Check a selected TV; install only with `--install` |
| `INSTALL-WITH-AN-AGENT.md` | Manual and agent-assisted installation instructions |
| `PLAY-SUBMISSION.md` | Outstanding submission requirements |

Package validation passed: AAB structure, package/version/SDK metadata, APK signatures and alignment, matching signing certificate, artifact checksums, and exclusion of source provenance metadata containing local paths. The signed candidate fixes dynamic drawable lookup when the public application ID differs from the compiled resource namespace. It also adds a persistent, TV-remote-accessible Start/Stop control for the companion receiver; stopping it preserves pairing and media data while preventing boot or package-replacement restart.

The signed 1.0.1 APK was tested on a Sony BRAVIA running Android API 31: upgrade, icon rendering, Radio player and favorites, and receiver Start/Stop persistence on relaunch. See `DEVICE-TEST.md` for evidence and untested paths. Android 14+ testing remains outstanding. Foreground-service policy, Data safety disclosures, developer-account setup, signing backup, release-device testing, and third-party distribution rights remain open submission gates. The privacy policy is published at `https://glasstv.xyz/privacy` and was verified over HTTPS on 21 October 2026.

The release key remains private and is not included. Preserve its backup for future updates. The Mac and Linux companion apps are separate products and are not changed by this Android kit.
