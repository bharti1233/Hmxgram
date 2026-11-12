# HMX Insta — baseline build plan (M0)

**Goal:** make the baseline reproducible and auditable without disturbing
`main`, the backup branch, the feature branch, any secret, or any private
release.

**Status:** plan only. No build was run in M0.

---

## 1. First decision: a rebuild is *not* needed for the baseline

The canonical baseline already exists and has been verified byte for byte:

| | |
| --- | --- |
| Release | `debug-test-43091674732-1` (private) |
| Asset | `hmx-insta-454.0.0.54.77-test.apk` |
| Size | 164,081,474 bytes |
| SHA-256 | `6de31b8eb9845ccd27cdd5ec6d140feaafbc943cc04c26481cde4594a7b0df65` |
| Source | `a5bace00e84094f06622ff8f47ae60990ded8e09` (`backup/hmx-insta-last-known-good-454`) |
| CI evidence | run `43091674732`, artifact `apk-build-report` (not expired) |
| Patch report | 14 applied, 0 failed |
| Signer | `CN=Morphe`, v2 |

For all comparison and recovery work, **use this artifact**. It is the
reference the M6 dex gate will compare against, and it is the APK to roll back
to on the device. Rebuilding it adds risk and proves nothing that the digest,
the build report and the source archive do not already prove.

A rebuild is only justified if you want to test the build pipeline itself, or
if the artifact is ever lost. Sections 3–5 describe that case.

---

## 2. What must never be rebuilt or replaced

* the two protected inputs (`Apk-input-original`, the `.apkm` bundle) — their
  digests and timestamps are recorded in `HMX_INSTA_M0_BASELINE_VERIFICATION.md`;
* the existing private debug releases — never overwrite an asset;
* `main`, `backup/hmx-insta-last-known-good-454`,
  `feature/hmx-downloader-ghost-mode`;
* any secret value. Only secret **names** are recorded.

---

## 3. If a rebuild is required — fixed inputs

| Item | Value |
| --- | --- |
| Source ref | `backup/hmx-insta-last-known-good-454` at `a5bace0` |
| Tree | `9e02ce3824586f01ed165686dcda08100c8495dc` |
| Input | complete `.apkm` bundle, `sha256:4245c41709b1f753e9f168961bcccdec750045e0cc3e690ac077bbc9656c380e` |
| Input kind | `apkm` (merge before patching) |
| Patcher | Morphe CLI, pinned by `MORPHE_CLI_URL` / `MORPHE_CLI_SHA256` |
| Merger | APKEditor V1.4.9, pinned by `APKEDITOR_URL` / `APKEDITOR_SHA256` |
| JDK | 21 |
| Android tools | `aapt`, `aapt2`, `apksigner`, `zipalign` from build-tools |
| Signing | **throwaway test key** (`allow_throwaway_signature=true`); v2 |
| Signature bypass | **false** |
| Clone build | **false** (package stays `com.instagram.android`) |
| Publish | **false** for a verification rebuild; publish only if explicitly approved |
| Install test | **false** on CI; manual on the OPPO A38 if a device run is intended |

