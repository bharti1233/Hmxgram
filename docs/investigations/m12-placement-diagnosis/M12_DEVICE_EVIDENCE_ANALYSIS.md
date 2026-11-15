# M12 device evidence analysis — OPPO A38, Android 15

Analysed files, exactly as supplied:

| File | Lines | SHA-256 |
|---|---|---|
| `HMX_M12_FILTERED.txt` | 4037 | `2ff6246254e2c0eee0cc6c51cff598778b2f6094aa59bdec9ebcc029b16b1a95` |
| `HMX_M12_DIAGNOSTICS.txt` | 83482 | `e894f8f23ceb91c7855d2b9b23018c25c0e25a54e79f1f8ec77b030ce9ca110d` |

Device: OPPO A38, Android 15, 720×1612, `currentDensityDpi: 320` (density 2.0).
App: Instagram `454.0.0.54.77` (confirmed in the log, and identical to the project's pinned baseline APK).

## 0. Two corrections before the diagnosis

1. **`HMX_M12_FILTERED.txt` contains no `HmxNativeUI` line at all.** It is a general logcat excerpt (system, Termux, launcher, MediaTek noise). Every HMX line cited below comes from `HMX_M12_DIAGNOSTICS.txt`.
2. **The captured build is the M11.1 instrumentation, not the M12 shadow build.** Every HMX line uses the old schema `m11.1 stage=<name> count=<n> detail`. The M12 build emits `m12 event surface=... name=... reason=...` and `m12 summary ...`. The Reels anchor logic is byte-identical between the two builds, so the conclusion still holds, but this capture cannot contain the M12 four-state summaries or the `SLOT_POLICY`/`DECISION_POLICY` shadow lines. The next capture must be from the M12 build.

## 1. Exact source locations of the quoted stages

All four quoted lines come from the baseline (M11.1) `ReelsDownloadBinding`. The current M12 tree keeps the same control flow, with `note`/`refused` calls instead of raw `event` calls.

| Log stage | Source (baseline `ca16a54`) | Method | Control flow |
|---|---|---|---|
| `reels.hook` | `ReelsDownloadBinding.java:23` | `register(Object owner)` | first statement of the constructor hook, before the weak reference and the retry runnable |
| `reels.pager` | `:34` | `register` → posted `Runnable.run` | after `pagerView(owner)`, before the attach/retry check |
| `reels.listener.attached` | `:99` | `Binding.attach()` | after `scrollState`/`currentItem` resolution, before `onPreDraw` is registered |
| `reels.anchor` | `:121` | `Binding.onPreDraw()` | every pre-draw pass, after `root.findViewById(clips_viewer_action_bar)` |
| `reels.reject.anchor` | `:125` | `Binding.onPreDraw()` | immediately after the guard fails |

The guard itself, verbatim from the baseline (`:122`–`:125`):

```java
if (!(anchor instanceof FrameLayout) || !anchor.isShown()
        || anchor.getRootView() != pager.getRootView()) {
    hide();
    NativeUiTrace.event("reels.reject.anchor", anchor);
    return true;
}
```

The current M12 tree has the same three conditions, with `NativeUiTrace.refused(REELS, "ANCHOR", anchor)` and `hide("ANCHOR")`.

Feed stages (`feed.hook`, `feed.model`, `feed.bookmark`, `feed.created`, `feed.bound`, `feed.after-layout`, `feed.reject.*`) come from `DownloaderBinder.bindFeed`/`observeRow` in the same baseline. They are all present in the M11.1 build.

## 2. The exact Reels rejection branch

**Branch: R-05, sub-condition `ANCHOR_TYPE`. The anchor is not a `FrameLayout`.**

The log line is identical on all 13 sampled occurrences:

```text
stage=reels.anchor count=N instagram.features.clips.viewer.actionbar.ClipsViewerActionBar
  id=clips_viewer_action_bar attached=true shown=true visibility=0
  size=720x112 measured=720x112 alpha=1.0 enabled=true
  parent=com.instagram.ui.gesture.GestureManagerFrameLayout
  lp=android.widget.FrameLayout$LayoutParams:-1x112
stage=reels.reject.anchor count=N <same detail>
```

The guard is a short-circuit `||`. Only the first conjunct has to be false, and it is:

**Proof of the class hierarchy** (independent of the log's own fields):

```bash
unzip -o -q /tmp/opencode/m0/baseline-43091674732.apk classes5.dex
dexdump classes5.dex | grep -A3 "ClipsViewerActionBar;"
```

```text
  Class descriptor  : 'Linstagram/features/clips/viewer/actionbar/ClipsViewerActionBar;'
  Access flags      : 0x0011 (PUBLIC FINAL)
  Superclass        : 'Landroid/widget/RelativeLayout;'
```

`ClipsViewerActionBar extends android.widget.RelativeLayout`. It is **not** a `FrameLayout`, and it is `final`, so no subclass can change that. Therefore:

```java
!(anchor instanceof FrameLayout)   ->  true   ->  the whole guard is true
```

The other two conjuncts do not need to be evaluated, and the log agrees with them anyway (`attached=true`, `shown=true`, `visibility=0`, same window).

`lp=android.widget.FrameLayout$LayoutParams` is the bar's own params **inside its parent** (`GestureManagerFrameLayout`); it says nothing about the bar's superclass. That detail is exactly the trap the M11 code fell into.

### What this rules out

| Candidate | Verdict from the log |
|---|---|
| Free-slot evaluation | **Not reached.** `reels.reject.slot` count = 0. `freeSlot` runs after the anchor guard. |
| Child overlap / transparent sibling | **Not reached.** No `reels.slot.blocker`, no slot evaluation at all. |
| Icon not measured | **Not reached.** `reels.created` count = 0 — the icon was never created. |
| Pager state (idle/page) | **Not reached.** `reels.reject.not-idle` and `reels.reject.no-page` = 0. |
| Switch off | **Not reached as a cause.** `switch.on` at 11:00:54.731; anchor detail shows `enabled=true`. |
| Pager discovery | **Passed.** `reels.pager` and `reels.listener.attached` each appear once, with a measured, attached `ViewPager2` (`clips_viewer_view_pager`, 720×1364). |
| Getter discovery | **Passed.** `reels.listener.attached` is only emitted after a unique getter was found and `Binding.attach()` ran. |
| Anchor discovery | **Passed.** `reels.anchor` prints a non-null, attached, shown `ClipsViewerActionBar`. |

So every discovery step succeeded and the first placement check rejected the control on a **type assumption**, before any geometry was considered. This is `ARMED_REFUSED`, reason `ANCHOR`, and the register's R-05 branch. The specific sub-condition gets its own stable code in the fix: `ANCHOR_TYPE`.

## 3. Why the rejection repeats to count 2048

It is **not** a retry loop and **not** 2048 log lines.

`Binding.onPreDraw()` is a `ViewTreeObserver.OnPreDrawListener` registered once. Android calls it **once per draw pass** for as long as it is registered. In Reels the surface is continuously redrawing (video playback, overlays), so the listener runs continuously.

The number in the log is the sampler's counter, not the line count:

```java
// NativeUiTrace.event (M11.1)
int count = COUNTS.get(stage) + 1;
if (count > 3 && (count & (count - 1)) != 0) return;   // print 1,2,3,4,8,16,... only
```

That is why only 13 lines exist per stage while the count reaches 2048: 1, 2, 3, 4, 8, 16, 32, 64, 128, 256, 512, 1024, 2048.

Measured rate:

```text
count=1     11:02:02.675
count=2048  11:03:12.318
delta       69.643 s
rate        (2048 - 1) / 69.643 ≈ 29.4 evaluations per second
```

That is one pre-draw callback per rendered frame at roughly 30 fps — consistent with a low-power render rate, not with a busy retry.

There is **no unbounded retry scheduler**: the only scheduled retry in the file is the pager-attach loop (30 × 100 ms), and it finished successfully at 11:02:01.380. The per-frame evaluation is the intended lifecycle for a visibility decision.

What *is* wasteful, and was fixed:

1. `getResources().getIdentifier("clips_viewer_action_bar", ...)` ran on every draw pass. The resolved id is now cached per pager binding.
2. The rejection was re-logged with a generic code. It now has a specific one (`ANCHOR_TYPE`) and a bounded, once-per-geometry child dump.
3. Nothing else needs a retry limit: the work inside the listener is three checks and, only when the anchor is valid, a bounded slot test.

## 4. Feed diagnosis — not classified

There are **zero** `stage=feed.*` lines in the capture.

This is not because the build lacks Feed diagnostics: the M11.1 build emits `feed.hook`, `feed.model`, `feed.bookmark`, `feed.created`, `feed.bound`, `feed.after-layout` and four `feed.reject.*` stages, all from `DownloaderBinder`. So "Feed diagnostics are missing from the build" is **disproven**.

Two possibilities remain, and the log cannot separate them:

1. **The Feed was not visited (or no row bound) during this capture window.** The capture spans several process starts (switch events from pids 6379 and 9344; Reels from 10443; another session at 13540). `MainFeedRequestHeaderInitializer` runs, but that is app-init scheduling, not proof that feed rows were laid out and bound. Without `feed.hook`, no row binder ran.
2. **The Feed binder hook did not execute on this host**, i.e. the patched 4-parameter method is not the one Instagram used to bind the visible rows.

Evidence that would separate them, in the M12 build:

- `m12 event surface=feed name=ARMED` → `DownloaderBinder.install` ran.
- `m12 event surface=feed name=BINDER` → the patched binder was invoked.
- `m12 event surface=feed name=BOOKMARK_FOUND` / `REFUSED reason=...` → discovery outcome.

**Feed is therefore `NOT CLASSIFIED`.** No Feed change was made, by design, and no claim about Feed placement is made in this document.

## 5. What was implemented, and what was deliberately not

### Implemented (only the proven branch)

| File | Change |
|---|---|
| `core/PlacementPolicy.java` | new `Container {FRAME, RELATIVE, UNSUPPORTED}`, new `Reason.ANCHOR_TYPE`, `reels(bar, w, h, container)`; the 3-arg form delegates to `FRAME` |
| `core/PlacementPolicyTest.java` | two new groups: unknown container refused; `FRAME` and `RELATIVE` accepted, overlap still refused (20 groups total) |
| `android/PlacementProbe.java` | `container(View)` maps `FrameLayout`/`RelativeLayout` and refuses everything else; `reportReels` passes the kind |
| `android/ReelsDownloadBinding.java` | anchor accepts `ViewGroup`, refuses unsupported kinds as `ANCHOR_TYPE`, caches the action-bar id, inserts with `RelativeLayout.LayoutParams` (`ALIGN_PARENT_RIGHT` + `CENTER_VERTICAL`) for a RelativeLayout and the original gravity for a FrameLayout, dumps up to 16 bar children once per geometry change |
| `android/NativeUiTrace.java` | `describe` now includes `bounds=l,t,r,b`, so the next capture shows exactly what occupies the bar |

Why this is safe:

- It changes **which containers we accept**, not where Instagram's own views go.
- A `RelativeLayout` gets params it can actually honour. Passing `FrameLayout.LayoutParams` to it would let the framework convert the params and drop the gravity, silently moving the control — that is the "arbitrary insertion" failure mode, avoided explicitly.
- The existing `freeSlot` gate is unchanged and still runs before visibility. If the native buttons occupy the slot, the icon stays `INVISIBLE` and the log will say `HIDDEN reason=SLOT` with `SLOT_BLOCKER`. Worst case: no button, same as today; no broken host UI.
- No Instagram method, patch anchor, identity rule, confirmation rule, queue rule or Ghost Mode rule is touched. Download interception/observation remains passive.

### Not implemented, because it is not yet proven

- **Choosing between the top and centre slots using the policy.** The device proves the slot was never evaluated. Adopting the policy's wrapper pass-through before we know what occupies the bar would be a guess.
- **Adding a fallback position, a second icon, or a native-menu entry.** Not allowed by the phase restrictions, and it would mask the next diagnosis.
- **Any Feed change.** No evidence, no change.

## 6. Expected next capture

Install an APK built from this branch by the gated workflow, enable the in-context patch, turn the downloader on, and repeat the Reels steps. The expected new lines are:

```text
m12 event surface=reels name=ANCHOR reason=FOUND detail=...ClipsViewerActionBar...
m12 event surface=reels name=CREATED reason=NONE count=1 ...
m12 event surface=reels name=SLOT_LEGACY reason=AVAILABLE|BLOCKED ...
m12 event surface=reels name=SLOT_POLICY reason=PLACE:END_TOP|REFUSE:OVERLAP ...
m12 event surface=reels name=SLOT_AGREE reason=AGREE|DISAGREE ...
m12 event surface=reels name=BAR_CHILD_<i> reason=<i> detail=... bounds=l,t,r,b ...
m12 summary surface=reels state=ATTACHED_VISIBLE|ARMED_INVISIBLE reason=SLOT ...
```

If the result is `ATTACHED_VISIBLE`, verify visually on the device before claiming it works. If it is `ARMED_INVISIBLE reason=SLOT`, the `BAR_CHILD_*` bounds and `SLOT_POLICY` line identify the occupier, and the next decision (a different slot, or the policy's wrapper rule) is then evidence-based.

## 7. Limitations of this analysis

1. The capture is M11.1 instrumentation. It is sufficient to prove the anchor type branch, but it cannot show the M12 shadow comparison.
2. The root-view identity conjunct was not evaluated (short-circuit). The class-type conjunct alone is sufficient and proven.
3. The bar's child layout is not in the log, so the slot availability is unknown. This is now the only unknown left in the Reels chain.
4. The Feed evidence is absent, not negative. `NOT CLASSIFIED` is not `NEVER_ARMED`.
5. The device is `454.0.0.54.77`; the baseline exec dir is the same version, so the class hierarchy is exact for this device. A future Instagram version must be re-checked, not assumed.
