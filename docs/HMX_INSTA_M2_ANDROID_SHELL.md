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
| share target (`ACTION_SEND` / `ACTION_SEND_MULTIPLE`) | downloader settings page (M3) |
| MediaStore writer with `IS_PENDING` | in-context feed/story/reel buttons (M4) |
| bounded background download worker | ghost mode (M5) |
| audio extraction for a video source | dex-level protected-method gate (M6) |
| preferences-backed history and duplicate detection | APK signing/publishing |
| progress and result notifications | Instagram UI, login, signup, auth, session or feed changes |

The Instagram integration remains disconnected: no patch reads a media model,
no extension class references an Instagram internal, and the M0 protected
surfaces are untouched.

---

## 2. Core changes

The M1 core stays network-free and Android-free. M2 adds only two deliberate
pieces:

| Change | Why |
| --- | --- |
| `MediaItem` accepts `content://` in addition to HTTP(S) | a share arrives as a user-selected content URI; `file://`, `javascript:` and every other scheme are still rejected |
| `DownloadTask.restore(...)` (package-visible) | rehydrates terminal tasks from persisted history without resuming active work |
| `DownloadHistoryCodec` (new) | versioned, deterministic, bounded text encoding of at most 100 terminal tasks; strips URL query/fragment credentials; rejects corrupt data |

`DownloadHistoryCodec` is tested with the same plain-JDK runner as M1. The
codec never writes a URL query or fragment, so signed CDN tokens do not reach
SharedPreferences.

---

## 3. Android shell

All classes live under
`extensions/extension/src/main/java/com/feurstagram/extension/downloader/android/`
and use only platform APIs (no third-party dependency, no AndroidX addition).

| Class | Responsibility |
| --- | --- |
| `HmxDownloader` | process-wide queue, fixed worker pool, history seeding, dispatch, cancel/retry |
| `DownloadWorker` | HTTP(S) or private `content://` source to a cache file, then publish |
| `MediaStoreWriter` | `IS_PENDING` insert, copy, publish, delete the pending row on failure |
| `AudioExtractor` | `MediaExtractor`/`MediaMuxer` audio copy without re-encoding |
| `PreferencesDownloadRepository` | `DownloadRepository` over `hmx_download_history` preferences |
| `DownloadNotifier` | low-importance progress and completion/failure notifications |
| `ShareTargetActivity` | invisible `ACTION_SEND` entry point; copies, enqueues, finishes |
| `ShareIntentParser` | action, scheme, MIME, count and size validation |
| `ShareImporter` | immediate cache copy and `share-<sha256>` stable id |
| `ShareCacheProvider` | private, read-only bridge from the cached share to the worker |

### Download path

1. The share activity validates every stream and copies it to
   `cache/hmx-share` while the sender's grant is still alive.
2. Each copy becomes a `MediaItem` whose id is `share-<sha256-of-bytes>` and
   whose URL is a private `content://...hmxshare/...` URI.
3. The activity enqueues the item and finishes; duplicates are detected by the
   stable id, so sharing the same item twice does not write twice.
4. A worker reads the private URI or an HTTP(S) URL into a cache file. No
   cookie, authorization header, Instagram host or session is read.
5. `MediaStoreWriter` inserts into `Pictures/HMXInsta`, `Movies/HMXInsta` or
   `Music/HMXInsta`, copies the bytes and clears `IS_PENDING`.
6. The terminal task is saved to preferences; the share cache is deleted on
   success or cancellation. A failed task keeps its cache copy for a manual
   retry.
7. Notifications show aggregate progress and a final saved/failed count. If
   notifications are denied, the save still completes.

### Audio extraction

`DownloadKind.AUDIO` tasks whose source is still a video are passed through
`AudioExtractor`. A video without an audio track fails with `unsupported`; the
share-target photo/video path never depends on extraction. Both temporary files
are deleted in `finally`.

---

## 4. Share-target patch

`patches/.../downloader/DownloaderShareTargetPatch.kt` is a new
**default-off** resource patch. It adds:

* `ShareTargetActivity`, exported only for `SEND`/`SEND_MULTIPLE` with
  `image/*` and `video/*`;
* `ShareCacheProvider`, private and not exported;
* `POST_NOTIFICATIONS` for Android 13+ (no storage permission).

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
| `scripts/ci/test_downloader_logic.sh` | 25 plain-JDK test groups, including `content://` scheme rules, history round trips, corruption and bounds |
| `scripts/ci/validate_features.sh` | core remains JDK-only; Android shell has no Instagram/auth/storage symbol; share patch is default-off; inventory is exact |
| `scripts/ci/test_downloader_isolation.sh` | protected digest and exact diff allowlist against `ba72c21` |
| `scripts/ci/test_protected_methods.sh` | 12 protected surfaces and baseline APK pin intact |
| `scripts/ci/validate_repo.sh` | required files, executable modes, pinned actions, no credentials/artifacts |
| CI bundle job | patch bundle compiles with the new patch (no APK job added) |

The M2 baseline digest deliberately includes the new share-target patch and
excludes the downloader tree, which is the reviewed feature boundary. Any later
change to a protected M0 file or to the share-target patch fails the digest.

---

## 6. Device checklist (OPPO A38)

1. Share a feed photo from Instagram to HMX Insta → saved in
   `Pictures/HMXInsta`, notification shown, history entry present.
2. Share a reel/video → saved in `Movies/HMXInsta`.
3. Share the same item twice → second attempt reports duplicate, no second
   file.
4. Cancel a large video mid-download → no gallery entry, temp file gone.
5. Deny notifications → save still completes.
6. Force storage-full/read-only failure → `storage` error, no pending row left.
7. Restart the app → history reloads and duplicate detection still works.
8. Verify Instagram login/signup/feed/session still behave exactly as before.

The device matrix is the only part of M2 that cannot run in CI. It must pass
before the draft PR leaves draft state and before any gated APK build.

---

## 7. What did not change

* `main` was not modified.
* No login, signup, authentication, session, feed or protected Instagram method
  was touched.
* No existing M0 patch source was modified; the new share-target patch is
  additive and default-off.
* No broad storage permission, `PackageInstaller`, upload or Instagram host was
  added.
* The protected-method baseline APK pin and 12 surfaces are unchanged.

---

## 8. Out of scope

Tier 2 row-binder observation and the downloader settings page (M3), in-context
buttons on feed/story/reels (M4), ghost mode (M5), the dex-level protected
method gate (M6), and APK signing/publishing all remain later milestones.
