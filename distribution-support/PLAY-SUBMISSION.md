# Glass TV Android submission checklist

Prepared 21 October 2026. These are release preparation notes, not a privacy policy or a claim of store approval.

## Package and publisher

- Publisher supplied by the owner: **21 Media LLC**.
- App ID: `xyz.glasstv.launcher`; version `1.0.1`; version code `2`.
- Minimum Android API 26, target API 34, compile API 36.
- Signed AAB for Google Play; signed universal APK for direct installation.
- Android TV banner is bundled; touchscreen hardware is not required.
- Public support/privacy email: **admin@21media.to**.
- MIT license added for code owned by **21 Media LLC**, copyright 2026. See `LICENSE` and `LICENSE-SCOPE.md`; third-party redistribution review remains outstanding.

API 34 currently meets the [Android TV target API exception](https://developer.android.com/google/play/requirements/target-sdk). Recheck requirements at actual submission time.

## Required before production submission

1. **Background receiver behavior.** The companion receiver uses a sticky `dataSync` foreground service and may run for a long time. Version 1.0.1 adds a persistent, TV-remote-accessible Start/Stop control and prevents boot or package-replacement restart while it is stopped. Review the remaining long-lived use case, notification, declaration and demonstration video against [Google Play foreground service requirements](https://support.google.com/googleplay/android-developer/answer/13392821). A signed package does not resolve this policy issue. Do not relabel the service type merely to bypass limits.
2. **Privacy and Data safety.** Complete Data safety using the verified app behavior and published privacy policy. Product inspection found local pairing/device/media traffic, Open-Meteo city/coordinate requests, Radio Browser station requests and third-party station streams. The app points to `https://glasstv.xyz/privacy`, published and verified over HTTPS on 21 October 2026. The policy distinguishes the app from Cloudflare website analytics. Do not submit a blanket “no data collected” statement without reviewing those flows and the form's definitions. See [Data safety guidance](https://support.google.com/googleplay/android-developer/answer/10787469).
3. **Release-device testing.** The signed public package passed the bounded Sony API 31 checks recorded in DEVICE-TEST.md. Still verify clean install, pairing, default-launcher selection, full media streaming, persistence across a physical TV reboot, background operation and service notifications. Android 14+ has not yet been tested. Earlier development-build testing does not verify this package's changed target SDK and identity. Review [TV quality criteria](https://developer.android.com/docs/quality-guidelines/tv-app-quality).
4. **Wi-Fi feature behavior.** The exposed Wi-Fi entry point opens native Android Settings. The unused custom Wi-Fi panel is not attached, and this candidate does not request Wi-Fi or location permissions for scanning. Do not add location permissions unless a supported in-app Wi-Fi feature requires them and its permission/availability flow is implemented.
5. **Signing continuity.** Securely back up the release keystore and its password outside the repository and distribution folder. That backup has not been confirmed. Decide Play App Signing enrollment before uploading. If Play signs with a different key than the direct APK, users cannot update between channels in place. Use Google's supported key import process if retaining the same signing identity across channels. Never place private signing material in the installation kit.
6. **Store listing.** No Google Play developer account is set up. Before production submission, confirm developer account/identity, privacy URL, content rating, ads declaration, app access instructions and applicable testing requirements in Play Console. The kit includes the feature graphic, icon, banner and four unedited TV screenshots from the signed candidate. Policy review remains required.
7. **Third-party rights and services.** Review redistribution rights and attribution for Central icons, service trademarks, bundled artwork and other assets. Confirm that the intended use is eligible for the Open-Meteo free tier before public distribution or commercial use. MIT for owned code does not relicense those materials.

## Other distribution channels

Version 1.0.1 is provided as a GitHub testing prerelease and has not been submitted to Google Play. The local kit includes checksums and agent installation instructions. Review [Android developer verification](https://developer.android.com/developer-verification/guides/google-play-console) for distribution outside Play.

F-Droid inclusion is a separate process. A public, reproducible source build and compatible licenses for all included components are required. The current private licensed icon dependency needs resolution; choosing MIT for owned code alone does not make this build F-Droid eligible. See the [F-Droid inclusion policy](https://f-droid.org/docs/Inclusion_Policy/).
