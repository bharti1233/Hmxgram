# HMX Insta+ — Phase 0 Source Audit

**Audit target:** FeurStagram (`https://github.com/jean-voila/FeurStagram.git`)
**Audited commit:** `bad4cd060db42e4434e254e37cf77e784de96d7e`
**Commit subject:** `Prepare FeurStagram v454-0-0-54-77`
**Commit date:** 2026-11-06T19:29:21+01:00
**Audit method:** static source, build-file, manifest, and documentation inspection only
**Local build/run:** **not performed** — explicitly out of scope for this phase
**Audit status:** complete; awaiting approval before any modification or feature work

---

## 1. Executive summary

FeurStagram is a real, coherent, GPL-3.0 Android patching project, not a mock-up. It has:

- 107 tracked files and roughly 9,130 lines of Kotlin/Java in the patch and runtime layers.
- A working-looking Morphe patch-bundle pipeline (`.mpp`) plus a compiled Java runtime extension (`.mpe`).
- 16 patch definitions, 14 of which are enabled by default.
- A coherent runtime configuration model backed by `SharedPreferences`.
- No native libraries, no WebView, no dynamic code loading, no credential/cookie capture, and no project-operated backend.

The project is a **viable Phase 0 foundation** for HMX Insta+, but it is **not self-contained**:

1. **No installable APK can be produced from this repository alone.** The build consumes a user-supplied, proprietary Instagram APK.
2. **The Morphe CLI is not vendored.** `build.sh` refuses to run without a locally supplied `tools/morphe-cli-*.jar` or `tools/morphe-desktop-*.jar`.
3. **GitHub Packages credentials are mandatory** to resolve `app.morphe:morphe-patches-library:1.2.0`.
4. **No CI exists**, no tests exist, and the baseline build has not been executed here.
5. Patch targeting is intentionally unbounded (`version = null`), so any future Instagram release is accepted by the patcher without a compatibility gate.

The correct next step is **not** feature implementation. It is to establish a reproducible, credentialed build environment, produce one verified baseline artifact from a known Instagram APK, and only then start HMX work.

---

## 2. Repository inventory

### 2.1 Top-level structure

```text
FeurStagram/
├── .github/                 issue/PR/release templates only; no workflows
├── assets/                  social preview image
├── docs/                    user docs, screenshots, FAQ, static website
├── extensions/              Java runtime extension compiled into the APK
│   └── extension/
│       ├── build.gradle.kts
│       └── src/main/
│           ├── AndroidManifest.xml
│           └── java/com/feurstagram/extension/*.java
├── gradle/
│   ├── libs.versions.toml
│   └── wrapper/
├── patches/                 Kotlin Morphe patch definitions
│   └── src/main/
│       ├── kotlin/com/feurstagram/patches/
│       └── resources/feurstagram/quoicouview.jpg
├── build.sh                 end-to-end build/patch/sign script
├── settings.gradle.kts      plugin repositories + credentials
├── gradle.properties
├── Dockerfile               JDK 21 + Android SDK 35 environment
├── LICENSE                  GPL-3.0
├── NOTICE                   upstream attribution (Morphe, Piko)
├── Readme.md
├── CHANGELOG.md
└── docs/BUILD_FROM_SOURCE.md
```

### 2.2 Measured inventory

| Metric | Value | Evidence |
|---|---:|---|
| Tracked files | 107 | `git ls-files \| wc -l` |
| Working tree size | ~62 MB | `du -sh .` |
| Kotlin patch files | 18 | `patches/src/main/kotlin/**/*.kt` |
| Java extension files | 19 | `extensions/extension/src/main/java/**/*.java` |
| Kotlin + Java LOC | 9,130 | `wc -l` over both source trees |
| Native `.so` libraries | 0 | `find` over tracked tree |
| Test source roots | 0 | no `src/test` or `src/androidTest` |
| GitHub Actions workflows | 0 | `.github/workflows` absent |
| Gradle wrapper | 9.5.0, SHA-256 pinned | `gradle/wrapper/gradle-wrapper.properties` |
| Largest tracked file | `docs/tuto.gif` (~23 MB) | `du` on tracked files |

