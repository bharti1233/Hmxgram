# HMX Insta+ — Universal APK Packaging

Status: implemented and validated offline. End-to-end bundle build is **blocked on the
missing complete original bundle** (see [Known limitations](#10-known-limitations) and
[Next steps](#12-next-steps)).

Target application:

| Property | Value |
| --- | --- |
| Package | `com.instagram.android` |
| Version name | `454.0.0.54.77` |
| Version code | `386014712` |
| ABI | `arm64-v8a` |
| Device reference | OPPO A38 (CPH2579), ColorOS 15 / Android 15 / API 35 |

---

## 1. Problem addressed

The previous HMX Insta+ APK patched and signed successfully but failed to install:

```text
INSTALL_FAILED_MISSING_SPLIT: Missing split for com.instagram.android
```

Root cause: the input was a **bundle-derived base APK** — an `Instagram.apk` extracted
from a split bundle. It declared `android:requiredSplitTypes="base__density"` (and
`com.android.vending.splits.required=true`) but contained none of the density split
payload. ColorOS 15 enforces required split types even when `isSplitRequired` is absent,
so the package was rejected at install time.

The official FeurStagram release for the same Instagram version was verified to be a
merged universal APK: no split metadata, no `res/xml/splits0.xml`, no
`stamp-cert-sha256`, and full density resources
(14,453 `res/` entries versus 7,202 in the base-only artifact). It was produced by
merging an APKMirror `.apkm` with APKEditor V1.4.9, then patching with Morphe, then
signing.

This implementation makes that path the only accepted path.

---

## 2. Architecture

```text
private input repo (.apkm/.apks/.xapk)
        |
        v
[validate_bundle.sh]  SHA-256 pin, ZIP integrity, base.apk + required splits,
        |             base identity (package/version/versionCode), Meta certificate,
        |             no already-patched input
        v
APKEditor V1.4.9 merge  (build.sh, pinned jar + SHA-256)
        |
        v
[validate_standalone_apk.sh --stage merge]  no split metadata, no split table/stamp,
        |                                   merged density payload, readable resources,
        |                                   package/version/versionCode, arm64
        v
Morphe patch bundle (.mpp) + Morphe CLI patch  (strict failures, patch report,
        |                                      expected patch set)
        v
signing  (existing release keystore, or throwaway key in explicit test mode)
        |
        v
[validate_standalone_apk.sh --stage final]  signature + signer pin, zipalign, plus all
        |                                   standalone checks
        v
[install_smoke_test.sh]  arm64-v8a, API 35, single-APK adb install (optional step;
        |                 when skipped, the summary reports it as pending)
        v
private debug release in bharti1233/hmx-build-inputs (72-hour retention)
```

Validation is **fail-closed**: every stage must pass before the next one runs, and the
publish step re-validates the exact bytes it is about to upload.

### Files

| File | Role |
| --- | --- |
| `scripts/ci/validate_bundle.sh` | Complete-bundle gate (digest, base, required splits, identity, signer). |
| `scripts/ci/validate_input_apk.sh` | Rejects single `.apk` inputs with split requirements or FeurStagram patch markers. |
| `scripts/ci/validate_standalone_apk.sh` | Merge-stage and final-stage standalone/signature/alignment gate. |
| `scripts/ci/install_smoke_test.sh` | arm64/API 35 install smoke test; refuses x86 targets. |
| `scripts/ci/test_universal_packaging.sh` | Offline tests A–H plus optional real-artifact checks. |
| `scripts/ci/verify_patch_report.py` | Patch-report gate: failed/empty/short reports and missing expected patches abort the build. |
| `scripts/ci/test_patch_report.sh` | Offline tests for the patch-report verifier. |
| `build.sh` | Input validation, APKEditor merge, patch report enforcement, final validation. |
| `.github/workflows/apk.yml` | Bundle/APK input selection, gates, optional install test, private publish. |
| `.github/workflows/ci.yml` | Runs the packaging gate tests on every push/PR. |

---

## 3. Input requirements

A build input must be one of:

1. **Preferred:** the complete original APKMirror `.apkm` bundle for Instagram
   `454.0.0.54.77` (`arm64-v8a`), containing `base.apk` **and** the density config
   splits.
2. `.apks` / `.xapk` only if the bundle is a ZIP containing a base APK plus the config
   splits. APKEditor-compatible layout is required (`base.apk` or
   `splits/base-master.apk`).

The following are rejected:

- A plain `.apk` that declares split metadata (`requiredSplitTypes`, `splitTypes`,
  `isSplitRequired`, `com.android.vending.splits.required`, or a Play split table).
- A bundle that contains only `base.apk` and no splits.
- A bundle with no density config split.
- A bundle whose base declares a required split type that the bundle does not contain.
- Any input already signed by Feurstagram or containing FeurStagram/Morphe artifacts.
- A base whose package, version, version code, or Meta certificate does not match.

Never:

- substitute a different Instagram version silently;
- treat a plain base APK as a complete bundle;
- use the already-patched official FeurStagram APK as input;
- remove split attributes by hand instead of merging the real bundle.

### Pinning

The bundle must be pinned by immutable asset URL **and** SHA-256. These live in the
private `apk-build` environment secrets as `BASE_APK_URL` and `BASE_APK_SHA256`; the
workflow downloads the exact asset and verifies the digest before anything else runs.
The version and version code are pinned in `.github/workflows/apk.yml` as
`EXPECTED_INSTAGRAM_VERSION` (`454.0.0.54.77`) and
`EXPECTED_INSTAGRAM_VERSION_CODE` (`386014712`).

> **Blocked:** no complete original bundle for version code `386014712` currently exists
> in the local workspace or in the private input repository. Only the bundle-derived
> base APK (`b334feef…`) and the failed patched APK (`68446312…`) are present. No bundle
> URL or SHA-256 has been invented. See [Next steps](#12-next-steps).

---

## 4. APKEditor merge process

`build.sh` already contained the merge branch; it is now reachable and guarded:

```sh
java -Xmx4g -jar tools/APKEditor-pinned.jar m -f -clean-meta \
  -i instagram.apkm -o build/merged/instagram.apk
```

- APKEditor version: **V1.4.9** (downloaded in CI as `tools/APKEditor-pinned.jar`,
  verified against the `APKEDITOR_SHA256` secret).
- The merge is cached under `build/merged/` and reused while it is newer than the
  bundle.
- A merge failure is fatal and its log is written next to the output.

APKEditor performs all of the packaging changes that the official release shows:

- `Merger.sanitizeManifest()` removes `requiredSplitTypes` and `splitTypes`.
- `AndroidManifestHelper.listSplitRequired()` removes every `com.android.vending.*` and
  `com.android.stamp.*` meta-data element.
- `Merger.removeSplitsTableEntry()` removes the `res/xml/splits0.xml` split table.
- `CommandExecutor.clearMeta()` removes `stamp-cert-sha256` and old `META-INF` signatures.
- Split resources (including density configurations) are merged into one resource table.

Do not hand-edit the manifest. If the merged output still has split metadata, the build
fails.

> **`-clean-meta` is mandatory.** `Merger.runCommand()` only calls `clearMeta()` when
> `options.cleanMeta` is set, and `clearMeta()` is the only place that removes
> `stamp-cert-sha256` and the original `META-INF/*.MF|SF|RSA` signatures. Without the
> flag the merged APK keeps Meta's signatures and a stale Google Play source stamp:
> the stamp no longer matches the certificate we sign with, Android 11+ refuses the
> package (`INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES`), and the merge-stage
> validator rejects it. This was the exact failure of gated run `43079743934`
> (`ERROR: stamp-cert-sha256 is present`), which failed closed before publishing.

---

## 5. Validation commands

The scripts print each check and exit non-zero on the first failure.

### Bundle

```sh
EXPECTED_INSTAGRAM_VERSION=454.0.0.54.77 \
EXPECTED_INSTAGRAM_VERSION_CODE=386014712 \
EXPECTED_META_CERT_SHA256=3a10c50c18ba937506c2875e9047be74f8fd8b86fae2ff9feaa50a81fcb4c014 \
bash scripts/ci/validate_bundle.sh \
  --expected-sha256 "$BASE_APK_SHA256" \
  --extract-base /tmp/base.apk \
  /path/to/instagram.apkm
```

Checks: SHA-256 pin, `unzip -t`, `base.apk` presence, at least one split and one density
split, base `aapt dump badging` identity, `apksigner verify --print-certs` Meta
certificate, no FeurStagram markers, all declared required split types present.

### Single `.apk` input

```sh
bash scripts/ci/validate_input_apk.sh /path/to/input.apk
```

Rejects a bundle-derived base APK with an explicit message telling the operator to use
the complete `.apkm/.apks` bundle.

### Merged / final APK

```sh
# after APKEditor, before patching
bash scripts/ci/validate_standalone_apk.sh --stage merge build/merged/instagram.apk

# after patching and signing
bash scripts/ci/validate_standalone_apk.sh --stage final \
  --expected-signer "$RELEASE_CERT_SHA256" feurstagram.apk
```

Checks:

1. `unzip -t` ZIP integrity.
2. `AndroidManifest.xml` and `resources.arsc` present.
3. No `requiredSplitTypes`, `splitTypes`, `isSplitRequired`, `configForSplit`,
   `uses-split`, `com.android.vending.splits(.required)`, or `com.android.stamp.*`.
4. No `res/xml/splits0.xml` and no `stamp-cert-sha256`.
5. `res/` count ≥ `MIN_RES_ENTRIES` (12,000 in CI) and merged density buckets
   `160/320/480/640`.
6. Package, version name, version code, `arm64-v8a` native code.
7. `resources.arsc` readable via `aapt2 dump configurations`.
8. Final stage only: `apksigner verify` with v2/v3, expected signer pin, and
   `zipalign -c -p 4`.

### Patch report

```sh
MIN_APPLIED_PATCHES=10 \
EXPECTED_PATCHES="Force SDR display,Feed item filtering,..." \
python3 scripts/ci/verify_patch_report.py build/patch-report.json
```

The Morphe CLI writes `build/patch-report.json` (`-r`). The verifier is the gate
between "Morphe said it applied everything" and "the APK may be signed and
published". It aborts the build when

- the report is missing or unreadable,
- any patch is listed as failed,
- the applied set is empty,
- fewer than `MIN_APPLIED_PATCHES` patches applied,
- a patch in `EXPECTED_PATCHES` did not apply and was not explicitly disabled
  through `DISABLE_PATCHES`.

The report shape differs between CLI releases (entries may be plain names or
objects, and the key names differ), so the verifier accepts every shape seen in
practice instead of assuming one. `scripts/ci/test_patch_report.sh` covers each
accepted shape and each failure mode. The raw report is uploaded as a text
artifact (`apk-build-report`) so the applied set can be audited after a run.

> The first gated run against the real bundle (run `43081109387`) applied all 13
> patches but aborted in the previous inline parser, which assumed object
> entries: `AttributeError: 'str' object has no attribute 'get'`. The verifier
> now normalises both shapes.

### Install smoke test

```sh
EXPECTED_PACKAGE=com.instagram.android bash scripts/ci/install_smoke_test.sh feurstagram.apk
```

Requires an attached device/emulator that reports `arm64-v8a` and API 35. It refuses
x86 targets because they cannot prove arm64 installation compatibility. It runs
`adb install -r -t`, checks `pm path`, and launches the package.

---

## 6. Signing

Signing is unchanged and only happens after merge + patch:

- Release mode uses the existing keystore secrets through `apksigner`, with
  v1 + v2 + v3 enabled and v4 disabled.
- Throwaway test mode is explicit (`allow_throwaway_signature=true`) and the artifact is
  labelled `-test`.
- Signature-check bypass is refused for release builds and requires throwaway signing.
- The release signer is pinned by `RELEASE_CERT_SHA256`; the final validator and the
  publish step both verify it.
- No keystore password, key, or token is written to logs or to `GITHUB_ENV` except the
  keystore path/passwords already required by the existing build script (unchanged).

---

## 7. GitHub Actions configuration

`.github/workflows/apk.yml` (manual, main-only, `apk-build` environment):

1. Checkout, JDK 21, Gradle, Android SDK 35 / build-tools 35.0.0.
2. Validate required inputs/secrets and token scope (unchanged).
3. Download the pinned bundle/APK, verify SHA-256.
4. Validate the bundle or single APK; for bundles, extract `base.apk` for identity
   checks.
5. Verify base identity: package, version, version code, Meta certificate.
6. Download pinned Morphe CLI and APKEditor.
7. Build the patch bundle and run `build.sh` (merge → merge validation → patch →
   patch report → sign → final validation).
8. Validate final standalone packaging again and verify the release signer.
9. Optional arm64/API 35 install smoke test (`run_install_test=true`).
10. Publish only a throwaway-signed `-test` APK to the private input repository,
    after re-validating digest, signer, package, version, and standalone packaging.
11. Always clean up proprietary inputs and credentials.

Preserved controls: private input repository, read-only `BASE_APK_TOKEN`, separate
`DIST_TOKEN`, `apk-build` environment, private debug release publishing, 72-hour
cleanup, main-only restriction, pinned actions, no APK artifact upload to the public
repository.

### Required secrets (names only)

`BASE_APK_URL`, `BASE_APK_SHA256`, `BASE_APK_TOKEN`, `MORPHE_CLI_URL`,
`MORPHE_CLI_SHA256`, `MORPHE_REGISTRY_USER`, `MORPHE_REGISTRY_TOKEN`,
`APKEDITOR_URL`, `APKEDITOR_SHA256`, `RELEASE_KEYSTORE_BASE64`,
`RELEASE_KEYSTORE_PASS`, `RELEASE_KEY_PASS`, `RELEASE_KEY_ALIAS`,
`RELEASE_CERT_SHA256`, `DIST_TOKEN`.

### Permissions

- Workflow: `contents: read`.
- `BASE_APK_TOKEN`: fine-grained, Contents: Read on the private input repository only.
- `DIST_TOKEN`: Contents: Read and write on the private input repository only, distinct
  from `BASE_APK_TOKEN`.

---

## 8. Testing

### Offline gate tests (CI, no Android SDK required)

`scripts/ci/test_universal_packaging.sh` uses real ZIP files and stubbed
`aapt`/`aapt2`/`apksigner`/`zipalign`/`adb`:

| Scenario | Expected | Result |
| --- | --- | --- |
| A. Complete valid bundle + merge + final APK | all pass | **pass** |
| B. Plain base APK with split metadata | rejected with "complete .apkm" guidance | **pass** |
| C. Wrong Instagram version | rejected | **pass** |
| D. Wrong bundle SHA-256 | rejected | **pass** |
| E. Missing density resources | rejected | **pass** |
| F. Split metadata / split table remaining | rejected | **pass** |
| G. Invalid signature / wrong signer | rejected | **pass** |
| H. Failed device install | fatal before publish (step order + publish re-validation) | **pass** |

The test is wired into `.github/workflows/ci.yml` and `scripts/ci/validate_repo.sh`.

### Real-artifact checks (local)

Run with the three known artifacts:

```sh
REAL_FIXTURES=1 \
WORKING_APK="/path/feurstagram-454-0-0-54-77.apk" \
FAILED_APK="/path/feurstagram-454.0.0.54.77-test.apk" \
STOCK_APK="/path/Instagram.apk" \
bash scripts/ci/test_universal_packaging.sh
```

| Artifact | SHA-256 | Result |
| --- | --- | --- |
| Official merged FeurStagram APK | `354553ff…` | **passes** final validation (no split metadata, 14,453 res entries, signer `cfddc301…`, zipalign OK) |
| Failed HMX APK | `68446312…` | **rejected**: split metadata / split table present |
| Stock base APK | `b334feef…` | **rejected** as a single input and as a bundle (not a complete bundle) |
| Tampered official APK | — | **rejected**: invalid signature |

### End-to-end build

Not run: the complete original bundle is unavailable (see limitations). No build success
is claimed.

---

## 9. Signing verification

The final validator checks:

- `apksigner verify --verbose --print-certs` returns `Verifies`;
- APK Signature Scheme v2 or v3 is present;
- the signer SHA-256 equals `RELEASE_CERT_SHA256` in release mode;
- `zipalign -c -p 4` succeeds;
- the publish step re-checks the signer and digest on the exact uploaded bytes.

---

## 10. Known limitations

1. **Complete bundle missing.** The pinned base (`Instagram.apk`, SHA-256 `b334feef…`,
   version code `386014758`) is a bundle-derived base APK, not a bundle. No APKMirror
   `.apkm` for version code `386014712` is available locally or in the private input
   repository. Until it is uploaded and pinned, `base_apk_kind=apkm` builds fail at the
   download/validation gate by design.
2. **Device installation test pending.** GitHub-hosted runners have no arm64 Android 15
   device. The install step is implemented and refuses x86 emulators, but it must be
   run on a self-hosted runner or real OPPO A38-class device with `run_install_test=true`
   before claiming device verification.
3. **End-to-end merge not executed.** APKEditor merge, Morphe patch, and signing run in
   CI once the bundle exists; none of those steps are claimed as executed here.
4. **`.apks` support** is accepted only when the bundle layout contains a base APK and
   config splits; a bundletool `.apks` without the density payload is rejected.
5. The `res/` floor (12,000) and density buckets are calibrated for this Instagram
   release. A future release with a materially different resource layout may need the
   pin updated intentionally.

---

## 11. Device installation verification

To verify on hardware:

1. Connect an arm64 API 35 device (OPPO A38 or equivalent) with USB debugging enabled.
2. Run the APK build workflow from `main` with `run_install_test=true`.
3. The step checks `ro.product.cpu.abi=arm64-v8a` and `ro.build.version.sdk=35`, then
   runs `adb install -r -t feurstagram-<version>-test.apk`, checks `pm path`, and
   launches the app.
4. A failed install stops the job **before** the private debug release is published.

If no device is attached, leave `run_install_test=false`; the job summary explicitly
records the device test as not run.

---

## 12. Next steps

Required from the operator:

1. Obtain the **complete original APKMirror `.apkm` bundle** for Instagram
   `454.0.0.54.77`, `arm64-v8a`, with density config splits. Do not use a single base
   APK and do not use the patched FeurStagram release.
2. Upload it as a new asset in the private `bharti1233/hmx-build-inputs` repository
   (do not overwrite `Apk-input-original`).
3. Record its immutable asset URL and SHA-256 in the `apk-build` environment secrets
   `BASE_APK_URL` / `BASE_APK_SHA256` (and `APKEDITOR_URL` / `APKEDITOR_SHA256` if not
   already set).
4. Run **APK build (gated)** from `main` with `base_apk_kind=apkm`,
   `instagram_version=454.0.0.54.77`, throwaway signing enabled, and
   `publish_test_apk=true` to produce a private debug release.
5. Run once with `run_install_test=true` on an arm64 API 35 device to close the device
   verification item.
6. Only after both succeed, consider a release-signed build.

Until step 1 is complete, the pipeline intentionally fails at the input gate rather than
producing an uninstallable APK.
