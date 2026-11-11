# HMX Insta+ — OPPO A38 (CPH2579) Device Compatibility Report

> **SUPERSEDED — do not act on the verdict in this document.**
> The device-side ADB result for the same artifact was
> `INSTALL_FAILED_MISSING_SPLIT: Missing split for com.instagram.android`.
> The APK is **not** a standalone installable package: it declares
> `requiredSplitTypes="base__density"` and ships no split APKs, and ColorOS 15 enforces that metadata
> even without the `isSplitRequired` attribute. The "compatible / device-side rejection" conclusion below,
> and especially §1's split row and §3, are wrong. See
> [`HMX_INSTAPLUS_SPLIT_INSTALL_INVESTIGATION.md`](./HMX_INSTAPLUS_SPLIT_INSTALL_INVESTIGATION.md)
> for the root cause, evidence, and recommended fix.

- **Date:** 2026-11-11
- **Artifact:** `debug-test-42998995696-1` (release id `441504347`, asset id `734995951`)
- **File:** 260,967,921 bytes
- **SHA-256:** `68446312f639ca6d873e3dee5fdcc5439f9b0cb3e969153c308cbbb5444c2dd2`
- **Device:** OPPO A38 4G, model `CPH2579` (device `OP5759L1`), MediaTek Helio G85 (`MT6769V/CZ`), Mali-G52 MC2, 4 GB RAM, 720×1612
- **Device OS at test time:** ColorOS 15 (Android 15, API 35)
- **Reported failure:** "App not installed as app isn't compatible with your phone."
- **Install path:** Instagram uninstalled first, APK tapped in the phone's file manager

Method: the APK was downloaded read-only from the published release and inspected with `aapt`, `apksigner`,
`zipalign`, `unzip`, `file`, plus source-level comparison against the stock Instagram input APK
(`Apk-input-original`, SHA-256 `b334feef23722f0364a80c2c9e99af48e0c460cc652213d3f1a919f2007e5136`).
No rebuild, no republish, no patch/minSdk/ABI changes were made.

---

## 1. Verdict — SUPERSEDED (see banner)

**The verdict below is wrong.** The device-side ADB install returned
`INSTALL_FAILED_MISSING_SPLIT: Missing split for com.instagram.android`, so the artifact is not a
standalone installable package. The split reconciliation did run on ColorOS 15; the manifest's
`requiredSplitTypes="base__density"` is enforced even though `isSplitRequired` is absent. The
compatibility gates listed here are all genuinely satisfied, but they are not the gate that fails.

**Current verdict: the build pipeline emitted a bundle-derived base APK that requires a
`base__density` split which was never shipped. Root cause and fix:
`HMX_INSTAPLUS_SPLIT_INSTALL_INVESTIGATION.md`.**

The original (superseded) reasoning is preserved below for the record.

| Gate | APK | CPH2579 | Result |
| --- | --- | --- | --- |
| ABI / native libs | `lib/arm64-v8a/*` only (15 libs, ELF aarch64) | `ro.product.cpu.abi=arm64-v8a`, `abilist=arm64-v8a,armeabi-v7a,armeabi`, `ro.zygote=zygote64_32` | pass |
| `minSdkVersion` | 28 | API 33 at launch, API 35 on ColorOS 15 | pass |
| `maxSdkVersion` | absent | — | pass |
| Required features | `faketouch` + OpenGL ES 2.0 only | both present on any Android phone; G85 supports ES 3.2 | pass |
| Required shared libs | all 6 `uses-library` and 8 `uses-native-library` are `required="false"` | — | pass |
| Split requirement | `requiredSplitTypes="base__density"`, **`isSplitRequired` absent** | ColorOS 15 enforces `requiredSplitTypes` even without `isSplitRequired` | **FAIL — root cause** |
| Deprecated SDK block | `targetSdkVersion=37` (≥ 24) | Android 15 blocks only `targetSdk < 24` | pass |
| APK integrity | `zipalign -c -p 4` pass, `unzip -t` pass, no duplicate/unsafe entries, v2 signature verifies | — | pass |

Therefore **the dialog is not evidence of an APK compatibility defect**, and the cause is on the device side:
ColorOS 15 rejected the install and rendered the rejection with the same generic compatibility string it uses
for policy/verification failures.