The repository is source-complete for the *patch project*, but it contains no base Instagram application and no patcher executable.

### 2.3 License and provenance

- `LICENSE:1` is the GNU GPL v3.0 text.
- `NOTICE` attributes:
  - **Morphe** — patcher and Gradle tooling.
  - **Piko** — network-blocking and clone approaches.
- `NOTICE` invokes GPLv3 Section 7(c) to state that the “Morphe” name is used descriptively and does not imply affiliation.
- `patches/build.gradle.kts:11` declares `license = "GPLv3"`.

**Implication:** any HMX fork distributed as an APK must keep the GPL-3.0 source offer, `LICENSE`, `NOTICE`, and upstream attribution. The GPL covers the project code, **not** Instagram’s proprietary APK; redistributing a patched Instagram binary is a separate legal risk.

---

## 3. Build system and dependency graph

### 3.1 Declared build configuration

| Item | Declared value | File |
|---|---|---|
| Gradle | 9.5.0 | `gradle/wrapper/gradle-wrapper.properties` |
| Gradle distribution checksum | `553c78f5...d6b746` | same file |
| Morphe Gradle plugin | `app.morphe.patches` 1.3.2 | `settings.gradle.kts:20` |
| Patch library | `app.morphe:morphe-patches-library:1.2.0` | `gradle/libs.versions.toml:3`, `patches/build.gradle.kts:17` |
| Patcher version entry | `1.5.2` | `gradle/libs.versions.toml:2` |
| Smali version entry | `d92701d947` | `gradle/libs.versions.toml:5` |
| Gson | `2.14.0` | `gradle/libs.versions.toml:6-9` |
| Project version | `1.0.0` | `gradle.properties:5` |
| Kotlin compiler flag | `-Xcontext-parameters` | `patches/build.gradle.kts:22` |

### 3.2 Dependency observations

- `morphe-patcher` and `smali` have **version entries but no `[libraries]` coordinates**; they are dead configuration. The imports resolve transitively through `morphe-patches-library`.
- `gson` is declared as a library but is **not referenced** by any Kotlin or Java source or module build file.
- There is **no root `build.gradle.kts`** and no explicit Android Gradle Plugin or Kotlin plugin version. AGP, Kotlin, compile SDK, and extension packaging are supplied transitively by `app.morphe.patches:1.3.2`.
- This makes the build **less reproducible** than it looks: the effective AGP/Kotlin versions can change with the plugin’s transitive graph, and there is no dependency lockfile.

### 3.3 Repository resolution

`settings.gradle.kts:3-17` declares:

- `gradlePluginPortal()`
- `google()`
- GitHub Packages: `https://maven.pkg.github.com/MorpheApp/registry`
- JitPack

Credentials are read from Gradle properties `gpr.user` / `gpr.key`, falling back to environment variables `GITHUB_ACTOR` / `GITHUB_TOKEN` (`settings.gradle.kts:10-13`).

**Blocker:** without a token that can read the MorpheApp GitHub Packages registry, Gradle cannot resolve the patch library. `docs/BUILD_FROM_SOURCE.md:9-14` confirms this is a required `read:packages` credential.

### 3.4 Effective build pipeline

`build.sh` is the only end-to-end entry point:

```text
stock Instagram APK / APKM / XAPK / APKS
        │
        ├─ [0/3] if split bundle: APKEditor merge → build/merged/*.apk
        │
        ├─ [1/3] ./gradlew :patches:build
        │          → patches/build/libs/patches-*.mpp
        │          → extension compiled into the bundle / .mpe
        │
        ├─ [2/3] java -jar tools/morphe-cli-*.jar patch
        │          -p <bundle> -f -r build/patch-report.json
        │          [-e Clone] [-e "Debug bridge"]
        │          → feurstagram.apk
        │
        └─ [3/3] optional apksigner with PKCS12 keystore
                   or CLI throwaway signature
                   → feurstagram.apk
```

Evidence:

- Input validation and CLI requirement: `build.sh:88-95`.
- Split merge and APKEditor requirement: `build.sh:97-125`.
- Bundle build: `build.sh:127-134`.
- Patch application and report: `build.sh:136-163`.
- Signing: `build.sh:145-184`.
- Optional install: `build.sh:202-205`.

