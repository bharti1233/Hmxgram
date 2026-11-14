# HMX Insta — M2 device readiness checklist

**Status:** device verification **not performed**. No OPPO A38 / Android 15
device was attached to the build environment, so every device check below is
recorded as **NOT TESTED**. Nothing in this document may be promoted to PASS
without an observed result on a physical device, together with the device
serial and the installed `versionName` / `versionCode`.

**Code status:** the seven audit findings F1–F7 of the M2 review are now
*implemented* on `feature/hmx-m2-android-shell` (section 2 lists the fix and
its test evidence). "Static readiness" below therefore describes reviewed
code, never an observed device result. Every device row stays NOT TESTED
until the session in section 3 is actually run.

**Scope:** the ten checks in
`docs/HMX_INSTA_M2_ANDROID_SHELL.md` §6, expanded for Android 15 / ColorOS 15.
No APK has been built or dispatched. Install the reviewed build only after
PR #20 leaves draft; these commands assume it is already installed.

---

## 0. Rules for recording a result

* **PASS** requires a command output, a screenshot or a log line captured on the
  device, copied into the evidence column.
* **FAIL** requires the same evidence, plus the observed behaviour.
* **NOT TESTED** is the only valid state for a check nobody ran. Code review is
  recorded separately as *static readiness* and never substitutes for a PASS.
* Run every destructive step (storage-full, force-stop, permission revoke) only
  on a test build, never on a build the user relies on.
* Do not change the user's wireless debugging configuration. The commands below
  use whatever `adb` connection is already authorised.

---

## 1. Status summary

| # | Check (M2 doc §6) | Device result | Static readiness | Blocking issue |
| --- | --- | --- | --- | --- |
| 1 | Share a photo → `Pictures/HMXInsta`, notification, history | **NOT TESTED** | PASS (F1 fixed) | One notification prompt per install; denial is respected and the save still completes |
| 2 | Share a reel/video → `Movies/HMXInsta` | **NOT TESTED** | PASS (F3 fixed) | Foreground `dataSync` service keeps the queue alive; ColorOS limits are documented |
| 3 | Share the same item twice → duplicate, no second file | **NOT TESTED** | PASS | — |
| 4 | Cancel a large video → no row, temp gone | **NOT TESTED** | PASS in code, **no trigger yet** | F5: cancellation now covers the whole lifecycle, but the cancel *action* is M3 scope (settings page) |
| 5 | Deny notifications → save still completes | **NOT TESTED** | PASS | One prompt per install; a denial is remembered and never re-asked |
| 6 | Storage failure → `storage` error, no pending row | **NOT TESTED** | PASS | A killed process can still leave a stale pending row until the platform cleans it (F6) |
| 7 | Restart → history reloads, duplicates still detected | **NOT TESTED** | PASS (F3 fixed) | Pending work is replayed from the ledger on the next start |
| 8 | Instagram login/signup/feed/session unchanged | **NOT TESTED** | PASS (gates) | Protected digest and 12 surfaces unchanged; still requires a device check |

Additional readiness checks requested for M2:

| Area | Device result | Static readiness | Notes |
| --- | --- | --- | --- |
| Android permissions | **NOT TESTED** | PASS (F1 fixed) | `POST_NOTIFICATIONS` is requested once per install; `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_DATA_SYNC` are declared for the service. No storage permission, by design |
| MediaStore behaviour | **NOT TESTED** | PASS | `IS_PENDING` insert → copy → publish → delete-on-failure; a late cancellation deletes the published row (F5) |
| Share-target registration | **NOT TESTED** | PASS | Exported activity with `SEND`/`SEND_MULTIPLE` for `image/*`, `video/*`, `audio/*`; private provider; non-exported service (F6) |
| `content://` handling | **NOT TESTED** | PASS (F4 fixed) | `EXTRA_STREAM` **and** `ClipData` are merged; scheme, MIME, count and size validated; copy happens while the grant is alive |
| Worker lifecycle / retry | **NOT TESTED** | PASS (F3 fixed) | Foreground `dataSync` service + pending-work ledger replay; in-process pool, 2 concurrent, 3 attempts |
| Cancellation | **NOT TESTED** | PASS in code, trigger in M3 | Signal checkpoints before download, per buffer, and inside the publish; the user-facing cancel action is still M3 scope |
| History persistence / duplicates | **NOT TESTED** | PASS | Terminal-only history, 100 entries, query stripped, seeded on next start |
| Notification behaviour / progress | **NOT TESTED** | PASS (F1 fixed) | Progress aggregation unchanged; the permission is requested once per install |
| Audio handling | **NOT TESTED** | PASS for direct audio (F2) | An `audio/*` share is saved directly to `Music/HMXInsta`. Video→audio extraction is policy-gated but has **no M2 entry point**; it is not claimed to work |
| Cache retention | **NOT TESTED** | PASS (F7 fixed) | The janitor only runs at start and when the queue is idle, so an active task's files are never candidates |

