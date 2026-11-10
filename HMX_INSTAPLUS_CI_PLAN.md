# HMX Insta+ — CI Plan

**Project under audit:** FeurStagram
**Audited commit:** `bad4cd060db42e4434e254e37cf77e784de96d7e`
**Current CI state:** none (`.github/workflows` does not exist)
**Plan status:** proposal only — no workflow has been added or run

---

## 1. Goals and non-goals

### Goals

1. Prove on every change that the Kotlin patch bundle compiles and the extension builds.
2. Catch accidental secrets, APKs, keystores, and debug-build settings before merge.
3. Make registry authentication explicit and auditable.
4. Produce a deterministic `.mpp` artifact with a SHA-256 checksum.
5. Create a safe, manual path for signed APK releases without exposing proprietary inputs to public runners.
6. Add tests incrementally around pure Java helpers and patch application.

### Non-goals

- Building an installable APK on GitHub-hosted runners from a clean checkout. That is impossible without a proprietary Instagram APK, the Morphe CLI, and a signing key.
- Uploading the stock Instagram APK or a patched APK to public CI artifacts.
- Automating device smoke tests on hosted runners.
- Replacing legal review for APK distribution.

---

## 2. Current state

| Item | State | Evidence |
|---|---|---|
| Workflows | None | `.github/workflows` absent |
| Issue/PR templates | Present | `.github/ISSUE_TEMPLATE`, `PULL_REQUEST_TEMPLATE.md` |
| Tests | None | no `src/test` / `src/androidTest` |
| Gradle wrapper checksum | Pinned | `gradle/wrapper/gradle-wrapper.properties` |
| Registry auth | Required | `settings.gradle.kts:7-13` |
| Build script | Manual | `build.sh` |
| Release process | Manual | `docs/REPOSITORY_SETTINGS.md`, `.github/RELEASE_TEMPLATE.md` |

The repository therefore has no automated regression signal today.

---

## 3. Proposed pipeline

```text
Pull request / push
        │
        ├─ Job 1: repository hygiene (no secrets/APKs/keystores, license present)
        ├─ Job 2: wrapper validation
        ├─ Job 3: patch bundle build (JDK 21, SDK 35, read:packages)
        │            └─ upload .mpp + SHA-256 + job summary
        └─ Job 4 (manual, protected): signed release build on self-hosted runner
                     └─ APK + hashes + notes + approval
```

### Stage detail

| Stage | Runs on | Network access | Secrets | Output |
|---|---|---|---|---|
| Hygiene | `ubuntu-latest` | Minimal | None | Pass/fail |
| Wrapper validation | `ubuntu-latest` | Gradle services | None | Pass/fail |
| Bundle build | `ubuntu-latest` | GitHub Packages, Google, Gradle, JitPack | `read:packages` | `.mpp`, checksum |
| Unit tests | `ubuntu-latest` | Maven/Gradle | None | Test report |
| Patch integration | Self-hosted, controlled | Internal | Base APK + CLI pre-provisioned | `patch-report.json` |
| Signed release | Self-hosted, protected environment | Internal | Keystore + passwords | APK + SHA-256 |

---

## 4. Required secrets and variables

### 4.1 Bundle build

| Name | Type | Purpose |
|---|---|---|
| `MORPHE_REGISTRY_USER` | Secret | GitHub username for `gpr.user` / `GITHUB_ACTOR` |
| `MORPHE_REGISTRY_TOKEN` | Secret | PAT with `read:packages` for the MorpheApp registry |

`GITHUB_TOKEN` provided by Actions is **not** assumed to have access to another organization’s packages. Use a dedicated PAT, ideally a fine-grained token limited to package read access. Rotate it on a schedule.

### 4.2 Release APK (only if APK publishing is approved)

| Name | Type | Purpose |
|---|---|---|
| `RELEASE_KEYSTORE_BASE64` | Secret | Base64-encoded PKCS12 keystore |
| `RELEASE_KEYSTORE_PASS` | Secret | Keystore password |
| `RELEASE_KEY_PASS` | Secret | Key password (optional) |
| `RELEASE_KEY_ALIAS` | Secret | Key alias |
| `BASE_APK_PATH` | Runner variable | Pre-provisioned stock APK path on the self-hosted runner |
| `MORPHE_CLI_PATH` | Runner variable | Pre-provisioned Morphe CLI jar path |

