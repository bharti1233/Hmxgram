# HMX Insta — Last Known Good (recovery point)

This document records the **last known working HMX Insta source** and how to
restore it. It is a backup and recovery reference only: nothing here was
restored, reset, reverted, rebuilt or published while writing it.

---

## 1. The commit

| | |
| --- | --- |
| Commit SHA | `a5bace00e84094f06622ff8f47ae60990ded8e09` |
| Short | `a5bace0` |
| Title | `Merge pull request #8 from bharti1233/feature/hmx-insta-branding` |
| Date | 2026-11-12 01:25:09 +0530 |
| Backup branch | `backup/hmx-insta-last-known-good-454` → points at exactly this commit |
| Position in history | an **ancestor** of `main` (`84d620d`); 18 commits behind `main` |

Nothing was rewritten to produce this point: `main` still contains the whole
feature chain, and the backup branch is an additional ref. No reset, no force
push, no branch or release was deleted.

## 2. The working artifact

| | |
| --- | --- |
| Private release | `debug-test-43091674732-1` (prerelease, `bharti1233/hmx-build-inputs`) |
| Asset | `hmx-insta-454.0.0.54.77-test.apk` |
| Size | 164,081,474 bytes |
| SHA-256 | `6de31b8eb9845ccd27cdd5ec6d140feaafbc943cc04c26481cde4594a7b0df65` |
| Built by | gated run `43091674732` (CI artifact `apk-build-report`, not expired) |
| Signer | `CN=Morphe`, SHA-256 `d9808bf36c4e85d8ec5a3f7d34f015f5b234a2e1be32d912379147183efeda1a` |
| Package / version | `com.instagram.android` · 454.0.0.54.77 · versionCode `386014712` |
| Packaging | standalone universal APK: 14,453 `res/` entries, 3,590 images, densities `160 240 320 480 640 65534 65535` |

The build's own report (from the CI artifact) confirms the patch set and the
final packaging state — quoted verbatim in section 4.

## 3. What this baseline contains

**HMX Insta branding** — app label, launcher icon, About page, notifications and
project surfaces all say *HMX Insta*, published by *HMXPanel*.

* Official logo `hm insta.png` (530 × 532 PNG) in the repository root.
* `scripts/branding/pngtool.py` and `scripts/branding/generate_launcher_icons.py`
  generate the launcher layers deterministically from that logo.
* `scripts/branding/apply_launcher_branding.py` places them into the merged APK.
* The *HMX Insta app name* patch (Morphe) renames the app.

**Universal APK packaging** — the complete work of PRs #5–#7 is present:

* `scripts/ci/validate_bundle.sh`, `validate_input_apk.sh`,
  `validate_standalone_apk.sh`, `install_smoke_test.sh`
* `scripts/ci/verify_patch_report.py` and `scripts/ci/test_patch_report.sh`
* the APKEditor V1.4.9 merge with `-clean-meta`
* the gated build in `.github/workflows/apk.yml` with the 16-patch expectation
  framework in place

**Upstream updater removed** — the FeurStagram release check, its "Update
available" page, the download and the `PackageInstaller` install path are gone
(the *HMX Insta* About page states the update policy instead).

**Not present** (this is what came later): the downloader and the ghost mode —
no `downloader/` or `ghost/` packages, no *HMX downloader capture* or *HMX ghost
mode* patches, no `scripts/ci/verify_capture_patch.py`, no
`scripts/ci/validate_features.sh`.

## 4. The 14 patches in the baseline build

From the baseline build's own `patch-report.json` (`success: true`,
`failedPatches: []`):

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

The 13 upstream patches are unchanged from the pre-branding build; the 14th is
the branding patch added by PR #8.

## 5. Build configuration