---

## 2. Findings: status after the M2 audit fixes

Each finding below lists the review text, the fix on
`feature/hmx-m2-android-shell`, the automated test evidence, and what still
requires a device. **None of these is a device result.**

### F1 — `POST_NOTIFICATIONS` was declared but never requested — FIXED

*Review:* the permission was added to the manifest, but no code called
`requestPermissions(...)`, so on a fresh Android 13+ install both the progress
notification (4101) and the result notification (4102) were silently skipped.

*Fix:* `ShareTargetActivity` now asks once per install through
`NotificationPermissionPolicy`: it requests only on Android 13+, only when the
permission is not granted, and only when the install has not asked before. The
"asked" flag lives in `hmx_download_prefs` / `notification_permission_asked`
and is written **before** the dialog appears, so a process recreation cannot
produce a second prompt. A denial is honoured permanently: the import resumes
in `onRequestPermissionsResult` regardless of the answer, and the save
completes either way. The foreground service does not depend on the
permission — Android hides the notification, the service still runs.

*Test evidence:* `DownloaderCoreTest` → "notification permission policy"
(asserts skip before Android 13, skip when granted, request on a fresh
install, skip after a denial on any version).

*Still NOT TESTED:* the real dialog on the device, the denial path, and that
the app never asks twice (see checks 1 and 5).

### F2 — Audio was unreachable from the share target — FIXED, with an honest limit

*Review:* `DownloadWorker` only extracts when `StoragePlan.isAudio()`, which
requires `DownloadKind.AUDIO`, and `ShareImporter` only ever created
`PHOTO`/`VIDEO`. The class and the scope table described a path no user could
trigger.

*Fix:* `ShareImporter` now maps an `audio/*` share to `DownloadKind.AUDIO`, so
a shared audio file is reachable and is saved directly to `Music/HMXInsta`
(`.m4a` fallback naming for a generic MIME). The extraction decision moved
into `AudioSourcePolicy`: `audio/*` saves directly; `video/mp4`,
`video/3gpp`, `video/x-m4v` and `video/quicktime` would be extracted;
every other container is refused up front with `unsupported`. `AudioExtractor`
reports failure when the muxer produced zero samples, so a silent file is
never published.

*Honest limit:* video→audio extraction still has **no M2 entry point** — a
video share stays a video. The code path exists and is policy-gated, but
until M3 adds an explicit "save as audio" action it cannot be exercised from
the UI, so it is **not claimed to work** and its device row stays NOT TESTED.

*Test evidence:* `DownloaderCoreTest` → "audio source policy" (direct vs
extractable vs unsupported per container, case-insensitive, parameter-tolerant).

*Still NOT TESTED:* a direct `audio/*` share on the device, and the
`unsupported` failure for a container the policy refuses.

### F3 — No foreground service, no process-death restoration — FIXED, limits documented

*Review:* the queue ran on a plain in-process `ExecutorService`. A ColorOS
reclaim during a long video stopped the download with no failure entry, no
notification and leftover files.

*Fix:* `DownloadService` is a declared, non-exported `dataSync` foreground
service (`foregroundServiceType="dataSync"`), started from the visible share
activity — never from the background. It holds a foreground notification while
the queue has work and stops itself when the queue drains. It performs no work
of its own. Independently of the restart, every accepted item is written to
`PendingWorkStore` before dispatch and removed when the task reaches a
terminal state, so the next start replays the ledger; `START_STICKY` lets the
system restart the service after a kill.