Within AOSP, the only status that can still produce this dialog after the eliminations above is
`INSTALL_FAILED_USER_RESTRICTED` (installation restricted for the user / by policy). ColorOS also surfaces
its own install-verification and retained-system-package (signature state) rejections with this text, so the
remaining candidates are, in order:

1. **Residual system/preload copy of `com.instagram.android`.** "Uninstalling" a preload on ColorOS removes it
   for the user but leaves the system APK and its signing record behind. A clean install of a differently
   signed APK then hits `INSTALL_FAILED_UPDATE_INCOMPATIBLE` ("signatures do not match"), which ColorOS
   displays with its generic compatibility message. (Whether Instagram is preloaded on this unit must be
   confirmed — see §5.)
2. **ColorOS installation verification / security policy** blocking an unknown-certificate sideload.
3. **Incomplete/corrupt local copy** of the 249 MiB download (normally `INSTALL_PARSE_FAILED_*`, but ColorOS
   can reuse the generic text). The published asset itself is intact.

**Do not change `minSdkVersion`, do not add 32-bit libraries, and do not touch the Instagram patches on the
basis of this report.** None of them is implicated.

---

## 2. What the message means (AOSP mapping)

`PackageManager.installStatusToPublicStatus()` in `core/java/android/content/pm/PackageManager.java` maps
legacy install codes to the public status that drives the dialog. Exactly these nine codes map to
`STATUS_FAILURE_INCOMPATIBLE`, whose string is
`install_failed_incompatible` → *"App not installed as app isn't compatible with your phone."*
(`packages/PackageInstaller/res/values/strings.xml`):

| Legacy code | Typical meaning | Excluded by this investigation |
| --- | --- | --- |
| `INSTALL_FAILED_NO_MATCHING_ABIS` | no native code for any device ABI | yes — APK is arm64, device supports arm64 |
| `INSTALL_FAILED_CPU_ABI_INCOMPATIBLE` | same class as above / ABI override | yes — no ABI override, clean install |
| `INSTALL_FAILED_OLDER_SDK` | app's `minSdkVersion` too high | yes — 28 ≤ 35 |
| `INSTALL_FAILED_NEWER_SDK` | app declares `maxSdkVersion` below the device | yes — no `maxSdkVersion` |
| `INSTALL_FAILED_MISSING_FEATURE` | required `uses-feature` absent | yes — all optional except faketouch/GLES 2.0 |
| `INSTALL_FAILED_MISSING_SHARED_LIBRARY` | required `uses-library`/`uses-native-library` absent | yes — all are `required="false"` |
| `INSTALL_FAILED_MISSING_SPLIT` | required split not supplied | **no — this is the actual failure; the APK declares `base__density` and ships no split** |
| `INSTALL_FAILED_DEPRECATED_SDK_VERSION` | `targetSdkVersion < 24` on Android 15 | yes — target 37 |
| `INSTALL_FAILED_USER_RESTRICTED` | installs disallowed for this user / by policy | **not excludable without device-side status** |

Everything else (signature conflict, invalid APK, storage) maps to *other* dialogs — conflict, invalid,
storage — under stock AOSP. ColorOS does not guarantee that separation.

---

## 3. Why `requiredSplitTypes="base__density"` is not the cause — SUPERSEDED (this section is wrong)

> **Correction:** this section is retained only for the record. The conclusion is false.
>
> AOSP's full-install gate is indeed `baseApk.isSplitRequired()` (attribute only), and this APK does not
> set that attribute. But the same Android 15 release defines
> `PackageLite.mSplitRequired = (baseApk.isSplitRequired() || hasAnyRequiredSplitTypes())`
> (`core/java/android/content/pm/parsing/PackageLite.java:165`), i.e. a non-empty `requiredSplitTypes`
> *does* imply "split required", and the inherited-install path already uses that broader test
> (`PackageInstallerSession.java:3797`). ColorOS 15 applies that broader test on the full-install path as
> well; the device returned `INSTALL_FAILED_MISSING_SPLIT`.
>
> The claim below that the check "never runs" is contradicted by the device. See
> `HMX_INSTAPLUS_SPLIT_INSTALL_INVESTIGATION.md` §4.

