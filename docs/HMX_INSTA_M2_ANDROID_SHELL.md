# HMX Insta — M2 Android shell

**Status:** implemented on `feature/hmx-m2-android-shell`, stacked on
`feature/hmx-downloader-ghost-mode-v2` after the M1 integration commit
`ba72c21d0c1aafc6aa4ec48283f9a75be26ca9d0`. M2 is not merged into `main`, and no
APK was built, signed or dispatched for this milestone.

**Milestone exit criterion** (from `docs/HMX_INSTA_NEXT_PHASE_PLAN.md` §5):
the patch bundle compiles with the new share-target patch, the plain-JDK core
tests pass, and the Android shell is ready for the documented device matrix.

---

## 1. Scope

M2 wraps the M1 pure-Java engine in the minimum Android shell needed by the
Tier 1 system share target:

| In scope | Out of scope (later milestones) |
| --- | --- |
| share target (`ACTION_SEND` / `ACTION_SEND_MULTIPLE`, images, videos and audio) | downloader settings page (M3) |
| MediaStore writer with `IS_PENDING` and full cancellation coverage | in-context feed/story/reel buttons (M4) |
| bounded background download worker | ghost mode (M5) |
| foreground download service with process-death recovery | dex-level protected-method gate (M6) |
| direct audio shares; audio extraction from MP4-family video containers only | APK signing/publishing |
| preferences-backed history, duplicate detection and a pending-work ledger | Instagram UI, login, signup, auth, session or feed changes |

The Instagram integration remains disconnected: no patch reads a media model,
no extension class references an Instagram internal, and the M0 protected
surfaces are untouched.

---

## 2. Core changes

The M1 core stays network-free and Android-free. M2 adds the share-item rules
plus the pure decision logic the audit fixes needed, so every rule is testable
without an emulator:

| Change | Why |
| --- | --- |
| `MediaItem` accepts `content://` in addition to HTTP(S) | a share arrives as a user-selected content URI; `file://`, `javascript:` and every other scheme are still rejected |
| `DownloadTask.restore(...)` (package-visible) | rehydrates terminal tasks from persisted history without resuming active work |
| `DownloadHistoryCodec` (new) | versioned, deterministic, bounded text encoding of at most 100 terminal tasks; strips URL query/fragment credentials; rejects corrupt data |
| `PendingWorkCodec` (new) | same format family for the accepted-but-unfinished ledger (100 entries cap, exact-id duplicate rejection, corrupt input rejected) |
| `SharedStreams` (new) | merges `EXTRA_STREAM` and `ClipData` URIs in order, collapses duplicates, caps at 10 items, never double-downloads an item present in both sources |
| `AudioSourcePolicy` (new) | routes an audio task: `audio/*` saves directly; extraction only from `video/mp4`, `video/3gpp`, `video/x-m4v`, `video/quicktime`; every other container is an honest `unsupported` failure |
| `CancellationSignal` + `DownloadCancelledException` (new) | one cancellation object per running task, checked at every lifecycle checkpoint |
| `GuardedCopy` (new) | bounded copy loop that honours the signal per buffer and rolls the destination back exactly once on cancel or I/O failure |
| `NotificationPermissionPolicy` (new) | Android 13 `POST_NOTIFICATIONS` decision: ask once per install, never re-prompt after a denial, save regardless |
| `CacheRetentionPolicy` (new) | selects cache evictions: nothing referenced by an active task, nothing inside the grace window, then oldest-first until the byte and file-count budgets hold |

All of the above are plain JDK classes tested by
`scripts/ci/test_downloader_logic.sh` (33 test groups).

---

## 3. Android shell

All classes live under
`extensions/extension/src/main/java/com/feurstagram/extension/downloader/android/`
and use only platform APIs (no third-party dependency, no AndroidX addition).

