# M12 Phase 6 — verification

## 1. Environment

| Item | Value |
|---|---|
| Date | 2026-11-15 |
| JDK | OpenJDK 17.0.20.1 |
| Python | 3.14.4 |
| Working tree | `/tmp/opencode/worktrees/hmx-v2`, branch `feature/hmx-downloader-ghost-mode-v2` |
| Commit at start | `ca16a54a7131e599b5267e7260e1e9e1f21ac345` |
| Android compile classpath | `/tmp/opencode/sdk35/android-35/android.jar` (type-check only) |
| Gradle / full Android SDK | not used |
| Device | not used |

No APK was built. No network was used beyond the local filesystem.

## 2. Results

| Check | Command | Result |
|---|---|---|
| Placement policy tests | `bash scripts/ci/test_downloader_logic.sh` | **PASS** — 20 groups, `PASS placement policy` |
| Downloader core regression | same script | **PASS** — all core suites |
| Ghost policy regression | same script | **PASS** — catalogue, policy, unsupported, supported-first, master gating |
| Native feed hook exact diff | same script (`test_native_feed_hook.py`) | **PASS** |
| Core compiles with `--release 11 -Werror` | same script | **PASS** — 32 core, 2 ghost, 8 downloader + 1 ghost test sources |
| Feature gate | `bash scripts/ci/validate_features.sh` | **PASS** |
| Downloader isolation / allowlist | `bash scripts/ci/test_downloader_isolation.sh` | **PASS** |
| Repository validation | `bash scripts/ci/validate_repo.sh` | **PASS** |
| Protected-method self-test | `bash scripts/ci/test_protected_methods.sh` | **PASS** — 9 checks |
| Protected-method manifest | same script | **PASS** — 12 surfaces |
| Protected-method verifier tests | same script | **PASS** |
| Branding gate | `bash scripts/ci/validate_branding.sh` | **PASS** |
| Cleanup workflow test | `bash scripts/ci/test_cleanup_workflow.sh` | **PASS** |
| Publish gates test | `bash scripts/ci/test_publish_gates.sh` | **PASS** |
| Universal packaging test | `bash scripts/ci/test_universal_packaging.sh` | **PASS** |
| Patch-report verifier test | `bash scripts/ci/test_patch_report.sh` | **PASS** |
| Full extension type-check | `javac --release 11 -Xlint:all -Werror -Xlint:-deprecation -cp android.jar` over 78 files | **PASS** |

The extension type-check is not a Gradle build. It compiles every `extensions/extension/src/main/java/**/*.java` against `android.jar` with `-Werror`, which catches syntax, type and lint errors in the M12 code. It does not run the Morphe plugin, resource linking, manifest merging, dexing, signing or packaging.

`-Xlint:-deprecation` is required because two untouched files (`Onboarding.java`, `Settings.java`) use deprecated platform APIs; the project's own CI compiles them through AGP, which does not fail on those warnings. M12 introduced no new warning.

## 3. What each result proves

| Result | Proves | Does not prove |
|---|---|---|
| Policy tests | every policy branch behaves as specified, deterministically | that the policy matches a real OPPO layout |
| Core/ghost tests | the downloader and ghost rules are unchanged | runtime behavior |
| Isolation gate | no protected file changed, no path outside the allowlist changed | APK-level protected comparison |
| Protected self-test | the verifier rejects tampered surfaces and accepts the pinned baseline | a candidate APK was compared |
| javac type-check | the extension compiles with the new classes | AGP/dex/package success |
| Feature gate | no forbidden symbol, no new patch, no manifest wiring | runtime UI behavior |

## 3b. Device evidence (M11.1 build)

The two supplied captures were analysed in `M12_DEVICE_EVIDENCE_ANALYSIS.md`:

| Item | Result |
|---|---|
| `HMX_M12_FILTERED.txt` | 4037 lines, **zero** `HmxNativeUI` lines |
| `HMX_M12_DIAGNOSTICS.txt` | 83482 lines, 31 `HmxNativeUI` lines, schema `m11.1 stage=` |
| Reels | `ARMED_REFUSED`, reason `ANCHOR` → sub-condition `ANCHOR_TYPE` proven by the class hierarchy |
| Reels repetition | one pre-draw pass per rendered frame, ~29.4/s; the `2048` is the sampler's power-of-two counter, not a log-line count |
| Feed | zero `feed.*` stages; `NOT CLASSIFIED` |

This is device evidence about the branch, not a visible verification of the fix. The fix has not been on a device.

## 3c. Gated APK build (performed)

Built by `.github/workflows/apk.yml` through the `apk-build` environment after
the branch was pushed; no local build was used.

