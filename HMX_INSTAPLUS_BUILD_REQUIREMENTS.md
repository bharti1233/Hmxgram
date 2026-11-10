# HMX Insta+ — Build Requirements and Reproduction Plan

**Project under audit:** FeurStagram
**Audited commit:** `bad4cd060db42e4434e254e37cf77e784de96d7e`
**Document status:** Phase 0 planning only
**Local build performed:** **No** (per instruction)
**Baseline build status:** **Unverified**

This document defines what is required to turn the repository into an installable APK, what cannot be reproduced from source alone, and the minimum ordered steps to obtain a verified baseline.

---

## 1. Bottom line

A clean clone **cannot** produce an APK by itself. It needs four external inputs:

1. A stock Instagram APK (or split bundle).
2. The Morphe CLI jar.
3. A GitHub token with `read:packages` for the Morphe registry.
4. A JDK 21 + Android SDK 35 environment.

Optional inputs:

- APKEditor, only for `.apkm` / `.xapk` / `.apks`.
- A release keystore and passwords, for stable update signing.
- `adb` and a device, for verification.

`docs/BUILD_FROM_SOURCE.md:15` and `Readme.md:277` both require a user-downloaded Instagram APK. `build.sh:33` and `:92-95` require a locally supplied Morphe CLI. Neither is in the repository.

---

## 2. Hard requirements

### 2.1 Host toolchain

| Requirement | Minimum / exact | Why | Evidence |
|---|---|---|---|
| OS | Linux x86-64 recommended | Android SDK command-line tools are x86-64 binaries; Dockerfile notes Apple Silicon needs amd64 emulation | `Dockerfile:13-14` |
| JDK | **21** | Patcher/AGP target; `build.sh` pins JDK 21 and notes JDK 17 is rejected | `Dockerfile:19`, `build.sh:36-40`, `:62-65` |
| Android SDK | `platforms;android-35`, `build-tools;35.0.0`, `platform-tools` | Extension compile + `apksigner` | `Dockerfile:34` |
| `ANDROID_HOME` | Set to SDK root | Build script locates `apksigner` | `build.sh:50-60`, `:71-73` |
| Gradle | Wrapper 9.5.0 | Repo wrapper | `gradle/wrapper/gradle-wrapper.properties` |
| Disk | ~2–4 GB free | JDK, SDK, Gradle cache | `Dockerfile:16` |
| RAM | **4 GB+** recommended | Dex patching and APKEditor merge | `Dockerfile:16-17`, `build.sh:116` |
| Network | GitHub, Google Maven, Gradle Plugin Portal, JitPack, Morphe GitHub Packages | Dependency resolution | `settings.gradle.kts:3-17` |

### 2.2 Credentials

| Credential | Scope | Use |
|---|---|---|
| GitHub username | — | `gpr.user` / `GITHUB_ACTOR` |
| GitHub token | **`read:packages`** on the MorpheApp registry | Resolve `app.morphe:morphe-patches-library:1.2.0` |

Configure one of:

**Option A — Gradle properties** (`~/.gradle/gradle.properties`, never committed):

```properties
gpr.user=<github-username>
gpr.key=<token-with-read:packages>
```

**Option B — environment variables:**

```sh
export GITHUB_ACTOR=<github-username>
export GITHUB_TOKEN=<token-with-read:packages>
```

Evidence: `settings.gradle.kts:10-13`, `docs/BUILD_FROM_SOURCE.md:9-14`.

> A token scoped only to the Hmxgram repository will not necessarily read the MorpheApp organization’s packages. Verify access before treating a 401/403 as a code failure.

### 2.3 Patcher and merge tools

| Tool | Required for | Expected location | Evidence |
|---|---|---|---|
| Morphe CLI / Desktop jar | Every build | `tools/morphe-cli-*.jar` or `tools/morphe-desktop-*.jar` | `build.sh:32-33` |
| APKEditor | Split bundles only | `tools/APKEditor-*.jar` | `build.sh:97-106` |

`tools/` is git-ignored (`.gitignore:9`). The CLI is **not** redistributed here.

The build script suggests APKEditor 1.4.9:

```sh
gh release download V1.4.9 -R REAndroid/APKEditor -p 'APKEditor-1.4.9.jar' -D tools/
```

