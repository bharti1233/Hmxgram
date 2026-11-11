# Hmxgram Phase 1 CI Report

**Date:** 2026-11-11
**Repository:** `bharti1233/Hmxgram`
**Branch:** `ci/phase1-baseline`
**Pull request:** https://github.com/bharti1233/Hmxgram/pull/1
**CI runs:** linked from the PR checks; this document is updated alongside the branch.

## Status

| Item | Result |
| --- | --- |
| Repository validation | **Success** |
| Build patch bundle (`.mpp`) | **Success** |
| Artifact upload (`.mpp` only) | **Success** |
| Stock APK verification | **Success** |
| APK build | **Not run** — workflow is ready but must be dispatched from `main` after merge |
| APK artifact upload | **Disabled by design** — see “APK artifact policy” |
| Local Gradle/APK build | **None performed** |

The `.mpp` bundle is a patch artifact, not an APK. A successful `.mpp` build does **not** mean an installable APK was produced. The stock APK verification proves the *input*, not the patched output.

## Verified stock APK input

The base APK is stored as a private release asset and downloaded on the runner only.

| Field | Value |
| --- | --- |
| Input repository | `bharti1233/hmx-build-inputs` (private) |
| Release tag | `Apk-input-original` |
| Asset name | `Instagram.apk` |
| Asset ID | not recorded (redacted on purpose) |
| Size | `259,865,899` bytes (247.8 MiB) |
| SHA-256 | `b334feef23722f0364a80c2c9e99af48e0c460cc652213d3f1a919f2007e5136` |
| Package | `com.instagram.android` |
| Version name | `454.0.0.54.77` |
| Version code | `386014758` |
| Min SDK | `28` |
| Target SDK | `37` |
| Native code | `arm64-v8a` only |
| Label | `Instagram` |

The downloaded file's SHA-256 matched the GitHub asset digest exactly, and `sha256sum -c` reported `OK`.

### Signer

| Field | Value |
| --- | --- |
| Current signer DN | `C=US, ST=California, L=Menlo Park, O=Meta Platforms Inc., OU=Meta Mobile, CN=Meta Platforms Inc.` |
| Current signer cert SHA-256 | `3a10c50c18ba937506c2875e9047be74f8fd8b86fae2ff9feaa50a81fcb4c014` |
| Key algorithm | RSA 4096 |
| Legacy lineage signer | `CN=Kevin Systrom, O=Instagram Inc, L=San Francisco, ST=California, C=US` (RSA 1024) |
| Google Play SourceStamp signer | `CN=Android, OU=Android, O=Google Inc., L=Mountain View, ST=California, C=US` |

`apksigner` reports v3 and v3.1 verified and v1/v2 false, which is normal for a modern Play build with `minSdkVersion=28`.

## Security hardening (Phase 1.1)

The gated APK workflow now enforces the following at runtime.

### Signature-check bypass

- `SignatureCheckBypassPatch` remains `default = true` in the patch bundle.
- `build.sh` now passes `-d "Signature check bypass"` unless the caller passes
  `--allow-signature-bypass`, so the bypass is **off by default**.
- The workflow exposes `allow_signature_bypass` (default `false`). Enabling it
  requires `allow_throwaway_signature=true`; a release keystore makes the job
  fail before any download.
- `build.sh` independently refuses `--allow-signature-bypass` when release
  keystore signing is active.
- The job summary always prints the selected signing mode and bypass state.

### APK artifact policy

The patched APK is **not uploaded** as an Actions artifact. Artifacts on a
public repository are not private storage, and the APK contains proprietary
Meta code. The workflow uploads only `feurstagram.apk.sha256` and the signer
certificate dump. The APK itself is deleted in an `always()` cleanup step.
Do not re-enable APK upload until a verified private distribution channel
exists.

### Signing

- Throwaway signing is an explicit, default-off input and is labelled with a
  `-test` suffix in the output filename and summary.
- Release builds must provide `RELEASE_CERT_SHA256`; the workflow refuses to
  finish unless the output APK presents that certificate.
- Release keystore and throwaway signing cannot be combined; the job fails on
  the ambiguous case.

### Input verification

Before patching, the workflow asserts:

- `base_apk_kind=apk` (split bundles are rejected until merge/signer checks are implemented);
- package `com.instagram.android`;
- `versionName` `454.0.0.54.77`, and the `instagram_version` input matches it;
- the expected Meta certificate `3a10c50c…c014` is present;
- the downloaded bytes match `BASE_APK_SHA256`.

