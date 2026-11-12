# HMX Insta — regression tests defined before implementation (M0)

**Rule:** a test is written and failing *before* the code it protects, and no
feature milestone is merged with a red or skipped test. Device rows may not be
marked "pending" in a milestone that claims the feature works.

Test IDs are stable. Each milestone lists the IDs it must turn green.

---

## 1. Static gates — `scripts/ci/validate_features.sh` (new)

Runs on every PR and in the gated build. Plain `bash` + `grep`, no device.

| ID | What it asserts | Fail condition |
| --- | --- | --- |
| **S1** | **protected-anchor isolation** — new patch sources (only the `downloader/` and `ghost/` patch packages) do not reference any protected anchor from `HMX_INSTA_PROTECTED_METHODS.md` | any match for a forbidden string/class, except the single declared exception in its declared file |
| **S1b** | the declared exception exists in exactly one place: `tier2.row_binding.observe` in the downloader binder patch | duplicate, missing, or any second declared exception (the ghost network guard was rejected for v2) |
| **S2** | **core purity** — `downloader/core/` has **no** `android.*` / `androidx.*` imports and compiles with plain `javac` | any Android import or a compile that needs `android.jar` |
| **S3** | **credential isolation** — downloader and ghost sources contain none of `CookieManager`, `getCookie`, `Cookie`, `Authorization`, `access_token`, `sessionid`, `UserSession`, `PackageInstaller`, `READ_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE`, `MANAGE_EXTERNAL_STORAGE` | any occurrence |
| **S4** | **ghost isolation** — own patch, own preference file (`hmx_ghost_mode`), no import of downloader packages, no network/request code, master-off fast path before any preference read, and unsupported capabilities cannot be enabled | missing isolation, any network hook, or an unsupported capability marked available |
| **S5** | **patch inventory exactness** — `EXPECTED_PATCHES` equals baseline 14 + the named new patches; `MIN_APPLIED_PATCHES` equals the full count (not 10); every new functional patch is `default = false` until its device row passes | count mismatch, weak minimum, or a default-on new patch |
| **S6** | **no permission escalation** — the merged manifest adds no sensitive permission compared with the baseline APK | any added storage/contacts/location/notification-listener permission |
| **S7** | **no view-tree scanning** — downloader sources contain no `getRootView`, `getDecorView`, `getChildCount` loop, `findViewWithTag`, or content-description search | any occurrence outside the narrow, allowlisted `DownloaderBinder` holder access |
| **S8** | **no updater reintroduction** — no FeurStagram release polling, no `PackageInstaller` install path | any occurrence |

`EXPECTED_PATCHES` lists all 14 and `MIN_APPLIED_PATCHES` is now the exact
`14` (raised in M0 from `10`). S5 keeps it from regressing.

---

## 2. Unit tests — plain JDK, no device (JVM)

Run with `scripts/ci/test_downloader_logic.sh` and
`scripts/ci/test_ghost_mode.sh`; a tiny assertion harness writes a
`PASS n / FAIL 0` line and exits non-zero on any failure. No Android
dependencies, no network, no real account data.

### 2.1 Downloader core

| ID | Test | Pass condition |
| --- | --- | --- |
| **U1** | task state machine | only `QUEUED→RUNNING→DONE/FAILED/CANCELLED` transitions are accepted; illegal transitions are rejected without throwing |
| **U2** | queue bounds and concurrency | never exceeds the configured workers; a full queue rejects cleanly; cancellation frees a slot |
| **U3** | retry policy | retryable errors retry up to the cap with a bounded backoff; non-retryable errors stop immediately |
| **U4** | naming and MIME | safe file names (no path traversal, no control chars), correct extension per MIME, deterministic de-duplication suffix |
| **U5** | duplicate detection | same media id/url is enqueued once; different renditions of one media are distinct |
| **U6** | carousel expansion | a carousel model expands to its children in order; an empty/partial carousel degrades without throwing |
| **U7** | expired story refusal | a story item past its expiry is refused with `EXPIRED`; it is never fetched |
| **U8** | error taxonomy | every failure maps to a stable code with `retryable` set correctly; user-facing text never contains a URL, token or exception trace |
| **U9** | defensive binder observer | `DownloaderBinder.observe(null, null)`, unknown model types and models without media return silently and never throw |
| **U10** | master switch inertness | with the downloader disabled, no task is created, no worker starts, no preference is written |

### 2.2 Ghost mode

| ID | Test | Pass condition |
| --- | --- | --- |
| **U11** | master switch | off means every feature is inactive, even if individual toggles were left on |
| **U12** | per-feature gating | story-seen, message-seen and typing suppression apply only to their own paths |
| **U13** | path matching | no false positives on unrelated paths (feed, media, login, direct-send); table-driven cases |
| **U14** | unsupported features | the four server-side features cannot be activated through the policy API or the UI model |
| **U15** | fail-safe evaluation | a policy invoked with a null/unknown input, or with a throwing preference backend, always returns normally; exceptions never escape |
| **U16** | zero-cost off | with the master switch off, evaluation returns before any allocation, reflection, preference read or logging (asserted with a counting/instrumented policy) |

---

## 3. Dex-level gates — CI, no device