Do **not** store the base APK in GitHub Actions artifacts, caches, or the repository. Provision it on a controlled runner and delete it after the job.

### 4.3 Fork pull requests

Secrets are not available to `pull_request` runs from forks. The workflow must:

- run hygiene and wrapper validation on all PRs;
- skip the credentialed bundle build on fork PRs;
- require a maintainer to label/approve before a credentialed build runs.

Never use `pull_request_target` to check out and build untrusted PR code with secrets.

---

## 5. Proposed workflow (bundle CI)

Create `.github/workflows/patch-bundle.yml`:

```yaml
name: Patch bundle CI

on:
  push:
    branches: [main, "hmx/**"]
  pull_request:
  workflow_dispatch:

permissions:
  contents: read

concurrency:
  group: patch-bundle-${{ github.ref }}
  cancel-in-progress: true

jobs:
  hygiene:
    name: Repository hygiene
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0

      - name: Reject committed binaries and signing material
        run: |
          set -euo pipefail
          bad=$(git ls-files | grep -Ei '\.(apk|apkm|xapk|apks|keystore|jks|mpp|mpe)$|^tools/' || true)
          if [ -n "$bad" ]; then
            echo "Forbidden tracked files:"
            echo "$bad"
            exit 1
          fi

      - name: Require license and notice
        run: |
          test -f LICENSE
          test -f NOTICE
          grep -qi "GNU GENERAL PUBLIC LICENSE" LICENSE

      - name: Reject debug-build markers in release sources
        run: |
          set -euo pipefail
          if grep -RIn --include='*.yml' --include='*.yaml' \
             -E 'debuggable|--debug' .github/workflows; then
            echo "Debug markers are not allowed in release workflows."
            exit 1
          fi

  wrapper:
    name: Gradle wrapper validation
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: gradle/actions/wrapper-validation@v4

  bundle:
    name: Build patch bundle
    runs-on: ubuntu-latest
    needs: [hygiene, wrapper]
    if: >-
      github.event_name != 'pull_request' ||
      github.event.pull_request.head.repo.full_name == github.repository
    env:
      GITHUB_ACTOR: ${{ secrets.MORPHE_REGISTRY_USER }}
      GITHUB_TOKEN: ${{ secrets.MORPHE_REGISTRY_TOKEN }}
    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'

      - name: Set up Gradle
        uses: gradle/actions/setup-gradle@v4

      - name: Set up Android SDK
        uses: android-actions/setup-android@v3

      - name: Install Android SDK packages
        run: |
          yes | sdkmanager --licenses > /dev/null
          sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"

      - name: Verify toolchain
        run: |
          java -version
          ./gradlew --version

      - name: Build patch bundle
        run: ./gradlew :patches:build --no-daemon --stacktrace

      - name: Collect artifacts
        run: |
          set -euo pipefail
          mpp=$(find patches/build/libs -maxdepth 1 -name 'patches-*.mpp' ! -name '*-sources*' ! -name '*-javadoc*' | head -1)
          test -n "$mpp"
          echo "MPP=$mpp" >> "$GITHUB_ENV"
          sha256sum "$mpp" | tee "$mpp.sha256"

      - name: Job summary
        run: |
          {
            echo "## Patch bundle build"
            echo ""
            echo "- Commit: \`${GITHUB_SHA}\`"
            echo "- JDK: \`$(java -version 2>&1 | head -1)\`"
            echo "- Bundle: \`${MPP}\`"
            echo "- SHA-256:"
            echo '```'
            cat "${MPP}.sha256"
            echo '```'
          } >> "$GITHUB_STEP_SUMMARY"

      - name: Upload bundle
        uses: actions/upload-artifact@v4
        with:
          name: patch-bundle
          path: |
            ${{ env.MPP }}
            ${{ env.MPP }}.sha256
          retention-days: 14
          if-no-files-found: error
