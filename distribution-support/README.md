# Glass TV 1.0.0 Android release candidate

Package ID: `xyz.glasstv.launcher` • Version code: `1` • Android TV 8.0 or later

This folder is a local distribution kit. Nothing in it has been uploaded to Google Play or a public APK repository.

| File | Purpose |
| --- | --- |
| `Glass-TV-1.0.0.aab` | Signed Google Play upload bundle |
| `Glass-TV-1.0.0-universal.apk` | Signed package for direct installation |
| `release.json` | Version, package ID, hashes and certificate fingerprint |
| `SHA256SUMS` | SHA-256 checksums for the APK and AAB |
| `publiccert.pem` | Public signing certificate, safe to share |
| `LICENSE` and `LICENSE-SCOPE.md` | MIT license for owned code; third-party exclusions |
| `install-tv.py` | Check a selected TV; install only with `--install` |
| `INSTALL-WITH-AN-AGENT.md` | Manual and agent-assisted installation instructions |
| `PLAY-SUBMISSION.md` | Outstanding submission requirements |
| `store-assets/` | TV banner, Play icon and feature graphic |

Package validation passed: AAB structure, package/version/SDK metadata, APK signatures and alignment, matching signing certificate, artifact checksums, and exclusion of source provenance metadata containing local paths. The signed candidate has not yet been installed on a TV. Background-service policy, privacy disclosures, release-device testing and third-party distribution rights remain submission gates.

The release key remains private and is not included. Preserve its backup for future updates. The Mac and Linux companion apps are separate products and are not changed by this Android kit.
