# M12 Phase 3 — device capture procedure

Target device: OPPO A38, Android 15. Target build: an HMX Insta+ APK produced by the gated GitHub Actions workflow **from the M12 commit**. The M11.1 build does not contain the M12 events.

> **Status: partially performed.** A Reels capture from an M11.1 build was supplied and analysed in `M12_DEVICE_EVIDENCE_ANALYSIS.md`; it identified `ANCHOR_TYPE` exactly. That capture used the old `m11.1` schema, so the M12 summary and shadow lines in this procedure have not been seen yet. Feed was not captured at all.

## 1. Preconditions

1. The M12 instrumented APK is built by `.github/workflows/apk.yml` and installed on the device.
2. The **In-context download actions** patch is enabled in the patcher. The patch is default-off; if it was not selected, the capture will show `NEVER_ARMED` and prove nothing about placement.
3. The HMX Downloader switch is **on** (HMX Insta settings → Downloads).
4. USB debugging is enabled, and `adb devices` lists the phone as `device` (not `unauthorized`).
5. No other app is writing enough log volume to rotate the buffer during the run.

## 2. Identify the package

```bash
adb shell pm list packages | grep -i -E 'insta|feur|hmx'
```

Record the package that was opened. Do not assume it: HMX can be installed as a clone.

## 3. Pre-flight record

Fill these in before capturing. They belong in the evidence file.

```text
device model        : CPH2579 / OPPO A38 (confirm with: adb shell getprop ro.product.model)
android release     : 15 (confirm with: adb shell getprop ro.build.version.release)
build fingerprint   : adb shell getprop ro.build.fingerprint
hmx version         : <settings screen>
instagram version   : <settings → about>
apk commit          : <the M12 commit the workflow built>
patch enabled       : yes / no
downloader switch   : on / off
locale              : <language and region>
display density     : adb shell wm density
screen size         : adb shell wm size
```

## 4. Capture

Run one continuous capture for the whole scenario, including the app restart.

```bash
# 1. clear the buffer so the file starts at the scenario
adb logcat -c

# 2. start the capture in a background shell on the workstation
adb logcat -v threadtime -s HmxNativeUI:V > m12-capture.txt &

# 3. mark the start
adb shell log -t HmxNativeUI "M12 STEP 0 START"
```

If `adb shell log` is unavailable on the OEM build, write the step number and the wall-clock time in the evidence file instead; the logcat lines still carry timestamps.

### Scenario

| Step | Action | Marker |
|---|---|---|
| 1 | Open Instagram and land on the Feed. Wait for the first post to settle. | `M12 STEP 1 FEED_OPEN` |
| 2 | Scroll slowly through at least **five** posts. Pause on each one for two seconds. | `M12 STEP 2 FEED_SCROLL` |
| 3 | Open Reels. Wait for the first Reel to settle. | `M12 STEP 3 REELS_OPEN` |
| 4 | Watch at least **five** Reels. Pause on each. | `M12 STEP 4 REELS_WATCH` |
| 5 | Switch rapidly between Reels for about 15 seconds. | `M12 STEP 5 REELS_RAPID` |
| 6 | Return to the Feed and scroll three posts. | `M12 STEP 6 BACK_TO_FEED` |
| 7 | Force-stop the app, relaunch, and repeat steps 1–4. | `M12 STEP 7 RESTART` |

```bash
adb shell am force-stop <package>
# relaunch from the launcher, then repeat
adb shell log -t HmxNativeUI "M12 STEP 7 RESTART"
```

### Stop

```bash
adb shell log -t HmxNativeUI "M12 STEP 99 END"
# stop the background adb logcat (Ctrl-C, or: kill %1)
```

Keep `m12-capture.txt` unchanged. Copy it, then filter the copy.

## 5. Extraction

```bash
# summaries only: one line per state change
grep 'm12 summary' m12-capture.txt

# every refusal and hide, with the reason
grep -E 'name=(REFUSED|HIDDEN|FAILED)' m12-capture.txt

# the Feed lifecycle
grep 'surface=feed' m12-capture.txt | grep -E 'ARMED|BINDER|MEDIA_MODEL|BOOKMARK|PARENT|DECISION|CREATED|ATTACH_RESULT|HIDDEN|VISIBLE'

# the Reels lifecycle
grep 'surface=reels' m12-capture.txt | grep -E 'ARMED|PAGER|GETTER|ANCHOR|PAGE|CREATED|SLOT|HIDDEN|VISIBLE|DETACHED'

# the shadow comparison
grep -E 'DECISION_AGREE|SLOT_AGREE|DISAGREE' m12-capture.txt

# the blocker
grep 'SLOT_BLOCKER' m12-capture.txt
```