```

### Notes on the workflow

- Actions are referenced by major tag for readability. In production, pin each action to a full commit SHA and use Dependabot to update.
- `sdkmanager --licenses` requires the SDK root; `android-actions/setup-android@v3` provides it.
- The `bundle` job is skipped for fork PRs because secrets are unavailable. Add a maintainer-approved job for trusted forks if needed.
- Do not print the token. Gradle may log repository URLs; that is fine. Ensure `--info`/`--debug` is not enabled on credentialed steps.
- The `.mpp` is a build artifact, not an APK. It may embed project resources, but not Instagram’s proprietary code.

---

## 6. Test plan

There are currently no tests. Add them in layers, without blocking the first CI rollout.

### Layer 1 — Compile and static checks (immediate)

- Kotlin compilation.
- Extension compilation.
- Wrapper validation.
- Secret scan.
- Forbidden-file scan.
- License/notice presence.
- Manifest debug marker scan.

### Layer 2 — Pure JVM unit tests (short term)

Target code that does not need Android:

- `Block` URI matching rules.
- `Config` lock/polarity logic (extract pure functions if needed).
- `CacheCleaner` path classification (`looksLikeAuth`, `looksLikeMediaCache`).
- Release JSON parsing and asset selection (extract from `UpdateChecker`).
- Version comparison and tag normalization.

These can run with JUnit on the JVM, with Android types stubbed or refactored out.

### Layer 3 — Patch application integration (medium term)

On a controlled self-hosted runner with:

- a pinned stock Instagram APK;
- a pinned Morphe CLI;
- the bundle from CI;

run `build.sh` with a test keystore and assert:

- all expected patches are applied;
- `failedPatches` is empty;
- the output APK exists and is signed;
- the package name is correct for classic/clone;
- no `debuggable` flag is present.

Do not upload the base APK or the patched APK to public artifacts.

### Layer 4 — Device smoke test (manual, per release)

- Install on a clean device/profile.
- Log in with a test account.
- Verify each toggle, deep links, settings entry, and update behavior.
- Record device model, Android version, Instagram version, and results.

---

## 7. Signed release plan (optional, gated)

An APK release should be a **separate, manual, protected workflow**, not part of pull-request CI.

### Preconditions

- Security findings SEC-01, SEC-02, SEC-04, SEC-11, SEC-12 have explicit decisions.
- Legal review for APK distribution is complete.
- Release keystore is in a protected environment with required reviewers.
- Base APK provenance and hash are recorded.
- `--debug` is impossible to enable accidentally.

### Shape

```yaml
name: Signed release

on:
  workflow_dispatch:
    inputs:
      instagram_version:
        description: Exact Instagram version of the base APK
        required: true
      base_apk_sha256:
        description: SHA-256 of the base APK
        required: true

permissions:
  contents: read

jobs:
  release:
    runs-on: [self-hosted, android-build]
    environment: release
    steps:
      - uses: actions/checkout@v4
      - name: Verify base APK hash
        run: echo "${{ inputs.base_apk_sha256 }}  $BASE_APK_PATH" | sha256sum -c -
      - name: Build signed APK
        env:
          FEURSTAGRAM_KEYSTORE: ${{ secrets.RELEASE_KEYSTORE_PATH }}
          FEURSTAGRAM_KEYSTORE_PASS: ${{ secrets.RELEASE_KEYSTORE_PASS }}
          FEURSTAGRAM_KEY_PASS: ${{ secrets.RELEASE_KEY_PASS }}
          FEURSTAGRAM_KEY_ALIAS: ${{ secrets.RELEASE_KEY_ALIAS }}
        run: ./build.sh "$BASE_APK_PATH" --clone
      - name: Verify signature and record hash
        run: |
          apksigner verify --print-certs feurstagram.apk
          sha256sum feurstagram.apk | tee feurstagram.apk.sha256
      - name: Upload release artifact
        uses: actions/upload-artifact@v4
        with:
          name: feurstagram-apk
          path: |
            feurstagram.apk
            feurstagram.apk.sha256
          retention-days: 7