*Documented limits:* aggressive ColorOS battery modes can delay or refuse the
service restart, and Android 15 caps `dataSync` foreground services at six
hours per day. In both cases the queued work resumes the next time the app
opens; it may simply not finish in the background.

*Test evidence:* `DownloaderCoreTest` → "pending work codec round trip" and
"pending work codec rejection" (bounded, versioned, duplicate-rejecting,
corruption-rejecting ledger format); the service lifecycle itself is a device
check.

*Still NOT TESTED:* the service staying alive with the screen locked, the
ledger replay after a kill, and the ColorOS behaviour (checks 2 and 7).

### F4 — `EXTRA_STREAM` only, no `ClipData` fallback — FIXED

*Review:* `ShareIntentParser` read `EXTRA_STREAM` only, so a sender that puts
the URI only in `Intent.getClipData()` was rejected with "nothing to share".

*Fix:* `SharedStreams` merges both sources — the extras first, then the
ClipData items — collapses an item present in both (exactly one download),
ignores blank entries, and applies the 10-item cap to the merged set.
`ShareIntentParser` uses the merged list and keeps the per-item MIME and size
validation.

*Test evidence:* `DownloaderCoreTest` → "shared stream selection" (extras
only, clip only, duplicate across sources, union order, blanks ignored, cap
over the union, immutable result).

*Still NOT TESTED:* the actual Instagram share sheet on the device, including
a sender that only fills `ClipData`.

### F5 — Cancellation had no trigger and no publish check — FIXED in code, trigger is M3

*Review:* nothing in the UI called `HmxDownloader.cancel(...)`, and the
cancel flag was only checked while reading the source, so a cancel landing
during the gallery copy still published the file.

*Fix:* each running task owns a `CancellationSignal` that is checked before
the download, between the download and the audio step, on every copy buffer,
before the MediaStore insert and before the row is published.
`MediaStoreWriter` deletes the pending row when the copy is cancelled or
fails; a cancel that lands after publication deletes the published row
through `discard`/`discardByPath`, so a cancelled task cannot leave a
finished file behind. `GuardedCopy` rolls the destination back exactly once.

*Honest limit:* M2 still has **no user-facing cancel action** — the button
belongs to the M3 settings page. The code path is correct and unit-tested;
the device check stays NOT TESTED and the action is explicitly M3 scope.

*Test evidence:* `DownloaderCoreTest` → "cancellation signal" and "guarded
copy cancellation" (mid-copy cancel rolls back exactly once; an I/O failure
rolls back exactly once; a failing rollback does not mask the original
error).

*Still NOT TESTED:* an end-to-end cancel on the device.

### F6 — Abrupt kill can leave an `IS_PENDING` row — ACCEPTED, bounded

*Review:* the `finally` block deletes the pending row on failure, but a
`SIGKILL` skips `finally`; Android cleans stale pending rows eventually.

*Fix/decision:* the exported surface was reviewed instead of widened: only
`ShareTargetActivity` is exported (limited to `SEND`/`SEND_MULTIPLE` with
`image/*`, `video/*`, `audio/*`); `ShareCacheProvider` is private and not
exported; `DownloadService` is not exported. Every shared URI must be a
`content://` URI with an image, video or audio MIME, at most 10 items and at
most 512 MiB each, and the bytes are copied into private cache storage while
the sender's grant is alive. A kill can still leave a pending row until the
platform cleans it; that is a platform behaviour, documented here instead of
claimed away.

*Test evidence:* `validate_features.sh` fails closed on any forbidden
Instagram/credential/broad-storage symbol in the Android shell and on a
storage permission in the share patch; `test_downloader_isolation.sh` pins
the protected digest and the exact path allowlist.

*Still NOT TESTED:* the force-stop row behaviour on the device (check 6).

### F7 — Low-severity notes — ADDRESSED

* `http://` sources: unchanged and documented; the patched app blocks
  cleartext, so an `http` CDN URL is a network failure. Instagram uses HTTPS.
* History path field: unchanged, display-only.
* Exported activity has no caller permission: the damage is bounded as
  described in F6.
