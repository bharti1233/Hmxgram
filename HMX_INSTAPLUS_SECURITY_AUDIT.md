# HMX Insta+ — Security Audit

**Target:** FeurStagram at commit `bad4cd060db42e4434e254e37cf77e784de96d7e`
**Scope:** repository source, patches, runtime extension, build/signing scripts, update path, manifest mutations
**Method:** static review only
**Dynamic testing:** none — no APK was built, installed, or run
**Classification:** internal Phase 0 security review

---

## 1. Executive summary

The codebase does **not** contain the classic red flags of a malicious Instagram mod:

- no credential, cookie, or session-token capture;
- no WebView or JavaScript bridge;
- no dynamic code loading (`DexClassLoader`, `PathClassLoader`, `System.load`);
- no native libraries;
- no project-operated backend or telemetry endpoint;
- no request proxying.

However, the project ships several **security-relevant design choices enabled by default**:

1. **Signature trust bypass** — Instagram’s signing-certificate checks are forced to return `true`.
2. **Unverified auto-update** — the app downloads an APK from GitHub Releases and hands it to `PackageInstaller` without hash or signer verification.
3. **`REQUEST_INSTALL_PACKAGES`** — declared by default to support that updater.
4. **Unbounded patch targeting** — `version = null` accepts every future Instagram build.
5. **Destructive cache cleaner** — heuristic file/database deletion plus process killing.
6. **Dev-only debug bridge** — an exported, permissionless broadcast receiver when `--debug` is used.

None of these is evidence of malicious intent. They are product/engineering trade-offs that must be explicitly accepted, narrowed, or disabled before an HMX release.

**Overall risk:** **Medium-High** for a distributed modified client; **Medium** for a locally built, single-user test build.

---

## 2. Threat model

### 2.1 Assets

| Asset | Why it matters |
|---|---|
| Instagram account and session | Compromise or flagging can lock the user out |
| Device data accessible to Instagram | Cache, media, preferences, databases |
| Update channel | A compromised release can execute code on every user device |
| Signing key | Determines update trust and app identity |
| User’s network metadata | IP, device model, timing |
| Build supply chain | CLI jar, Gradle plugins, transitive dependencies |

### 2.2 Adversaries and failure modes

| Adversary / failure | Relevant surface |
|---|---|
| Malicious app on the same device | Debug bridge receiver, exported components, intents |
| Network attacker | Update download, metadata fetch |
| Compromised GitHub account/release | Auto-update, release asset naming |
| Malicious or trojaned base APK | Input to the patcher |
| Malicious CLI or dependency | Build host compromise |
| User error | Cache deletion, debug build shipped, weak signing key |
| Instagram anti-tamper / policy enforcement | Signature bypass, modified client detection |

### 2.3 Trust boundaries

```text
[Stock Instagram APK] --untrusted input--> [Morphe CLI on build host]
                                              |
                                              v
[Patch bundle + extension] -------------> [Patched APK]
                                              |
                                              v
[GitHub Releases] --unverified download--> [PackageInstaller] --> [Device]
```

The build host and the GitHub release account are the highest-value trust anchors. The device-side app trusts both.

---

## 3. Findings

Severity scale: **Critical / High / Medium / Low / Info**.
Status: **Open**, **Accepted by design**, **Needs decision**, **Mitigated in plan**.

| ID | Finding | Severity | Status |
|---|---|---|---|
| SEC-01 | Signature trust checks forced to `true` by default | High | Needs decision |
| SEC-02 | Auto-update installs unverified APK; `REQUEST_INSTALL_PACKAGES` default-on | High | Needs decision |
| SEC-03 | Dev-only debug bridge is an exported, permissionless receiver | Medium | Open / dev-only |
| SEC-04 | Cache cleaner deletes heuristically and kills processes | Medium | Needs decision |
| SEC-05 | Unbounded Instagram version targeting | Medium | Open |
| SEC-06 | Unpinned build supply chain | Medium | Open |
| SEC-07 | Clone patch rewrites package names and provider authorities globally | Low | Accepted by design |
| SEC-08 | Hidden-API reflection (`ActivityThread.currentApplication`) | Low | Accepted by design |
| SEC-09 | Update check discloses IP/User-Agent to GitHub | Low | Accepted by design |
| SEC-10 | No tests, CI, SBOM, or reproducible-build attestation | Low | Open |
| SEC-11 | Proprietary APK redistribution / ToS / legal risk | High (legal) | Needs decision |
| SEC-12 | Signature bypass may trigger anti-tamper or policy action | Medium | Needs decision |