Android 15 (`android-15.0.0_r1`) gates the only enforcement point on the base APK's `isSplitRequired` flag:

```java
// services/core/java/com/android/server/pm/PackageInstallerSession.java:3652
if (baseApk.isSplitRequired() && (stagedSplits.size() <= 1
        || !stagedSplitTypes.containsAll(requiredSplitTypes))) {
    throw new PackageManagerException(INSTALL_FAILED_MISSING_SPLIT,
            "Missing split for " + mPackageName);
}
```

and `isSplitRequired` is read **only** from the `android:isSplitRequired` manifest attribute, defaulting to
`false` (`core/java/android/content/pm/parsing/ApkLiteParseUtils.java:435`). It is never derived from
`requiredSplitTypes`.

The published APK and the stock Instagram input both contain `requiredSplitTypes="base__density"` and
`splitTypes=""` and **neither declares `isSplitRequired`**, so the check above never runs. Verified against
the APK's own manifest and the stock base manifest. If it had been the cause, the untouched stock APK would
fail identically — which it does not when installed by Play (Play supplies the density splits).

---

## 4. APK evidence

| Property | Value |
| --- | --- |
| Package / version | `com.instagram.android`, 454.0.0.54.77, versionCode `386014758` |
| `minSdk` / `targetSdk` / `compileSdk` | 28 / 37 / 37 (`platformBuildVersionName="17"`) |
| `installLocation` / `extractNativeLibs` | `auto` (0) / `true` |
| Split metadata | `requiredSplitTypes="base__density"`, `splitTypes=""`, `isSplitRequired` absent |
| Entries | 11,115; `classes.dex` … `classes32.dex` (all DEX 035), `resources.arsc` STORED |
| Native code | 15 `.so`, `lib/arm64-v8a/` only; ELF 64-bit ARM aarch64, built for Android 28 (NDK r26b) |
| Native libs vs stock | identical file set |
| Required features | `android.hardware.faketouch` (implied), `glEsVersion 0x00020000`; all others `required=false` |
| `uses-library` / `uses-native-library` | 6 / 8 entries, **all `required=false`** (MediaTek Neuron libs are optional) |
| Signing | v2 = true; v1 / v3 / v3.1 / v4 = false; `CN=Morphe`, RSA 4096, cert SHA-256 `7327dd86f71482175e9c95d58f1c6e350535347f581926eead5685f5362cfc80` |
| Alignment / integrity | `zipalign -c -v 4` pass, `zipalign -c -v -p 4` pass, `unzip -t` pass, 0 duplicate entries, 0 unsafe paths |
| Only badging delta vs stock | adds `android.permission.REQUEST_INSTALL_PACKAGES` (Morphe self-update); no installability impact |

The stock input (`b334feef…5136`) has the same `minSdk`, `targetSdk`, split flags and arm64-only native
libraries. The patched build therefore differs from an installable Play-served APK only in its signing
identity, its patched dex, and that one added permission.

---

## 5. Device evidence

From a public OPPO A38 `CPH2579` / `OP5759L1` build.prop archive (ColorOS 13.1, Android 13,
`TP1A.220905.001`, security patch 2023-08-05), i.e. the firmware this model shipped with:

```
ro.product.model=CPH2579
ro.product.name=CPH2579
ro.product.device=OP5759L1
ro.product.cpu.abi=arm64-v8a
ro.product.cpu.abilist=arm64-v8a,armeabi-v7a,armeabi
ro.product.cpu.abilist64=arm64-v8a
ro.config.zygote... / ro.zygote=zygote64_32
ro.build.version.sdk=33
ro.product.first_api_level=33
```

So the model supports 64-bit apps in practice, not just on paper, and every API level it runs satisfies
`minSdkVersion=28`. This eliminates the ABI and SDK hypotheses independently of the APK.

---

## 6. Confirmation procedure (no rebuild needed)

These commands produce the exact failing status code. Everything here is read-only.