## 6. Classification

Classify each surface **once per step**, using the decision tree:

```text
no ARMED line for the surface                 -> NEVER_ARMED
ARMED, never CREATED, last line REFUSED       -> ARMED_REFUSED
ARMED, CREATED, no VISIBLE, last line HIDDEN  -> ARMED_INVISIBLE
ARMED, VISIBLE with attached=1 and shown      -> ATTACHED_VISIBLE
```

Fill a table like this for the capture:

| Step | Feed state | Feed reason | Reels state | Reels reason |
|---|---|---|---|---|
| 1 FEED_OPEN | | | | |
| 2 FEED_SCROLL | | | | |
| 3 REELS_OPEN | | | | |
| 4 REELS_WATCH | | | | |
| 5 REELS_RAPID | | | | |
| 6 BACK_TO_FEED | | | | |
| 7 RESTART | | | | |

Rules:

1. A step may contain more than one state. Record the **dominant** state and note exceptions.
2. `ARMED_INVISIBLE` with reason `NOT_IDLE` during rapid switching is expected and is not a defect.
3. `ARMED_INVISIBLE` with reason `SLOT` while the Reel is idle is the interesting case. Pair it with `SLOT_BLOCKER`.
4. A `DISAGREE` line is a finding, not an error. Record the policy reason beside the legacy reason.

## 7. What each result would mean

| Observation | Conclusion |
|---|---|
| No `ARMED` on either surface | patch not enabled, or the insertion point never ran. Check the patch selection first. |
| Feed `BOOKMARK_MISSING` | the holder does not expose the bookmark through a public field (F-03) |
| Feed `PARENT` with a non-null bookmark | the bookmark's parent is not a `LinearLayout` (F-04) |
| Feed `ORIENTATION` | vertical or non-LTR row (F-05) |
| Feed `CREATED` then `HIDDEN/ZERO_SIZE` | the icon is laid out at zero size (F-07) |
| Feed `CREATED` then `HIDDEN/NOT_SHOWN` | an ancestor hides it (F-09) |
| Reels `PAGER_ATTACH_TIMEOUT` with `PAGER_ATTEMPT NOT_FOUND` | the pager is not a public `ViewPager2` field (R-02) |
| Reels `GETTER_MISSING` | the page model does not expose a single public `Media` field (R-03) |
| Reels `GETTER_AMBIGUOUS` | more than one page accessor matches (R-04) |
| Reels `ANCHOR` | the action bar is missing, hidden, not a `FrameLayout`, or on another root (R-05) |
| Reels `SLOT` with `SLOT_BLOCKER` | a visible sibling blocks the corner; the index names it (R-11) |
| Reels `SLOT` without a blocker | the slot is outside the padding box or the icon is unmeasured (R-09/R-10) |
| `DISAGREE` on `SLOT` and policy `PLACE` | the legacy blocker is likely a transparent wrapper — the R1 hypothesis |
| `ATTACHED_VISIBLE` on both | M12 cannot reproduce the absence; the next step is a layout probe, not a placement change |

## 8. Evidence file template

Save the capture and this header together. Do not publish the raw buffer unfiltered: it can contain other apps' lines even when filtered later.

```text
M12 DEVICE EVIDENCE
captured by        :
capture date/time  :
device             :
android            :
hmx build commit   :
apk source         : GitHub Actions run <id>
patch enabled      : yes/no
downloader switch  : on/off
capture file       : m12-capture.txt (sha256: ...)
step table         : <the filled table>
notable lines      : <quoted lines, tag only>
classification     :
  feed             : NEVER_ARMED | ARMED_REFUSED(reason) | ARMED_INVISIBLE(reason) | ATTACHED_VISIBLE
  reels            : NEVER_ARMED | ARMED_REFUSED(reason) | ARMED_INVISIBLE(reason) | ATTACHED_VISIBLE
device screenshots : <file names>
```

A screenshot alone is **not** sufficient evidence for M12. The classification must come from the trace; the screenshot only confirms what the user saw.

## 9. If logcat is empty

Some ColorOS builds restrict third-party log access. In that order:

1. Confirm the tag exists at all: open the HMX settings screen and look for any `m11.1` line.
2. Try the buffer explicitly: `adb logcat -b main -b system -s HmxNativeUI:V`.
3. Try by PID: `adb shell pidof <package>`, then `adb logcat --pid=<pid> -s HmxNativeUI:V`.
4. Confirm the device is not in a "no logs" developer setting.
5. If none works, record it as an instrumentation-access limitation, not as `NEVER_ARMED`. An empty log is not evidence that the hook did not run.