* Rotating the invisible activity re-runs the import; the content hash makes
  the second enqueue a duplicate, so no double save, only wasted I/O.
* A permanently failed task keeps its share cache copy for a manual retry.
  **Now bounded:** `ShareCacheJanitor` applies `CacheRetentionPolicy` at
  downloader start (before the first dispatch) and when the queue becomes
  idle. A share copy is protected by name whenever an active task or the
  pending ledger references it, whatever its age. A temporary download file is
  referenced by nothing but the running worker, so it is only reclaimed when
  the caller states that no task is running — the byte budget can evict a file
  that is still inside the grace window, so the temp sweep must never race a
  live task.

*Test evidence:* `DownloaderCoreTest` → "cache retention policy" (an active
task's file is never evicted; count budget evicts oldest first; byte budget
evicts oldest until under the cap; the budget evicts a fresh file when the
cache is over budget; invalid input rejected).

---

## 3. Preflight (run once)

```bash
adb devices -l
adb shell getprop ro.product.model          # expected: CPH… (OPPO A38)
adb shell getprop ro.build.version.release  # expected: 15
adb shell getprop ro.build.version.sdk      # expected: 35
adb shell getprop ro.build.display.id       # ColorOS build id

# Use the package actually installed: clone builds differ.
PKG=com.instagram.android                   # clone: com.instagram.android.feurstagram
adb shell dumpsys package "$PKG" | grep -E "versionName|versionCode"
```

Record model, build id, `PKG`, `versionName` and `versionCode` in the evidence
log (section 12). A result is only valid for that exact build.

ColorOS 15 background policy (do this before checks 2, 4, 7):

```
Settings → Battery → App battery usage → HMX Insta → Allow background activity
```

```bash
adb shell dumpsys deviceidle whitelist | grep "$PKG"   # optional: confirm exemption
```

---

## 4. Check 1 — photo share, notification, history

**Preparation**

```bash
adb logcat -c
```

1. Open Instagram → a feed photo → Share → HMX Insta.
2. Wait for the toast ("Saving to the gallery") and the result notification.

**Expected**

* Exactly one new row:

```bash
adb shell content query \
  --uri content://media/external/images/media \
  --projection _id:_display_name:relative_path:is_pending:mime_type \
  --where "_display_name LIKE 'hmx%'"
```

Expected: `relative_path=Pictures/HMXInsta/`, `is_pending=0`,
`mime_type=image/jpeg` (or the shared type), name matches
`hmx_share-<hash>_photo_<timestamp>.jpg`.

* File on disk:

```bash
adb shell ls -l /sdcard/Pictures/HMXInsta
```

* Notification: ID 4101 while saving, then 4102 "Saved — 1 item saved to the
  gallery".
* No error in logcat:

```bash
adb logcat -d -v time | grep -Ei "HmxDownloader|ShareTarget|MediaStore|AndroidRuntime"
```

**Notification permission (F1) — run this first on a fresh install**

The first share now raises the Android 13+ `POST_NOTIFICATIONS` dialog exactly
once per install. Record which branch you take:

```bash
# before the first share: confirm the permission is not granted
adb shell pm check-permission "$PKG" android.permission.POST_NOTIFICATIONS
```

* **Allow** → the share continues after the dialog, and notifications 4101 and
  4102 appear. Verify:

```bash
adb shell dumpsys notification --noredact | grep -A4 "$PKG"
```

* **Deny** → the share still completes: the file is in `Pictures/HMXInsta`, the
  toast is shown, the history entry is written, and no notification appears.

Then share a second item and confirm the app does **not** ask again:

```bash
adb shell dumpsys package "$PKG" | grep -A2 "notification_permission_asked"
# (prefs are only readable on a debuggable build)
adb shell run-as "$PKG" cat shared_prefs/hmx_download_prefs.xml
```

Expected: `notification_permission_asked=true` and no second dialog.

To re-test the granted path later, grant it manually:

```bash
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS
# repeat the share → notifications must appear
adb shell dumpsys notification --noredact | grep -A4 "$PKG"
```

---

## 5. Check 2 — video/reel share

Same procedure with a reel or a DM video.

