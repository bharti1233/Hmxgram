# Hmxgram Phase 1 CI Report

**Date:** 2026-11-10
**Repository:** `bharti1233/Hmxgram`
**Branch:** `ci/phase1-baseline`
**Commit:** `58789b895686c2694fc41ace992310c1afb711a6`
**Pull request:** https://github.com/bharti1233/Hmxgram/pull/1
**Workflow run:** https://github.com/bharti1233/Hmxgram/actions/runs/42850650287

## Status

| Item | Result |
| --- | --- |
| Repository validation | **Success** |
| Build patch bundle (`.mpp`) | **Success** |
| Artifact upload | **Success** |
| APK build | **Not started** — requires a stock Instagram APK and signing input |
| Local Gradle/APK build | **None performed** |

The `.mpp` bundle is a patch artifact, not an APK. A successful `.mpp` build does **not** mean an installable APK was produced.

## Verified CI result

- Workflow: `CI`
- Event: `pull_request`
- Attempt: `2` (first attempt failed on infrastructure inputs; see the troubleshooting document)
- Head SHA: `58789b895686c2694fc41ace992310c1afb711a6`
- Started: `2026-11-10T16:02:58Z`
- Finished: `2026-11-10T16:12:05Z`
- Conclusion: **success**

| Job | Result | Duration |
| --- | --- | --- |
| `Repository validation` | Success | ~10 s |
| `Build patch bundle (.mpp)` | Success | ~2 min |

Job URLs:

- Validation: https://github.com/bharti1233/Hmxgram/actions/runs/42850650287/job/129954082646
- Bundle: https://github.com/bharti1233/Hmxgram/actions/runs/42850650287/job/129954081126

## Bundle artifact

| Field | Value |
| --- | --- |
| File | `patches-1.0.0.mpp` |
| Size | `1,265,320` bytes |
| SHA-256 | `5fc6201ef39a006ae358ceba29b2144e346a0eb820f212ac0142cf1a178b00de` |
| Workflow artifact | `patch-bundle` (ID `13779713967`) |
| Retention | 14 days |

The run log reports:

```text
BUILD SUCCESSFUL in 1m 39s
29 actionable tasks: 29 executed
```

The bundle was built with JDK 21, Android SDK 35, and build-tools 35.0.0 on `ubuntu-latest`.

## What the PR adds

- `.github/workflows/ci.yml` — validation and `.mpp` build on push/PR to `main`, plus manual dispatch.
- `.github/workflows/apk.yml` — manual, environment-gated APK build. It does not run on push or PR.
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

The `apk-build` environment exists with a required reviewer and a deployment branch policy restricted to `main`.

## APK workflow readiness

The workflow is implemented but has not been run. It requires:

1. A **stock, Meta-signed** Instagram APK or split bundle.
2. `BASE_APK_URL` and `BASE_APK_SHA256`.
3. Signing material — either `RELEASE_KEYSTORE_BASE64`/`RELEASE_KEYSTORE_PASS` (plus optional alias/password), or the `allow_throwaway_signature` input for a debug/test artifact.
4. `MORPHE_CLI_*` and `APKEDITOR_*` secrets, which are already configured.

The APK currently installed on the development device is **not** a stock base: it is version `454.0.0.54.77` but is signed by `CN=Feurstagram` (SHA-256 `cfddc301047c7b08b7151cf15d91a585f1d9518b6cdefadfe40f0996d11b3918`). Patching an already-patched APK is not a supported baseline.

APKPure and APKMirror are protected by Cloudflare challenge pages, so the workflow does not attempt to scrape them. The stock APK must be supplied through a private HTTPS URL or a private release asset.

## Security decisions

- No APK, keystore, Morphe CLI, stock APK, or registry token is committed.
- The APK workflow is manual and gated by the `apk-build` environment.
- The base APK is downloaded into the runner temp directory, verified by SHA-256, and deleted in an `always()` cleanup step.
- The patched APK is not a release artifact by default. A debug build must use a throwaway signature and must not be distributed.
- APK release production remains blocked on the legal/security gate described in `HMX_INSTAPLUS_SECURITY_AUDIT.md` (SEC-11).

## Next actions

1. Review and merge PR #1 after the documentation commit passes CI.
2. Supply a stock Instagram APK through a private URL and set `BASE_APK_URL`/`BASE_APK_SHA256`.
3. Run the `APK build (gated)` workflow from `main` with `allow_throwaway_signature=true` for a debug/test artifact.
4. For a release build, provide the release keystore and complete the distribution review.
5. Rotate any GitHub token that was shared in chat after CI setup is complete.
