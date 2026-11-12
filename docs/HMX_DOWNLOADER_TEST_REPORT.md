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
