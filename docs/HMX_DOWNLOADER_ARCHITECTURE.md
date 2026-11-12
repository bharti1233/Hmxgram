# HMX Downloader — Architecture

The HMX downloader saves the media Instagram already shows the user — feed
posts, stories and reels — straight into the device's gallery, without leaving
the app and without sending anything anywhere.

---

## 1. What already existed

The audit of Instagram 454.0.0.54.77 (the version this project builds on)
found that Instagram ships its own download machinery:

| Instagram's own code | What it does |
| --- | --- |
| `com.instagram.common.api.base.Downloader` | the base class of the app's media downloader |
| `com.instagram.creation.reeldownload.ReelVideoDownloader` | downloads a reel's video for export |
| `com.instagram.music.download.TrackDownloader` | downloads an audio track |
| `com.instagram.genai.imageservice.download.GenAIImageDownloadUtil` | saves generated images |
| `#instagram_clips_save_to_camera_roll`, `#IG_ANDROID_BASEL_VIDEO_GALLERY_SAVE`, `save_media_permission_failure_toast` | the "save to camera roll" flows and their permission handling |

**HMX Insta itself had no downloader** before this change: the extension
shipped no download code at all, and Instagram's own save flows are limited to
what its UI exposes (collections, camera roll for some surfaces) and cannot be
pointed at a specific item from outside.

The HMX downloader therefore does not re-implement Instagram's downloader: it
reads the media model Instagram already parsed, takes the URL the app itself
uses, and writes the bytes into the gallery with MediaStore.

---

## 2. Architecture

```text
Instagram parses a media model
        |  (HMX downloader capture patch, anchored on Instagram's media parser)
        v
MediaRegistry  ── keeps the last 24 models, newest first
        |
        v
Surface  ── decides which adapter applies (reels viewer vs. everything else)
        |
        v
MediaSource adapters
   FeedMediaAdapter   photo | video (+audio, +cover) | carousel children
   StoryMediaAdapter  the same, plus "this story has expired"
   ReelMediaAdapter   video (+audio) + cover
        |
        v
MediaResolver  ── reads the model: getImageUrl(), getVideoUrl(), getMediaType(),
        |            getHasAudio(), getOriginalWidth/Height(), getPk(), getUser()
        v
MediaItem.Offer  ── what can be downloaded, and why something cannot
        |
        v
DownloadMenu  ── the contextual menu (Download Photo / All Photos / Video /
        |            Audio / Cover)
        v
HmxDownloader  ── queue, workers, progress, cancel, retry, duplicates, history
        |
        v
MediaStoreWriter  ── writes into Pictures/HMXInsta or Movies/HMXInsta
AudioExtractor    ── copies the audio track out of a downloaded video
```

### Files

| File | Role |
| --- | --- |
| `downloader/MediaKind.java` | Instagram's media types (1 image, 2 video, 8 carousel) |
| `downloader/MediaItem.java` | one downloadable item and the offer it belongs to |
| `downloader/MediaNaming.java` | safe file names, MIME types, duplicate keys |
| `downloader/MediaResolver.java` | reads the media model (allowlisted accessors only) |
| `downloader/MediaSource.java` | the adapter contract |
| `downloader/FeedMediaAdapter.java` | feed posts, including carousels |
| `downloader/StoryMediaAdapter.java` | stories, with the expiry rule |
| `downloader/ReelMediaAdapter.java` | reels: video, audio, cover |
| `downloader/MediaRegistry.java` | the media Instagram has loaded |
| `downloader/HmxDownloader.java` | the engine: queue, workers, retry, duplicates |
| `downloader/MediaStoreWriter.java` | writes into the shared media collection |
| `downloader/AudioExtractor.java` | extracts the audio track with MediaExtractor/MediaMuxer |
| `downloader/DownloadHistory.java` | the history, in the app's own preferences |
| `downloader/Notifications.java` | the ongoing and the finished notification |
| `downloader/DownloadMenu.java` | the contextual menu |
| `downloader/DownloadManagerScreen.java` | the queue and history page in the settings |
| `downloader/DownloaderInstaller.java` | puts the HMX button into Instagram's own views |
| `downloader/Surface.java` | which adapter applies on which screen |
| `patches/downloader/DownloaderCapturePatch.kt` | hands the parsed media to the registry |

