# Hmxgram Phase 1 CI Report

**Date:** 2026-11-11
**Repository:** `bharti1233/Hmxgram`
**Branch:** `ci/phase1-baseline`
**Commit:** `a27aff1` (`a27aff1` = head of PR #1 at the time of this report)
**Pull request:** https://github.com/bharti1233/Hmxgram/pull/1
**CI run:** https://github.com/bharti1233/Hmxgram/actions/runs/42968227013
**Stock APK verification run:** https://github.com/bharti1233/Hmxgram/actions/runs/42968215698

## Status

| Item | Result |
| --- | --- |
| Repository validation | **Success** |
| Build patch bundle (`.mpp`) | **Success** |
| Artifact upload | **Success** |
| Stock APK verification | **Success** |
| APK build | **Not run** — workflow is ready but must be dispatched from `main` |
| Local Gradle/APK build | **None performed** |

The `.mpp` bundle is a patch artifact, not an APK. A successful `.mpp` build does **not** mean an installable APK was produced. The stock APK verification proves the *input*, not the patched output.

## Verified CI result

- Workflow: `CI`
- Event: `pull_request`
- Head SHA: `a27aff1`
- Conclusion: **success**

| Job | Result | Job URL |
| --- | --- | --- |
| `Repository validation` | Success | https://github.com/bharti1233/Hmxgram/actions/runs/42968227013/job/130350342011 |
| `Build patch bundle (.mpp)` | Success | https://github.com/bharti1233/Hmxgram/actions/runs/42968227013/job/130350397265 |

Earlier green CI runs on the same branch:

- https://github.com/bharti1233/Hmxgram/actions/runs/42959373143 (`815dfba`)
- https://github.com/bharti1233/Hmxgram/actions/runs/42853041675 (`8d3a6dc`)
- https://github.com/bharti1233/Hmxgram/actions/runs/42850650287 (`58789b8`)

## Bundle artifact

| Field | Value |
| --- | --- |
| File | `patches-1.0.0.mpp` |
| Workflow artifact | `patch-bundle` |
| Retention | 14 days |

The `.mpp` SHA-256 is not stable across builds, because the bundle embeds build metadata. Use the `.sha256` written by the run that produced the artifact. Observed values:

| Commit | SHA-256 |
| --- | --- |
| `58789b8` | `5fc6201ef39a006ae358ceba29b2144e346a0eb820f212ac0142cf1a178b00de` |
| `8d3a6dc` | `622fd485df414b756061c5d312f223484e4d7262d16926e9961baa60af68e339` |

The bundle was built with JDK 21, Android SDK 35, and build-tools 35.0.0 on `ubuntu-latest`.

## Stock APK verification

The base APK is stored as a private release asset in `bharti1233/hmx-build-inputs` and downloaded on the runner only.

| Field | Value |
| --- | --- |
| Input repository | `bharti1233/hmx-build-inputs` (private) |
| Release tag | `Apk-input-original` |
| Asset name | `Instagram.apk` |
| Asset ID | `734297498` |
| Size | `259,865,899` bytes (247.8 MiB) |
| SHA-256 | `b334feef23722f0364a80c2c9e99af48e0c460cc652213d3f1a919f2007e5136` |
| Package | `com.instagram.android` |
| Version name | `454.0.0.54.77` |
| Version code | `386014758` |
| Min SDK | `28` |
| Target SDK | `37` |
| Native code | `arm64-v8a` only |
| Label | `Instagram` |

The downloaded file's SHA-256 matched the GitHub asset digest exactly, and the workflow's `sha256sum -c` check reported `OK`.

### Signer

| Field | Value |
| --- | --- |
| Current signer DN | `C=US, ST=California, L=Menlo Park, O=Meta Platforms Inc., OU=Meta Mobile, CN=Meta Platforms Inc.` |
| Current signer cert SHA-256 | `3a10c50c18ba937506c2875e9047be74f8fd8b86fae2ff9feaa50a81fcb4c014` |
| Key algorithm | RSA 4096 |
| Legacy lineage signer | `CN=Kevin Systrom, O=Instagram Inc, L=San Francisco, ST=California, C=US` (RSA 1024) |
| Legacy signer cert SHA-256 | `5f3e50f435583c9ae626302a71f7340044087a7e2c60adacfc254205a993e305` |
| Google Play SourceStamp signer | `CN=Android, OU=Android, O=Google Inc., L=Mountain View, ST=California, C=US` |

### Signature schemes

```text
Verified using v1 scheme (JAR signing): false
Verified using v2 scheme (APK Signature Scheme v2): false
Verified using v3 scheme (APK Signature Scheme v3): true
Verified using v3.1 scheme (APK Signature Scheme v3.1): true
Verified using v4 scheme (APK Signature Scheme v4): false
Verified for SourceStamp: true
Number of signers: 1
```

The absence of v1/v2 is expected for a modern Play-distributed app with `minSdkVersion=28`; the v3/v3.1 chain verifies.

### Stock checks

- No `Feurstagram` or `Morphe` entries in the APK's file list.
- No `Lcom/feurstagram/` classes in the dex payload.
- The signer is not `CN=Feurstagram`, so this is not the already-patched build installed on the development device.
- The verification job uploaded only a text report (`base-apk-verification`) and deleted the APK in an `always()` step.

## What the PR adds

- `.github/workflows/ci.yml` — validation and `.mpp` build on push/PR to `main`, plus manual dispatch.
- `.github/workflows/apk.yml` — manual, environment-gated APK build. It does not run on push or PR.
- `.github/workflows/verify-base.yml` — read-only stock APK verification; no patching, no APK upload.
- `scripts/ci/validate_repo.sh` — license/attribution, tracked-artifact, secret-pattern, executable-mode, and workflow-safety checks.
- `patches/build.gradle.kts` — package metadata points to the Hmxgram source/website while preserving Jean Herail's credit.
- `gradle/wrapper/gradle-wrapper.properties` — bounded retries for the Gradle distribution download.

## Actions secrets configured

Only names are recorded here. No secret values are stored in the repository or in this report.

| Secret | Purpose |
| --- | --- |
| `MORPHE_REGISTRY_USER` | GitHub Packages username |
| `MORPHE_REGISTRY_TOKEN` | Token with `read:packages` for the Morphe registry |
| `MORPHE_CLI_URL` | Pinned Morphe Desktop CLI release URL |
| `MORPHE_CLI_SHA256` | SHA-256 of the pinned CLI jar |
| `APKEDITOR_URL` | Pinned APKEditor release URL |
| `APKEDITOR_SHA256` | SHA-256 of the pinned APKEditor jar |
| `BASE_APK_URL` | API asset URL of the stock APK (`https://api.github.com/repos/bharti1233/hmx-build-inputs/releases/assets/734297498`) |
| `BASE_APK_SHA256` | SHA-256 of the stock APK |
| `BASE_APK_TOKEN` | Token used to read the private release asset |

The `apk-build` environment exists with a required reviewer (`bharti1233`) and a deployment branch policy restricted to `main`.

Private release assets must be fetched through the API asset URL with `Accept: application/octet-stream`. The browser URL `github.com/OWNER/REPO/releases/download/TAG/FILE` returns `404` for a private repository even with a valid token.

## APK workflow readiness

The workflow is implemented and every input it needs is configured, but it has **not** been dispatched. It requires:

1. ~~A stock, Meta-signed Instagram APK~~ — **verified** (`Instagram.apk`, `454.0.0.54.77`).
2. ~~`BASE_APK_URL` and `BASE_APK_SHA256`~~ — **configured**.
3. Signing material — `allow_throwaway_signature=true` for the debug/test artifact, or `RELEASE_KEYSTORE_BASE64`/`RELEASE_KEYSTORE_PASS` (plus optional alias/password) for a release build.
4. ~~`MORPHE_CLI_*` and `APKEDITOR_*` secrets~~ — **configured**.

Two constraints still block dispatch:

- `workflow_dispatch` only exposes workflows that exist on the default branch, so PR #1 must be merged to `main` first.
- The `apk-build` environment only allows deployments from `main`.

Debug build inputs for the first run:

| Input | Value |
| --- | --- |
| `instagram_version` | `454.0.0.54.77` |
| `base_apk_kind` | `apk` |
| `clone` | `false` |
| `allow_throwaway_signature` | `true` |

The APK currently installed on the development device is **not** a stock base: it is version `454.0.0.54.77` but is signed by `CN=Feurstagram` (SHA-256 `cfddc301047c7b08b7151cf15d91a585f1d9518b6cdefadfe40f0996d11b3918`). Patching an already-patched APK is not a supported baseline.

APKPure and APKMirror are protected by Cloudflare challenge pages, so the workflow does not attempt to scrape them. The stock APK is supplied as a private release asset instead.

## Security decisions

- No APK, keystore, Morphe CLI, stock APK, or registry token is committed.
- The APK workflow is manual and gated by the `apk-build` environment.
- The base APK is downloaded into the runner temp directory, verified by SHA-256, and deleted in an `always()` cleanup step.
- The patched APK is not a release artifact by default. A debug build must use a throwaway signature and must not be distributed.
- APK release production remains blocked on the legal/security gate described in `HMX_INSTAPLUS_SECURITY_AUDIT.md` (SEC-11).

## Next actions

1. Review and merge PR #1 (`a27aff1`) to `main`.
2. Dispatch `APK build (gated)` from `main` with `allow_throwaway_signature=true` to exercise the full patch/sign path.
3. Inspect the resulting artifact and logs; do not treat a throwaway-signed APK as a release.
4. For a release build, provide the release keystore and complete the distribution review.
5. Delete the stock APK release asset after the base APK is no longer needed, and rotate any GitHub token that was shared during setup.
