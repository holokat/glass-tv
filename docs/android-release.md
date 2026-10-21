# Android release packaging

`build-android-release.sh` creates the local 1.0.0 Android release artifacts:

- `distribution/android/1.0.0/Glass-TV-1.0.0.aab`
- `distribution/android/1.0.0/Glass-TV-1.0.0-universal.apk`
- `distribution/android/1.0.0/publiccert.pem`
- `distribution/android/1.0.0/SHA256SUMS`
- `distribution/android/1.0.0/release.json`

The AAB is the Play upload artifact. The APK is generated from that signed AAB
with bundletool's universal mode for local verification. It is larger than a
device-specific APK and is not a device-size suitability check.

The build is fixed to application version `1.0.0` (version code `1`), minimum
SDK `26`, target SDK `34`, and compile SDK `36`. Google Play's target API
requirements list API 34 as the Android TV and Android XR exception for new
apps and updates from 31 August 2026. See [Google Play target API
requirements](https://developer.android.com/google/play/requirements/target-sdk).

The script keeps the Java and generated resource namespace as
`local.glasstv.launcher`, while `aapt2 --rename-manifest-package` supplies the
public `GLASSTV_RELEASE_APPLICATION_ID`. Release manifest components must be
fully qualified, because relative component names would otherwise resolve under
the public application ID.

Before linking resources, the script stages Android assets under its release
work directory. It excludes `README.md`, `provenance.json`, and
`atlas-provenance.json` files, which are source-audit metadata rather than
runtime assets. It rejects staged JSON that contains local user or temporary
paths, private-network addresses, or private-key markers. The source asset tree
is never modified.

## Prerequisites

Use JDK 17, Android platform 36, and Android build-tools 36.0.0. The script
expects those in the existing command-line-tools installation unless
`ANDROID_HOME` or `JAVA_HOME` is supplied. It verifies the project-local
`work/android-release/tools/bundletool-all-1.18.3.jar` before use against this
SHA-256:

`a099cfa1543f55593bc2ed16a70a7c67fe54b1747bb7301f37fdfd6d91028e29`

The JAR comes from the official [google/bundletool 1.18.3
release](https://github.com/google/bundletool/releases/tag/1.18.3). Android's
[bundletool documentation](https://developer.android.com/tools/bundletool)
describes bundle validation and universal APK sets. Android documents that AABs
must be signed with `jarsigner`, while generated APKs are verified with
`apksigner`.

The script also uses the official Google Maven AAPT2 artifact, rather than the
SDK build-tools copy, for proto resource compilation, linking, bundletool APK
generation, and manifest inspection. This follows Android's command-line
[bundle build documentation](https://developer.android.com/build/building-cmdline),
which warns that the SDK build-tools AAPT2 does not support bundletool. The
pinned universal macOS artifact is `aapt2-9.4.1-15978811-osx.jar` with SHA-256
`eb93ce9d0fa121333f12395b3ab30822c5cdb9155737f20d342fe71809757059`.

Create a dedicated release signing identity outside tracked source. Supply it
only through the explicit environment variables in
[`android-release.env.example`](../android-release.env.example). The password
variables point to files. They are passed to `jarsigner`, `bundletool`, and
`apksigner` through each tool's `file:` option, so the script never reads or
prints the passwords. It does not create, discover, or reuse the development
key from `build-android.sh`.

## Run and verify

First check that all explicit inputs and tools are present:

```sh
source /private/path/to/android-release.env
./build-android-release.sh --check
```

After confirming the release identity and disk budget, build locally:

```sh
./build-android-release.sh
```

The script validates the signed AAB with bundletool, verifies its manifest
values, regenerates the universal APK from the AAB, verifies the APK signature
with `apksigner`, checks its ZIP alignment, verifies its package/version/SDK
values with `aapt2`, and writes SHA-256 checksums. Build evidence remains in
`work/android-release/build-<UTC timestamp>/`; no upload or device installation
is part of this script. `release.json` records the candidate status, application
identity, version and SDK values, artifact names and checksums, and the APK
signer fingerprint. It also records the source TV-banner SHA-256 so the package
can be traced to its bundled release graphic. The script exports the matching
public PEM certificate as `publiccert.pem`. Neither file claims a public URL
because the artifacts have not been published.