---

## 4. Detailed findings

### SEC-01 — Signature trust checks forced to `true` by default

**Severity:** High (security-control weakening)
**Status:** Needs decision
**Default:** On

**Evidence**

- `patches/src/main/kotlin/com/feurstagram/patches/signature/SignatureCheckBypassPatch.kt:48-92`
- The patch locates Instagram’s key-hash allowlist check via the string `"Invalid SHA256 key hash"` (`:35-37`).
- It replaces the body of the static allowlist membership check with `const/4 v0, 0x1; return v0` (`:39-45`, `:62-66`).
- It also replaces the family-app scope trust check, including the newer instance-method form (`:68-91`).
- Description: “Forces Instagram’s signing-certificate trust checks to always pass, so a re-signed APK is treated as an official Meta build” (`:49-52`).
- Default is `true` (`:53`).

**Impact**

- Any Instagram code path that relies on these checks now sees “trusted” for a re-signed build.
- This is broader than the stated deep-link use case. The comment itself says the allowlist powers “the self-signature gate and every other trust decision” (`:60-61`).
- It weakens a platform-provided authenticity signal and may interact with anti-tamper, integrity, or future security features in ways that cannot be established statically.
- It does **not** bypass Instagram login, and it does not decrypt or extract credentials. The impact is integrity/trust, not credential theft.

**Why it exists**

Instagram drops deep links into content when the APK is not signed by Meta (`Readme.md:410-417`). Re-signing is unavoidable for a modified client, so the project forces the check to pass.

**Recommendation**

- Do not ship this as an unconditional default without an explicit HMX security decision.
- Narrow the patch to the deep-link resolver call site if feasible, instead of rewriting the global allowlist and scope checks.
- Make it a user-visible, off-by-default option with a clear warning, or gate it behind a build flavor.
- Add a regression test that confirms non-deep-link trust behavior is unchanged.
- Document the anti-tamper/ToS risk in the release notes.

---

### SEC-02 — Auto-update installs an unverified APK; install permission is default-on

**Severity:** High
**Status:** Needs decision
**Default:** Auto-update on; install permission on

**Evidence**

- Release endpoint is hardcoded to `jean-voila/Feurstagram` (`extensions/extension/src/main/java/com/feurstagram/extension/UpdateChecker.java:77-80`).
- Automatic check runs on launch when `auto_update` is true (`extensions/extension/src/main/java/com/feurstagram/extension/UpdateChecker.java:122-126`); default is true (`extensions/extension/src/main/java/com/feurstagram/extension/Config.java:108-111`).
- The download follows redirects and writes the response body to `getExternalFilesDir(DIRECTORY_DOWNLOADS)/feurstagram-update.apk` (`extensions/extension/src/main/java/com/feurstagram/extension/UpdateChecker.java:922-976`).
- The only integrity check is a length/truncation check (`extensions/extension/src/main/java/com/feurstagram/extension/UpdateChecker.java:971-973`). There is **no SHA-256, no signature verification, and no signer-certificate pinning** before staging.
- The file is written into a `PackageInstaller` full-install session and committed (`extensions/extension/src/main/java/com/feurstagram/extension/UpdateChecker.java:995-1051`).
- `REQUEST_INSTALL_PACKAGES` is added to the manifest by a default-on resource patch (`patches/src/main/kotlin/com/feurstagram/patches/update/InstallPermissionPatch.kt:8-18`).

