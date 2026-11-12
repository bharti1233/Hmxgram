# HMX Downloader — Test Report

Every row says what was actually run. A test that was not executed is marked
**pending**; nothing is reported as passing on the strength of a code review.

* **offline** — runs on a plain JDK, no device, no emulator, no network
  (`scripts/ci/test_downloader_logic.sh`)
* **static** — repository checks (`scripts/ci/validate_features.sh`)
* **device** — needs an OPPO A38 / Android 15 / arm64-v8a
* **emulator** — needs an emulator; an x86 emulator cannot prove arm64
  installation, so it is not used for the install checks

---

## 1. What ran

| Suite | Command | Result |
| --- | --- | --- |
| Downloader logic (unit) | `bash scripts/ci/test_downloader_logic.sh` | **passed — 52 checks** |
| Feature safeguards (static) | `bash scripts/ci/validate_features.sh` | **passed** |
| Repository validation | `bash scripts/ci/validate_repo.sh` | **passed** |
| Packaging gates, patch report, publish gates, cleanup gates, branding | the existing suites | **passed** |

The unit suite compiles the downloader's rules with `javac` and exercises them
against fake media models that have the same shape as Instagram's (the
accessors listed in the architecture document).

---

## 2. Scenario matrix

### Media resolution

| Scenario | Level | Result | What was checked |
| --- | --- | --- | --- |
| Feed photo | offline | **passed** | one item, variant `PHOTO`, `image/jpeg`, `.jpg`, the media id and the owner are read |
| Feed video | offline | **passed** | video + audio + cover are offered; the video's MIME type and extension are `video/mp4`/`.mp4` |
| Video without an audio track | offline | **passed** | no audio item is offered |
| Carousel | offline | **passed** | every child of the carousel is resolved on its own |
| Carousel with children that expose no URL | offline | **passed** | nothing is offered for that child and a reason is given |
| A post that exposes no URL | offline | **passed** | the offer is empty with a reason; no URL is ever invented |
| Public story photo | offline | **passed** | a live story offers its photo |
| Expired story | offline | **passed** | an expired story offers nothing and says "this story has expired" |
| Reels video | offline | **passed** | video, audio and cover are offered |
| Reel without a cover | offline | **passed** | the cover is reported as unavailable, with a reason |
| Private-account media | — | **not applicable** | the downloader only ever sees media the signed-in account already parsed; a post the account may not view never reaches the registry, so there is nothing to unlock |
| Audio extraction | device | **pending** | the `MediaExtractor`/`MediaMuxer` path needs a real video on a device |
| Cover extraction | offline | **passed** | the cover is offered as its own variant with the image MIME type |

### The queue

| Scenario | Level | Result | What was checked |
| --- | --- | --- | --- |
| Duplicate detection | offline | **passed** | the same media id is refused twice; a different id is not; the URL fallback ignores the query string |
| Cancellation | offline | **passed** | a cancelled task ends in `CANCELLED` and is not finished as successful |
| Retry | offline | **passed** | a network failure can be retried; an expired link cannot |
| Task progress | offline | **passed** | the percentage is computed from the bytes and stays inside 0–100 |
| Queue limit and concurrency | device | **pending** | two at a time, twenty waiting |
| Gallery saving | device | **pending** | MediaStore insert with `IS_PENDING`, then publish |
| Android 15 permissions | device | **pending** | no storage permission is requested; the insert needs none |
| Network failure | device | **pending** | the error is reported and the task can be retried |
| Expired or inaccessible media | offline | **passed** | the expiry rule refuses the offer; an expired link is a `EXPIRED` error |

### Safety

| Check | Level | Result |
| --- | --- | --- |
| No third-party service in the downloader | static | **passed** |
| No cookies, tokens or credentials read | static | **passed** |
| No installer, no `REQUEST_INSTALL_PACKAGES` | static | **passed** |
| No broad storage permission | static | **passed** |
| A download only starts from the download menu | static | **passed** |

