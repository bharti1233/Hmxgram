# HMX Insta — M3 downloader screen

**Status:** implemented on `feature/hmx-m3-downloader-ui`, stacked on the M2
branch `feature/hmx-m2-android-shell`. M3 is not merged, no APK was built for
it yet, and every device row is still NOT TESTED.

**Prerequisite:** M2 (the Android shell, PR #20) is implemented and its CI is
green. M3 connects that engine to a screen; it does not replace it.

---

## 1. Scope

M3 delivers the dedicated downloader screen the product goal asks for, on top
of the existing M1 core and M2 Android shell:

| In scope | Out of scope (later milestones) |
| --- | --- |
| a downloader screen with the real queue and history | download buttons on feed posts, reels, stories and profiles (M4) |
| per-task progress, retry, cancel and delete | Ghost Mode settings and controls (M5) |
| persistent state across restarts, duplicate detection | in-app entry points inside the Instagram UI (M4/M5) |
| honest error messages from the engine | re-downloading an item that is still queued |

Nothing in Instagram is touched. The screen is a separate activity in the
downloader package, it uses platform widgets only, and it reads its state from
the same engine the share target feeds.

---

## 2. What was implemented

### 2.1 Core (plain Java, unit-tested)

| Class | Purpose |
| --- | --- |
| `DownloaderRow` | the immutable projection of one task the screen renders: title, state, progress, error, saved path, and the actions the engine allows |
| `DownloadRepository.delete(taskId)` | removes one history entry; the downloaded file is not touched |

`DownloaderRow` is the only new decision logic: it decides what the screen is
allowed to say. The rules it enforces:

* a row is **verified** only when the task finished *and* its result carries
  the path the gallery row was published under; the engine itself refuses to
  build a success without a path, and the history codec rejects one on decode,
  so "Saved" is never shown for something that was not saved;
* the percentage is unknown (and shown as bytes) when the source did not
  announce a size;
* `canRetry()` mirrors `DownloadTask.canRetry()` exactly: a failed task with a
  recoverable error and attempts left;
* `canCancel()` is true only while the task is queued or running;
* `canDelete()` is true only for a finished entry.

### 2.2 Android shell

| Class | Change |
| --- | --- |
| `HmxDownloader` | per-task byte counters, a change listener, `rows()`, a retry that also works for a task that only exists in the persisted history, and `delete(taskId)` |
| `PreferencesDownloadRepository` | implements the new `delete` |
| `DownloaderActivity` | the screen: a list of the engine's rows with per-row Retry / Cancel / Delete, an empty state and a "clear finished entries" action |

The screen is a view, never a second source of truth: every row comes from
`HmxDownloader.rows()`, which projects the live queue and the persisted
history. It refreshes on the engine's change listener and on a slow poll, so a
transfer that was started from the share target shows up here too.

The activity is declared by the same default-off downloader patch as the share
target, as a launcher activity of its own (`HMX Insta Downloader`). That keeps
the whole feature behind one default-off gate and avoids touching the
Instagram UI; M4 can add in-app entry points later without changing this
declaration.

### 2.3 Tests

`DownloaderUiTest` (new, plain JDK) covers the projection: state mapping, the
verified-success rule, the action rules, progress with and without an
announced size, byte formatting, and the history deletion (including that the
duplicate key is released again). The downloader core suite grows from 33 to
38 test groups.

---

## 3. Change control

Every file touched by M3 is either new or owned by the downloader milestones:

| File | Why it changed |
| --- | --- |
| `downloader/core/DownloaderRow.java` (new) | the screen's projection |
| `downloader/core/DownloadRepository.java` | the history needs a delete for the screen's delete action |
| `downloader/core/InMemoryDownloadRepository.java` | implements the new contract |
| `downloader/android/HmxDownloader.java` | exposes the state the screen shows and the actions it offers |
| `downloader/android/PreferencesDownloadRepository.java` | implements the new contract |
| `downloader/android/DownloaderActivity.java` (new) | the screen |
| `patches/.../downloader/DownloaderShareTargetPatch.kt` | declares the new activity; the downloader's own default-off patch |
| `scripts/ci/test_downloader_logic.sh` | runs the new test group |
| `scripts/ci/validate_features.sh` | the new file and the new manifest needle |
| `scripts/ci/test_downloader_isolation.sh` | allows the M3 document |

**Not touched:** no login, signup, authentication, session, feed, story, reel,
profile or navigation code; no Instagram patch outside the downloader patch;
no existing patch was removed or replaced; no permission was added or changed;
no protected method, hook or manifest surface of Instagram was modified.

---

## 4. Acceptance criteria

1. The screen lists queued, running, finished, failed and cancelled items
   from the engine, with no second copy of the state.
2. A running item shows its progress; an item whose source announced no size
   shows the bytes instead of a fake percentage.
3. A finished item is only shown as saved when the engine published it.
4. Retry, cancel and delete are offered exactly when the engine allows them,
   and each one changes the engine's state.
5. The queue and the history survive a process restart.
6. The core suite passes (38 groups), the Android shell compiles, and all ten
   repository gates pass.
7. The protected-method verification still passes against the pinned baseline.

---

## 5. Device checklist (OPPO A38) — all rows NOT TESTED

The M3 rows join the M2 checklist in
[HMX_INSTA_M2_DEVICE_CHECKLIST.md](HMX_INSTA_M2_DEVICE_CHECKLIST.md); the
M2 device test has not been performed either, so no M3 row can be PASS yet.

| # | Check | Result |
| --- | --- | --- |
| M3-1 | The launcher shows "HMX Insta Downloader" and the screen opens | NOT TESTED |
| M3-2 | A shared photo appears as "Downloading" and then "Saved" with the gallery path | NOT TESTED |
| M3-3 | The progress updates while a large video downloads | NOT TESTED |
| M3-4 | Cancel removes the item and leaves no file | NOT TESTED |
| M3-5 | A failed item shows the engine's message and offers Retry | NOT TESTED |
| M3-6 | Retry re-queues the item | NOT TESTED |
| M3-7 | Delete removes the entry but keeps the file | NOT TESTED |
| M3-8 | "Clear finished entries" empties the list | NOT TESTED |
| M3-9 | After a restart the list and the queue are still there | NOT TESTED |
| M3-10 | The Instagram UI is unchanged by the new screen | NOT TESTED |

---

## 6. What did not change

* `main` was not modified; no branch was merged.
* No login, signup, authentication, session, feed, story, reel, profile or
  navigation code was touched.
* The protected-method baseline pin, the 12 protected surfaces and the
  protected-file digest remain the gate they were.
* No APK was built or published for M3, and no device test was performed.
