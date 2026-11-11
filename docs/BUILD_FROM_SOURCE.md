# Build from source

This guide explains how to build Feurstagram yourself. Building from source lets
you inspect the patches and avoid trusting a prebuilt APK.

## Requirements

- JDK 21 and the Android SDK (`ANDROID_HOME` set, build-tools installed)
- A GitHub token with the `read:packages` scope in `~/.gradle/gradle.properties`
  (used to fetch the patcher dependency):
  ```properties
  gpr.user=<your-github-username>
  gpr.key=<token-with-read:packages>
  ```
- A supported Instagram **complete bundle** (`.apkm`, arm64-v8a, from a trusted source
  such as APKMirror) or a standalone APK that has no split requirements

## Steps

1. Clone the repository.
   ```sh
   git clone https://github.com/jean-voila/Feurstagram.git
   cd Feurstagram
   ```

2. Build and apply the patches to your Instagram bundle or APK.
   ```sh
   ./build.sh instagram.apkm
   ```
   Add `--clone` to install alongside a stock Instagram, and `--install` to push
   to a connected device:
   ```sh
   ./build.sh instagram.apkm --clone --install
   ```

   A single `.apk` is accepted only when it is complete. A base APK extracted from a
   split bundle declares `requiredSplitTypes` and is rejected: patching it produces
   `INSTALL_FAILED_MISSING_SPLIT`. Supply the original `.apkm/.apks` bundle instead;
   `build.sh` merges it with APKEditor V1.4.9 before patching.

   The build validates the input, the merged APK, and the final signed APK. See
   [`HMX_INSTAPLUS_UNIVERSAL_APK_PACKAGING.md`](HMX_INSTAPLUS_UNIVERSAL_APK_PACKAGING.md)
   for the gates and the CI secrets.

3. Install the signed result (if you did not pass `--install`).
   ```sh
   adb install -r feurstagram.apk
   ```

## Signing

The bundle is signed during the build. Set `FEURSTAGRAM_KEYSTORE_PASS` (and
optionally `FEURSTAGRAM_KEY_PASS`) to sign with `feurstagram.keystore`;
otherwise a throwaway keystore is generated. Do not commit your keystore, and
keep the same one to reinstall without losing app data.

## Notes

- Only use APKs from trusted sources.
- Patches are fingerprint-based, so a new Instagram version usually only needs a
  rebuild.
- If the build fails, check open issues or create a bug report.