---

## 4. Patch architecture

### 4.1 Model

FeurStagram uses **Morphe’s fingerprint-based patch DSL**. Patches do not depend on fixed obfuscated class names. They locate injection points by stable traits such as:

- literal strings (`patches/src/main/kotlin/com/feurstagram/patches/signature/SignatureCheckBypassPatch.kt:35-37`)
- method shape / return type / parameter types (`patches/src/main/kotlin/com/feurstagram/patches/signature/SignatureCheckBypassPatch.kt:62-90`)
- stable named classes such as `TigonServiceLayer` (`patches/src/main/kotlin/com/feurstagram/patches/network/NetworkBlockPatch.kt`, per `Readme.md:387-390`)
- wire-format tokens and `parseFromJson` (`Readme.md:392-401`)
- debug-string shape for the feed request class (`Readme.md:403-408`)
- framework calls such as `Window.setColorMode` (`Readme.md:419-426`)

When a fingerprint matches, the patch inserts calls into the compiled Java extension or rewrites bytecode/resource content.

### 4.2 Patch inventory

| Patch | Type | Default | Purpose | File |
|---|---|---:|---|---|
| Network content blocking | bytecode | on | Blocks feed/stories/explore/reels/ads/suggestions/tracking at the network layer | `patches/src/main/kotlin/com/feurstagram/patches/network/NetworkBlockPatch.kt` |
| Feed item filtering | bytecode | on | Drops inline ads/netego units at JSON parse time | `patches/src/main/kotlin/com/feurstagram/patches/network/FeedItemFilterPatch.kt` |
| Limit feed to following profiles | bytecode | on | Rewrites the feed pagination source to `following` | `patches/src/main/kotlin/com/feurstagram/patches/network/LimitFeedToFollowingPatch.kt` |
| Suggested accounts in story tray | bytecode | on | Drops `suggested_user` tray entries | `patches/src/main/kotlin/com/feurstagram/patches/network/SuggestedTrayEntriesPatch.kt` |
| Settings entry point | bytecode | on | Long-press Home tab opens settings; installs hiders and update check | `patches/src/main/kotlin/com/feurstagram/patches/settings/SettingsEntryPointPatch.kt` |
| Hidden Home in Back history | bytecode | on | Prevents Back from landing on hidden Home | `patches/src/main/kotlin/com/feurstagram/patches/settings/BackHistoryPatch.kt` |
| Quoicouview mode | bytecode | on | Stories tray in the Direct inbox; camera from Messages | `patches/src/main/kotlin/com/feurstagram/patches/quoicouview/QuoicouviewPatch.kt` |
| Quoicouview assets | resource | on | Adds the intro screenshot | `patches/src/main/kotlin/com/feurstagram/patches/quoicouview/QuoicouviewAssetsPatch.kt` |
| Signature check bypass | bytecode | **on** | Forces Instagram signing-cert trust checks to return true | `patches/src/main/kotlin/com/feurstagram/patches/signature/SignatureCheckBypassPatch.kt` |
| Force SDR display | bytecode | on | Pins window colour mode to SDR | `patches/src/main/kotlin/com/feurstagram/patches/display/SetColorModePatch.kt` |
| Popup hiding | bytecode | on | Drops Instagram toasts/popups | `patches/src/main/kotlin/com/feurstagram/patches/ui/PopupHidingPatch.kt` |
| Notes tray | bytecode | on | Removes the Notes row from the inbox | `patches/src/main/kotlin/com/feurstagram/patches/ui/NotesTrayPatch.kt` |
| Restart relay | resource | on | Declares a one-shot activity in `:feurstagram_restart` | `patches/src/main/kotlin/com/feurstagram/patches/restart/RestartRelayPatch.kt` |
| Install-packages permission | resource | **on** | Adds `REQUEST_INSTALL_PACKAGES` | `patches/src/main/kotlin/com/feurstagram/patches/update/InstallPermissionPatch.kt` |
| Clone | resource | off | Renames package/label/authorities for side-by-side install | `patches/src/main/kotlin/com/feurstagram/patches/clone/ClonePatch.kt` |
| Debug bridge | bytecode | off | Dev-only exported ADB control receiver | `patches/src/main/kotlin/com/feurstagram/patches/debug/DebugBridgePatch.kt` |

