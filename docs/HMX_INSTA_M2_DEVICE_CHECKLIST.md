# HMX Insta — M2 device readiness checklist

**Status:** device verification **not performed**. No OPPO A38 / Android 15
device was attached to the build environment, so every device check below is
recorded as **NOT TESTED**. Nothing in this document may be promoted to PASS
without an observed result on a physical device, together with the device
serial and the installed `versionName` / `versionCode`.

**Scope:** the eight checks in
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
| 1 | Share a photo → `Pictures/HMXInsta`, notification, history | **NOT TESTED** | **FAIL** | F1: `POST_NOTIFICATIONS` is never requested, so no notification can appear on a fresh Android 13+ install |
| 2 | Share a reel/video → `Movies/HMXInsta` | **NOT TESTED** | PASS | Large videos run without a foreground service (F3) |
| 3 | Share the same item twice → duplicate, no second file | **NOT TESTED** | PASS | — |
| 4 | Cancel a large video → no row, temp gone | **NOT TESTED** | **FAIL** | F5: M2 has no user-visible cancel action, and cancellation is not checked during the MediaStore copy |
| 5 | Deny notifications → save still completes | **NOT TESTED** | PASS | This is the shipped default; it also means check 1 fails by default (F1) |
| 6 | Storage failure → `storage` error, no pending row | **NOT TESTED** | PASS | Abrupt process death can leave an `IS_PENDING` row (F6) |
| 7 | Restart → history reloads, duplicates still detected | **NOT TESTED** | PASS | In-flight work is lost and left unreported (F3) |
| 8 | Instagram login/signup/feed/session unchanged | **NOT TESTED** | PASS (gates) | Protected digest and 12 surfaces unchanged; still requires a device check |

Additional readiness checks requested for M2:

| Area | Device result | Static readiness | Notes |
| --- | --- | --- | --- |
| Android permissions | **NOT TESTED** | **FAIL** | Only `POST_NOTIFICATIONS` is added; it is declared but never requested (F1). No storage permission is requested, by design |
| MediaStore behaviour | **NOT TESTED** | PASS | `IS_PENDING` insert → copy → publish → delete-on-failure (F6 is the kill case) |
| Share-target registration | **NOT TESTED** | PASS | Exported activity with `SEND`/`SEND_MULTIPLE` for `image/*`, `video/*`; private provider |
| `content://` handling | **NOT TESTED** | PASS | Scheme, MIME, count and size validated; copy happens while the grant is alive |
| Worker lifecycle / retry | **NOT TESTED** | PASS with risk | In-process pool, 2 concurrent, 3 attempts; no foreground service and no process-death resumption (F3) |
| Cancellation | **NOT TESTED** | **FAIL** | No user-facing trigger; publish phase ignores the cancel flag (F5) |
| History persistence / duplicates | **NOT TESTED** | PASS | Terminal-only history, 100 entries, query stripped, seeded on next start |
| Notification behaviour / progress | **NOT TESTED** | **FAIL** | Permission gap (F1); progress aggregation otherwise sound |
| Audio extraction | **NOT TESTED** | **FAIL** | Unreachable from the share target: `ShareImporter` only creates PHOTO/VIDEO (F2) |

---

## 2. Findings to resolve before the draft leaves draft

### F1 — `POST_NOTIFICATIONS` is declared but never requested (blocking)

`DownloaderShareTargetPatch.kt` adds the permission, but no code calls
`requestPermissions(...)`; `DownloadNotifier.canNotify()` simply returns false
when the permission is missing (`DownloadNotifier.java:149-155`). On a fresh
Android 13+ install the permission is denied, so both the progress notification
(4101) and the result notification (4102) are silently skipped. The save still
works, which is why this is invisible in a gallery-only test.

Options: request the permission from `ShareTargetActivity` after a successful
enqueue (one-time rationale + `requestPermissions`, no Instagram UI), or accept
"no notification" and change the M2 exit criterion and check 1. The first
option matches the documented checklist.