(`build.sh:104`)

For supply-chain safety, download from the official release page, record the SHA-256, and verify it before use.

### 2.4 Base application

| Input | Notes |
|---|---|
| Stock Instagram APK | Must be `com.instagram.android`; arm64-v8a recommended (`Readme.md:277`) |
| Split bundle `.apkm` / `.xapk` / `.apks` | Merged to one APK with APKEditor first |
| Source | Must be trusted (APKMirror or equivalent); verify signer and hash |
| Version | Not pinned by the repo (`patches/src/main/kotlin/com/feurstagram/patches/shared/Constants.kt:19-23`); record the exact version used for the baseline |

The stock APK is proprietary. Do **not** commit it, upload it to a public artifact store, or redistribute it as part of the source repository.

### 2.5 Signing material

| Item | Required? | Notes |
|---|---|---|
| Release keystore (PKCS12) | For stable releases | Default path `feurstagram.keystore`; override with `FEURSTAGRAM_KEYSTORE` |
| `FEURSTAGRAM_KEYSTORE_PASS` | If using keystore | Enables `apksigner` path |
| `FEURSTAGRAM_KEY_PASS` | Optional | Defaults to keystore password |
| `FEURSTAGRAM_KEY_ALIAS` | Optional | Defaults to `feurstagram` |

Evidence: `build.sh:19-25`, `:67-73`, `:145-184`.

Without a keystore password, the Morphe CLI signs with a throwaway key. That is fine for a smoke test but **not** for a release: a new key means users cannot update in place (`Readme.md:328-333`, `docs/INSTALL.md:45-47`).

---

## 3. Environment options

### 3.1 Recommended: Docker

The repository ships a build environment (`Dockerfile:19-35`):

```sh
docker build --platform linux/amd64 -t feurstagram-build .

docker run --rm --platform linux/amd64 \
  -v "$PWD:/work" \
  -v feur-gradle:/root/.gradle \
  -e GITHUB_TOKEN=<token-with-read:packages> \
  -e GITHUB_ACTOR=<github-username> \
  feurstagram-build ./build.sh instagram.apk
```

Add `--clone` for a side-by-side package, `--install` to push over ADB, and `--debug` only for development.

**Advantages:** exact JDK 21, SDK 35, build-tools 35.0.0; no host pollution.
**Disadvantages:** ~2–4 GB image/cache; needs amd64 emulation on ARM hosts; the base APK must be mounted into `/work`.

### 3.2 Local host

Requirements:

```sh
java -version          # must report 21
echo "$ANDROID_HOME"   # must point at an SDK with platform 35 + build-tools 35.0.0
```

Then:

```sh
./gradlew :patches:build
./build.sh /path/to/instagram.apk --clone
```

`build.sh` will try to locate a JDK 21 on macOS and will locate common SDK paths (`build.sh:36-60`), but it does **not** install either.

### 3.3 Current audit host (for the record)

- JDK 17 only; no JDK 21.
- No usable Android SDK `platforms`.
- No `tools/` directory.
- No `GITHUB_TOKEN` in the environment.
- No build was attempted.

This is why the baseline remains unverified.

---

## 4. Exact build procedure

### 4.1 Prepare

```sh
git clone https://github.com/jean-voila/FeurStagram.git
cd FeurStagram
```

For the Hmxgram fork, use the Hmxgram remote while preserving upstream:

```sh
git remote add upstream https://github.com/jean-voila/FeurStagram.git
git remote -v
```

### 4.2 Configure credentials

```sh
mkdir -p ~/.gradle
cat >> ~/.gradle/gradle.properties <<'EOF'
gpr.user=<github-username>
gpr.key=<token-with-read:packages>
EOF
chmod 600 ~/.gradle/gradle.properties
```

Do not place the token in the repository, a commit, a remote URL, or a workflow log.

### 4.3 Provide tools

```sh
mkdir -p tools
# Place the Morphe CLI jar here:
#   tools/morphe-cli-<version>.jar
# or
#   tools/morphe-desktop-<version>.jar

# Only for split bundles:
#   tools/APKEditor-1.4.9.jar
```

Record versions and SHA-256 hashes in the build log.

### 4.4 Build the patch bundle only (no base APK)