Each extension-using patch declares `extendWith("extensions/extension.mpe")`; the shared constant is `patches/src/main/kotlin/com/feurstagram/patches/shared/Constants.kt:12`.

### 4.3 Injection points

| Surface | Hook | Runtime code |
|---|---|---|
| Request filtering | `TigonServiceLayer.startRequest` | `Block.java` throws `IOException` for matching URIs |
| Feed item filtering | feed-item JSON deserializer | rewrites blocked type token to an invalid one |
| Following-only feed | feed request constructor | rewrites `pagination_source` header |
| Story tray suggestions | tray entry type getter | drops `reel_type: suggested_user` |
| Settings | tab-bar binder | installs long-press listener, hiders, update check |
| Quoicouview | inbox adapter/row registration and tab/pager checks | `Quoicouview.java`, `StoriesTrayRow.java` |
| Signature trust | `X.1eX.A01` / scope check | method bodies replaced with `return true` |
| Colour mode | `Window.setColorMode` | `Display.java` |
| Popups | Instagram toast `show` | `Toasts.java` |
| Notes | inbox row model | `NotesTrayPatch.kt` |
| Restart | new manifest activity | `RestartActivity.java`, `CacheCleaner.java` |

For readability, `*.java` names in this table live under `extensions/extension/src/main/java/com/feurstagram/extension/`, and `*Patch.kt` names live under `patches/src/main/kotlin/com/feurstagram/patches/`.

### 4.4 Compatibility model

`patches/src/main/kotlin/com/feurstagram/patches/shared/Constants.kt:14-24`:

```kotlin
Compatibility(
    name = "Instagram",
    packageName = "com.instagram.android",
    apkFileType = ApkFileType.APK,
    targets = listOf(AppTarget(version = null)),
)
```

`version = null` means the patches advertise compatibility with **the latest known build and every future one**. There is no minimum, maximum, or tested-version allowlist. This is deliberate (`patches/src/main/kotlin/com/feurstagram/patches/shared/Constants.kt:19-20`) and is the main reason a new Instagram release “usually just needs a rebuild” (`Readme.md:454-458`), but it also means the patcher will attempt unsupported builds rather than refusing them.

---

## 5. Runtime extension architecture

The extension is an Android library with an empty manifest (`extensions/extension/src/main/AndroidManifest.xml` is `<manifest/>`). Its classes are compiled to `extensions/extension.mpe` and merged into Instagram at patch time.

### 5.1 Class responsibilities

| Class | Responsibility |
|---|---|
| `Config.java` | SharedPreferences access, defaults, permanent-lock semantics, `ActivityThread` context reflection |
| `Block.java` | URI blocklists, analytics/commerce always-blocked rules, feed-item type filtering |
| `LimitFeed.java` | Rewrites the feed request header to the following feed |
| `Settings.java` | Full-screen Material settings UI, toggles, update controls, cache-clear action |
| `HomeTabWatcher.java` | Watches the tab bar and installs the long-press entry point |
| `Hiders.java` | Hides nav tabs, Notes, Instants; landing-page redirects |
| `HiddenTabSwipeSkipper.java` | Prevents swiping onto hidden tab pages |
| `Quoicouview.java` | Reflection-heavy inbox/tray/controller integration |
| `StoriesTrayRow.java` | Inbox row type that hosts the stories tray |
| `QuoicouviewIntro.java` | One-time Quoicouview introduction card |
| `FollowPrompt.java` | Follow-prompt/onboarding surface |
| `Onboarding.java` | First-run onboarding |
| `UpdateChecker.java` | GitHub release check, APK download, `PackageInstaller` staging |
| `CacheCleaner.java` | Heuristic cache/database deletion and process restart |
| `DebugBridge.java` | Dev-only ADB broadcast control surface |
| `RestartActivity.java` | One-shot restart relay in its own process |
| `Display.java` | Window colour-mode / SDR control |
| `Toasts.java` | Instagram popup suppression |
| `ExpressiveSwitch.java` | Custom Material switch widget |

### 5.2 Configuration and persistence