| Item | Value |
|---|---|
| Commit | `27b6653` |
| Workflow run | `43684131825` (attempt 1, success) |
| Base input | `454.0.0.54.77`, `base_apk_kind=apkm` |
| Signing | throwaway test key, `SIGNATURE_BYPASS=false` |
| Patches applied | 18, including `In-context download actions` and `Ghost Mode settings` |
| APK SHA-256 | `f3f15772503cb580cb78951186c49e4720a52e7e9304985592ee0d76e14f965c` |
| Signer cert SHA-256 | `4d16945404feb9b736d2a266b85b6d0226e39c39f96bb02d50bb13b5df559cb8` |
| Published prerelease | `debug-test-43684131825-1` in the private input repo |

Independent checks on the downloaded APK:

| Check | Result |
|---|---|
| `apksigner verify --print-certs` | **PASS** — v2 scheme, one signer, cert SHA-256 matches the build |
| `aapt dump badging` | **PASS** — `com.instagram.android`, `454.0.0.54.77` (`386014712`) |
| `unzip -t` | **PASS** — 18 365 entries, no errors |
| `sha256sum` | **PASS** — matches `f3f1577…` |
| `validate_standalone_apk.sh --stage final` | **PASS** — merged resources, arm64, no split metadata, zipalign |
| `verify_patch_report.py` | **PASS** — 18 applied, 0 failed, no expected patch missing |
| `verify_protected_methods.py` vs pinned baseline (`6a327db6…`) | **PASS** — 12 surfaces, 2 recorded |
| Built dex vs pinned baseline | `ClipsViewerActionBar` is `RelativeLayout` and byte-identical (normalised) in both |
| Built dex contains the fix | `PlacementPolicy$Container`, `ANCHOR_TYPE`, `BAR_CHILD_`, `layoutParams` present |

## 4. Not performed

| Item | Reason |
|---|---|
| Device capture of the fix | not yet run on the phone; the supplied logs predate the fix and use the M11.1 schema |
| Device screenshot of the control | requires the test APK installed on the OPPO A38 |
| `gradlew :patches:build` / `.mpe` bundle | not needed; the gated workflow builds the plugin |

## 5. Gated build readiness

The candidate is ready for `apk.yml` when the branch is pushed. Expected properties:

- patch inventory unchanged: 23 reviewed patch sources;
- the in-context patch is byte-identical to the reviewed M11 file, so `m11_reviewed_sources.sha256` still matches;
- no new dependency, no manifest entry, no resource;
- the extension compiles under `javac` with `-Werror`;
- all offline gates pass locally.

After the workflow produces an APK, the following must be run and recorded. These are the workflow's own commands, not new ones.

```bash
# 1. ZIP integrity, alignment, package, version, signer, arm64
bash scripts/ci/validate_standalone_apk.sh --stage final \
  --expected-package <expected> --expected-version <expected> \
  --expected-version-code <expected> --expected-signer-sha256 <expected> \
  feurstagram.apk

# 2. signer certificate
apksigner verify --print-certs feurstagram.apk

# 3. patch report (exact 14 applied)
MIN_APPLIED_PATCHES=14 python3 scripts/ci/verify_patch_report.py patch-report.json

# 4. protected Instagram methods against the pinned baseline APK
python3 scripts/ci/verify_protected_methods.py \
  --manifest scripts/ci/protected_methods.json \
  --baseline hmx-baseline-454-verified.apk \
  --candidate feurstagram.apk \
  --report protected-methods-report.json

# 5. artifact digest
sha256sum feurstagram.apk
```

> These commands have **not** been run in M12. There is no APK to run them against. They are the acceptance checklist for the gated build.

## 6. Verification of the instrumentation itself

| Property | How checked |
|---|---|
| No text, description, tag or URL is logged | source review of `NativeUiTrace.describe` and `PlacementProbe`; both read class/geometry/flags only |
| Bounded log volume | stage cap 128, powers-of-two sampling, summary cap 40 then every 50th |
| No per-frame slot work during scroll | `freeSlot` keeps its original short-circuit; the probe runs only while idle and only when the geometry signature changes |
| No placement change | behavioral diff review in the regression matrix |
| No download from observation | queue call sites reviewed; none on a bind, draw, attach or detach path |
| No new protected surface | isolation gate and `m11_reviewed_sources.sha256` |

## 7. Residual verification gaps

1. **Reels discovery has no JVM test.** The pager and getter lookup is Android reflection over a live object.
2. **Feed holder discovery has no JVM test.** `Class.getFields()` and resource-id lookup need a real holder.
3. **Probe cost is unmeasured on the OPPO A38.** It is bounded, but "bounded" is not "measured".
4. **The gated build has not run for this commit.** A green local javac check is not a green Gradle build.
5. **The M12 build is not device-verified.** The capture procedure is ready; the evidence is not.

These are stated as limitations, not worked around with claims.