**Impact**

- The update channel inherits the security of the GitHub account/release process. A compromised maintainer account, a malicious release asset, or a DNS/TLS compromise that can serve a valid GitHub response could deliver arbitrary code.
- Android still shows an install confirmation and enforces APK signature consistency for in-place updates, which limits silent replacement. A clone package with a different key has a separate trust boundary.
- The app’s own signature check is bypassed (SEC-01), so the modified app cannot rely on Instagram’s internal integrity checks either.
- The downloaded APK is stored in app-specific external storage, readable by the app and potentially by other privileged components; it is not world-readable on modern Android, but it is outside internal storage.

**Recommendation**

- Verify a SHA-256 published in the release metadata before staging. Ideally verify the APK signer certificate against a pinned HMX release certificate.
- Prefer a signed update manifest (e.g., detached signature over version + URL + hash).
- Make auto-update opt-in, or at least default-off for HMX.
- Remove `REQUEST_INSTALL_PACKAGES` unless the updater is retained; if retained, keep it behind the same opt-in.
- Do not follow arbitrary redirects to non-GitHub hosts without validation.
- Publish hashes in release notes, as the project’s own `docs/REPOSITORY_SETTINGS.md:44` recommends.

---

### SEC-03 — Dev-only debug bridge is an exported, permissionless receiver

**Severity:** Medium
**Status:** Open / dev-only
**Default:** Off (requires `--debug`)

**Evidence**

- `patches/src/main/kotlin/com/feurstagram/patches/debug/DebugBridgePatch.kt:30-71`
- The patch sets `android:debuggable="true"` (`:20-27`).
- It registers a dynamic receiver with `Context.RECEIVER_EXPORTED` (`2`) on Android 13+ (`extensions/extension/src/main/java/com/feurstagram/extension/DebugBridge.java:107-128`).
- The receiver has no permission check and handles `SET`, `GET`, `REMOVE`, `DUMP`, `RESET`, `RESTART`, `UPDATE`, `NAV`, `CLICK`, `TOUCH`, `FIELDS`, and more (`extensions/extension/src/main/java/com/feurstagram/extension/DebugBridge.java:63-66`, `:147-193`).
- `DUMP` returns every stored preference and effective value (`:245-263`).
- `SET ... --ez force true` writes directly to SharedPreferences, bypassing the permanent lock (`:201-233`).
- `FIELDS` uses reflection to dump view fields, including hidden `ListenerInfo` (`:389-446`).
- The patch description warns: “Never ship a release with this enabled: any app on the device could send those broadcasts” (`patches/src/main/kotlin/com/feurstagram/patches/debug/DebugBridgePatch.kt:39-40`).

**Impact**

If a release is accidentally built with `--debug`:

- any app on the device can read and modify FeurStagram settings;
- any app can force a restart, trigger update checks, or dump the view tree;
- the app is debuggable, so a local attacker with ADB access can attach a debugger to Instagram’s process (which already requires USB debugging and user authorization, but broadens exposure).

The preferences do not contain Instagram credentials, but they do reveal behavior and allow persistent configuration changes.

**Recommendation**

- Keep the debug patch out of release builds by construction: fail the release script if `--debug` is present.
- Replace the exported receiver with a signature-level permission or `adb shell`-only mechanism.
- Remove the `android:debuggable` mutation from any artifact that is not explicitly a debug build.
- Add a CI/release check that scans the manifest for `debuggable="true"` and the debug receiver.
- Consider stripping `DebugBridge` from release `.mpe` entirely.

---

### SEC-04 — Cache cleaner deletes heuristically and kills processes

**Severity:** Medium (data loss / availability)
**Status:** Needs decision
**Default:** Reachable from settings and the debug bridge

**Evidence**