```bash
adb shell content query \
  --uri content://media/external/video/media \
  --projection _id:_display_name:relative_path:is_pending:mime_type \
  --where "_display_name LIKE 'hmx%'"
adb shell ls -l /sdcard/Movies/HMXInsta
```

Expected: `relative_path=Movies/HMXInsta/`, `is_pending=0`, playable file of the
same duration as the source.

Long-file variant (also F3 evidence): share a video ≥ 100 MiB, lock the screen,
wait 2 minutes, unlock. Record whether the row completes or the process was
killed:

```bash
adb shell pidof "$PKG"
adb logcat -d | grep -Ei "lowmemorykiller|am_kill|HmxDownloader"
```

While the download runs, the `dataSync` foreground service must be visible and
the process must survive the screen lock:

```bash
adb shell dumpsys activity services "$PKG" | grep -Ei "DownloadService|foreground"
adb shell dumpsys notification --noredact | grep -A4 "hmx_downloads"
adb shell dumpsys activity services "$PKG" | grep -i "isForeground"
```

Expected: `DownloadService` is listed as a foreground service while the queue
has work, and it is gone a few seconds after the last item is published (the
service stops itself when the queue is idle).

---

## 6. Check 3 — duplicate detection

1. Share the same photo twice, several minutes apart (and once after a reboot).
2. Expected toast: "Already saved"; no second row; the existing file is
   untouched.

```bash
adb shell content query \
  --uri content://media/external/images/media \
  --projection _id:_display_name --where "_display_name LIKE 'hmx%'"
```

```bash
# Only on a debuggable test build; release builds deny run-as.
adb shell run-as "$PKG" cat shared_prefs/hmx_download_history.xml
```

Expected history: header `hmx-download-history\t1`; one entry per saved item;
no `?token=`, no `#fragment`, no `%3F` query remnants in the encoded URL.

---

## 7. Check 4 — cancellation

**Still NOT TESTED — no trigger in M2 (F5).** The M2 shell has no cancel
button, notification action or settings entry; that surface belongs to the M3
settings page. Do not fake a PASS with `force-stop`: that is process death,
covered by check 7.

The code path itself changed and is unit-tested: the task's cancellation
signal is now checked before the download, on every copy buffer, and inside
the MediaStore publish, and a cancelled publish deletes the pending row (a
cancel that lands after publication deletes the published row). Evidence:
`test_downloader_logic.sh` → "cancellation signal" and "guarded copy
cancellation".

When a cancel trigger exists (M3), the procedure is:

1. Start a ≥ 100 MiB video.
2. Trigger cancel.
3. Expected: no row for that name, `is_pending` rows absent, `.part` file gone,
   history entry `CANCELLED`, cache copy deleted, result notification does not
   count it as saved.

```bash
adb shell content query --uri content://media/external/video/media \
  --projection _id:_display_name:is_pending --where "is_pending=1"
adb shell run-as "$PKG" ls -l cache
```

Until then record **NOT TESTED — no trigger**.

---

## 8. Check 5 — notifications denied

The prompt now appears once per install, so this check must run **before** the
first share, or the app has to be reinstalled to see the prompt again.

```bash
adb shell pm revoke "$PKG" android.permission.POST_NOTIFICATIONS
adb shell cmd appops set "$PKG" POST_NOTIFICATION ignore
```

Share a photo. Expected: no notification, save still completes, toast still
shown, history entry written. Then share a second item and confirm **no second
dialog** appears (the denial is remembered for the whole install):

```bash
adb shell dumpsys notification --noredact | grep -A4 "$PKG"   # expect nothing
adb shell run-as "$PKG" cat shared_prefs/hmx_download_prefs.xml
# expect notification_permission_asked=true
```

Restore with:

```bash
adb shell cmd appops set "$PKG" POST_NOTIFICATION allow
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS
```

A reinstall clears the "asked" flag, so a fresh install asks once again.

---

## 9. Check 6 — storage failure

Pick one non-destructive method on the test device:

* fill the shared storage with a throwaway file (`adb shell dd`) until
  MediaStore refuses the insert, or
* use a read-only/limited test volume if the device offers one.

Expected:

* result notification/error mapped to `storage`, not `unknown`;
* no row with `is_pending=1`;
* no partial file in `Pictures/HMXInsta`;
* the failed task appears in history as `FAILED` / `storage`.