### F2 — Audio extraction cannot be reached in M2 (blocking for check 8 claims)

`DownloadWorker` only extracts when `StoragePlan.isAudio()`
(`DownloadWorker.java:75`), which requires `DownloadKind.AUDIO`. `ShareImporter`
always builds `PHOTO` or `VIDEO` (`ShareImporter.java:97-99`), and M2 has no
other enqueue entry point. The class, its docs and the M2 scope table therefore
describe a path no user can trigger. Either add an explicit audio action (M3
territory) or move audio extraction out of the M2 scope and reword
`docs/HMX_INSTA_M2_ANDROID_SHELL.md`.

### F3 — No foreground service and no process-death restoration (risk, decision needed)

`HmxDownloader` uses a plain in-process `ExecutorService`. The only startup
restoration is `queue.seedSaved(repository)`, which seeds duplicate keys from
terminal history; queued/running tasks are neither persisted nor resumed. If
ColorOS reclaims the process during a long video:

* the download stops with no failure history entry and no notification;
* `cache/hmx-download-*.part` and `cache/hmx-share/*` are left behind;
* an `IS_PENDING` row inserted before the kill can stay pending until the
  platform cleans stale rows.

Decide whether M2 is "the app must stay foreground/unrestricted while saving"
(documented limitation) or M2 needs a foreground service with a
`dataSync`/`shortService` type. Android 15 enforces foreground-service types,
so this is a design change, not a one-line fix.

### F4 — `EXTRA_STREAM` only, no `ClipData` fallback (compatibility)

`ShareIntentParser` reads `EXTRA_STREAM` only
(`ShareIntentParser.java:56-63`). Senders that put the URI only in
`Intent.getClipData()` are rejected with "nothing to share". Instagram normally
uses `EXTRA_STREAM`; verify on the actual build and add a ClipData fallback if
the share sheet sends nothing.

### F5 — Cancellation has no trigger and no publish check (check 4 cannot run)

`HmxDownloader.cancel(...)` exists, but nothing in the M2 UI calls it. Even if
it did, `DownloadWorker` checks the cancel flag only while reading the source,
not during `MediaStoreWriter.publish(...)` (`DownloadWorker.java:84`), so a
cancel that lands during the gallery copy still publishes the file. Check 4 is
not performable as written.

### F6 — Abrupt kill can leave an `IS_PENDING` row

The `finally` block deletes the pending row on failure, but a `SIGKILL` skips
`finally`. Android's MediaStore eventually cleans stale pending rows; the check
"no pending row left" must distinguish a graceful failure (passes) from a
force-stop mid-write (may leave a row until cleanup).

### F7 — Low-severity notes

* `MediaItem` accepts `http://`, but the patched app blocks cleartext by
  default; an `http` CDN URL becomes a network failure. Instagram uses HTTPS.
* History stores `relativeDirectory + "/" + fileName` on Android 10+ and an
  absolute path on older versions; the field is display-only.
* The exported share activity has no caller permission. Validation bounds the
  damage (10 items, 512 MiB each, image/video only), but any app can make the
  device copy up to ~5 GiB into cache.
* Rotating the invisible activity re-runs the import; the content hash makes
  the second enqueue a duplicate, so no double save, only wasted I/O.
* A permanently failed task keeps its share cache copy for a manual retry that
  M2 cannot trigger; the cache is reclaimed by the system eventually.

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

**Current static expectation: FAIL (F1).** On a fresh install the notification
is absent. Grant it manually to test the rendering path, and record both states:

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

**Blocked in M2 (F5).** There is no cancel button, notification action or
settings entry. Do not fake a PASS with `force-stop`; that is process death,
covered by check 7.

When a cancel trigger exists (M2.1/M3), the procedure is:

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

```bash
adb shell pm revoke "$PKG" android.permission.POST_NOTIFICATIONS
adb shell cmd appops set "$PKG" POST_NOTIFICATION ignore
```