```sh
./gradlew :patches:build --no-daemon --stacktrace
```

Expected output:

```text
patches/build/libs/patches-<version>.mpp
```

This step validates Kotlin compilation, patch registration, extension compilation, and registry access. It does **not** produce an installable APK.

### 4.5 Build the APK

```sh
./build.sh /path/to/instagram.apk --clone
```

For split bundles:

```sh
./build.sh /path/to/instagram.apkm --clone
```

For release signing:

```sh
export FEURSTAGRAM_KEYSTORE_PASS='...'
export FEURSTAGRAM_KEYSTORE=/secure/path/feurstagram.keystore
./build.sh /path/to/instagram.apk --clone
```

### 4.6 Verify

```sh
ls -lh feurstagram.apk
cat build/patch-report.json
```

Check:

- All required patches appear in `appliedPatches`.
- `failedPatches` is empty.
- The output is signed (`apksigner verify --print-certs feurstagram.apk`).
- The package name matches the intended classic or clone ID.
- Install on a test device with `adb install -r feurstagram.apk`.
- Launch, log in with a **test account**, and smoke-test the required surfaces.

Per instruction, these verification steps were not executed in this phase.

---

## 5. What each invocation produces

| Command | Output | Installable? |
|---|---|---|
| `./gradlew :patches:build` | `.mpp` patch bundle | No |
| `./build.sh <apk>` | `feurstagram.apk` | Yes, if signing succeeds |
| `./build.sh <apkm>` | merged APK + `feurstagram.apk` | Yes |
| `./build.sh <apk> --clone` | clone-package APK | Yes |
| `./build.sh <apk> --debug` | debuggable APK with ADB bridge | Yes — **do not release** |

A `.mpp` alone is not an app. It is a patch program that must be applied to a compatible Instagram APK.

---

## 6. Why source alone is insufficient

1. **The base app is proprietary.** FeurStagram only contains the patch code; the target `com.instagram.android` binary is absent.
2. **The patcher is an external executable.** `tools/` is intentionally empty and git-ignored.
3. **The registry is private.** The Morphe patch library needs `read:packages`.
4. **Signing material is intentionally absent.** No keystore is committed.
5. **No APK is tracked.** `.gitignore:2-3` excludes `*.apk` and `*.apkm`.

Therefore, “build from source” means “build the patch bundle from source, then apply it to a separately obtained Instagram APK.”

---

## 7. Minimal build-fix sequence

The following sequence is ordered to expose failures early. It is a plan, not an executed build.

### Step 1 — Pin the environment

- Use the Dockerfile or install JDK 21 + SDK 35 + build-tools 35.0.0.
- Confirm `java -version` reports 21 and `sdkmanager --list_installed` includes the required packages.

**Exit criterion:** `./gradlew --version` runs on JDK 21.

### Step 2 — Prove registry access

- Provision a `read:packages` token.
- Run `./gradlew :patches:build`.
- If it fails with 401/403, fix credentials before touching code.

**Exit criterion:** the `.mpp` is produced and the build log shows the Morphe library resolved.

### Step 3 — Pin the patcher

- Download a specific Morphe CLI release.
- Record its version and SHA-256.
- Place it at `tools/morphe-cli-<version>.jar`.
- Do not use “latest” in a reproducible build.

**Exit criterion:** `build.sh` finds exactly one CLI and prints its path.

### Step 4 — Acquire and fingerprint the base APK

- Obtain a stock Instagram APK from a trusted source.
- Record the exact version, architecture, and SHA-256.
- Verify the signer is Meta before patching.
- Do not commit the APK.

**Exit criterion:** a manifest file (outside git) records the base APK’s version and hash.

### Step 5 — Produce the first patched APK

- Run `./build.sh <apk>` without `--clone` first. Fewer variables.
- Capture `build/patch-report.json`.
- If a patch fails, do not “fix” the fingerprint blindly; compare against the expected Instagram version.

**Exit criterion:** all default patches applied; no failed patches.

### Step 6 — Sign deterministically

- Generate or provision a release keystore.
- Store it outside the repo with restrictive permissions.
- Set `FEURSTAGRAM_KEYSTORE_PASS` and rerun.
- Verify v1/v2/v3 signatures.