```bash
# 1. Exact installer result (this is the ground truth):
adb install -r --user 0 HMX_INSTAPLUS_debug-test-42998995696-1.apk

# 2. Is there a residual system/preload copy of Instagram?
adb shell pm list packages -u -f | grep -i instagram
adb shell pm path com.instagram.android
adb shell dumpsys package com.instagram.android | grep -iE "signatures|system|preload|hidden|stopped"

# 3. Device profile and restrictions:
adb shell getprop ro.product.cpu.abilist
adb shell getprop ro.build.version.sdk
adb shell dumpsys user | grep -iE "restricted|DISALLOW_INSTALL_APPS"

# 4. Installer-side reason text:
adb logcat -d -s PackageManager:* PackageInstaller:* | tail -80
```

Interpretation:

| `adb install` result | Cause | Action |
| --- | --- | --- |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` / "signatures do not match" | residual preload/system Instagram or another copy signed by Meta | `adb shell pm uninstall --user 0 com.instagram.android` (if allowed) or disable the system app, reboot, retry |
| `INSTALL_FAILED_USER_RESTRICTED` | installs restricted by user/policy (ColorOS verification, kids space, secondary user, MDM) | disable ColorOS installation verification / switch to owner user / clear policy, retry |
| `INSTALL_FAILED_NO_MATCHING_ABIS` | device userspace is 32-bit (contradicts §5) | only then build from the `armeabi-v7a` Instagram variant |
| `INSTALL_FAILED_INSUFFICIENT_STORAGE` | needs ~1 GB free for the APK + native extraction + dexopt | free space, retry |
| `INSTALL_PARSE_FAILED_*` | local file damaged (download incomplete) | re-download and compare SHA-256 with the published digest |
| install succeeds over adb but not from the file manager | OEM installer UI/verification policy | install via adb, or disable ColorOS verification for that source |

**Device-only test that isolates the APK from the phone:** install the untouched stock Instagram
454.0.0.54.77 APK (same package, Meta signature) on the phone. If it also shows the same dialog, the block
is the phone/OS; if it installs, the block is tied to this build's signing/verification path only — the
APK content itself is still proven compatible by §4.

---

## 7. Ruled out (do not "fix" these)

| Suspect | Status | Reason |
| --- | --- | --- |
| `minSdkVersion` too high | ruled out | 28 ≤ 33/35 |
| `targetSdkVersion=37` | ruled out | no upper-bound install check; the only SDK block on Android 15 is `targetSdk < 24` |
| ABI / native libs | ruled out | APK arm64-only, device arm64-primary; stock input identical |
| Missing 32-bit libs | not a defect | Facebook ships separate arm-v7a and arm64 builds; the device is arm64 |
| Split metadata | **root cause, not ruled out** | `MISSING_SPLIT` is enforced via `requiredSplitTypes="base__density"` on ColorOS 15 even with `isSplitRequired` absent; no split APKs are shipped |
| Required features | ruled out | only faketouch + GLES 2.0 are required, both present |
| Required shared/native libs | ruled out | all 14 declarations are `required="false"` |
| APK corruption / alignment | ruled out | SHA matches published, v2 verifies, `unzip -t` and `zipalign` pass |
| Signature conflict from stock | user states Instagram was uninstalled | still check for a residual preload copy (§6) |

---

## 8. Bottom line — SUPERSEDED

The text below is the original, incorrect conclusion and is kept only for the record.

> The published APK is a complete, correctly aligned, v2-signed, arm64 universal APK with `minSdk 28` and the
> same installability attributes as the stock Instagram build it was patched from. On a `CPH2579` running
> ColorOS 15 it satisfies every Android 15 compatibility gate. The "isn't compatible with your phone" dialog
> therefore reflects a **ColorOS-side install rejection** (user/policy restriction, retained system-package
> signature state, or installer verification), not a defect in the APK's compatibility declarations.
> Changing `minSdkVersion`, ABIs, or Instagram patches would not address it; the `adb` result in §6 names the
> exact code and the fix.

**Corrected bottom line:** the artifact is a bundle-derived base APK that declares
`requiredSplitTypes="base__density"` and ships no split APKs. The `adb` result is
`INSTALL_FAILED_MISSING_SPLIT`, and that is the expected behavior for this package on Android 15. It is not
a universal APK and must not be published as one. See
`HMX_INSTAPLUS_SPLIT_INSTALL_INVESTIGATION.md`.

No build, release, or source change was made during this diagnosis.
