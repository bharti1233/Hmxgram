# HMX Insta — baseline build plan (M0)

**Goal:** make the baseline reproducible and auditable without disturbing
`main`, the backup branch, the feature branch, any secret, or any private
release.

**Status:** the fresh baseline **has been built and verified** (section 1). This
plan remains the procedure for any future rebuild; it does not authorize one.

---

## 1. The canonical baseline (fresh build)

The canonical baseline is the fresh gated build, independently verified:

| | |
| --- | --- |
| Release | `debug-test-43210749344-1` (private); protected copy `hmx-baseline-454-verified` |
| Asset | `hmx-insta-454.0.0.54.77-test.apk` |
| Size | 164,081,474 bytes |
| SHA-256 | `6a327db6ec9c0ccc83f82a76eaf62451c6fac526131e6f12b260bec2f5cd2822` |
| Source | `6474df0d504b7fdce45bb6c5c1e7d19e24b0ed8b` / tree `9e02ce3824586f01ed165686dcda08100c8495dc` |
| Recovery reference | `a5bace00e84094f06622ff8f47ae60990ded8e09` (`backup/hmx-insta-last-known-good-454`, same tree) |
| CI evidence | run `43210749344`, artifact `apk-build-report` |
| Patch report | 14 applied, 0 failed |
| Signer | `CN=Morphe`, v2 |
| Device | user reports installed and tested on the OPPO A38 |

The earlier audited build `debug-test-43091674732-1`
(`sha256:6de31b8e…2df65`) is retained as a historical cross-check. Its **dex
content is byte-identical** to the fresh build
(`8d9ffacd38097fb58c9e608e0195fba5a1823926a2a62dd7de30bd0141564ba7`); only the
signature differs.

For all comparison and recovery work, **use the fresh artifact** (or its
protected copy). It is the reference the M0 dex gate compares against, and it
is the APK to roll back to on the device. Rebuilding adds risk and proves
nothing that the digest, the build report and the source archive do not already
prove.

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
| Source ref | `6474df0d504b7fdce45bb6c5c1e7d19e24b0ed8b` (or recovery `a5bace0`; same tree) |
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

The gated workflow runs from an **exact allowlist**:

```yaml
if: >-
  github.ref == 'refs/heads/main' ||
  github.ref == 'refs/heads/feature/hmx-downloader-ghost-mode-v2'
```

and the `apk-build` environment must allow the same two refs. A wildcard or
prefix is forbidden; `validate_repo.sh` fails if one appears. There is no local
build path for milestones: the device is not a build machine.

**Approved milestone path:**

1. Push the milestone commit to `feature/hmx-downloader-ghost-mode-v2`.
2. Dispatch `apk.yml` with `ref` set to that branch.
3. Approve the `apk-build` deployment when the required reviewer prompt appears.
4. Throwaway signing only; `allow_signature_bypass=false`.
5. `publish_test_apk=true` creates a new `debug-test-<run>-<attempt>`
   prerelease; it never overwrites an existing release.
6. Attach the run URL, APK SHA-256 and report to the milestone record. Device
   verification stays manual on the OPPO A38.

Recommended dispatch payload:

```json
{
  "ref": "feature/hmx-downloader-ghost-mode-v2",
  "inputs": {
    "instagram_version": "454.0.0.54.77",
    "base_apk_kind": "apkm",
    "clone": false,
    "allow_throwaway_signature": true,
    "allow_signature_bypass": false,
    "publish_test_apk": true,
    "run_install_test": false,
    "disable_patches": ""
  }
}
```

`run_install_test=false` is deliberate: the hosted runner has no device. The
install and feature checks happen on the OPPO A38.

### 5.1 Building a milestone that ships a default-off patch

A patch that is `default = false` in its source is **not applied** by the
build: the CLI only applies the default-enabled patches plus the ones named
with `-e`. The `Downloader share target` patch is default-off on purpose — it
declares an exported activity — so a build that must carry it asks for it
explicitly:

* `build.sh --downloader` adds `-e "Downloader share target"`;
* the workflow exposes it as the boolean input `enable_downloader`
  (default `false`, so no existing build changes);
* the switch applies the downloader's two own patches, the share target and
  the media observer;
* a second switch, `enable_ghost`, applies the "Ghost Mode settings" patch on
  its own, so a build that only needs the downloader does not carry the
  privacy screen;
* when it is enabled the patch-report expectation switches to the 16-name
  list and `MIN_APPLIED_PATCHES` becomes 16, so the build **fails** if
  either patch did not apply;
* the publish step re-checks the patch report before uploading, so an APK
  without the patch is never published.

Nothing else changes: the environment approval, the required reviewer, the
protected-method verification, the signer pins and the publish gates all
apply exactly as before.

Dispatch payload for the M2 device build:

```json
{
  "ref": "feature/hmx-downloader-ghost-mode-v2",
  "inputs": {
    "instagram_version": "454.0.0.54.77",
    "base_apk_kind": "apkm",
    "clone": false,
    "allow_throwaway_signature": true,
    "allow_signature_bypass": false,
    "publish_test_apk": true,
    "enable_downloader": true,
    "run_install_test": false,
    "disable_patches": ""
  }
}
```

`allow_signature_bypass=false` is mandatory for a published build: the
publish step refuses to upload a build with the Instagram signature-check
bypass enabled. The published APK is therefore throwaway-signed and must
replace the stock app on the device (a different signer cannot update an
installed package).

The old temporary-branch workaround is **retired**. Do not create a throwaway
branch with a relaxed guard, and do not dispatch a build from any ref outside
the allowlist.

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
  --baseline /tmp/opencode/m0/hmx-baseline-454-verified.apk \
  --candidate feurstagram.apk \
  --manifest scripts/ci/protected_methods.json
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
  canonical baseline reference (`6a327db6…2822`) unless the team explicitly
  re-baselines;

---

## 9. Rollback

The device rollback is the same as before:

```sh
adb uninstall com.instagram.android
adb install -r hmx-insta-454.0.0.54.77-test.apk   # debug-test-43210749344-1
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
| raising `MIN_APPLIED_PATCHES` in the workflow | done in M0 (`10`→`14`); any later change needs **yes** |
| enabling signature bypass | **yes**, separate evidence-first decision; not a login workaround |
| merging any milestone | **yes** |