- Store: `SharedPreferences` named `feurstagram_prefs` (`extensions/extension/src/main/java/com/feurstagram/extension/Config.java:17`).
- Default-on blocks: feed, explore, reels, friends lane, instants, notes, suggested, ads, popups (`extensions/extension/src/main/java/com/feurstagram/extension/Config.java:130-162`).
- Default-off: stories, notifications button, following-only feed, Quoicouview (`extensions/extension/src/main/java/com/feurstagram/extension/Config.java:126-152`, `:197`).
- Auto-update: default on (`extensions/extension/src/main/java/com/feurstagram/extension/Config.java:108-111`).
- Force SDR: default on (`extensions/extension/src/main/java/com/feurstagram/extension/Config.java:113-119`).
- Permanent lock: `hardcore_mode`, default off, freezes revealing previously hidden surfaces (`extensions/extension/src/main/java/com/feurstagram/extension/Config.java:52-106`).

No credentials, cookies, or session tokens are read or written by this store.

### 5.3 Update path

- Release metadata: `https://api.github.com/repos/jean-voila/Feurstagram/releases/latest` (`extensions/extension/src/main/java/com/feurstagram/extension/UpdateChecker.java:77-80`).
- Download: `browser_download_url` from the release JSON, written to `getExternalFilesDir(DIRECTORY_DOWNLOADS)/feurstagram-update.apk` (`extensions/extension/src/main/java/com/feurstagram/extension/UpdateChecker.java:922-976`).
- Install: `PackageInstaller` full-install session (`extensions/extension/src/main/java/com/feurstagram/extension/UpdateChecker.java:995-1051`).
- No checksum, hash, or signer-certificate verification before staging.
- Automatic check runs on launch unless `auto_update` is false (`extensions/extension/src/main/java/com/feurstagram/extension/UpdateChecker.java:122-126`).

---

## 6. Data and control flow

```text
User launches patched Instagram
  └─ Instagram starts normally; authentication remains Instagram's own
      └─ tab-bar hook installs:
           ├─ long-press settings entry
           ├─ surface hiders
           ├─ Quoicouview hooks (if enabled)
           └─ UpdateChecker.check()
                └─ HTTPS GET api.github.com (release metadata)
                     └─ if newer: download release APK
                          └─ PackageInstaller confirmation

Instagram makes a network request
  └─ TigonServiceLayer.startRequest
       └─ Block.matches(uri)?
            ├─ yes → throw IOException → surface stays empty
            └─ no  → request proceeds

Instagram parses the home feed
  └─ feed-item deserializer
       └─ blocked type token? → rewrite to invalid → parser drops item

Settings changed
  └─ SharedPreferences updated
       └─ restart required → CacheCleaner wipes heuristic caches
            └─ RestartActivity kills/relaunches the app
```

No project-operated server sits in this path. The only non-Instagram endpoint is GitHub for update metadata and release assets.

---

## 7. Inputs required to produce an installable APK

| Input | Required? | Why | Evidence |
|---|---|---|---|
| Stock Instagram APK (or split bundle) | **Yes** | The patcher modifies an existing app; none is in the repo | `docs/BUILD_FROM_SOURCE.md:15`, `Readme.md:277` |
| Morphe CLI jar | **Yes** | `build.sh` applies the `.mpp`; not vendored | `build.sh:32-33`, `:92-95` |
| GitHub Packages `read:packages` token | **Yes** for source build | Resolves `morphe-patches-library` | `settings.gradle.kts:7-13` |
| JDK 21 | **Yes** | Patcher/AGP target | `Dockerfile:19`, `build.sh:36-40` |
| Android SDK platform 35 + build-tools 35.0.0 | **Yes** | Extension compile and `apksigner` | `Dockerfile:34` |
| APKEditor jar | Only for `.apkm/.xapk/.apks` | Merges splits into one APK | `build.sh:97-106` |
| Release keystore + passwords | Optional | Reproducible update signature; otherwise throwaway key | `build.sh:19-25`, `:145-184` |
| `adb` + device | Optional | `--install` verification | `build.sh:202-205` |

**Conclusion:** the repository is a *recipe and source tree*, not a distributable build product. No APK can be generated from a clean clone without proprietary inputs.