Expected environment values (they are the baseline workflow's own values):

```sh
EXPECTED_INSTAGRAM_PACKAGE=com.instagram.android
EXPECTED_INSTAGRAM_VERSION=454.0.0.54.77
EXPECTED_INSTAGRAM_VERSION_CODE=386014712
EXPECTED_META_CERT_SHA256=3a10c50c18ba937506c2875e9047be74f8fd8b86fae2ff9feaa50a81fcb4c014
MIN_RES_ENTRIES=12000
# Record the baseline's weak gate exactly, then raise it for new builds:
MIN_APPLIED_PATCHES=14
EXPECTED_PATCHES='Force SDR display,Feed item filtering,Limit feed to following profiles,Network content blocking,Suggested accounts in the story tray,Quoicouview assets,Quoicouview mode,Restart relay,Hidden Home in Back history,Settings entry point,Notes tray,Popup hiding,Install-packages permission,HMX Insta app name'
```

`MIN_APPLIED_PATCHES=14` is a **deliberate difference** from the baseline
workflow (`10`): a baseline rebuild should prove all 14 patches applied. This
is a local environment value; it does not modify the repository.

---

## 4. Option A — local build (workstation, not the phone)

Use a Linux/macOS machine with JDK 21, the Android SDK, ~8 GB free RAM and the
pinned Morphe CLI/APKEditor jars under `tools/`. Do **not** run this on the
OPPO A38 sandbox.

```sh
git fetch hmxgram
git worktree add /tmp/hmx-baseline a5bace00e84094f06622ff8f47ae60990ded8e09
cd /tmp/hmx-baseline

export EXPECTED_INSTAGRAM_PACKAGE=com.instagram.android
export EXPECTED_INSTAGRAM_VERSION=454.0.0.54.77
export EXPECTED_INSTAGRAM_VERSION_CODE=386014712
export EXPECTED_META_CERT_SHA256=3a10c50c18ba937506c2875e9047be74f8fd8b86fae2ff9feaa50a81fcb4c014
export MIN_RES_ENTRIES=12000
export MIN_APPLIED_PATCHES=14
export EXPECTED_PATCHES='Force SDR display,Feed item filtering,Limit feed to following profiles,Network content blocking,Suggested accounts in the story tray,Quoicouview assets,Quoicouview mode,Restart relay,Hidden Home in Back history,Settings entry point,Notes tray,Popup hiding,Install-packages permission,HMX Insta app name'

# Morphe registry credentials: use the existing private env, never echo them
#   MORPHE_REGISTRY_USER / MORPHE_REGISTRY_TOKEN  (or ~/.gradle/gradle.properties)

./build.sh /path/to/com.instagram.android_454.0.0.54.77-386014712_....apkm
```

Output: `./feurstagram.apk`.

**Do not** pass `--allow-signature-bypass`. **Do not** pass `--install` unless
the phone is intentionally targeted.

---

## 5. Option B — cloud rebuild through the gated workflow

The existing gated workflow is guarded with
`if: github.ref == 'refs/heads/main'`, so it **cannot** build a non-main tree
as-is. Three ways to handle this, in order of preference:

1. **Do nothing.** Use the published baseline artifact (section 1), which is
   the recommended path.
2. **Verify on a workstation** with Option A, with no push and no publish.
3. **A throwaway CI branch**, only with explicit approval. The branch is based
   on `a5bace0`; the *only* change is relaxing the job guard in
   `.github/workflows/apk.yml` (no app code). Dispatch with `ref` set to that
   branch and every publish input `false`. Delete the branch afterwards. Never
   merge it, never push an artifact, never overwrite a release.

Recommended dispatch payload for option 3 (no publish):

```json
{
  "ref": "baseline/rebuild-<date>",
  "inputs": {
    "instagram_version": "454.0.0.54.77",
    "base_apk_kind": "apkm",
    "clone": false,
    "allow_throwaway_signature": true,
    "allow_signature_bypass": false,
    "publish_test_apk": false,
    "run_install_test": false,
    "disable_patches": ""
  }
}
```

---

## 6. Validation of a rebuilt baseline

A rebuild is **not** expected to be byte-identical: zip timestamps, signing
metadata and tool versions can differ. Validate semantics, not the digest.

| Check | Expected |
| --- | --- |
| patch report | `success: true`, `failedPatches: []`, exactly the 14 names, `MIN_APPLIED_PATCHES=14` |
| package / version / code | `com.instagram.android` · 454.0.0.54.77 · `386014712` |
| label | `HMX Insta` in every locale |
| signer | `CN=Morphe` (or the throwaway key used), v2, `zipalign -c -p 4` OK |
| dex count | 32 |
| `res/` entries | ≥ 14,000 (baseline: 14,453) |
| native ABI | `arm64-v8a` |
| downloader / ghost strings in dex | **0** |
| protected-method comparison | every method matches the canonical baseline APK under D1 |

Commands:

```sh
sha256sum feurstagram.apk
unzip -l feurstagram.apk | tail -1
unzip -l feurstagram.apk | grep -cE ' classes[0-9]*\.dex$'
aapt dump badging feurstagram.apk | grep -E "^package:|application-label:"
apksigner verify --print-certs feurstagram.apk
zipalign -c -p 4 feurstagram.apk
python3 scripts/ci/verify_protected_methods.py \
  --baseline /tmp/opencode/m0/baseline-43091674732.apk \
  --candidate feurstagram.apk \
  --manifest scripts/ci/protected_methods.json   # script lands in M6
```

---

## 7. Device install of the baseline (optional, manual)

Only if you intend to re-verify the baseline on the OPPO A38. A differently
signed APK cannot be installed over stock Instagram, so this clears app data.

```sh
adb uninstall com.instagram.android || true
adb install -r hmx-insta-454.0.0.54.77-test.apk
adb shell pm path com.instagram.android

# verify the installed bytes match what you meant to install
adb shell sha256sum "$(adb shell pm path com.instagram.android | sed 's/package://')"

adb logcat -c
adb shell am start -n com.instagram.android/com.instagram.mainactivity.LauncherActivity
sleep 20
adb shell dumpsys activity activities | grep -iE "mResumedActivity|topResumedActivity"
```

Do **not** script or log credentials. Compare the result against V1–V4 and
V8–V10 in `HMX_INSTA_REGRESSION_TESTS.md`.

---

## 8. Publishing rules for any future baseline rebuild

* throwaway-signed only; never a release keystore for a test artifact;
* `allow_signature_bypass=false` is mandatory, and the workflow already refuses
  to publish a bypass build;
* publish only to the private debug repository, as a **new**
  `debug-test-<run>-<attempt>` release; never overwrite an asset;
* record the new digest in the build report, but do **not** replace the
  canonical baseline reference (`6de31b8e…2df65`) unless the team explicitly
  re-baselines.

---

## 9. Rollback

The device rollback is the same as before:

```sh
adb uninstall com.instagram.android
adb install -r hmx-insta-454.0.0.54.77-test.apk   # debug-test-43091674732-1
```

Source rollback (only if ever approved, not part of M0):

```sh
git switch -c restore/last-known-good-454 backup/hmx-insta-last-known-good-454
git diff --stat backup/hmx-insta-last-known-good-454   # must be empty
```

`main` is never reset or force-pushed.

---

## 10. Approval points

| Action | Needs approval |
| --- | --- |
| local baseline rebuild | no (but no push, no publish) |
| throwaway CI rebuild branch | **yes** |
| publishing any APK | **yes** |
| raising `MIN_APPLIED_PATCHES` in the workflow | **yes** (M6 change) |
| enabling signature bypass | **yes**, separate evidence-first decision; not a login workaround |
| merging any milestone | **yes** |