---

## 3. The media source

Instagram's media models are obfuscated, but a handful of accessors keep stable
names. Each one was verified to exist in Instagram 454.0.0.54.77:

| Accessor | Used for | Verified in the build |
| --- | --- | --- |
| `getImageUrl()` | the still image, and a video's or a reel's cover | yes |
| `getMediaType()` | 1 image, 2 video, 8 carousel | yes |
| `getHasAudio()` | whether a video has an audio track | yes |
| `getOriginalWidth()`, `getOriginalHeight()` | the file name and the card | yes |
| `getPk()` | the media id: the file name and the duplicate key | yes |
| `getUser()` → `getUsername()` | the owner in the file name | yes |
| `getVersions()` | the version list, whose entries have `getUrl()` and `getType()` | yes |

The video URL is not exposed under a stable name, so it is taken from the
version list: the entry whose URL is an `.mp4`. If a model exposes none of
these, the adapter returns an **empty offer with a reason** — the downloader
never invents a URL and never offers a link it cannot fetch.

The lookup is allowlisted: only the names above are ever called, and only
public members are read.

---

## 4. Downloading

* **Queue**: at most 20 waiting downloads; two run at a time.
* **Progress**: the bytes read are reported to the task and to the notification.
* **Cancellation**: a running download stops at the next chunk; the temporary
  file is deleted.
* **Retry**: a failed download can be retried while the error is recoverable
  (network or server); an expired link or a storage error is not retried.
* **Duplicates**: the media id (or, without one, the URL without its query) is
  remembered, so the same media is not saved twice. The user is told instead.
* **History**: the last 100 entries with their state, in the app's own
  preferences file (`hmx_download_history`), never uploaded.
* **Gallery**: MediaStore, `Pictures/HMXInsta` and `Movies/HMXInsta`, published
  with `IS_PENDING` so a half-written file never appears in the gallery.
* **Audio**: the audio track is copied out of the downloaded video with the
  platform's own `MediaExtractor`/`MediaMuxer` — no re-encoding, no tool.
* **Android 15**: MediaStore inserts need no storage permission from Android 10
  onwards, and the app asks for none.
* **Temporary files**: the downloaded file lives in the app's cache directory
  and is deleted as soon as it is written to the gallery.

---

## 5. The entry points

`DownloaderInstaller` watches the screens and adds the HMX button to
Instagram's own views: in the feed it goes into the post's action row, after
Instagram's share button and before its save button. The views are recognised
at runtime by their content descriptions, so no Instagram class or view id has
to be named — nothing breaks when Instagram renames them.

Tapping the button opens the contextual menu for the media that is on screen:

* photo post → **Download Photo**
* carousel → **Download All Photos** (one entry per item)
* video post → **Download Video**, **Download Audio** (when the video has an
  audio track), **Download Cover**
* story → the same, and an expired story offers nothing and says why
* reel → **Download Video**, **Download Audio**, **Download Cover**

---

## 6. What it will not do

* No password, cookie, token or session material is read or sent.
* No third-party download service is used; the bytes come from the CDN URL the
  app itself uses and stay on the device.
* Nothing is downloaded without the user tapping a download action.
* Nothing is installed: the downloader writes to the gallery, and Android's
  installer is never involved.
* No broad storage permission is requested.
* Only media the signed-in account can already see is offered: the downloader
  works on the media the app itself parsed, so a private post the account may
  not view is never in the registry at all. Access controls are not bypassed,
  and protected or DRM media is not touched.
* The downloader never uploads the user's media anywhere.

---

## 7. Known limitations

| Limitation | Detail |
| --- | --- |
| Attribution to the tapped post | the button resolves the media Instagram parsed most recently. On a fast scroll that can be the previous post; the menu shows exactly what will be downloaded, and the user can cancel before it starts. |
| Expired links | Instagram's CDN links are signed and expire. An expired link fails with "this media link has expired" instead of downloading a wrong file. |
| Audio extraction | the audio track is copied out of the video into an `.m4a` file (AAC), not re-encoded to MP3. |
| Live photos and 360° media | not handled separately: only the still image and the video are offered. |
| Verification on a device | the placement of the button and the menu were checked in code and statically, not on a device. See the test report. |