---

## 8. Findings and blockers

### B1 — No Instagram base APK (release-blocking for artifacts)

The build requires a user-supplied APK. The repo contains none, and `.gitignore:2-3` excludes `*.apk` / `*.apkm`. This is correct for licensing and hygiene, but it means CI cannot produce an installable artifact from source alone.

### B2 — Morphe CLI is not vendored (release-blocking for artifacts)

`build.sh:33` searches `tools/`, which is git-ignored (`.gitignore:9`). A clean clone always fails at `build.sh:92-95`.

### B3 — GitHub Packages authentication (build-blocking)

`settings.gradle.kts:7-13` requires credentials for the Morphe registry. Without them, dependency resolution fails before any patch compiles. The token needs `read:packages`; a normal `GITHUB_TOKEN` scoped to the Hmxgram repository will not necessarily have access to the MorpheApp organization’s packages.

### B4 — Toolchain mismatch (environment-blocking)

`Dockerfile:19` uses JDK 21 and `Dockerfile:34` installs `platforms;android-35` and `build-tools;35.0.0`. The current host has JDK 17 and no usable SDK platforms, so a local build here would need a new toolchain. Per instruction, no local build was attempted.

### B5 — No CI and no tests (quality-blocking)

There is no `.github/workflows` directory. `.github` contains only issue/PR/release templates. There are no `src/test` or `src/androidTest` roots. Patch correctness is validated only by the patcher aborting or by `build/patch-report.json` after a manual build (`build.sh:186-198`).

### B6 — Unbounded version targeting (compatibility risk)

`patches/src/main/kotlin/com/feurstagram/patches/shared/Constants.kt:19-23` sets `version = null`. The patcher will attempt every future Instagram build. A fingerprint can stop matching, or — worse — match a structurally similar but semantically different method and corrupt behavior. There is no allowlist, canary, or version gate.

### B7 — Transitive, unpinned Android toolchain (reproducibility risk)

There is no root `build.gradle.kts`, no AGP declaration, no Kotlin plugin declaration, and no dependency lock. The Morphe plugin pulls the Android/Kotlin toolchain transitively. Builds at different times can resolve different effective versions.

### B8 — Split-bundle memory requirement (operational risk)

`build.sh:116` runs APKEditor with `-Xmx4g`; the Dockerfile recommends 4 GB RAM (`Dockerfile:16-17`). Instagram bundles are large (~400 MB per `build.sh:114`). Low-memory CI runners will fail during merge.

### B9 — Signing key continuity (update risk)

Release signing needs the same keystore across builds or Android treats the APK as a different app (`Readme.md:328-333`, `docs/INSTALL.md:45-47`). The keystore is correctly git-ignored (`.gitignore:11-13`) but must be provisioned from a secret store. `build.sh` uses `apksigner` with v1/v2/v3 and disables v4 (`build.sh:167-179`).

### B10 — Upstream/GPL obligations (compliance risk)

The audit clone preserves `origin` and the original commit. Any published fork must keep GPL-3.0, `LICENSE`, `NOTICE`, and upstream attribution. Publishing a patched Instagram binary adds Meta copyright/ToS risk that the GPL does not solve.

### B11 — No verified baseline

Because no build or device run was permitted, this audit cannot assert that the current commit compiles, that all fingerprints match a current Instagram APK, or that the resulting APK installs and runs. All statements about runtime behavior are derived from source and documentation.

---

## 9. Positive findings

- **No credential or cookie capture.** No `CookieManager`, password, session-token, or credential API usage was found in `extensions/` or `patches/`.
- **No dynamic code loading.** No `DexClassLoader`, `PathClassLoader`, `InMemoryDexClassLoader`, `System.load`, or `loadLibrary`.
- **No WebView / JavaScript bridge.** No `WebView` or `addJavascriptInterface`.
- **No native libraries.** Only `gradle/wrapper/gradle-wrapper.jar` is present.
- **No project backend.** The only network calls are to GitHub’s release API and release asset URLs.
- **No analytics.** `docs/PRIVACY.md` states no analytics or usage collection, and no analytics SDK is declared.
- **Gradle wrapper integrity.** The distribution URL is pinned with a SHA-256 checksum.
- **GPL compliance artifacts present.** `LICENSE`, `NOTICE`, and in-repo attribution are intact.
- **Clear separation of dev-only code.** `DebugBridge` is behind an off-by-default patch with explicit “never ship it” warnings.

