# Glass TV Android submission checklist

Prepared 21 October 2026. These are release preparation notes, not a privacy policy or a claim of store approval.

## Package and publisher

- Publisher supplied by the owner: **21 Media LLC**.
- App ID: `xyz.glasstv.launcher`; version `1.0.0`; version code `1`.
- Minimum Android API 26, target API 34, compile API 36.
- Signed AAB for Google Play; signed universal APK for direct installation.
- Android TV banner is bundled; touchscreen hardware is not required.
- Public support/privacy email: **admin@21media.to**.
- MIT license added for code owned by **21 Media LLC**, copyright 2026. See `LICENSE` and `LICENSE-SCOPE.md`; third-party redistribution review remains outstanding.

API 34 currently meets the [Android TV target API exception](https://developer.android.com/google/play/requirements/target-sdk). Recheck requirements at actual submission time.

## Required before production submission

1. **Background receiver behavior.** The companion receiver currently uses a sticky `dataSync` foreground service started at boot and may run indefinitely. Review its lifecycle, user controls, notification and declared use case against [Google Play foreground service requirements](https://support.google.com/googleplay/android-developer/answer/13392821). Prepare the required declaration and demonstration video. A signed package does not resolve this policy issue. Do not relabel the service type merely to bypass limits.
2. **Privacy and Data safety.** Confirm actual data handling and retention, then publish an accurate privacy policy and complete Data safety. Product inspection found local pairing/device/media traffic, Open-Meteo city/coordinate requests, Radio Browser station requests and third-party station streams. Do not submit a blanket “no data collected” statement without reviewing those flows and the form's definitions. See [Data safety guidance](https://support.google.com/googleplay/android-developer/answer/10787469).
3. **Release-device testing.** Validate this exact signed public package on Android TV: clean install, pairing, home/default-launcher behavior, remote navigation, radio, media, restart, service notifications, background operation and upgrade. Earlier development-build testing does not verify this package's changed target SDK and identity. Review [TV quality criteria](https://developer.android.com/docs/quality-guidelines/tv-app-quality).
4. **Wi-Fi feature behavior.** Code requests location permissions for Wi-Fi functionality, while the manifest currently does not declare them. Confirm which Wi-Fi features the app needs, and fix the relevant permission/availability flow before presenting those features as supported.
5. **Signing continuity.** Securely back up the release keystore and its password outside the repository and distribution folder. Decide Play App Signing enrollment before uploading. If Play signs with a different key than the direct APK, users cannot update between channels in place. Use Google's supported key import process if retaining the same signing identity across channels. Never place private signing material in the installation kit.
6. **Store listing.** Confirm developer account/identity, privacy URL, content rating, ads declaration, app access instructions and applicable testing requirements in Play Console. Capture current TV screenshots from the signed candidate. The supplied feature graphic, icon and banner are starting assets; screenshots and policy review are still required.
7. **Third-party rights.** Review redistribution rights and attribution for Central icons, service trademarks, bundled artwork and other assets. MIT for owned code does not relicense those materials.

## Other distribution channels

The signed APK is published as an [Android testing prerelease](https://github.com/holokat/glass-tv/releases/tag/android-v1.0.0-rc.1), with checksums and agent installation instructions. The AAB has not been uploaded to Google Play. Review [Android developer verification](https://developer.android.com/developer-verification/guides/google-play-console) for distribution outside Play.

F-Droid inclusion is a separate process. A public, reproducible source build and compatible licenses for all included components are required. The current private licensed icon dependency needs resolution; choosing MIT for owned code alone does not make this build F-Droid eligible. See the [F-Droid inclusion policy](https://f-droid.org/docs/Inclusion_Policy/).