- `extensions/extension/src/main/java/com/feurstagram/extension/CacheCleaner.java:36-77`
- Deletes contents of `cacheDir`, `codeCacheDir`, and external cache (`:42-46`).
- Walks `filesDir` and deletes any subdirectory whose lowercase name contains a media marker, unless it contains an auth marker (`:195-232`).
- Deletes Room databases whose filename contains `delivery_media_room_db`, `user_reel_medias_room_db`, `flash_media_`, or `clips_` (`:139-173`).
- Kills every process whose name equals or starts with the package name (`:256-283`), then kills itself (`:75-76`).
- The auth protection is a name heuristic (`AUTH_MARKERS`, `:210-213`), not a structural guarantee.

**Impact**

- A future Instagram rename or a legitimate directory containing “media”/“cache” can be deleted unexpectedly.
- Database deletion can log the user out, break DM state, or remove drafts if naming changes.
- Process killing can interrupt uploads, messages, or background work.
- The behavior is user-triggered, but it is destructive and hard to undo.

**Recommendation**

- Replace heuristics with an explicit allowlist of known-safe paths per tested Instagram version.
- Add a dry-run mode and a summary of what will be deleted.
- Never delete databases unless explicitly requested; prefer cache-only cleanup.
- Add unit tests for path classification.
- Keep auth/session directories structurally excluded, not just by name.
- Consider removing the “clear cache and restart” action from HMX until it is test-covered.

---

### SEC-05 — Unbounded Instagram version targeting

**Severity:** Medium (correctness / integrity)
**Status:** Open

**Evidence**

- `patches/src/main/kotlin/com/feurstagram/patches/shared/Constants.kt:14-24`
- `AppTarget(version = null)` with the comment that it “targets the latest known build and every future one” (`:19-20`).

**Impact**

- The patcher will attempt unsupported Instagram versions.
- A fingerprint can match a structurally similar method that has different semantics, producing corrupted behavior rather than a clean failure.
- Users may install a build that silently lacks a block or breaks a surface.
- There is no tested-version allowlist, no maximum version, and no runtime version check.

**Recommendation**

- Introduce a tested-version range (minimum/maximum) or an explicit allowlist.
- Add a runtime warning when the Instagram version is outside the tested range.
- Keep `version = null` only for development builds.
- Record the exact base APK version and patch report for every release.

---

### SEC-06 — Unpinned build supply chain

**Severity:** Medium
**Status:** Open

**Evidence**

- `settings.gradle.kts:3-17` resolves plugins from Gradle Plugin Portal, Google, GitHub Packages, and JitPack.
- `settings.gradle.kts:20` pins the Morphe plugin to `1.3.2`, but there is no root `build.gradle.kts`, no AGP version, no Kotlin version, and no dependency lock.
- `gradle/libs.versions.toml:2-6` contains unused version entries (`morphe-patcher`, `smali`) and an unused `gson` dependency.
- `build.sh:32-33` accepts any `tools/morphe-cli-*.jar` or `tools/morphe-desktop-*.jar`; there is no hash check.
- `build.sh:100` accepts any `tools/APKEditor-*.jar`.
- No SBOM or dependency verification metadata exists.

**Impact**

- Two builds at different times can use different AGP/Kotlin/transitive versions.
- A compromised or replaced CLI/APKEditor jar on the build host is trusted completely.
- JitPack and GitHub Packages are additional supply-chain surfaces.
- The Gradle wrapper checksum is pinned (good), but it only covers Gradle itself.

**Recommendation**

- Pin AGP, Kotlin, and all dependencies explicitly; add Gradle dependency verification/lockfiles.
- Pin the Morphe CLI and APKEditor versions and verify SHA-256 in `build.sh`.
- Remove unused dependencies.
- Generate an SBOM and publish hashes with releases.
- Use a clean, ephemeral build host.

---

### SEC-07 — Clone patch rewrites package names and authorities globally

**Severity:** Low
**Status:** Accepted by design
**Default:** Off

**Evidence**

