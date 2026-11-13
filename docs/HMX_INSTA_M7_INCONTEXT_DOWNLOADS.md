# HMX Insta — M7 in-context download actions

**Status:** implemented on `feature/hmx-m7-incontext-downloads`, stacked on the
M6 branch (`feature/hmx-m6-consolidation`, PR #24). Not merged. No APK has been
built for this milestone yet and **every device row is NOT TESTED**.

**Prerequisite:** M6 (one launcher icon, the downloader and the privacy screen
inside the HMX settings). M7 reuses the M4 read-only observation and the M1–M3
download engine; it adds no second media source and no second download path.

---

## 1. Scope

| In scope | Not in scope |
| --- | --- |
| a download action on supported feed photos, videos and carousels | a button injected into each post's own view group |
| the same action on reels and on supported story media | rendering a thumbnail or scraping a row's text |
| a confirmation sheet naming the media before anything is queued | audio extraction or cover download from this sheet |
| progress, completion, duplicate and failure reporting through the existing engine | changing Instagram's row binder, request paths or auth |

### 1.1 The integration point that was chosen, and the one that was not

Instagram's row binder (`IgRecyclerViewAdapter`'s per-row bind, the protected
`list.row_binding` surface) would be the only way to attach a button to one
specific post. Attaching a button there means modifying the protected method
"merely to insert buttons", which this milestone forbids. The binder is
therefore **unchanged**. No Instagram view inside a post is touched, no view
hierarchy is parsed for media and no request is intercepted.

Instead M7 adds an **HMX-owned chip** to Instagram's own window:

* it is added at runtime to the window's `FrameLayout` root, not to a system
  overlay and not to Instagram's list; no overlay permission is used;
* it is installed by a default-off patch from the extension's existing
  `Settings.installHomeTabWatcher(ViewGroup)` call, so a build without the
  patch never reaches the class;
* it is driven by the **existing** `MediaObserver` (M4) and the existing
  `HmxDownloader` queue (M1–M3);
* it appears only while a fresh observation exists **and** a supported surface
  anchor is present in the window: `like_button` for feed,
  `clips_ufi_like_button` / `clips_viewer_action_bar` for reels and
  `reel_viewer_message_composer` / `reel_viewer_header` for stories;
* it is draggable and remembers its position, so it does not have to cover a
  fixed Instagram control;
* tapping it opens a confirmation sheet; nothing is downloaded on a single tap.

That is the honest boundary: the action is in context (it lives on the feed,
reels and story screens and acts on the media Instagram just parsed) but it is
not a per-row button, because a per-row button is not reachable without changing
a protected method.

## 2. What was implemented

### 2.1 Core policy (plain Java, unit-tested)

`extensions/.../downloader/core/InContextAction.java`

| Rule | Behaviour |
| --- | --- |
| freshness | an observation older than 20 s is not offered; a preloaded or stale row cannot turn into a download of the wrong post |
| surfaces | feed, reels and stories are the only recognised surfaces; an unknown screen offers nothing |
| carousel | the container is expanded into its downloadable children; a child with no `http(s)` URL is skipped and counted |
| wording | the sheet names kind, owner (when known), audio for videos, carousel item count and observation age |
| stories | the sheet states that a story can only be saved while Instagram still has it loaded, and that private or expired stories cannot be downloaded |
| result | added, duplicate and refused counts are reported honestly |

### 2.2 Observer additions

`MediaObserver` now exposes `latest()`, `latestAgeMs()`, a main-thread
`Listener`, and an observation timestamp. It also contains a re-entrancy guard:
reading a model invokes the same accessor the Instagram hook is installed on, so
without the guard every read would enqueue the model again forever. The guard is
per-thread; Instagram's own calls are unaffected.

### 2.3 The chip and the sheet

* `InContextDownloadOverlay` — the draggable chip, surface detection by resource
  name, freshness refresh once a second, install/teardown with the window.
* `InContextDownloadSheet` — a card built from the existing HMX settings
  components, with Cancel and Download. Download queues the confirmed items
  through `HmxDownloader.enqueue`, which supplies duplicate detection, the
  persisted pending ledger, the foreground service, notifications and the
  verified MediaStore publish.

### 2.4 The patch and the build switch

* `patches/.../downloader/InContextDownloadPatch.kt` — default-off. It adds one
  `invoke-static` at the start of the extension's `installHomeTabWatcher`; it
  names no Instagram class and never touches the row binder.
* `build.sh --incontext` enables it and implies `--downloader`.
* `.github/workflows/apk.yml` has `enable_incontext`, expects the patch in the
  patch report and raises the applied-patch minimum by one.

## 3. What this milestone does **not** do

* No per-row download button. This is blocked by the protected row-binder rule,
  and no Instagram method was modified to fake one.
* No mapping between an observation and the exact row under the chip. The
  confirmation sheet is the safeguard; the user is told what is about to be
  saved.
* No thumbnail in the sheet. Fetching one would add a second network path.
* No audio-extraction or cover action from the chip. Videos are saved with
  their original audio track; a muted original stays muted.
* Unsupported stories are not claimed: only media Instagram parsed and exposed
  with an `http(s)` URL can be offered. Private, expired or otherwise
  unavailable stories are refused with the engine's error, not an invented
  success.

## 4. Verification

Offline gates (all must pass before a gated build is requested):

* `scripts/ci/test_downloader_logic.sh` — pure-JDK compile with `--release 11`,
  `-Xlint:all -Werror`, all core and M7 policy tests.
* `scripts/ci/validate_features.sh` — new patch is default-off, hook-only,
  inventory and component complete, no launcher entry, no Instagram class.
* `scripts/ci/test_downloader_isolation.sh` — protected paths and pinned digest.
* `scripts/ci/validate_repo.sh`, `test_published_gates`, `test_patch_report`,
  `test_protected_methods` — unchanged policy gates.
* The gated GitHub Actions build applies the patch, verifies the patch report
  lists `In-context download actions`, checks the protected-method baseline and
  publishes only after the human `apk-build` approval.

Device verification is **NOT TESTED**. Each of the following stays NOT TESTED
until real evidence exists:

| Device row | Status |
| --- | --- |
| chip appears on a feed post and saves the visible photo | NOT TESTED |
| chip saves a visible feed video with its audio | NOT TESTED |
| chip saves every usable child of a visible carousel | NOT TESTED |
| chip appears on a reel and saves it | NOT TESTED |
| chip appears on a supported story and saves it | NOT TESTED |
| duplicate tap does not save a second copy | NOT TESTED |
| a stale observation is not offered | NOT TESTED |
| progress/completion notification and saved file verified | NOT TESTED |
| login, feed scrolling, reels playback, stories and profile navigation unchanged | NOT TESTED |