```bash
adb shell content query --uri content://media/external/images/media \
  --projection _id:_display_name:is_pending --where "is_pending=1"
adb shell ls -l /sdcard/Pictures/HMXInsta
```

Then delete the throwaway filler.

---

## 10. Check 7 — restart, history, process death

**Graceful restart**

```bash
adb shell am force-stop "$PKG"
# reopen Instagram, share the already-saved item
```

Expected: "Already saved", no new file — the DONE history re-seeds duplicate
detection.

**Process death during a download (F3)**

```bash
# while a large video is downloading
adb shell am kill "$PKG"        # background kill; may be a no-op if foreground
adb shell am force-stop "$PKG"  # hard kill, mimics ColorOS reclaim
```

Expected per current code (record actual):

* no crash loop on next launch;
* the interrupted item is **not lost**: it is still in the pending-work ledger
  and is replayed into the queue on the next start, so the download resumes
  (the share cache copy is kept for exactly this reason);
* after the resumed download finishes, the ledger entry is gone:

```bash
adb shell run-as "$PKG" cat shared_prefs/hmx_download_pending.xml   # debuggable build
# after completion: the ledger must be empty (no items)
adb shell run-as "$PKG" ls -l cache        # debuggable build
adb shell content query --uri content://media/external/video/media \
  --projection _id:_display_name:is_pending --where "is_pending=1"
```

* the stale `.part` file of the killed attempt is reclaimed by the cache
  janitor at the next downloader start or when the queue goes idle — it must
  never be a file an active task still needs.

Do not mark this check PASS if the item silently disappears without a record
and without a replay; that would mean the ledger replay is broken.

---

## 11. Check 8 — Instagram unchanged, plus registration and permissions

Registration and permission audit (no sharing needed):

```bash
adb shell cmd package query-activities --brief \
  -a android.intent.action.SEND -t image/jpeg
adb shell cmd package query-activities --brief \
  -a android.intent.action.SEND_MULTIPLE -t video/mp4
adb shell cmd package query-activities --brief \
  -a android.intent.action.SEND -t audio/mpeg

adb shell dumpsys package "$PKG" | sed -n '/ShareTargetActivity/,/^$/p'
adb shell dumpsys package "$PKG" | sed -n '/ShareCacheProvider/,/^$/p'
adb shell dumpsys package "$PKG" | sed -n '/DownloadService/,/^$/p'
```

Expected:

* activity `…ShareTargetActivity`, `exported=true`, two intent filters, theme
  translucent, `excludeFromRecents=true`;
* provider `…ShareCacheProvider`, `exported=false`, authority `<PKG>.hmxshare`;
* service `…DownloadService`, `exported=false`, `foregroundServiceType=dataSync`;
* the activity appears in the Instagram share sheet for a photo, a video and
  an audio file.

```bash
adb shell dumpsys package "$PKG" | grep -E "POST_NOTIFICATIONS|FOREGROUND_SERVICE|EXTERNAL_STORAGE"
adb shell pm check-permission "$PKG" android.permission.POST_NOTIFICATIONS
```

Expected: `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE` and
`FOREGROUND_SERVICE_DATA_SYNC` declared, no
`READ/WRITE/MANAGE_EXTERNAL_STORAGE`.

Instagram regression (manual, 5 minutes):

1. cold start, login state preserved;
2. feed, stories, reels, explore, DMs open and scroll;
3. profile and settings open;
4. a like/comment/save still works;
5. no new crash in logcat:

```bash
adb logcat -d -v time | grep -E "FATAL EXCEPTION|AndroidRuntime.*$PKG"
```

---

## 12. Audio handling (check requested separately)

### 12.1 Direct audio share — reachable in M2 (F2)

**Device result: NOT TESTED.**

An `audio/*` share is now mapped to `DownloadKind.AUDIO` and saved directly
(no extraction involved). Procedure:

1. Share an audio file (for example a voice note or an `.m4a`) to HMX Insta;
2. expected: a row in the audio collection with `relative_path=Music/HMXInsta/`,
   `is_pending=0`, and the same duration as the source;