- `patches/src/main/kotlin/com/feurstagram/patches/clone/ClonePatch.kt:62-130`
- Rewrites the manifest package (`:71`), custom permissions (`:73-85`), provider authorities (`:87-99`), the app label (`:102-111`), and every string constant matching the package or an authority (`:113-128`).
- The transformation is global across all classes with strings (`:133-157`).

**Impact**

- A string that merely contains `com.instagram.android` but is not a package/authority reference can be rewritten.
- Provider authority collisions or broken deep links are possible if Instagram adds new authority patterns.
- This is a compatibility risk more than a security vulnerability.

**Recommendation**

- Narrow transformations to known fields and exact matches.
- Add a post-patch manifest validation step.
- Test clone install alongside stock Instagram.

---

### SEC-08 — Hidden-API reflection for application context

**Severity:** Low
**Status:** Accepted by design

**Evidence**

- `extensions/extension/src/main/java/com/feurstagram/extension/Config.java:36-45`
- Uses `Class.forName("android.app.ActivityThread")` and `currentApplication()`.

**Impact**

- Relies on a hidden Android API. It works on many versions but can break under future restrictions or be blocked by OEM changes.
- Failure is handled by returning `null`, so the app degrades rather than crashes in most paths.
- No dynamic code loading is involved.

**Recommendation**

- Prefer an injected `Context` from a patch hook where possible.
- Add a fallback and a test on the minimum supported Android version.

---

### SEC-09 — Update check discloses IP/User-Agent to GitHub

**Severity:** Low (privacy)
**Status:** Accepted by design
**Default:** On

**Evidence**

- `extensions/extension/src/main/java/com/feurstagram/extension/UpdateChecker.java:77-81`, `:219-233`
- Sends `User-Agent: Feurstagram-UpdateCheck` and `Accept: application/vnd.github+json` to `api.github.com`.
- Automatic check is on by default (`extensions/extension/src/main/java/com/feurstagram/extension/Config.java:108-111`).

**Impact**

- GitHub learns the user’s IP, timing, and the fact that a FeurStagram build is in use.
- No credentials or Instagram identifiers are sent.
- This is a small but real privacy disclosure that `docs/PRIVACY.md` does not call out explicitly.

**Recommendation**

- Document the GitHub contact in the privacy policy.
- Make automatic checks opt-in.
- Consider a randomized or generic User-Agent.

---

### SEC-10 — No tests, CI, SBOM, or reproducible-build attestation

**Severity:** Low (assurance)
**Status:** Open

**Evidence**

- No `.github/workflows`.
- No `src/test` or `src/androidTest`.
- No lockfiles, SBOM, provenance, or signed-release workflow in the repo.
- `build.sh:186-198` only prints the patch report.

**Impact**

- Regressions are discovered by users, not by the build.
- A release cannot be independently reproduced or attested.
- Security review must rely on reading code, not on verified behavior.

**Recommendation**

- Add unit tests for pure Java helpers (`Block`, `Config`, `CacheCleaner` classifiers).
- Add a patch-application integration test with a pinned APK on a controlled runner.
- Add CI for bundle compilation and static checks.
- Publish SBOM and SHA-256 hashes; sign release artifacts.

---

### SEC-11 — Proprietary APK redistribution, ToS, and legal risk

**Severity:** High (legal/compliance, not a code vulnerability)
**Status:** Needs decision

**Evidence**

- The build modifies `com.instagram.android` (`patches/src/main/kotlin/com/feurstagram/patches/shared/Constants.kt:14-24`).
- `Readme.md:79` acknowledges ToS risk.
- `NOTICE` covers GPL-3.0 for the project code; it does not grant rights to Meta’s APK.
- `docs/REPOSITORY_SETTINGS.md:44` recommends publishing SHA-256 hashes for APK artifacts.

**Impact**

- Distributing a patched Instagram APK redistributes Meta’s proprietary code.
- The GPL does not cure copyright or contract issues with Meta.
- A public HMX release could receive takedown notices or account/legal action.
- Users may face account restrictions for using a modified client.