---

## 10. Suitability for HMX Insta+

### What can be reused

- The Morphe patch-bundle and extension architecture.
- The `SharedPreferences`-backed runtime toggle model.
- The network-block/feed-filter/following-feed patch patterns.
- The settings UI and tab-bar entry-point pattern.
- The build/sign script structure.
- The GPL-compatible attribution model.

### What must be fixed before HMX work

1. Reproducible toolchain (JDK 21, SDK 35, pinned CLI).
2. Credentialed dependency resolution.
3. A pinned, legally sourced test APK and a documented baseline version.
4. A first successful build with `patch-report.json` showing all required patches applied.
5. Device install/run verification (outside this audit’s constraints).
6. Version/compatibility policy (allowlist or explicit “unsupported” behavior).
7. CI for bundle build and static checks.
8. Security decisions on signature bypass, auto-update, debug bridge, and `REQUEST_INSTALL_PACKAGES` before shipping.

### What must not be carried into HMX blindly

- Default-on signature trust bypass.
- Default-on `REQUEST_INSTALL_PACKAGES` plus unverified auto-update.
- Heuristic cache deletion with process killing.
- Any debug bridge in a release build.
- Unbounded `version = null` targeting without a test matrix.

---

## 11. Evidence index

| Claim | Evidence |
|---|---|
| Audited commit | `git log -1` → `bad4cd060db42e4434e254e37cf77e784de96d7e` |
| Upstream remote preserved | `git remote -v` → `https://github.com/jean-voila/FeurStagram.git` |
| 107 tracked files | `git ls-files \| wc -l` |
| No workflows | `.github/workflows` absent |
| No tests | no `src/test` / `src/androidTest` |
| Build entry point | `build.sh:1-205` |
| CLI requirement | `build.sh:32-33`, `:92-95` |
| Split merge | `build.sh:97-125` |
| Signing | `build.sh:145-184` |
| Registry credentials | `settings.gradle.kts:3-17` |
| Morphe plugin version | `settings.gradle.kts:20` |
| Patch library version | `gradle/libs.versions.toml:3` |
| Unbounded targeting | `patches/src/main/kotlin/com/feurstagram/patches/shared/Constants.kt:14-24` |
| GPL-3.0 | `LICENSE:1`, `NOTICE`, `patches/build.gradle.kts:11` |
| Stock APK required | `docs/BUILD_FROM_SOURCE.md:15`, `Readme.md:277` |
| Signature bypass | `patches/src/main/kotlin/com/feurstagram/patches/signature/SignatureCheckBypassPatch.kt:48-92` |
| Install permission | `patches/src/main/kotlin/com/feurstagram/patches/update/InstallPermissionPatch.kt:8-18` |
| Auto-update download | `extensions/extension/src/main/java/com/feurstagram/extension/UpdateChecker.java:77-80`, `:922-976` |
| Debug bridge | `patches/src/main/kotlin/com/feurstagram/patches/debug/DebugBridgePatch.kt:30-71`, `extensions/extension/src/main/java/com/feurstagram/extension/DebugBridge.java:107-128` |
| Cache cleaner | `extensions/extension/src/main/java/com/feurstagram/extension/CacheCleaner.java:36-77`, `:139-173`, `:195-283` |

---

## 12. Phase 0 verdict

**PASS with conditions.** FeurStagram is a credible, auditable foundation with a clear architecture and no obvious credential-stealing or dynamic-loading behavior. It is **not** a turnkey APK source, and its default security posture is more permissive than an HMX release should be.

**Approval requested for the next phase only after:**

- the build environment and credentials are available;
- one baseline APK is produced and verified on a device;
- the security findings in `HMX_INSTAPLUS_SECURITY_AUDIT.md` are triaged;
- the CI approach in `HMX_INSTAPLUS_CI_PLAN.md` is accepted.

No HMX feature work, source modification, or build execution has been performed.