| Class | Responsibility |
| --- | --- |
| `HmxDownloader` | process-wide queue, fixed worker pool, history seeding, dispatch, cancel/retry, pending-work ledger, cache sweeps, idle callback |
| `DownloadService` | `dataSync` foreground service that keeps the queue alive while work exists and stops itself when idle; no work of its own |
| `PendingWorkStore` | durable ledger of accepted-but-unfinished items, replayed on the next start |
| `ShareCacheJanitor` | bounded cleanup of stale share copies and interrupted temp files; never touches a file an active task references |
| `DownloadWorker` | HTTP(S) or private `content://` source to a cache file, optional policy-driven extraction, then publish; cancellation-aware at every checkpoint |
| `MediaStoreWriter` | `IS_PENDING` insert, copy, publish; deletes the pending row on any failure, and can delete a published row on a late cancellation |
| `AudioExtractor` | `MediaExtractor`/`MediaMuxer` audio copy without re-encoding; an empty mux output is reported as failure, not published |
| `PreferencesDownloadRepository` | `DownloadRepository` over `hmx_download_history` preferences |
| `DownloadNotifier` | low-importance progress and completion/failure notifications; also builds the foreground-service notification |
| `ShareTargetActivity` | invisible `ACTION_SEND` entry point; one notification prompt per install, copies, enqueues, finishes |
| `ShareIntentParser` | action, scheme, MIME, count and size validation; `EXTRA_STREAM` plus `ClipData` merge; accepts `audio/*` |
| `ShareImporter` | immediate cache copy and `share-<sha256>` stable id; maps `audio/*` to `DownloadKind.AUDIO` |
| `ShareCacheProvider` | private, read-only bridge from the cached share to the worker |

### Download path

1. The share activity validates every stream (merging `EXTRA_STREAM` with
   `ClipData` so a relay that drops the extra cannot lose the item) and copies
   it to `cache/hmx-share` while the sender's grant is still alive.
2. On Android 13+ the activity asks for `POST_NOTIFICATIONS` at most once per
   install. A denial is remembered and respected; the save continues either
   way.
3. Each copy becomes a `MediaItem` whose id is `share-<sha256-of-bytes>` and
   whose URL is a private `content://...hmxshare/...` URI.
4. The activity enqueues the item and finishes; duplicates are detected by the
   stable id, so sharing the same item twice does not write twice. The item is
   written to the pending-work ledger before dispatch.
5. A worker reads the private URI or an HTTP(S) URL into a cache file. No
   cookie, authorization header, Instagram host or session is read. The
   task's cancellation signal is checked before the download, between the
   download and the audio step, on every buffer, before the MediaStore insert
   and before the row is published.
6. `MediaStoreWriter` inserts into `Pictures/HMXInsta`, `Movies/HMXInsta` or
   `Music/HMXInsta`, copies the bytes and clears `IS_PENDING`. Any failure —
   including a cancellation inside the publish — deletes the pending row; a
   cancellation that lands after publication deletes the published row.
7. The terminal task is saved to preferences and removed from the ledger; the
   share cache is deleted on success or cancellation. A failed task keeps its
   cache copy for a manual retry, bounded later by the janitor.
8. Notifications show aggregate progress and a final saved/failed count. If
   notifications are denied, the save still completes.

### Persistence and process death

`DownloadService` is a declared `dataSync` foreground service, started from
the visible share activity (never from the background). While the queue has
work the process holds a foreground notification; when the queue drains the
service removes the notification and stops itself. `START_STICKY` lets the
system restart it after a kill, and the pending-work ledger is replayed on
the next start regardless of whether the restart happens, so a killed process
never silently loses a queued download.

Known limits, stated plainly: aggressive ColorOS battery modes can delay or
refuse the service restart, and Android 15 caps `dataSync` foreground
services at six hours per day. In both cases the download resumes the next
time the app opens; nothing is lost, but it may not finish in the
background.

### Audio extraction

Audio is handled by `AudioSourcePolicy`, not by a blanket promise:

* an `audio/*` share is saved directly as audio (`.m4a` fallback naming when
  the MIME is generic);
* an `AUDIO` task whose source is `video/mp4`, `video/3gpp`, `video/x-m4v` or
  `video/quicktime` is passed through `AudioExtractor` (first audio track,
  remuxed without re-encoding). A video with no audio track, or a muxer that
  produced zero samples, fails with `unsupported` — no silent file is
  published;
* every other container (`video/webm`, `video/x-matroska`, `video/avi`, …)
  fails with `unsupported` up front. The milestone does not claim extraction
  for containers the platform muxer cannot write, and no claim is made that
  extraction works until the device checklist row has evidence.

The share-target photo/video path never depends on extraction. Both
temporary files are deleted in `finally`, and the janitor protects an active
task's extraction output.

---

## 4. Share-target patch

`patches/.../downloader/DownloaderShareTargetPatch.kt` is a new
**default-off** resource patch. It adds:

