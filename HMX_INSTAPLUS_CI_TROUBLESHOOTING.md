# Hmxgram CI Troubleshooting

This document records the failures observed during the first Phase 1 CI runs, their causes, and the fixes applied. It is intended for maintainers and contributors who need to reproduce or repair the workflows.

> Do not paste tokens, keystores, APK URLs, or signing passwords into issues, pull requests, workflow YAML, or logs. Use GitHub Actions secrets.

## Quick reference

| Symptom | Cause | Fix |
| --- | --- | --- |
| `Failed to find package 'tools'` in `Set up Android SDK` | `android-actions/setup-android@v3` defaults to installing the obsolete `tools` package | Set `packages: platform-tools` and `cmdline-tools-version: '20.0'` |
| Gradle wrapper fails with HTTP 500 from `services.gradle.org` | One-shot download with `retries=0` and a 10-second timeout | Set `retries=3`, `retryBackOffMs=1000`, `networkTimeout=60000` |
| `Missing registry credentials` | `MORPHE_REGISTRY_USER` or `MORPHE_REGISTRY_TOKEN` is absent | Add both repository secrets |
| `401 Unauthorized` from `maven.pkg.github.com/MorpheApp/registry` | Token lacks `read:packages` | Add `read:packages` to the classic PAT or create a new one |
| APK workflow has no stock base | A proprietary Instagram APK is required and is not in the repository | Supply it through a private HTTPS URL + SHA-256 |
| APK workflow rejects the installed APK | The installed APK is already patched and signed by `CN=Feurstagram` | Use a genuine stock, Meta-signed APK |
| `workflow_dispatch` does not appear | The workflow is not on the default branch | Merge the workflow to `main` first |
| A push to the PR branch shows no new run immediately | GitHub Actions registration delay | Wait and re-check the run list |

## 1. Android SDK setup failed on the obsolete `tools` package

### Symptom

The first bundle run failed at `Set up Android SDK`:

```text
Warning: Failed to find package 'tools'
Error: The process '.../sdkmanager' failed with exit code 1
```

### Cause

`android-actions/setup-android@v3` defaults to `packages: tools platform-tools`. Current Android command-line tools no longer publish a package named `tools`, so `sdkmanager tools` exits non-zero.

### Fix

Both workflows now use the runner's preinstalled command-line tools and install only `platform-tools` through the action:

```yaml
- name: Set up Android SDK
  uses: android-actions/setup-android@v3
  with:
    cmdline-tools-version: '20.0'
    packages: platform-tools
    accept-android-sdk-licenses: true
```

The next step installs the actual build targets:

```bash
sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"
```

The run log should show both `platforms;android-35` and `build-tools;35.0.0` in `sdkmanager --list_installed`.

## 2. Gradle distribution download returned HTTP 500

### Symptom

The toolchain verification step failed while downloading Gradle:

```text
Attempt 1/1 failed. Reason: Server returned HTTP response code: 500
for URL: https://services.gradle.org/distributions/gradle-9.5.0-bin.zip
```

### Cause

The wrapper was configured with `retries=0` and `networkTimeout=10000`. A transient error from the Gradle distribution redirect (GitHub release assets) aborted the run immediately.

### Fix

`gradle/wrapper/gradle-wrapper.properties` now uses:

```properties
networkTimeout=60000
retries=3
retryBackOffMs=1000
```

The distribution SHA-256 checksum remains pinned:

```properties
distributionSha256Sum=553c78f50dafcd54d65b9a444649057857469edf836431389695608536d6b746
```

Do not remove the checksum to work around a download failure.

## 3. Missing Morphe registry credentials

### Symptom

The bundle job reached the credential step and failed with:

```text
##[error]Set repository secrets MORPHE_REGISTRY_USER and MORPHE_REGISTRY_TOKEN (read:packages).
```

### Cause

The `app.morphe.patches` Gradle plugin and the `morphe-patches-library` dependency are published only to GitHub Packages:

