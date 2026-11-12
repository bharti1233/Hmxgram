# HMX Insta — M4 Instagram media integration

**Status:** implemented on `feature/hmx-m4-instagram-media`, stacked on the M3
branch `feature/hmx-m3-downloader-ui` (PR #21) and on M2 (PR #20). Not merged,
no APK was built for it yet, and every device row is still NOT TESTED.

**Prerequisite:** M3 (the downloader screen) is implemented and its CI is
green. M4 adds the source of the media: what the user is looking at.

---

## 1. Scope

M4 connects the downloader to the media inside the app, using the milestone
plan's Tier 2: one read-only observation of a model Instagram already parsed.

| In scope | Out of scope (later milestones) |
| --- | --- |
| observing the media the user browses | a download button inside the feed (Tier 3) |
| listing the observed media in the downloader screen | story-tray and reels row buttons |
| queueing an observed photo, video or carousel | M5 Ghost Mode |
| honest reporting of what cannot be downloaded | M6 integration and release |

**Nothing in Instagram is modified.** The observation reads a model; it does
not write to it, does not touch a view, does not scan a view hierarchy and
does not intercept a request.

---

## 2. What was implemented

### 2.1 Core (plain Java, unit-tested)

| Class | Purpose |
| --- | --- |
| `MediaModelReader` | reads a media model into an `ObservedMedia` |
| `ObservedMedia` | the plain copy: id, kind, URL, owner, audio flag, children |
| `MediaRegistry` | the bounded list of observations, newest first |

`MediaModelReader` never looks for an accessor by name. Instagram's media
model is map-backed and its accessors are renamed in every release, so the
reader finds them by **type and by the value they produce**:

* the image accessor is the no-argument method whose result type is called
  `ImageInfo`, the video accessor the one called `VideoInfo`;
* the URL is the string the info object exposes that starts with `http` — a
  width or a label is not accepted;
* the children are the list whose elements are models again.

Every step is validated by the value it produces, so a reshaped model yields
nothing instead of a wrong URL. The shape was confirmed against the **dex of
the published build** (`com.instagram.feed.media.Media` holds
`image_versions2`, `video_versions`, `media_type` and `carousel_media`, and
resolves them through obfuscated accessors — it has no JSON serializer at
all, which is why the reader does not use one).

### 2.2 Android shell

| Class | Change |
| --- | --- |
| `MediaObserver` | the hook's landing point: it stores the model reference on the calling thread and reads it on a worker thread |
| `DownloaderActivity` | lists the observed media above the downloads and queues them on demand |

The observer only stores an object reference on the calling thread, so a long
feed cannot slow the UI down, and every failure inside it is swallowed: the
observer must never reach Instagram's code.

A carousel is queued as its children, because the engine refuses a container
as it is. A photo or a video becomes an engine item with the media's own id,
so the same media is recognised as a duplicate.

### 2.3 The hook (default-off)

`DownloaderMediaObserverPatch` is a **new, default-off** bytecode patch. It
resolves the accessor that turns Instagram's parsed `image_versions2` into an
image info object and inserts one read-only call there:

```
invoke-static { p0 }, MediaObserver->observeMedia(Ljava/lang/Object;)V
```

The anchor is the behaviour, not a name: the class holds the media JSON keys
and the method is the one that reads `image_versions2` and answers an image
info. If the anchor does not resolve in a future release, the patch reports
it and installs **nothing** — a miss degrades the feature instead of breaking
the build.

**Deviation from the plan, stated plainly:** the plan's Tier 2 anchor is the
*row binder* (`bind(holder, model)`). This implementation anchors on the
media model's own accessor instead, for two reasons: the row definitions
Instagram registers for its own feeds cannot be enumerated reliably from the
patch, while the media accessor is a single, precisely identifiable method
that every media item passes through. The guarantee the plan asks for is the
same — a read of an object Instagram already parsed, with no modification, no
view injection and no network hook — but the anchor is not the one the plan
names. The observation is also broader in one respect: it fires for every
media the app resolves, not only for the rows of one list.

### 2.4 Tests

`MediaObservationTest` (new, plain JDK) covers the reader against fake models
with the shape of the real one (meaningless accessor names on purpose), the
rejection of everything that is not media, the conversion to an engine item
and the bounded registry. The core suite grows from 38 to **44 groups**.

---

## 3. Change control

| File | Why it changed |
| --- | --- |
| `downloader/core/MediaModelReader.java` (new) | reads a media model |
| `downloader/core/ObservedMedia.java` (new) | the plain copy of an observation |
| `downloader/core/MediaRegistry.java` (new) | the bounded observation list |
| `downloader/android/MediaObserver.java` (new) | the hook's landing point |
| `downloader/android/DownloaderActivity.java` | lists and queues the observed media |
| `patches/.../downloader/DownloaderMediaObserverPatch.kt` (new) | the default-off hook |
| `scripts/ci/validate_features.sh` | the new patch source in the inventory |
| `scripts/ci/test_downloader_logic.sh` | runs the new test group |
| `scripts/ci/test_downloader_isolation.sh` | the M4 document and the baseline move |
| `build.sh`, `.github/workflows/apk.yml` (CI commit) | the gated build must be able to apply the downloader's second patch |

**Not touched:** no login, signup, authentication, session, feed, story, reel,
profile or navigation code; no Instagram patch outside the downloader folder;
no existing patch was removed or replaced; no permission was added; no
protected method, hook or manifest surface of Instagram was modified.

The protected-method gate is unaffected: the observation hooks an *accessor*,
while the protected surface covers the *parser* (`unsafeParseFromJson` and the
media error strings), which is untouched.

---

## 4. Acceptance criteria

1. A media model the user browses is recorded with its id, kind and URL.
2. A model that cannot be read yields nothing — never a wrong URL.
3. The observed list is bounded and survives the app restart only as long as
   the process lives (it is a live view, not history).
4. Queueing an observed item reaches the engine with the media's own id, so
   the same media is a duplicate.
5. A carousel is queued as its children.
6. The core suite passes (44 groups), the Android shell compiles and all ten
   repository gates pass.

---

## 5. Device checklist (OPPO A38) — all rows NOT TESTED

| # | Check | Result |
| --- | --- | --- |
| M4-1 | Browse a photo in the feed, open the downloader, the photo is listed | NOT TESTED |
| M4-2 | Download it from the list → it appears in `Pictures/HMXInsta` | NOT TESTED |
| M4-3 | Browse a reel, download it → `Movies/HMXInsta` | NOT TESTED |
| M4-4 | Browse a carousel, download it → each child is saved | NOT TESTED |
| M4-5 | The same media twice → the second is a duplicate, no second file | NOT TESTED |
| M4-6 | An expired link → the honest error, no file | NOT TESTED |
| M4-7 | The feed still scrolls, stories and reels still play | NOT TESTED |
| M4-8 | The observation costs nothing visible while scrolling | NOT TESTED |

---

## 6. What did not change

* `main` was not modified; no branch was merged.
* No login, signup, authentication, session, feed, story, reel, profile or
  navigation code was touched.
* The protected-method baseline pin, the 12 protected surfaces and the
  protected-file digest remain the gate they were.
* No APK was built or published for M4, and no device test was performed.