```

This sketch omits publishing to GitHub Releases on purpose. Publishing must be a separate, explicitly approved step with provenance and release notes.

---

## 8. Caching and performance

| Cache | Contents | Notes |
|---|---|---|
| Gradle | `~/.gradle/caches`, `~/.gradle/wrapper` | Managed by `gradle/actions/setup-gradle` |
| Android SDK | `$ANDROID_HOME` | Optional; setup cost is modest |
| Kotlin | `~/.kotlin` | Optional |

Do **not** cache:

- `~/.gradle/gradle.properties` (credentials);
- `tools/` (external jars);
- base APKs;
- keystores.

Target bundle build time: a few minutes after cache warm. APK patching is dominated by dex processing and APKEditor merge; expect longer and memory-heavy.

---

## 9. Artifact and retention policy

| Artifact | Public CI | Retention | Notes |
|---|---|---|---|
| `.mpp` + SHA-256 | Yes | 14 days | Patch bundle only |
| Test reports | Yes | 14 days | No secrets |
| `patch-report.json` | Yes, if no base APK metadata leaks | 14 days | Review for paths/versions |
| Base Instagram APK | **No** | Never | Proprietary |
| Patched APK | Only in protected release | 7 days / release asset | Legal review required |
| Keystore | **No** | Never | Secret manager only |
| Registry token | **No** | Never | Actions secret |

---

## 10. Security gates for CI

1. **Secret scanning** on every push/PR. Fail on verified secrets.
2. **Forbidden-file check**: no `*.apk`, `*.apkm`, `*.xapk`, `*.apks`, `*.keystore`, `*.jks`, `*.mpp`, `*.mpe`, or `tools/` tracked.
3. **Wrapper validation** with the official Gradle action.
4. **No `pull_request_target` builds of untrusted code.**
5. **Least-privilege `GITHUB_TOKEN`** (`contents: read` by default).
6. **Protected release environment** with required reviewers.
7. **No debug build in release.** Scan the manifest and build args.
8. **Artifact checksums** in the job summary and release notes.
9. **Dependency pinning** for actions (full SHA) and build tools.
10. **Token rotation** and audit of package access.

---

## 11. Rollout plan

### Week 0 — Source-only CI

- Add hygiene, wrapper validation, and bundle build jobs.
- Provision `MORPHE_REGISTRY_USER` / `MORPHE_REGISTRY_TOKEN`.
- Confirm the bundle builds on a hosted runner.

**Exit:** green workflow on `main`, `.mpp` artifact with checksum.

### Week 1 — Static hardening

- Add secret scanning and dependency review.
- Add license/notice checks.
- Remove unused dependencies (`gson`, dead version entries) in a separate, reviewed change.
- Add a job summary with versions and hashes.

**Exit:** all gates green; no secrets in logs.

### Week 2 — Tests

- Extract pure logic and add JVM unit tests.
- Add a self-hosted patch integration job with a pinned APK.
- Publish `patch-report.json` summary.

**Exit:** patch failures are caught before release.

### Week 3 — Release rehearsal

- Run the protected release workflow with a test keystore and a test base APK.
- Verify signature, install, and smoke test.
- Document the base APK provenance.

**Exit:** repeatable signed build; no proprietary artifact leaked.

### Later — Public APK release (only after approval)

- Legal review.
- Publish SHA-256 and provenance.
- Monitor for takedown/security issues.
- Keep source-only builds available.

---

## 12. What CI cannot solve

- It cannot create an Instagram APK from source.
- It cannot verify runtime behavior without a device.
- It cannot prove the absence of malicious behavior in a proprietary base APK.
- It cannot make APK redistribution legally safe.
- It cannot replace the security decisions in `HMX_INSTAPLUS_SECURITY_AUDIT.md`.

---

## 13. Acceptance criteria for this plan

- [ ] Maintainers approve hosted-runner bundle CI.
- [ ] `read:packages` secret is provisioned and tested.
- [ ] Workflow pins actions and build tools.
- [ ] No proprietary input enters public artifacts or caches.
- [ ] Release workflow is protected and manual.
- [ ] Security gates are enforced before any APK publication.
- [ ] Unit and integration test layers have owners and target dates.

Until then, CI remains **proposed, not implemented**.
