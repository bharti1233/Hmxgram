# HMX Insta — M0 baseline verification

**Milestone:** M0 (verification, archiving, protected-method record, enforced
protected-method gate, baseline feature inventory, test definitions, baseline
build plan). **No feature code yet.**

---

## 0. M0 v2 re-freeze — canonical baseline (fresh build)

This section supersedes the original M0 artifact record below. The original
record is retained as history and as an independent cross-check.

| Item | Value |
| --- | --- |
| Recovery branch | `backup/hmx-insta-last-known-good-454` |
| Recovery commit | `a5bace00e84094f06622ff8f47ae60990ded8e09` |
| Recovery tree | `9e02ce3824586f01ed165686dcda08100c8495dc` |
| Fresh source commit | `6474df0d504b7fdce45bb6c5c1e7d19e24b0ed8b` (merge of PR #17) |
| Fresh source tree | `9e02ce3824586f01ed165686dcda08100c8495dc` (identical to recovery) |
| Fresh build run | `43210749344` (attempt 1), `2026-11-12T11:37:28Z` |
| Fresh debug release | `debug-test-43210749344-1` |
| Fresh APK asset | `hmx-insta-454.0.0.54.77-test.apk` |
| Fresh APK size | **164,081,474 bytes** |
| Fresh APK SHA-256 | `6a327db6ec9c0ccc83f82a76eaf62451c6fac526131e6f12b260bec2f5cd2822` |
| Protected baseline release | `hmx-baseline-454-verified` (same bytes; outside the `debug-test-*` cleanup pattern) |
| Package / version / code | `com.instagram.android` · 454.0.0.54.77 · `386014712` |
| Label / launcher | `HMX Insta`; foreground/background artwork matches the branding source pixel-for-pixel; HMX monochrome vector present |
| Signer | `CN=Morphe`, SHA-256 `ffdf8557d617931dd857cfffd109ebe47b5882f1508faaea4f8dae367750c46e` |
| Signature scheme | APK Signature Scheme **v2** (v1/v3/v4 false), throwaway test key |
| Patches | **14 applied, 0 failed**; signature-check bypass disabled |
| Dex content | `sha256:8d9ffacd38097fb58c9e608e0195fba5a1823926a2a62dd7de30bd0141564ba7` — **identical** to the older audited build; only the signature differs |
| Device | User reports the fresh baseline installed and tested on OPPO A38 (CPH2579), Android 15 / API 35, arm64-v8a |
| Feature branch | `feature/hmx-downloader-ghost-mode-v2`, created from `6474df0` |

The fresh build was produced by the existing main-only gated workflow after a
reviewed baseline-tree PR (#17). No workflow, secret, signing gate or patch
definition was changed or bypassed.

### M0 v2 additions

* `scripts/ci/verify_protected_methods.py` — real `dexdump` gate, wired into
  `apk.yml` before publish and self-tested in `ci.yml`.
* `scripts/ci/protected_methods.json` — baseline pin plus machine-readable
  enforced surfaces.
* `docs/HMX_INSTA_BASELINE_FEATURE_INVENTORY.md` — the tested baseline feature
  inventory.
* `MIN_APPLIED_PATCHES` raised from `10` to the exact `14`; a dropped baseline
  patch is now a build failure.

### M0 v2 — protected-method gate evidence

| Check | Result |
| --- | --- |
| Offline self-test (`scripts/ci/test_protected_methods.sh`) | **PASS**, 6 checks: layout/debug noise ignored; literal, register, access, changed-class and missing-class failures detected |
| Manifest validation | **PASS**, 12 gate surfaces, unique ids, every enforced surface has anchors |
| Baseline-vs-baseline dex comparison | **PASS**, exit 0, `protected-methods-report.json` with 9 enforced surfaces `pass`, 3 record-only surfaces `recorded`, 0 problems |
| Runtime (same file, resolution reused) | **133 s** on the sandbox; a real candidate performs two resolutions, so budget ~5 minutes in CI |
| Resolved parser classes | 3,733 classes match the media-parser anchors and are frozen as a group |
| Wired into `apk.yml` | runs after APK verification, before install/publish; downloads `hmx-baseline-454-verified`, refuses a digest mismatch, uploads `protected-methods-report.json` as evidence |
| Wired into `ci.yml` | runs the offline self-test in Repository validation |

The gate fails closed: an unresolvable or ambiguous anchor, a missing protected
class, a changed protected class, an unparseable dex, or a baseline APK that
does not match the pinned SHA-256 all exit non-zero and stop the build.

Because the comparison is baseline-to-candidate, a build that **disables** a
baseline patch which touches a protected surface also fails the gate. That is
intentional: `disable_patches` is a diagnostic input, not a way to ship a
different Instagram code path.

---

## 0.1 Original M0 record (historical)

**Milestone:** M0 (verification, archiving, protected-method record, test
definitions, baseline build plan). **No feature code, no build, no publish, no
merge.**

---

## 1. What was verified, and how

The recovery point was resolved **through the backup branch reference**, not
through the previously copied SHA.

```console
$ git ls-remote hmxgram 'refs/heads/backup/*' 'refs/heads/main' 'refs/heads/feature/hmx-downloader-ghost-mode'
a5bace00e84094f06622ff8f47ae60990ded8e09  refs/heads/backup/hmx-insta-last-known-good-454
924105c039721eaeb9300b721db10ba4b69db36a  refs/heads/feature/hmx-downloader-ghost-mode
84d620debba55c1bfbe57cccb2d6b03404346874  refs/heads/main
```

| Item | Value |
| --- | --- |
| Backup branch | `backup/hmx-insta-last-known-good-454` |
| Resolved commit | `a5bace00e84094f06622ff8f47ae60990ded8e09` |
| Object type | `commit` |
| Tree | `9e02ce3824586f01ed165686dcda08100c8495dc` |
| Subject | `Merge pull request #8 from bharti1233/feature/hmx-insta-branding` |
| Author / date | `bharti1233 <harshmaurya5015@gmail.com>` · 2026-11-12 01:25:09 +0530 |
| Parents | `e1ba7c41b9f215c456f15e9ef6832e27c4f9f86a`, `0abf2cee3f48034a54567f1f4aea4b56b4ebfa20` |
| Position | ancestor of `main` (`84d620d`); 18 commits behind it |

The SHA quoted in the previous request
(`a5bace0e84a094f06622f8f47a06909d0ed8e09`) **does not exist** in this
repository:

```console
$ git cat-file -t a5bace0e84a094f06622f8f47a06909d0ed8e09
fatal: Not a valid object name a5bace0e84a094f06622f8f47a06909d0ed8e09
```

The branch reference is the authority; it is correct, and the copy was a typo.

---

## 2. Source archive of the recovery point

A read-only export was created outside the repository (not committed):

```sh
git archive --format=tar --prefix="hmx-insta-baseline-a5bace0/" \
  hmxgram/backup/hmx-insta-last-known-good-454 \
  | gzip -9n > /tmp/opencode/m0/hmx-insta-baseline-a5bace0.tar.gz
```

| Item | Value |
| --- | --- |
| Archive | `/tmp/opencode/m0/hmx-insta-baseline-a5bace0.tar.gz` |
| Size | 32,602,168 bytes |
| SHA-256 | `37ee255258c6f74bfbc4979f70eaf9741824ab196808c8893e30d965c8099db8` |
| Tracked files | **161** |
| File list check | archive contents == `git ls-tree -r` file list (the only difference is the trailing empty line produced by the extraction pipeline) |

Per-top-level-directory file counts at the recovery point:

| Directory | Files |
| --- | --- |
| `docs/` | 48 |
| `patches/` | 32 |
| root files | 24 |
| `extensions/` | 21 |
| `scripts/` | 14 |
| `.github/` | 10 |
| `branding/` | 5 |
| `gradle/` | 3 |
| `distribution/` | 3 |
| `assets/` | 1 |

The tree hash `9e02ce3824586f01ed165686dcda08100c8495dc` is the authoritative
content identity; any change to any tracked file changes it.

---

## 3. Original baseline artifact (historical)

> Superseded by section 0. The artifact below is still preserved and is used as
> an independent dex-level cross-check: its dex content is byte-identical to the
> fresh build.

The older published known-good APK was re-downloaded from the private release
and verified byte for byte.

| Item | Value |
| --- | --- |
| Private release | `debug-test-43091674732-1` (`bharti1233/hmx-build-inputs`) |
| Asset | `hmx-insta-454.0.0.54.77-test.apk` |
| Size | **164,081,474 bytes** |
| SHA-256 | `6de31b8eb9845ccd27cdd5ec6d140feaafbc943cc04c26481cde4594a7b0df65` |
| CI run | `43091674732`, artifact `apk-build-report` (not expired) |
| CI artifact digest | the artifact's `feurstagram.apk.sha256` is exactly the published digest above |
| Package / version | `com.instagram.android` · 454.0.0.54.77 · versionCode `386014712` |
| App label | `HMX Insta` (every locale in `aapt dump badging`) |
| Signer | `CN=Morphe`, SHA-256 `d9808bf36c4e85d8ec5a3f7d34f015f5b234a2e1be32d912379147183efeda1a` |
| Signature scheme | APK Signature Scheme **v2** (v1/v3/v4 false) |
| Zip alignment | `zipalign -c -p 4` → **OK** |
| Zip entries | 18,365 |
| `res/` entries | 14,453 |
| `assets/` entries | 3,152 |
| Dex files | 32 |
| Native ABIs | `arm64-v8a` only (15 `lib/` entries) |
| Density buckets | `mdpi`, `hdpi`, `xhdpi`, `xxhdpi`, `xxxhdpi`, plus `anydpi` / `nodpi` |

This is a **universal (merged, standalone) APK** in the project's sense: all
splits needed by the target device are merged into one installable APK, with
the launcher branding applied and the base signature replaced by the test key.

### Patch report (from the build's own CI artifact)

`success: true`, `failedPatches: []`, **14 applied patches**:

1. Feed item filtering
2. Force SDR display
3. HMX Insta app name
4. Hidden Home in Back history
5. Install-packages permission
6. Limit feed to following profiles
7. Network content blocking
8. Notes tray
9. Popup hiding
10. Quoicouview assets
11. Quoicouview mode
12. Restart relay
13. Settings entry point
14. Suggested accounts in the story tray

### Downloader / Ghost Mode absence (from the dex itself)

The baseline APK contains **zero** occurrences of the feature code:

| Needle | Dex files containing it |
| --- | --- |
| `feurstagram/extension/downloader` | 0 |
| `feurstagram/extension/ghost` | 0 |
| `HmxDownloader` | 0 |
| `GhostNetwork` | 0 |
| `onMediaFieldCaptured` | 0 |

So the baseline cannot fail because of the downloader or ghost code: that code
is not in it.

---

## 4. Build configuration at the recovery point

| Item | Value at `a5bace0` |
| --- | --- |
| Workflow | `.github/workflows/apk.yml`, manual `workflow_dispatch`, job guard `if: github.ref == 'refs/heads/main'` |
| Base input | complete `.apkm` bundle, `4245c417…c380e` |
| Base format | `base_apk_kind=apkm` (merged with APKEditor V1.4.9 before patching) |
| Patching | Morphe CLI + `patches/` + `extensions/extension`, pinned by `MORPHE_CLI_URL` / `MORPHE_CLI_SHA256` |
| Signing | throwaway test key (`allow_throwaway_signature=true`), v2 |
| Signature bypass | **disabled** (workflow default `false`; `build.sh` strips the patch unless `--allow-signature-bypass`) |
| Publishing | private prerelease only, `publish_test_apk=true`, `DIST_TOKEN` |
| Device install test | `run_install_test=false` (no arm64 device on the runner) |
| Patch expectation | `EXPECTED_PATCHES` lists all 14; **`MIN_APPLIED_PATCHES` raised from `10` to `14` in M0 v2** |
| Environment secrets | `BASE_APK_URL`, `BASE_APK_SHA256`, `BASE_APK_TOKEN`, `DIST_TOKEN` (names only; values untouched) |
| Repository secrets | `MORPHE_CLI_URL`, `MORPHE_CLI_SHA256`, `MORPHE_REGISTRY_USER`, `MORPHE_REGISTRY_TOKEN`, `APKEDITOR_URL`, `APKEDITOR_SHA256` |

At the time of the original record, `MIN_APPLIED_PATCHES: '10'` was a real
weakness of the baseline build gate. **M0 v2 fixes it**: the value is now the
exact `14`, and `validate_repo.sh` / the regression gate S5 keep it from
regressing.

---

## 5. Protected inputs and releases — integrity check

Checked read-only through the private-repository API. Nothing was modified,
renamed, deleted or re-uploaded.

| Release | Asset | Created | Digest |
| --- | --- | --- | --- |
| `instagram-454-0-0-54-77-386014712-bundle` | `com.instagram.android_454.0.0.54.77-386014712_1dpi_725fab133b6c91e3a363462cd910eea0_apkmirror.com.apkm` | 2026-11-11T18:13:32Z | `sha256:4245c41709b1f753e9f168961bcccdec750045e0cc3e690ac077bbc9656c380e` |
| `Apk-input-original` | `Instagram.apk` | 2026-11-11T05:41:11Z | `sha256:b334feef23722f0364a80c2c9e99af48e0c460cc652213d3f1a919f2007e5136` |
| `debug-test-43091674732-1` | `hmx-insta-454.0.0.54.77-test.apk` | published 2026-11-11T19:58:10Z | `sha256:6de31b8eb9845ccd27cdd5ec6d140feaafbc943cc04c26481cde4594a7b0df65` |

The two protected inputs are unchanged; the baseline debug release still holds
the exact digest recorded above and five debug releases remain present.

---

## 6. Proven vs not proven

**Proven by M0:**

* the backup branch resolves to `a5bace0`, and it is an ancestor of `main`;
* its tree is reproducible from the archived export (digest and file list);
* the published baseline APK matches the build report's digest and the release
  asset, has 14 applied patches and no downloader/ghost code;
* the baseline build configuration, signer, packaging and patch expectations
  are recorded exactly as they were;
* the protected input assets and all private releases are untouched.

**Not proven / not claimed:**

* **Login was not device-tested for this build.** The repository records device
  work on the earlier pre-branding build; the branding build at `a5bace0` has
  no device-test record of its own. "Last known good" here means *the last
  published, known-good source state*, not a newly re-verified device result.
* No device verification of any kind was performed in M0. No APK was built,
  installed, launched, published or merged.

---

## 7. M0 exit criteria

| Criterion | Status |
| --- | --- |
| Backup commit verified through the branch reference | ✅ |
| Source archived with a recorded digest and file list | ✅ |
| Baseline APK identified, downloaded and verified by digest | ✅ |
| Patch inventory recorded from the build's own report | ✅ |
| Protected methods recorded (stable anchors + concrete classes) | ✅ `HMX_INSTA_PROTECTED_METHODS.md` + `scripts/ci/protected_methods.json` |
| Regression tests defined before implementation | ✅ `HMX_INSTA_REGRESSION_TESTS.md` |
| Baseline build plan prepared | ✅ `HMX_INSTA_BASELINE_BUILD_PLAN.md` |
| Downloader/Ghost Mode implemented | ❌ intentionally not started (M1+) |
| Anything built, published or merged | ❌ none |

---

## 8. Independent re-verification (backup-publish stage)

The artifact was downloaded again from the private release into a fresh path
and checked without relying on the M0 report.

| Check | Result |
| --- | --- |
| Fresh download | `hmx-insta-454.0.0.54.77-test.apk`, 164,081,474 bytes |
| SHA-256 | `6de31b8eb9845ccd27cdd5ec6d140feaafbc943cc04c26481cde4594a7b0df65` — **match** |
| Package / version / code | `com.instagram.android` · 454.0.0.54.77 · `386014712` |
| Label | `HMX Insta` |
| SDK | min 28, target 37 (device API 35) |
| Native code | `arm64-v8a` (15 libraries) |
| Density buckets | 160 / 240 / 320 / 480 / 640 / 65534 / 65535 |
| Signer | `CN=Morphe`, SHA-256 `d9808bf36c4e85d8ec5a3f7d34f015f5b234a2e1be32d912379147183efeda1a`, scheme **v2** |
| ZIP integrity | `unzip -t` OK — **18,365 entries** tested, no CRC error |
| Alignment / signature | `zipalign -c -p 4` OK; `apksigner verify` OK |
| Required split metadata | **absent**: `isSplitRequired`, `requiredSplitTypes`, `splitTypes`, `splitName`, `uses-split`, `com.android.vending.splits`, `android:split` |
| Nested split APKs | none |
| Dex | 32 files, all parse with `dexdump`, 0 failures |
| Resources | `resources.arsc` parses; 14,453 `res/` entries |
| Assets | 3,152 entries |
| Launcher | exactly one enabled `MAIN`+`LAUNCHER` component: `com.instagram.android.activity.MainTabActivity` → `com.instagram.mainactivity.InstagramMainActivity` |
| Build report link | CI artifact `apk-build-report` digest equals the downloaded APK digest; 14 applied, 0 failed |
| Downloader / Ghost code | absent from all 32 dex files |

**Static standalone-installability prerequisites all pass**: complete dex set,
resources, arm64 native libraries, no split requirement, valid v2 signature,
valid zip, enabled launcher. **Actual installation was not performed in this
environment**; it is the manual device step below.

**Signing-key note:** the baseline signer
(`d9808bf3…eda1a`) differs from the currently published fixed build
(`8f1e37ce…fdb0`) and from stock Instagram. Installing it over either requires
uninstalling `com.instagram.android` first, which clears app data.