```bash
adb shell content query --uri content://media/external/audio/media \
  --projection _id:_display_name:relative_path:is_pending:mime_type \
  --where "_display_name LIKE 'hmx%'"
adb shell ls -l /sdcard/Music/HMXInsta
```

3. failure case: an `audio/*` item the provider cannot read → `storage` or
   `unknown` in the history, no row, no crash.

### 12.2 Video → audio extraction — implemented but unreachable in M2

**Device result: NOT TESTED — no M2 entry point. Not claimed to work.**

`AudioSourcePolicy` decides whether an `AUDIO` task may extract: only
`video/mp4`, `video/3gpp`, `video/x-m4v` and `video/quicktime` are accepted;
`video/webm`, `video/x-matroska`, `video/avi`, images and unknown types are
refused with `unsupported`. `AudioExtractor` reports failure when the muxer
wrote zero samples, so a silent file is never published.

No M2 surface creates an `AUDIO` task from a video source — a video share
stays a video — so this path cannot be triggered from the share sheet in M2.
The procedure below applies once M3 adds an explicit "save as audio" action:

1. enqueue a video as `AUDIO`;
2. expected: `.m4a` in `Music/HMXInsta`, `relative_path=Music/HMXInsta/`,
   `is_pending=0`;
3. pull and probe:

```bash
adb shell content query --uri content://media/external/audio/media \
  --projection _id:_display_name:relative_path:is_pending --where "_display_name LIKE 'hmx%'"
adb pull /sdcard/Music/HMXInsta/<name>.m4a /tmp/opencode/audio-check.m4a
ffprobe -v error -show_streams /tmp/opencode/audio-check.m4a
```

4. failure case: a video with no audio track → `unsupported`, no row, no
   `.m4a` left behind, no crash;
5. failure case: a `.webm` video → `unsupported` without attempting a mux.

---

## 13. Evidence log (fill during the session)

| Field | Value |
| --- | --- |
| Date / tester | |
| Device model / serial | |
| ColorOS build | |
| `PKG` / versionName / versionCode | |
| Patch set (commit) | |
| Check 1 photo | NOT TESTED |
| Check 2 video | NOT TESTED |
| Check 3 duplicate | NOT TESTED |
| Check 4 cancel | NOT TESTED — no trigger (M3) |
| Check 5 deny notifications | NOT TESTED |
| Check 6 storage failure | NOT TESTED |
| Check 7 restart / process death | NOT TESTED |
| Check 8 Instagram unchanged | NOT TESTED |
| Permissions audit | NOT TESTED |
| Share-target registration | NOT TESTED |
| MediaStore `IS_PENDING` audit | NOT TESTED |
| History / duplicate audit | NOT TESTED |
| Notification progress audit | NOT TESTED |
| One-prompt-per-install (F1) | NOT TESTED |
| Foreground service lifecycle (F3) | NOT TESTED |
| Pending-work ledger replay (F3) | NOT TESTED |
| Direct audio share (F2) | NOT TESTED |
| Video → audio extraction (F2) | NOT TESTED — no M2 entry point |
| ClipData-only sender (F4) | NOT TESTED |
| Cache janitor bounds (F7) | NOT TESTED |

A check becomes PASS only when the observed output is pasted next to it (or
attached as a screenshot/log) and the tester signs the row.

---

## 14. Exit criteria for leaving draft

1. F1 — implemented and unit-tested; the device rows (one prompt per install,
   allow path, deny path) have evidence.
2. F2 — implemented and unit-tested; the direct-audio row has device evidence.
   Video→audio extraction stays explicitly out of the M2 claim set until M3
   adds an entry point.
3. F3 — implemented (foreground service + pending-work ledger); the device
   rows for the service lifecycle and the ledger replay have evidence, and the
   ColorOS / Android 15 limits stay documented.
4. F4 — implemented and unit-tested; the device row for a ClipData-only sender
   (or the actual Instagram share sheet) has evidence.
5. F5 — the code path is implemented and unit-tested; check 4 stays
   **NOT TESTED — no trigger** until M3 ships the cancel action. The M2 exit
   criterion is not blocked by it.
6. Checks 1-8 above have device evidence, with all eight PASS.
7. CI stays green after any of the above changes.
