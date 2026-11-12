# HMX Feature Implementation Report — Downloader and Ghost Mode

Branch: `feature/hmx-downloader-ghost-mode` (merged as `da04cbd`)
Fix: `fix/downloader-capture-npe` (merged as `68b8818`)
Base: `main` at `a5bace0` (HMX Insta branding, universal APK packaging)

---

## 1. What was found before the change

* **No downloader and no privacy feature existed** in HMX Insta. The extension
  shipped the content blockers, the settings, the Quoicouview mode and the
  debug bridge — and nothing else.
* **Instagram ships its own download code** (`common.api.base.Downloader`,
  `creation.reeldownload.ReelVideoDownloader`, `music.download.TrackDownloader`,
  the "save to camera roll" flows). It could not be pointed at an item from
  outside Instagram's own UI.
* **Two stable, already-verified anchors exist** in this project's patches and
  were reused rather than re-invented:
  * `com.instagram.api.tigon.TigonServiceLayer.startRequest` — where every
    outbound request is materialised (used by the content blocker);
  * Instagram's media parser (`com.instagram.feed.media.Media`, the parser
    that returns it) — used by the feed filter.
* The Instagram build's own strings confirmed the accessors and the receipt
  endpoints this work relies on (`getImageUrl`, `getMediaType`, `getHasAudio`,
  `getPk`, `getUser`, `getVersions`, `media/seen/`, `/direct_v2/…/items/…/seen/`,
  `/activity/`).

---

## 2. What was built

### The downloader

One engine, three entry points, all local:

* `HmxDownloader` — a bounded queue, two workers, progress, cancellation,
  retry for recoverable errors, duplicate detection, history.
* `MediaStoreWriter` — writes into `Pictures/HMXInsta` and `Movies/HMXInsta`
  with `IS_PENDING`, so no storage permission is needed and a half-written
  file never appears in the gallery.
* `AudioExtractor` — copies the audio track out of a downloaded video with the
  platform's own media stack.
* `FeedMediaAdapter`, `StoryMediaAdapter`, `ReelMediaAdapter` — what each
  surface can offer, including the "this story has expired" rule.
* `MediaResolver` — reads Instagram's media model through an allowlist of
  accessors that were verified in this build; it never invents a URL.
* `DownloaderInstaller` — puts the HMX button into Instagram's own action row
  and viewer controls, found at runtime by their content descriptions.
* `DownloadMenu`, `DownloadManagerScreen` — the contextual menu and the queue
  and history page in the HMX Insta settings.

### Ghost mode

* `GhostPolicy` — the master-switch rule and the request matching, in pure
  Java so it can be tested without a device.
* `GhostNetwork` — the request hook, on the same network anchor the content
  blocker uses.
* `GhostModeScreen` — the page, with a status per control.
* Three controls work client-side and are implemented: **Hide Story Seen**,
  **Hide Message Seen**, **Hide Typing Status**.
* Four controls are Instagram's own server-side settings and are shown as
  **not supported** and cannot be switched on: **Hide Online Status**,
  **Disable Activity Status**, **Anonymous Story Viewing**, **Hide Live View**.

### The new patches

| Patch | Anchor | Fails when |
| --- | --- | --- |
| **HMX downloader capture** | Instagram's media parser (the same fingerprint the feed filter uses) | the parser is not found, is never called, or hands back no media |
| **HMX ghost mode** | `TigonServiceLayer.startRequest` | the network anchor is not found |

Both are version-specific, strictly fingerprinted and fail closed: the build
fails rather than shipping a feature that silently does nothing.

---

## 3. Files

**New (extension)**

```
extensions/extension/src/main/java/com/feurstagram/extension/downloader/
    AudioExtractor.java  DownloadError.java  DownloadHistory.java
    DownloadManagerScreen.java  DownloadMenu.java  DownloadTask.java
    DownloaderInstaller.java  DuplicateDetector.java  FeedMediaAdapter.java
    HmxDownloader.java  MediaItem.java  MediaKind.java  MediaNaming.java
    MediaRegistry.java  MediaResolver.java  MediaSource.java
    MediaStoreWriter.java  Notifications.java  ReelMediaAdapter.java
    StoryMediaAdapter.java  Surface.java
extensions/extension/src/main/java/com/feurstagram/extension/ghost/
    GhostFeature.java  GhostMode.java  GhostModeScreen.java
    GhostNetwork.java  GhostPolicy.java
```

**New (patches)**

```
patches/src/main/kotlin/com/feurstagram/patches/downloader/DownloaderCapturePatch.kt
patches/src/main/kotlin/com/feurstagram/patches/ghost/GhostModePatch.kt
```

**New (tests and checks)**

```
scripts/ci/test_downloader_logic.sh   scripts/ci/test_ghost_mode.sh
scripts/ci/validate_features.sh       scripts/ci/tests/DownloaderLogicTest.java
scripts/ci/tests/GhostModeTest.java
```