---

## 2b. The regression of 2026-11-12, and the guard it produced

**Symptom**: the build that added the downloader and the ghost mode left the app
on a black screen with an endless spinner when the network was on; with the
network off the login screen appeared; and logging in with the network on left
the spinner again.

**Root cause** (measured on the published broken APK, `debug-test-43168216595-1`,
with `dexdump`): the capture hook was inserted *between* Instagram's call of the
media parser and Instagram's own `move-result-object`:

```
0387: invoke-static {v12}, LX/06nm;.A02:(...)Lcom/instagram/feed/media/Media;   <- Instagram's parser call
038b: invoke-static/range {v0}, Lcom/feurstagram/extension/downloader/MediaRegistry;.capture:(Ljava/lang/Object;)V   <- our hook
038e: move-result-object v0                                    <- Instagram's move-result, now on OUR void call
038f: if-eqz v0, ...
```

A `move-result-object` after a **void** call is invalid dex. The class fails
verification, every media parse throws, and the app never gets a usable model:
with a network the feed and the session are parsed and the spinner never ends;
without one nothing is parsed, so the login screen appears; after logging in the
feed is parsed and the spinner is back.

The pattern this project has shipped for months — the feed filter — inserts
*after* Instagram's own `move-result-object`:

```
0387: invoke-static {v12}, ...A02:(...)Lcom/instagram/feed/media/Media;   <- parser call
038a: move-result-object v37                                   <- Instagram's move-result: v37 = the media
038b: invoke-static/range {v37}, Block.filterFeedMedia:(Ljava/lang/Object;)Ljava/lang/Object;   <- the filter
038e: move-result-object v37                                   <- the filter puts the media back
038f: check-cast v37, Lcom/instagram/feed/media/Media;
```

**Fix**: the hook is now inserted after Instagram's `move-result-object`,
`MediaRegistry.capture` returns the media unchanged (not `void`), and the patch
puts it straight back — the feed filter's pattern, instruction for instruction.

**Second defect in the same new code**: the installer added a global layout
listener on *every* activity resume and never removed it, so the listeners
piled up and each layout pass walked the view tree again and again. The
listener is now attached once per screen, removed when the screen goes away,
and the scan is throttled.

**Guards added**
* a unit test that the capture hands the media back (56 checks in the suite);
* a static check that the patch restores the register and that the installer
  removes its listener;
* a dex-level check (`scripts/ci/verify_capture_patch.py`) that runs in every
  gated build: it disassembles the patched APK and fails the build unless every
  capture call returns the media, sits after Instagram's `move-result-object`
  and is followed by the restore. It was calibrated against the published
  broken APK, which it rejects with the exact reason for all 32 call sites.

---

## 3. What still needs a device

| Check | Why it cannot be done offline |
| --- | --- |
| The HMX button's placement in the action row | the row is found at runtime; only a real feed shows whether it lands between "Share" and "Save" |
| The button in the story and reels viewers | the viewers' own controls are found at runtime |
| The menu on a real post | the offer depends on the media Instagram parsed |
| The file in the gallery | MediaStore behaviour differs per Android version |
| The audio file | needs a real video with an audio track |
| Installation on the OPPO A38 | needs the device |

---

## 4. Honest summary

The capture hook was **proved in a real build**: the gated run 43168216595
applied *HMX downloader capture*, and that patch fails closed when it finds no
media model, so a passing build means the hook actually attached to Instagram's
media parser.

The downloader's **rules** are implemented and tested: what can be offered for
a photo, a video, a carousel, a story and a reel, how items are named, how
duplicates are caught, when a download can be retried and when a story is
expired. All of that is covered by the offline suite.

The **plumbing** — the queue's workers, the notification, the MediaStore write,
the audio extraction and the placement of the button — is written but has not
run on a device, because the device test is still pending. Those rows are
marked pending above and must not be read as passing until someone installs
the build on the OPPO A38 and checks them.