| | |
| --- | --- |
| Base input | the complete original bundle, release `instagram-454-0-0-54-77-386014712-bundle`, asset `com.instagram.android_454.0.0.54.77-386014712_1dpi_725fab133b6c91e3a363462cd910eea0_apkmirror.com.apkm`, 144,039,598 bytes, SHA-256 `4245c41709b1f753e9f168961bcccdec750045e0cc3e690ac077bbc9656c380e` |
| Legacy input (untouched) | release `Apk-input-original`, asset `Instagram.apk`, 259,865,899 bytes, SHA-256 `b334feef23722f0364a80c2c9e99af48e0c460cc652213d3f1a919f2007e5136` |
| Patch bundle | `patches/` (Morphe patch project) + `extensions/extension` (the runtime extension) |
| Patcher | Morphe CLI, pinned by `MORPHE_CLI_URL` / `MORPHE_CLI_SHA256` |
| Merger | APKEditor V1.4.9, pinned by `APKEDITOR_URL` / `APKEDITOR_SHA256` |
| Workflow | `.github/workflows/apk.yml` — manual, main-only, `apk-build` environment |
| Environment secrets | `BASE_APK_URL`, `BASE_APK_SHA256`, `BASE_APK_TOKEN`, `DIST_TOKEN` (names only; values unchanged by this audit) |
| Repository secrets | `MORPHE_CLI_URL`, `MORPHE_CLI_SHA256`, `MORPHE_REGISTRY_USER`, `MORPHE_REGISTRY_TOKEN`, `APKEDITOR_URL`, `APKEDITOR_SHA256` |
| Build inputs | `base_apk_kind=apkm`, `allow_throwaway_signature=true`, `publish_test_apk=true`, `run_install_test=false` |

## 6. Known limitations of this baseline

* **Device verification of this exact build is not recorded.** The split-install
  investigation in `docs/investigations/` documents device work on the earlier
  pre-branding build; the branding build (`a5bace0`) was built and published
  (`debug-test-43091674732-1`) but the repo contains no device-test record for
  it. Treat "last known good" as *the last source state that was known to work*,
  not as a re-verified device result.
* The app is signed with a throwaway test key, so it is **not** a
  distribution build and cannot be installed over stock Instagram without
  uninstalling it first (which clears the session).
* The device install test (`run_install_test=true`) was never executed on
  hardware; CI only validated the packaging statically.
* The downloader and ghost mode are absent by design here — they are the work
  that is being recovered from.

## 7. Restore procedure

Nothing below was executed. These are the exact steps to return the tree to the
recovery point.

```sh
cd /mnt/sdcard/AIProjects/hmx-instaplus-feurstagram/FeurStagram
git fetch hmxgram

# 1. work on the recovery point in a new local branch (main stays untouched)
git switch -c restore/last-known-good-454 backup/hmx-insta-last-known-good-454

# 2. confirm the tree is exactly the recovery point
git diff --stat backup/hmx-insta-last-known-good-454   # must be empty
git log -1 --oneline                                    # a5bace0 Merge pull request #8 …

# 3. build (the gated workflow runs from main only — see note below)
#    locally:  ./build.sh <the .apkm bundle>
#    in CI:    merge a restore PR into main, then dispatch
#              "APK build (gated)" with base_apk_kind=apkm,
#              allow_throwaway_signature=true, publish_test_apk=true
```

Notes:

* `main` is **not** reset; the restore is a branch. If `main` must go back to
  the recovery point, that is a separate, explicit decision — do not force-push
  `main`.
* The protected input assets (`Apk-input-original`, the `.apkm` bundle) and every
  private release are untouched and are not part of any restore.
* The environment secrets do not need to change: `BASE_APK_URL` /
  `BASE_APK_SHA256` already point at the bundle, and `MIN_APPLIED_PATCHES` /
  `EXPECTED_PATCHES` live in the workflow file, so the recovery point's own
  values (14 patches) apply as soon as that tree is checked out.

## 8. What was verified while writing this document

* `a5bace00e84094f06622ff8f47ae60990ded8e09` is an ancestor of `main`
  (`84d620d`) — the feature chain is preserved, nothing was rewritten.
* The baseline tree contains the branding patch, the official logo, the launcher
  generator and the universal-packaging scripts, and contains **no** downloader
  or ghost-mode code.
* The baseline build's CI artifact (`apk-build-report`, run `43091674732`) lists
  the 14 applied patches, 0 failed, and the final packaging state; its
  `feurstagram.apk.sha256` equals the published asset digest
  `6de31b8e…2df65`.
* The protected input assets still carry their original digests
  (`b334feef…` and `4245c417…`) and their original creation timestamps.
* No secret was read, changed or printed; only secret *names* and update
  timestamps were inspected.