```text
https://maven.pkg.github.com/MorpheApp/registry
```

Anonymous access returns `401`. A token scoped only to the Hmxgram repository does not grant access to the MorpheApp registry.

### Fix

Create a classic personal access token with `read:packages` and add it as the repository secret `MORPHE_REGISTRY_TOKEN`. Add the token owner's username as `MORPHE_REGISTRY_USER`.

Test the token without printing it:

```bash
curl -sS -o /dev/null -w '%{http_code}\n' \
  -u "$GITHUB_USER:$GITHUB_TOKEN" \
  "https://maven.pkg.github.com/MorpheApp/registry/app/morphe/patches/app.morphe.patches.gradle.plugin/1.3.2/app.morphe.patches.gradle.plugin-1.3.2.pom"
```

A working token returns a redirect such as `302`; a missing scope returns `401`.

The workflow writes the credentials to `$HOME/.gradle/gradle.properties` with `umask 077` and deletes the file in an `always()` step.

## 4. APK workflow has no stock base APK

### Symptom

The `APK build (gated)` workflow cannot start without `BASE_APK_URL` and `BASE_APK_SHA256`, and it is not triggered by pushes or pull requests.

### Cause

The repository contains patch code only. The Instagram APK is proprietary and must not be committed, cached, or uploaded to a public artifact store.

### Fix

Supply a stock APK through a private HTTPS URL and record its SHA-256. The workflow downloads it into `$RUNNER_TEMP`, verifies the hash, and deletes it after the job.

Do not scrape APKPure or APKMirror from CI. Both are protected by Cloudflare challenge pages; bypassing those protections is out of scope and unreliable.

### Installed APK is not a stock base

The APK installed on the development device is version `454.0.0.54.77`, but its signer is:

```text
CN=Feurstagram
SHA-256: cfddc301047c7b08b7151cf15d91a585f1d9518b6cdefadfe40f0996d11b3918
```

That means it is an already-patched FeurStagram build. Patching it again is unsupported. The base must be a genuine Meta-signed build.

## 5. Token scope confusion

A classic PAT can be edited in place; its value does not change when scopes are added. Re-pasting the same token does not add scopes. Verify the scopes with:

```bash
curl -sS -D - -o /dev/null -H "Authorization: Bearer $GITHUB_TOKEN" \
  https://api.github.com/user | grep -i '^x-oauth-scopes:'
```

For the Morphe registry, the required scope is `read:packages`. The token used for repository administration also needs `repo`; `workflow` is needed to re-run or dispatch workflows.

Rotate any token that was shared in chat after the CI setup is complete. Do not reuse an exposed token.

## 6. Workflow dispatch and PR timing

- `workflow_dispatch` only works after the workflow file exists on the default branch. Until the PR is merged, use a `pull_request` run or re-run an existing run.
- A push to a PR branch can take a short time to appear as a new run. Check the run list by `head_sha` rather than assuming the push failed to trigger CI.
- Re-running only failed jobs preserves the already-successful validation job and uses the current repository secrets.

## 7. APK signing modes

The APK workflow has two signing paths:

1. **Release**: `RELEASE_KEYSTORE_BASE64` and `RELEASE_KEYSTORE_PASS` (plus optional `RELEASE_KEY_PASS` and `RELEASE_KEY_ALIAS`). The APK is built unsigned and signed with `apksigner` using v1/v2/v3.
2. **Debug/test**: `allow_throwaway_signature=true`. The Morphe CLI signs with a throwaway key. This artifact must not be distributed or presented as a release.

The workflow never prints the keystore password. The temporary keystore and downloaded base APK are removed in the cleanup step.

## 8. Reproducing the checks locally

Only lightweight, non-build checks should be run on a low-resource device:

```bash
bash scripts/ci/validate_repo.sh
```

Do not run Gradle or the APK patching pipeline on the development device. Builds belong on GitHub Actions. If a local check requires Gradle, use the Actions run instead.
