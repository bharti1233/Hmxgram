# HMX Insta — Next Phase Implementation Plan

**Base commit (recovery point):** `a5bace00e84094f06622f8f47ae60990ded8e09`
(`a5bace0`, "Merge pull request #8 from bharti1233/feature/hmx-insta-branding"),
also reachable as the branch `backup/hmx-insta-last-known-good-454`.

> **Correction:** the SHA quoted in the request
> (`a5bace0e84a094f06622f8f47a06909d0ed8e09`) does not exist in this
> repository. The real commit is `a5bace00e84094f06622f8f47ae60990ded8e09`;
> the backup branch points at exactly that object.

**Status of this document:** plan only. No build, no publish, no merge, no
revert, no source modification.

---

## 0. What the previous attempt taught us

The Downloader/Ghost Mode attempt (PRs #9–#15) broke startup and login. The
root cause was *not* one line but an **architecture**: it put new code on
Instagram's **media-parsing path** and **every network request**, and it
injected views into Instagram's own view hierarchy from a global layout
listener. The dex-level defect was found and fixed; the architectural exposure
was not.

Three rules follow, and they shape everything below:

1. **Never touch Instagram's parse, auth, session or feed-request code.** New
   features observe; they do not rewrite.
2. **Never scan or mutate Instagram's view hierarchy.** No global layout
   listener, no content-description search, no button insertion into recycled
   rows.
3. **Every new hook is independently gated** — its own patch, its own
   preference, its own kill-switch, its own device verification.

---

## 1. Branch strategy

| Branch | Base | Purpose |
| --- | --- | --- |
| `feature/hmx-insta-downloader-v2` | `backup/hmx-insta-last-known-good-454` (`a5bace0`) | the new work, built **on** the recovery point |
| `feature/hmx-insta-ghost-v2` | `feature/hmx-insta-downloader-v2` | ghost mode, split out so it can be reviewed and reverted independently |

`main` (`84d620d`) and `feature/hmx-downloader-ghost-mode` (`924105c`) are **not
touched**. The failed chain stays in history for reference; nothing is
cherry-picked back from it except ideas re-derived from scratch.

The recovery point is merged into `main` only when the new work is verified —
as a normal PR, not a reset.

---

## 2. Proposed architecture

### 2.1 Downloader engine (no Instagram code at all)

```
extensions/extension/src/main/java/com/feurstagram/extension/downloader/
  core/            pure Java, unit-tested, zero Android imports
    DownloadKind      PHOTO | VIDEO | CAROUSEL
    MediaItem         one downloadable item (url, kind, mime, size, owner)
    DownloadTask      state machine: QUEUED RUNNING DONE FAILED CANCELLED
    DownloadQueue     bounded queue, concurrency limit, retry policy
    DownloadResult    outcome (ok / error code / path)
    DownloadError     error taxonomy + recoverability
    MediaNaming       safe names, MIME, extension, duplicate keys
    DuplicateDetector id/url based duplicate detection
    MediaSource       interface: media model -> DownloadTask sources
  android/         the shell around the core
    HmxDownloader      engine, workers, notifications
    MediaStoreWriter   MediaStore insert (IS_PENDING), no permission
    AudioExtractor     MediaExtractor/MediaMuxer audio extraction
    DownloadHistory    preferences-backed history
    DownloadNotifier   ongoing + finished notifications
    DownloadShareTarget  the system share entry point (Tier 1)
  ui/
    DownloadSheet      contextual menu for one media item
    DownloadQueueScreen queue + history page
```

Hard constraints (asserted by tests):

* the core has **no Android imports** and is compiled/tested with plain `javac`;
* nothing in the engine opens a socket except the download worker, and that
  worker only fetches the URL the *app itself* handed us;
* no credential, cookie, token or header of Instagram is read;
* the engine is a **pure consumer**: it never calls into Instagram.

### 2.2 Where the media comes from — three tiers, safest first

Each tier is a separate, separately gated step. **Tier 1 ships first and is
sufficient** to deliver the feature without touching Instagram.

**Tier 1 — system share target (no Instagram hooks at all).**
The app registers a share receiver for `image/*` and `video/*`. Sharing a photo
or video from Instagram (or any app) to "HMX Insta Downloader" saves it to the
gallery. Zero Instagram internals, zero main-thread work, zero risk to startup
or login.

**Tier 2 — our own surfaces, fed by a narrow read-only observation.**
A single hook on the **row binder** (`IgRecyclerViewAdapter` → the row's
`bind(holder, model)` method — the same anchor the Quoicouview patch already
resolves) gives us the **media model as a method parameter**. We read it; we do
not modify the row, the holder, the model or the layout. The model is registered
in our own registry, and the HMX Insta **Downloader page** (in our settings)
lists what the user has been looking at, with the contextual actions.

* No view hierarchy scanning.
* No view injection.
* The hook is a *read* of a parameter — Instagram's code path is unchanged; the
  only added instruction is a call that stores an object reference.

**Tier 3 — in-context action buttons (one surface at a time, each gated).**

| Surface | Anchor | Action |
| --- | --- | --- |
| Feed post | the row binder's **holder** (the same object Tier 2 already touches) | a single small button added **to that row only**, never to a shared container; the media comes from the binder's model parameter — never from a tree scan |
| Story | the story-tray row **we already render** (`StoriesTrayRow`, our own view) | the action lives in a view we own; Instagram's story viewer is not modified |
| Reels | the reels row binder, if it is the same binder as the feed | reuse the feed action; otherwise a separate gated patch |
| Carousel | the model exposes its children | *Download All Photos* plus per-item selection from the model, not from the UI |

Rules for Tier 3:

* one patch per surface, each with its own fingerprint, its own preference and
  its own device gate;
* each patch is **default-off** until it has been verified on the OPPO A38;
* the button is added to the **row's own view group**, and is removed/ignored on
  recycling by checking a tag on the holder;
* a row that cannot be identified safely is left completely alone.

### 2.3 Ghost mode — independent hooks, independent state

```
extensions/extension/src/main/java/com/feurstagram/extension/ghost/
  GhostFeature   id, title, explanation, support level, preference key
  GhostPolicy    pure rules: master switch, per-feature gating, path matching
  GhostState     the master switch and per-feature preferences (own file)
  GhostNetwork   the request hook, fail-safe, zero-cost when off
  GhostModeScreen the settings page
```

* **Its own patch** (`HMX ghost mode`), separate from the downloader, so it can
  be dropped without touching anything else.
* **Its own preference file** (`hmx_ghost_mode`), independent of the content
  blocks and of the permanent lock.
* **Zero cost when off**: the hook reads one `volatile boolean` and returns
  immediately — no preferences, no reflection, no allocations on the request
  path.
* **Fail-safe**: the hook is wrapped so that it can never throw into Instagram's
  request path; a failure inside ghost mode must never fail a request.
* **Server-side reality is stated in the UI**: only *Hide Story Seen*, *Hide
  Message Seen* and *Hide Typing Status* are implemented; *Hide Online Status*,
  *Disable Activity Status*, *Anonymous Story Viewing* and *Hide Live View* are
  shown as **not supported** with the reason and cannot be switched on.
* The master switch controls **only** the implemented controls.

---

## 3. Instagram surfaces that must remain untouched

These are the surfaces the 14 baseline patches already hook, plus the ones the
device evidence pointed at. **No new feature may target any of them.** The
regression test in §4.3 enforces this mechanically.

| # | Surface | Anchor at `a5bace0` | Why it is protected |
| --- | --- | --- | --- |
| 1 | **Authentication / key-hash trust** | `X.1eX.A01` / `X.1eY.A01`, fingerprint `"Invalid SHA256 key hash"` (the *Signature check bypass* patch, **default-off in our builds** via `build.sh -d`) | decides whether Instagram treats the APK as an official build; our APK is re-signed, so this gate is **already** a known, deliberate, opt-in exception — it must not be touched by any new work |
| 2 | **Network layer / request construction** | `com.instagram.api.tigon.TigonServiceLayer.startRequest*` (the *Network content blocking* patch) | carries auth headers, session tokens and request signing; a change here breaks every request |
| 3 | **Media parsing** | `com.instagram.feed.media.Media` parse + field dispatch + `unsafeParseFromJson` (the *Feed item filtering* patch) | the previous attempt's capture hook sat here and produced invalid dex; **out of bounds for all new work** |
| 4 | **Feed loading** | feed request builder (`"Request{mReason="`, `"pagination_source"`, `"FEED_REQUEST_SENT"`) (the *Limit feed to following* patch) | feed paging, retries and the login wall all hang off it |
| 5 | **Session initialisation / app start** | `com.instagram.mainactivity.InstagramMainActivity`, `com.instagram.android.activity.MainTabActivity` | bootstrap, session restore and the signed-out decision happen here |
| 6 | **Story viewer** | `instagram.features.stories.fragment.ReelViewerFragment` (the *Quoicouview mode* patch) | story rendering and the view receipt; the viewer must not be modified |
| 7 | **List adapter / row binding** | `IgRecyclerViewAdapter` + its row definitions (the *Quoicouview mode* patch) | **read-only observation is allowed here (Tier 2); no modification** |
| 8 | **Navigation / back** | `"NavigationController.onBackPress"` (the *Hidden Home in Back history* patch) | back-stack behaviour |
| 9 | **Toasts / popups** | `IgToastShow.show` (the *Popup hiding* patch) | the app's own toast surface |
| 10 | **Tab bar / main tab controller** | tab-bar binder, `"MainTabControllerImpl.onTabChanged"`, `"IgSwipeableTabHostFragment.onCreate"` | where the app installs its own entry points; additions must be *ours*, not Instagram's |
| 11 | **Story tray type getter** | `trayEntryTypeGetter` (the *Suggested accounts* patch) | story-tray filtering |
| 12 | **Notes tray binding** | `"notes_tray_bind_"` (the *Notes tray* patch) | notes row |

**Rule:** new patches may only *read* from #7 (the row binder's model parameter).
Everything else in this table is read-only for us, and the regression test
compares those methods' bytecode against the baseline APK.

---

## 4. Regression tests (defined before implementation)

### 4.1 Static (CI, no device)
| Test | Rule |
| --- | --- |
| `validate_features.sh` (new) | no new patch may reference the protected anchors in §3; the downloader core must not import `android.*`; the engine must not contain `CookieManager`, `getCookie`, `Authorization`, `PackageInstaller`, `WRITE_EXTERNAL_STORAGE` |
| `validate_features.sh` | the ghost hook must be wrapped in a fail-safe and must short-circuit on a single `volatile boolean` |
| `validate_repo.sh` | required files, executable modes, expected-patch manifest, patch count |
| patch inventory | the expected patch list grows **only** by explicitly named, default-off patches |

### 4.2 Unit (CI, plain JDK, no device)
* `test_downloader_logic.sh` — queue limits, concurrency, retry policy, duplicate
  detection, file naming, MIME/extension mapping, media-kind detection, carousel
  expansion, expired-story refusal, error taxonomy.
* `test_ghost_mode.sh` — master-switch semantics, per-feature gating, path
  matching (story seen, message seen, typing), *no* false positives on unrelated
  paths, unsupported features never active.

### 4.3 Dex-level (CI, the strongest new guard)
A new script, `scripts/ci/verify_protected_methods.py`, compares the **patched**
APK against the **baseline** APK (`debug-test-43091674732-1`, sha256
`6de31b8e…2df65`) method by method, for the protected surfaces of §3, and
fails the build if any of them differs:

* the media parser, its field dispatch and `unsafeParseFromJson`;
* the feed request builder and its header map;
* `TigonServiceLayer.startRequest` / `startRequestInternal` **except** for the
  explicitly allowed ghost-mode instruction;
* the key-hash trust checks;
* the story-viewer fragment's methods;
* `MainTabActivity` / `InstagramMainActivity` bootstrap methods.

This is the test that would have caught the previous regression, and it is
written **before** the first new patch exists.

### 4.4 Device (OPPO A38, Android 15 / API 35 / arm64-v8a)
See §6. A build is only called working when: launch with network on → login
screen → login completes → feed, stories and reels load → downloader actions work
→ ghost-mode page opens → no FeurStagram updater popup → all 14 baseline patches
still applied.

---

## 5. Milestones (each ends in a reviewable, revertable commit)

| # | Milestone | Exit criterion |
| --- | --- | --- |
| M0 | branch + plan + baseline APK archive | plan reviewed; baseline APK and its digest recorded |
| M1 | engine core (pure Java) + unit tests | `test_downloader_logic.sh` green; core has no Android imports |
| M2 | Android shell (MediaStore, queue, history, notifications, share target) | build green; share-target save works on device |
| M3 | Downloader page in the HMX settings (Tier 2, read-only binder observation) | device: the page lists the media that was viewed; **Instagram's UI is visually unchanged** |
| M4 | Tier 3 in-context actions, one surface per patch, default-off | device: feed button works; story action works; reels action works; carousel works |
| M5 | ghost mode, separate patch and preferences | device: the three implemented controls behave; the four unsupported ones show their reason and cannot be switched on |
| M6 | dex-level protected-method guard wired into the gated build | CI fails if a protected method changes |

Each milestone is a separate PR against `main`, built and published to the
private debug repository only after its gates pass.

---

## 6. Exact build and OPPO A38 test procedure

### 6.1 Build (cloud, no local high-memory build)
1. Merge the milestone PR into `main` (or open a PR and let CI run).
2. Dispatch **"APK build (gated)"** from `main` with:
   * `instagram_version = 454.0.0.54.77`
   * `base_apk_kind = apkm`
   * `allow_throwaway_signature = true`
   * `allow_signature_bypass = false` (unchanged default; see §7)
   * `publish_test_apk = true`
   * `run_install_test = false` (no arm64 device on the runner)
3. The run must end `success`; the private release appears as
   `debug-test-<run>-<attempt>` with the asset `hmx-insta-454.0.0.54.77-test.apk`.
4. Record the published digest:
   ```sh
   curl -sS -H "Authorization: Bearer $TOKEN" -H 'Accept: application/vnd.github+json' \
     https://api.github.com/repos/bharti1233/hmx-build-inputs/releases \
     | python3 -c "import json,sys;[print(a['name'],a['size'],a['digest']) for r in json.load(sys.stdin) for a in r.get('assets',[]) if 'hmx-insta' in a['name']]"
   ```

### 6.2 Device test (OPPO A38, Android 15 / API 35 / arm64-v8a)
```sh
# 0. identity of the build under test
adb shell pm path com.instagram.android
adb shell sha256sum "$(adb shell pm path com.instagram.android | sed 's/package://')"

# 1. clean state (a differently-signed APK cannot be installed over another one,
#    so the previous app is uninstalled and the session is gone anyway)
adb uninstall com.instagram.android || true
adb install -r <the downloaded hmx-insta-454.0.0.54.77-test.apk>

# 2. launch with the network on — the app must not sit on a spinner
adb shell svc wifi enable; adb shell svc data enable
adb logcat -c
adb shell am start -n com.instagram.android/com.instagram.android.activity.MainTabActivity
sleep 20
adb shell dumpsys activity activities | grep -i "mResumedActivity\|topResumedActivity"

# 3. login screen, then sign in
#    (credentials are entered by hand; never scripted, never logged)
#    after Login: the feed must load

# 4. surfaces
#    feed / stories / reels must scroll and load
#    long-press Home -> HMX Insta settings -> Downloader  (queue, history, per-item actions)
#    long-press Home -> HMX Insta settings -> HMX Ghost Mode (page opens, controls reflect state)

# 5. no upstream updater popup, no release polling
adb logcat -d | grep -iE "jean-voila|api.github.com/repos|Update available" | head

# 6. patch set actually applied
adb logcat -d | grep -iE "Applied:|patch(es) applied" | head -20
```
Pass criteria: the app reaches the feed after login; the downloader saves a photo
and a video (and extracts audio) into the gallery; the ghost-mode page opens and
its three implemented controls take effect; the four unsupported ones are
inert; Instagram's own UI is visually unchanged; no FeurStagram update popup
appears; the patch report lists the 14 baseline patches.

### 6.3 Log capture discipline
Redact before sharing: passwords, OTPs, cookies, `Authorization` headers,
`access_token`/`sessionid` values, and the account's own identifiers. The
capture script `scripts/ci/capture_device_logs.sh` already applies that
redaction for the filtered view; the raw logs stay on the device.

---

## 7. Open questions for the review (no action taken yet)

1. **The signature-check gate.** Our APK is re-signed, so Instagram's
   "is this APK signed by Meta" check fails; the upstream
   *Signature check bypass* patch exists for exactly that and is **disabled by
   default** in our builds. This is very likely why the app is signed out and
   why a login may not persist. It is an **authentication-adjacent** surface and
   therefore explicitly out of scope for the downloader/ghost work — but it
   needs a decision and a device test of its own before any further login work.
2. **Which baseline was device-verified.** The repo records device work on the
   pre-branding build; the branding build (`a5bace0`) has no device-test
   record. The plan treats `a5bace0` as the recovery point on the strength of
   it being the last published, known-good *source* state.
3. **Tier 3 necessity.** If Tier 1 (share target) plus Tier 2 (our own page)
   cover the use case, Tier 3's three in-context patches could be dropped
   entirely, removing most of the risk.