* `ShareTargetActivity`, exported only for `SEND`/`SEND_MULTIPLE` with
  `image/*`, `video/*` and `audio/*`;
* `ShareCacheProvider`, private and not exported;
* `DownloadService`, not exported, with `foregroundServiceType="dataSync"`;
* `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE` and
  `FOREGROUND_SERVICE_DATA_SYNC` (no storage permission).

The provider authority is `com.instagram.android.hmxshare`; the existing Clone
patch rewrites that prefix for side-by-side builds, and the provider computes
the same authority from `Context.getPackageName()`.

The patch is intentionally not enabled by default: it exposes one exported
component, so it gets its own review. `validate_features.sh` lists it explicitly
in the patch inventory and fails closed if it appears without review.

---

## 5. Gates and tests

| Gate | What it proves |
| --- | --- |
| `scripts/ci/test_downloader_logic.sh` | 33 plain-JDK test groups: `content://` scheme rules, history and pending-ledger round trips, corruption and bounds, stream merging, audio routing, cancellation, guarded copy rollback, cache eviction selection |
| `scripts/ci/validate_features.sh` | core remains JDK-only; Android shell has no Instagram/auth/storage symbol; share patch is default-off and names the service and foreground-service permissions; inventory is exact |
| `scripts/ci/test_downloader_isolation.sh` | protected digest and exact diff allowlist against `ba72c21` |
| `scripts/ci/test_protected_methods.sh` | 12 protected surfaces and baseline APK pin intact |
| `scripts/ci/validate_repo.sh` | required files, executable modes, pinned actions, no credentials/artifacts |
| CI bundle job | patch bundle compiles with the new patch (no APK job added) |

The M2 baseline digest deliberately includes the new share-target patch and
excludes the downloader tree, which is the reviewed feature boundary. The
digest was re-pinned once for the audit-fix edits to the share-target patch
(service element, foreground-service permissions, audio MIME), with the delta
attributed to that file alone; any later change to a protected M0 file or to
the share-target patch fails the digest again.

Instrumentation tests are not part of this milestone: the extension module
has no `androidTest` infrastructure and CI has no emulator, so the Android
behaviour (permission prompt, foreground service, MediaStore rollback) is
covered by device checks, not automated tests. Every device row is marked
NOT TESTED until run.

---

## 6. Device checklist (OPPO A38)

**Device status: NOT TESTED.** No physical device was available for M2 review,
so none of the checks below has evidence yet. The expanded Android 15 /
ColorOS 15 procedure, the ADB commands and the static findings that block
promotion are in [HMX_INSTA_M2_DEVICE_CHECKLIST.md](HMX_INSTA_M2_DEVICE_CHECKLIST.md).

1. Share a feed photo from Instagram to HMX Insta → saved in
   `Pictures/HMXInsta`, notification shown, history entry present.
2. Share a reel/video → saved in `Movies/HMXInsta`.
3. Share an audio item (for example a voice note) → saved in `Music/HMXInsta`.
4. Share the same item twice → second attempt reports duplicate, no second
   file.
5. Cancel a large video mid-download → no gallery entry, temp file gone.
6. Deny the notification prompt once → the save still completes and the app
   never asks again (until a reinstall).
7. Force storage-full/read-only failure → `storage` error, no pending row left.
8. Let the system kill the app mid-queue → on the next open, the queued items
   resume (device check for the pending-work replay).
9. Restart the app → history reloads and duplicate detection still works.
10. Verify Instagram login/signup/feed/session still behave exactly as before.

The device matrix is the only part of M2 that cannot run in CI. It must pass
before the draft PR leaves draft state and before any gated APK build.

---

## 7. What did not change

* `main` was not modified.
* No login, signup, authentication, session, feed or protected Instagram method
  was touched.
* No existing M0 patch source was modified; the new share-target patch is
  additive, default-off, and was revised in place only by the M2 audit fixes.
* No broad storage permission, `PackageInstaller`, upload or Instagram host was
  added; the only new permissions are the notification permission and the two
  foreground-service permissions the download service requires.
* The protected-method baseline APK pin and 12 surfaces are unchanged.

---

## 8. Out of scope

Tier 2 row-binder observation and the downloader settings page (M3), in-context
buttons on feed/story/reels (M4), ghost mode (M5), the dex-level protected
method gate (M6), and APK signing/publishing all remain later milestones.