| ID | Test | Pass condition |
| --- | --- | --- |
| **D1** | **protected-method equality** — `scripts/ci/verify_protected_methods.py` (M0) compares every surface in `scripts/ci/protected_methods.json` between the candidate APK and the frozen baseline APK | all protected methods equal after canonicalisation; only the declared exception may differ, and only after its exact-diff check is active |
| **D2** | **media-parse freeze** — explicit subset of D1 | **zero** new instructions in any parser, codec, field-dispatch or `unsafeParseFromJson` method |
| **D3** | **dex structural validity** | every `classes*.dex` parses with `dexdump` without error; no duplicate class/method descriptors; no unresolved reference introduced by a patch |
| **D4** | **packaging gates unchanged** | bundle gate, input gate, merge-stage validation, final standalone validation, `apksigner` v2, `zipalign -c -p 4` all pass exactly as at the baseline |
| **D5** | **patch report** | `success: true`, `failedPatches: []`, and the applied names equal `EXPECTED_PATCHES` exactly |
| **D6** | **allowed-diff shape** | for the row-binding exception (activated at M4): exactly one inserted instruction, at the first instruction, with the declared callee; no other instruction changes |

D1 exists and is wired into the gated build **now**, in M0, before any new
patch that could touch a protected surface. The baseline is compared against
itself (must pass) and every later candidate is compared against the frozen
reference release.

---

## 4. Device tests — OPPO A38 (CPH2579), Android 15 / API 35 / arm64-v8a

Every device row is manual, on the real handset, with the network on. Logs are
captured with the redacting helper; raw logs stay on the device.

| ID | Test | Pass condition |
| --- | --- | --- |
| **V0** | install identity | installed APK SHA-256 equals the published asset; signer, version and label match; `pm path` resolves |
| **V1** | cold launch | app reaches an interactive screen within **5 s** of `am start`; no black screen, no endless spinner, no ANR; `dumpsys activity` shows the expected resumed activity |
| **V2** | login | login screen appears with the network on; manual sign-in completes; no crash or loop; **credentials are never scripted or logged** |
| **V3** | feed / stories / reels | home feed loads and scrolls; stories open and advance; reels play; images and video render |
| **V4** | baseline 14 patches | spot-check the visible ones: SDR colour mode, hidden Home in back, settings entry, notes tray, popup hiding, suggested-account filtering, limit-feed and network blocks respond to their toggles; app name and icon are HMX Insta |
| **V5** | downloader | Tier 1: share a photo/video to HMX Insta Downloader and find it in the gallery; Tier 2: the Downloader page shows its own queue/history and saves shared photo, video, carousel and audio items; Tier 3 (per surface): feed/story/reels/carousel actions work and survive scrolling/recycling |
| **V6** | ghost mode | page opens; the three implemented controls take effect; the four unsupported ones show their reason and cannot be enabled; master switch off restores normal behaviour |
| **V7** | updater removed | no release poll, no "Update available" prompt, no install path |
| **V8** | Instagram UI unchanged | with new features off, feed/story/reels layouts are visually identical to the baseline APK; no stray buttons, padding or overlays |
| **V9** | kill switch | disabling downloader and ghost mode and rebooting leaves launch/login/feed identical to the baseline |
| **V10** | soak | 10 minutes of normal use (feed, stories, reels, background/foreground) with no crash, ANR, or request-timeout storm |
| **V11** | log discipline | captured logs contain no password, OTP, cookie, `Authorization` header, session id or access token |

### Device A/B protocol (used at every feature milestone)

1. Install and launch the **baseline** APK; capture a 90-second window from
   `am start` (activity, `AndroidRuntime`, `CrashLoopRemedyLog`, Tigon request
   timing).
2. Install and launch the **candidate** the same way, same account state.
3. Compare: time to first frame, time to first Tigon request, any crash/ANR,
   any change in the resumed activity sequence.
4. A candidate that is slower to first frame, later to first request, or that
   adds any crash/ANR line is **not accepted**, even if the feature works.

Because installing a differently-signed APK clears app data, both sides start
signed out. Record that state explicitly; do not compare a signed-out candidate
with a signed-in baseline.

---

## 5. Milestone → test matrix

| Milestone | Must be green before merge |
| --- | --- |
| **M0** baseline freeze | S1/S1b declarations, D1 run baseline-vs-baseline, D2, D4, D5; protected-method self-test; the 14-patch minimum |
| **M1** engine core | M0 + S2, S3, S5, U1–U10 |
| **M2** Android shell + share target | M1 + S6, S7, S8, D3, D5, V0–V4, V7 |
| **M3** Downloader page (Tier 2) | M2 + V5 (Tier 1+2), V8, V9 |
| **M4** feed integration (Tier 3) | M3 + D1, D6 (exception activated), V5 (feed), V8 |
| **M5** story integration | M4 + D1, V5 (story), V8 |
| **M6** reels + carousel integration | M5 + D1, V5 (reels, carousel), V8 |
| **M7** ghost core + settings | M6 + S4, U11–U16, V6 (settings, unsupported list), V9 |
| **M8** ghost integrations | M7 + D1, V6 (each implemented control), V8 |
| **M9** full regression | all of the above + V1–V11, no pending device row |

---

## 6. What these tests deliberately do **not** do

* They do not test with real credentials, real session cookies or a live
  account in automation.
* They do not bypass certificate checks or Instagram networking.
* They do not exercise the media parser as a download source.
* They do not weaken the existing packaging, signature or patch-report gates.
* They do not mark a feature "verified" from CI alone; device rows are
  mandatory.