Share a photo. Expected: no notification, save still completes, toast still
shown, history entry written. Restore with:

```bash
adb shell cmd appops set "$PKG" POST_NOTIFICATION allow
```

On a fresh install this is the default state, so it runs before check 1 unless
the permission is granted first.

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

**Process death during a download**

```bash
# while a large video is downloading
adb shell am kill "$PKG"        # background kill; may be a no-op if foreground
adb shell am force-stop "$PKG"  # hard kill, mimics ColorOS reclaim
```

Expected per current code (record actual):

* no crash loop on next launch;
* in-flight item is not resumed and has no `FAILED` history entry (F3);
* leftover files to inspect and then clean:

```bash
adb shell run-as "$PKG" ls -l cache        # debuggable build
adb shell content query --uri content://media/external/video/media \
  --projection _id:_display_name:is_pending --where "is_pending=1"
```

Do not mark this check PASS if the item silently disappears without a record;
that is the F3 decision, not a device glitch.

---

## 11. Check 8 — Instagram unchanged, plus registration and permissions

Registration and permission audit (no sharing needed):

```bash
adb shell cmd package query-activities --brief \
  -a android.intent.action.SEND -t image/jpeg
adb shell cmd package query-activities --brief \
  -a android.intent.action.SEND_MULTIPLE -t video/mp4

adb shell dumpsys package "$PKG" | sed -n '/ShareTargetActivity/,/^$/p'
adb shell dumpsys package "$PKG" | sed -n '/ShareCacheProvider/,/^$/p'
```

Expected:

* activity `…ShareTargetActivity`, `exported=true`, two intent filters, theme
  translucent, `excludeFromRecents=true`;
* provider `…ShareCacheProvider`, `exported=false`, authority `<PKG>.hmxshare`;
* the activity appears in the Instagram share sheet for a photo and a video.

```bash
adb shell dumpsys package "$PKG" | grep -E "POST_NOTIFICATIONS|EXTERNAL_STORAGE"
adb shell pm check-permission "$PKG" android.permission.POST_NOTIFICATIONS
```

Expected: `POST_NOTIFICATIONS` declared, no `READ/WRITE/MANAGE_EXTERNAL_STORAGE`.

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

## 12. Audio extraction (check requested separately)

**Device result: NOT TESTED — unreachable in M2 (F2).**

The share target maps `video/*` to `DownloadKind.VIDEO`, so
`DownloadWorker` never takes the `plan.isAudio()` branch and `AudioExtractor` is
never invoked. A device test cannot exercise it without a test-only entry point.

If an audio action is added, the procedure is:

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
   `.m4a` left behind, no crash.

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
| Check 4 cancel | NOT TESTED |
| Check 5 deny notifications | NOT TESTED |
| Check 6 storage failure | NOT TESTED |
| Check 7 restart / process death | NOT TESTED |
| Check 8 Instagram unchanged | NOT TESTED |
| Permissions audit | NOT TESTED |
| Share-target registration | NOT TESTED |
| MediaStore `IS_PENDING` audit | NOT TESTED |
| History / duplicate audit | NOT TESTED |
| Notification progress audit | NOT TESTED |
| Audio extraction | NOT TESTED |

A check becomes PASS only when the observed output is pasted next to it (or
attached as a screenshot/log) and the tester signs the row.

---

## 14. Exit criteria for leaving draft

1. F1 resolved or the M2 exit criterion reworded (notification is optional).
2. F2 resolved or audio extraction moved out of M2 scope.
3. F3 decided: documented "keep the app foreground" limitation, or a
   foreground service added and re-reviewed for Android 15 service types.
4. F4 verified against the actual Instagram share sheet (ClipData fallback
   added only if the share sheet sends no `EXTRA_STREAM`).
5. F5 gives cancellation a trigger and checks the flag during publish — or
   check 4 is removed from the M2 gate and moved to M3.
6. Checks 1-8 above have device evidence, with all eight PASS.
7. CI stays green after any of the above changes.