### Workflow and supply chain

- The APK job runs only when `github.ref == 'refs/heads/main'`, in addition to
  the `apk-build` environment’s main-only deployment policy and required
  reviewer.
- All third-party actions are pinned to full commit SHAs.
- The private-asset token must be fine-grained. A classic PAT that reports
  broad scopes (`repo`, `workflow`, `write:packages`, …) is rejected before the
  download, and nothing prints the token.

## Secrets

Secret **names** only; no values are stored in this repository.

### `apk-build` environment secrets

| Secret | Purpose |
| --- | --- |
| `BASE_APK_URL` | API asset URL of the private stock APK |
| `BASE_APK_SHA256` | SHA-256 of the stock APK |
| `BASE_APK_TOKEN` | Fine-grained token, Contents: Read on the private input repository |
| `RELEASE_KEYSTORE_BASE64` | Release keystore, base64 (set only for release builds) |
| `RELEASE_KEYSTORE_PASS` | Release keystore password |
| `RELEASE_KEY_PASS` | Release key password (optional) |
| `RELEASE_KEY_ALIAS` | Release key alias (optional) |
| `RELEASE_CERT_SHA256` | Expected release signing certificate SHA-256 (required for release builds) |

### Repository secrets

| Secret | Purpose |
| --- | --- |
| `MORPHE_REGISTRY_USER` | GitHub Packages username |
| `MORPHE_REGISTRY_TOKEN` | Token used to read the Morphe registry (`.mpp` build only) |
| `MORPHE_CLI_URL` | Pinned Morphe Desktop CLI release URL |
| `MORPHE_CLI_SHA256` | SHA-256 of the pinned CLI jar |
| `APKEDITOR_URL` | Pinned APKEditor release URL |
| `APKEDITOR_SHA256` | SHA-256 of the pinned APKEditor jar |

The registry token is only needed for `.mpp` builds and should be rotated to a
least-privilege `read:packages` token.

## Branch protection (NOT enabled)

Verified via the GitHub API on 2026-11-11:

| Check | Result |
| --- | --- |
| `GET /branches/main/protection` | `404 Branch not protected` |
| `GET /rulesets` | `[]` (no rulesets) |
| `apk-build` environment | required reviewer `bharti1233`; custom deployment policy = `main` |
| Rulesets API available to this repository | Yes (admin access) |

Branch protection is **not** enabled. It is deliberately deferred because a
required-review rule would block the sole maintainer from merging PR #1, and
required status checks should be added after the workflows exist on `main`.

Recommended ruleset for `main`:

1. Block branch deletion and non-fast-forward pushes.
2. Require a pull request before merging, with at least one approval once a
   second maintainer exists.
3. Require the `Repository validation` and `Build patch bundle (.mpp)` checks.
4. Require signed commits if the maintainers use signing.
5. Restrict bypass to repository admins, and document every bypass.

## APK workflow readiness

Every input is configured, but the workflow has **not** been dispatched.

| Input | Value |
| --- | --- |
| `instagram_version` | `454.0.0.54.77` |
| `base_apk_kind` | `apk` |
| `clone` | `false` |
| `allow_throwaway_signature` | `true` for the first test build |
| `allow_signature_bypass` | `false` (enable only if testing deep links) |

Remaining blockers before dispatch:

1. PR #1 must be merged to `main` (`workflow_dispatch` requires the file on the
   default branch, and the environment is `main`-only).
2. `BASE_APK_TOKEN` must be swapped from the current classic PAT to a
   fine-grained token with **Contents: Read** on `bharti1233/hmx-build-inputs`.
   The workflow rejects broad classic scopes by design.
3. A release build additionally requires the release keystore and
   `RELEASE_CERT_SHA256`.

Expected first-test behaviour: throwaway signing, bypass disabled, output named
`feurstagram-454.0.0.54.77-test.apk`, not uploaded, deleted at job end.

## Next actions

1. Create the fine-grained asset token and replace the `apk-build`
   environment secret `BASE_APK_TOKEN`.
2. Review and merge PR #1.
3. Dispatch the gated APK workflow from `main` with the inputs above.
4. Delete the stock APK release asset after the base is no longer needed.
5. Rotate the registry token to `read:packages` only.