**Exit criterion:** `apksigner verify` passes, and repeated builds use the same certificate.

### Step 7 — Device smoke test

- Install on a clean test device/profile.
- Verify launch, login, settings entry, each toggle, deep links, and update prompt behavior.
- Use a test account; do not use a personal account for destructive tests.

**Exit criterion:** a written smoke-test record with device, Android version, Instagram version, and results.

### Step 8 — Only then plan HMX changes

Do not add HMX features until the above is green. A feature built on an unverified baseline cannot be distinguished from a broken baseline.

---

## 8. Troubleshooting matrix

| Symptom | Likely cause | Action |
|---|---|---|
| `Could not resolve app.morphe:morphe-patches-library` | Missing/invalid `read:packages` token, or no MorpheApp access | Recheck `gpr.user` / `gpr.key`; test with `curl` against the registry |
| `401 Unauthorized` from GitHub Packages | Token lacks scope or is expired | Issue a new token with `read:packages`; do not use a repo-scoped token |
| `Unsupported class file major version` / patcher rejects JDK | Running JDK 17 | Set `JAVA_HOME` to JDK 21 |
| `SDK location not found` | `ANDROID_HOME` unset | Set it to the SDK root |
| `apksigner not found` | No build-tools | Install `build-tools;35.0.0` |
| `Morphe CLI not found under tools/` | CLI absent | Place the jar in `tools/` |
| `needs APKEditor to merge` | Split bundle without APKEditor | Add `tools/APKEditor-*.jar` or use a single APK |
| APKEditor OOM / killed | <4 GB RAM | Increase memory or use a single APK |
| `App not installed` after rebuild | Different signing key | Reinstall with the same keystore or uninstall first |
| Patch report shows `FAIL` | Fingerprint drift on a new Instagram version | Pin to a tested version or update the fingerprint |
| Update prompt loops or fails | Stale `PackageInstaller` session, or wrong asset selected | Clear app data; inspect the release asset naming logic |

---

## 9. CI versus manual builds

| Capability | GitHub-hosted CI | Manual/self-hosted |
|---|---|---|
| Compile `.mpp` / extension | Yes, with `read:packages` secret | Yes |
| Static checks, license scan, secret scan | Yes | Yes |
| Produce installable APK | **No**, without a proprietary base APK and signing key | Yes |
| Publish APK | Legally/security sensitive; requires explicit approval | Yes, with policy |
| Verify on device | No (no device) | Yes, with `adb` |

See `HMX_INSTAPLUS_CI_PLAN.md` for the proposed workflow.

---

## 10. Acceptance checklist for Phase 0 exit

- [ ] JDK 21 and SDK 35/build-tools 35.0.0 confirmed.
- [ ] `read:packages` credential confirmed against the Morphe registry.
- [ ] Morphe CLI version and SHA-256 recorded.
- [ ] Base APK version, architecture, and SHA-256 recorded; signer verified.
- [ ] `./gradlew :patches:build` succeeds.
- [ ] `./build.sh <apk>` produces `feurstagram.apk`.
- [ ] `build/patch-report.json` has zero failed patches.
- [ ] Release keystore provisioned outside the repo.
- [ ] `apksigner verify` passes with v1/v2/v3.
- [ ] Device smoke test recorded.
- [ ] Security findings triaged (see security audit).
- [ ] No secret, keystore, APK, or token committed.

Until this checklist is green, HMX feature work should remain blocked.

---

## 11. Compliance and supply-chain notes

- **Do not commit** the stock APK, patched APK, keystore, `tools/` jars, or registry tokens.
- **Do not publish** a patched Instagram binary without a legal review: it contains Meta’s proprietary code and may violate Instagram’s Terms of Service, independent of the GPL.
- **Preserve** `LICENSE`, `NOTICE`, and upstream commit attribution in any fork.
- **Pin** the CLI and APKEditor versions and verify hashes.
- **Record** the base APK signer; patching an already-modified APK is not a supported baseline.
- **Rotate** any token that was ever pasted into a terminal, chat, or log.

---

## 12. Status

- Build requirements: **documented**.
- Build execution: **not performed**.
- Baseline artifact: **not produced**.
- Next gate: approval to provision the environment and produce a baseline, still without adding HMX features.