**New (documents)**

```
docs/HMX_DOWNLOADER_ARCHITECTURE.md   docs/HMX_GHOST_MODE_ARCHITECTURE.md
docs/HMX_DOWNLOADER_TEST_REPORT.md     docs/HMX_GHOST_MODE_TEST_REPORT.md
docs/HMX_FEATURE_IMPLEMENTATION_REPORT.md
```

**Changed**

| File | Change |
| --- | --- |
| `extensions/.../Settings.java` | the Downloader and HMX Ghost Mode pages, the downloader installer hook, and the helpers the new packages use |
| `extensions/.../ExpressiveSwitch.java` | the class and its constructor are public, so the ghost-mode page can use the app's own switch |
| `.github/workflows/apk.yml` | the two new patches join the expected patch manifest; `MIN_APPLIED_PATCHES` 10 → 16 |
| `.github/workflows/ci.yml` | the two new test suites and the new static check |
| `scripts/ci/validate_repo.sh` | the new files, their executable modes and the new needles |
| `patches/build.gradle.kts` | the patch bundle is now "HMX Insta Patches" |

---

## 4. Tests

| Suite | Result |
| --- | --- |
| `scripts/ci/test_downloader_logic.sh` | **passed — 52 checks** |
| `scripts/ci/test_ghost_mode.sh` | **passed — 36 checks** |
| `scripts/ci/validate_features.sh` | **passed** |
| `scripts/ci/validate_repo.sh` | **passed** |
| `scripts/ci/validate_branding.sh` | **passed** |
| `scripts/ci/test_universal_packaging.sh` | **passed** |
| `scripts/ci/test_patch_report.sh` | **passed** |
| `scripts/ci/test_publish_gates.sh` | **passed** |
| `scripts/ci/test_cleanup_workflow.sh` | **passed** |

The Android-only parts of the new code (MediaStore, the notification, the
audio extraction, the button placement) compile in CI and run in the APK build,
but their **effect on a device** is still pending — see the two test reports.

---

## 5. The build that proved it

| | |
| --- | --- |
| Gated run | **43168216595** — https://github.com/bharti1233/Hmxgram/actions/runs/43168216595 (`main`, sha `68b8818`) — **success** |
| Patches applied | **16 of 16**, 0 failed: the 13 upstream patches, *HMX Insta app name*, *HMX downloader capture*, *HMX ghost mode* |
| Packaging | no split metadata, no Play split table or source stamp, 14,453 `res/` entries / 3,590 images, densities 160–640, `arm64-v8a`, readable resource table |
| Signing | `apksigner` v2/v3 verified, `zipalign -c -p 4` verified |
| Published | `hmx-insta-454.0.0.54.77-test.apk`, 172,576,047 bytes, `sha256:af0721c2e53aa2f4bc0e73244aa7c04d941a3cb62e2cd7e692074ad6409368b5`, private prerelease `debug-test-43168216595-1` |

Checked in the **published** bytes, not just in the build log: the extension's
new code is inside the APK (`Updates are published by HMXPanel`, `HMX Download`,
`MediaRegistry`, `GhostNetwork`), and the upstream updater is gone —
`jean-voila`, `api.github.com/repos` and `FeurStagram is up to date` all return
zero hits.

The first attempt at this build failed closed at the capture patch — Morphe's
`instructions` extension throws on a method without an implementation — and was
fixed in PR #10. Nothing was published before the fix.

---

## 6. What was preserved

* The 14 existing patches (13 upstream + *HMX Insta app name*), unchanged.
* Instagram `454.0.0.54.77` / versionCode `386014712`.
* The APKEditor V1.4.9 universal packaging with `-clean-meta`.
* The bundle, standalone and patch-report gates.
* The signing verification and the private debug distribution.
* The CI security gates, the environment secrets and the main-only policy.
* The GPL-3.0 attribution and the upstream identifiers.

---

## 7. Remaining blockers

| Blocker | Detail |
| --- | --- |
| No device test | the download button's placement, the gallery write, the audio extraction and the ghost-mode effect on a second account are all pending an install on the OPPO A38 |
| Post attribution | the download button resolves the media Instagram parsed most recently; on a fast scroll that can be the previous post. The menu shows exactly what will be downloaded, and the user can cancel before it starts. |
| Media URLs expire | Instagram's CDN links are signed and short-lived; an expired link fails with a clear message instead of downloading the wrong file. |
| Four ghost-mode controls | Instagram decides them on its servers; they are shown as not supported rather than faked. |

---

## 8. Next steps

1. Install the published private debug APK on the OPPO A38 and check the
   downloader and ghost mode against the two test reports' pending rows.
2. If the button does not land where it should, adjust the runtime discovery
   in `DownloaderInstaller` — no Instagram class names are involved, so it needs
   no rebuild of Instagram's internals.
3. Only then consider a release-signed build.