**Recommendation**

- Obtain legal review before any public APK distribution.
- Keep source-only distribution as the default; let users build locally.
- Do not host the stock APK or patched APK in the source repository.
- Preserve GPL notices and upstream attribution.
- Make the ToS/account risk explicit in release notes.

---

### SEC-12 — Signature bypass may trigger anti-tamper or policy action

**Severity:** Medium
**Status:** Needs decision

**Evidence**

- `patches/src/main/kotlin/com/feurstagram/patches/signature/SignatureCheckBypassPatch.kt:48-92`
- `Readme.md:410-417` explains the deep-link motivation.

**Impact**

- Instagram/Meta may add or change integrity checks that detect the modified client.
- The bypass may mask a signal the app uses for security decisions beyond deep links.
- Account risk cannot be assessed statically.

**Recommendation**

- Treat this as an explicit product risk.
- Prefer the narrowest possible patch.
- Monitor upstream changes and test each Instagram release.
- Provide a build flavor without the bypass for users who prefer deep-link breakage over trust modification.

---

## 5. Positive security controls

| Control | Evidence |
|---|---|
| No credential/cookie APIs | static search found no `CookieManager`, password, token APIs |
| No dynamic code loading | no `DexClassLoader`, `PathClassLoader`, `System.load`, `loadLibrary` |
| No WebView/JS bridge | no `WebView` or `addJavascriptInterface` |
| No native libraries | only `gradle-wrapper.jar` |
| No telemetry/backend | only GitHub release API and asset download |
| HTTPS for update traffic | `https://api.github.com`, release URLs |
| Android install confirmation | `PackageInstaller` user action, not silent install |
| Restart activity not exported | `patches/src/main/kotlin/com/feurstagram/patches/restart/RestartRelayPatch.kt:42` |
| Update receivers not exported | `extensions/extension/src/main/java/com/feurstagram/extension/UpdateChecker.java:846`, `:1123` |
| Debug bridge off by default | `patches/src/main/kotlin/com/feurstagram/patches/debug/DebugBridgePatch.kt:47` |
| GPL notices preserved | `LICENSE`, `NOTICE` |
| Gradle distribution checksum | wrapper properties |

---

## 6. Security release gates

A release must not be published unless all of the following are true:

1. **No `--debug` build.** Manifest has no `android:debuggable="true"` and no debug receiver.
2. **No keystore, token, APK, or `tools/` content in git.**
3. **Update integrity.** Either auto-update is disabled, or the downloaded APK is verified against a published hash/signature.
4. **Install permission decision.** `REQUEST_INSTALL_PACKAGES` is justified and documented, or removed.
5. **Signature bypass decision.** The patch is narrowed, opt-in, or explicitly accepted with a documented risk.
6. **Version pinning.** The release records the exact Instagram version, base APK hash, CLI version, and patch report.
7. **Cache cleaner decision.** Destructive cleanup is removed, allowlisted, or explicitly accepted.
8. **Release signing.** Same keystore as prior releases; v1/v2/v3 verified; key stored in a secret manager.
9. **Artifact hashes.** SHA-256 published in release notes.
10. **Legal review.** Public APK distribution has been reviewed for Meta/ToS exposure.

---

## 7. Residual risk statement

Even with all recommendations applied:

- A modified Instagram client remains unsupported by Meta and may be detected or restricted.
- A locally built APK trusts the build host, the CLI jar, and the base APK.
- A distributed APK trusts the release account and signing key.
- The signature bypass changes an integrity signal by design.
- No static audit can prove the absence of runtime vulnerabilities in Instagram’s own code or in transitive patch-library behavior.

These risks must be accepted explicitly by the project owner before HMX feature work.

---

## 8. Status

- Static security review: **complete**.
- Dynamic verification: **not performed**.
- Findings requiring a decision before release: **SEC-01, SEC-02, SEC-04, SEC-11, SEC-12**.
- No credentials, cookies, or private data were accessed during this audit.
