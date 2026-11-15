# M12 rejection branch register

Every branch that can make the HMX download control absent, hidden or inert. IDs are stable and used by the M12 trace, the capture procedure and the final report.

Classification is assigned by the trace state machine:

| State | Meaning |
|---|---|
| `NEVER_ARMED` | the hook never ran; nothing was attempted |
| `ARMED_REFUSED` | the hook ran and refused before creating a control |
| `ARMED_INVISIBLE` | a control exists but is not visible to the user |
| `ATTACHED_VISIBLE` | a control is attached and visible |

## 1. Feed branches

| ID | Trace signal | Condition in source | Source | Classification |
|---|---|---|---|---|
| F-00 | no `m12 event surface=feed name=ARMED` | the in-context patch was not enabled in the patcher, or the tab-bar watcher never ran | patch `default = false`; `Settings.installHomeTabWatcher` | `NEVER_ARMED` |
| F-01 | `name=REFUSED reason=THREAD_OR_HOLDER` | holder is `null`, or the binder is not on the main thread | `DownloaderBinder.java:71` | `ARMED_REFUSED` |
| F-02 | `name=REFUSED reason=BOOKMARK_AMBIGUOUS` | two different bookmark candidates on one holder | `:86` | `ARMED_REFUSED` |
| F-03 | `name=REFUSED reason=BOOKMARK_MISSING` | no public `View` field reaches `row_feed_button_save` | `:97` | `ARMED_REFUSED` |
| F-04 | `name=REFUSED reason=PARENT` | bookmark is `null`, or its parent is not a `LinearLayout` | `:107` | `ARMED_REFUSED` |
| F-05 | `name=REFUSED reason=ORIENTATION` | parent is vertical, or resolved layout direction is not LTR | `:118` | `ARMED_REFUSED` |
| F-06 | `name=HIDDEN reason=SWITCH_OFF` | Downloader switch is off | `:162`, `:200` | `ARMED_INVISIBLE` |
| F-07 | `name=HIDDEN reason=ZERO_SIZE` | icon measured 0 in either dimension | `:205` | `ARMED_INVISIBLE` |
| F-08 | `name=HIDDEN reason=NOT_ATTACHED` | icon not in a window at post time | `:202` | `ARMED_INVISIBLE` |
| F-09 | `name=HIDDEN reason=NOT_SHOWN` | an ancestor is `GONE`, `INVISIBLE` or clipped | `:211` | `ARMED_INVISIBLE` |
| F-10 | `name=DETACHED reason=DETACHED` | row left the window (recycle or navigation) | `HmxDownloadIcon.onDetachedFromWindow` | `ARMED_INVISIBLE` |
| F-11 | `name=FAILED reason=EXCEPTION` / `INSTALL_EXCEPTION` / `PROBE_EXCEPTION` | a `Throwable` was swallowed by the fail-safe | `:186`, `:42` | `ARMED_REFUSED` |
| F-12 | `name=HIDDEN reason=GONE` | visibility set to `GONE` by something other than the switch | `:208` | `ARMED_INVISIBLE` |
| F-13 | no `HIDDEN`, no `VISIBLE` after `CREATED` | the post never ran, or the icon was removed with its row before it ran | `:166` | `ARMED_INVISIBLE` |

### Feed note on F-03/F-04

`Class.getFields()` returns public fields only, and it runs at binder entry. A holder that keeps the bookmark in a private field, or a bookmark assigned later in the binder body, produces exactly F-03/F-04. The trace distinguishes them:

- `BOOKMARK_MISSING` — no field reaches the id at all;
- `PARENT` with a non-null bookmark — the id was found but the parent type is wrong;
- `PARENT` with a null bookmark — `getParent()` returned `null` (detached view) or the bookmark was never found.

## 2. Reels branches

| ID | Trace signal | Condition in source | Source | Classification |
|---|---|---|---|---|
| R-00 | no `m12 event surface=reels name=ARMED` | the pager constructor was never reached, or the patch is off | `ReelsDownloadBinding.java:22` | `NEVER_ARMED` |
| R-01 | `name=REFUSED reason=PAGER_ATTACH_TIMEOUT` | pager view null or unattached after 30 × 100 ms | `:40` | `ARMED_REFUSED` |
| R-02 | same as R-01, with `PAGER_ATTEMPT` details showing `NOT_FOUND` | the pager is not a public field of type `ViewPager2`, or two different pager fields exist | `:74` | `ARMED_REFUSED` |
| R-03 | `name=REFUSED reason=GETTER_MISSING` | no no-arg accessor returns a type with exactly one public `Media` field | `:61` | `ARMED_REFUSED` |
| R-04 | `name=REFUSED reason=GETTER_AMBIGUOUS` | two or more such accessors | `:53` | `ARMED_REFUSED` |
| R-05 | `name=REFUSED reason=ANCHOR` | anchor null, not shown, or a different root | `:152` | `ARMED_REFUSED` |
| R-05b | `name=REFUSED reason=ANCHOR_TYPE` | anchor is not a container the adapter can lay out (M11 accepted only `FrameLayout`) | `:157` | `ARMED_REFUSED` |
| R-06 | `name=HIDDEN reason=NOT_IDLE` | pager scrolling or not shown | `:177` | `ARMED_INVISIBLE` |
| R-07 | `name=HIDDEN reason=NO_PAGE` | page accessor returned `null` while idle | `:178` | `ARMED_INVISIBLE` |
| R-08 | `name=HIDDEN reason=SWITCH_OFF` | Downloader switch is off | `:179` | `ARMED_INVISIBLE` |
| R-09 | `name=HIDDEN reason=SLOT` before any `SLOT_BLOCKER`, icon size 0 | icon not measured yet (first frames) | `:261` | `ARMED_INVISIBLE` |
| R-10 | `name=HIDDEN reason=SLOT`, `SLOT_BLOCKER` absent, icon sized | hit rect leaves the bar's padding box | `:263` | `ARMED_INVISIBLE` |
| R-11 | `name=HIDDEN reason=SLOT` + `name=SLOT_BLOCKER reason=<child index>` | hit rect intersects a visible sibling | `:273` | `ARMED_INVISIBLE` |
| R-12 | `name=DETACHED reason=DETACHED` | pager left its window | `:249` | `ARMED_INVISIBLE` |
| R-13 | `name=FAILED reason=DRAW_EXCEPTION` | `onPreDraw` threw; icon hidden | `:193` | `ARMED_INVISIBLE` |
| R-14 | `name=FAILED reason=REGISTER_EXCEPTION` | reflection threw during registration | `:70` | `ARMED_REFUSED` |

### Reels note on R-05 / R-05b — confirmed on device

The OPPO A38 capture shows `REFUSED reason=ANCHOR` on every pre-draw pass, with the anchor logged as:

```text
instagram.features.clips.viewer.actionbar.ClipsViewerActionBar id=clips_viewer_action_bar
attached=true shown=true visibility=0 size=720x112
```

`ClipsViewerActionBar` extends `android.widget.RelativeLayout` in the installed `454.0.0.54.77` build, so the M11 conjunct `anchor instanceof FrameLayout` was false on every pass. The exact branch is R-05's type check; R-05b is the code that names it after the fix. Full proof in `M12_DEVICE_EVIDENCE_ANALYSIS.md`.

### Reels note on R-11

`freeSlot` compares **hit rects of every visible sibling**. It does not look at the sibling's background, clickability, alpha or whether it is a wrapper. A transparent full-width child blocks the corner exactly like a real button. That is why `SLOT_BLOCKER` reports the child **index**, not just a class: the capture identifies the actual blocker on the device.

## 3. Tap-time and post-tap branches (not placement)

These do not explain an absent control. They are listed so a capture is not misread.

| ID | Signal | Meaning |
|---|---|---|
| T-01 | `name=HIDDEN reason=STALE_TAP` | the Reel changed between tap and validation |
| T-02 | `name=TAP_UNRESOLVED` | the bound model could not be read at tap |
| T-03 | `name=TAP` but no sheet | the sheet itself failed; it shows a Toast instead |
| T-04 | `ExplicitDownloadRequest` refused | switch off, no identity, expired story, Reels cover, carousel child not chosen |
| T-05 | confirmation refused | generation or switch changed while the sheet was open |
| T-06 | queue refused | duplicate, waiting limit, or storage failure |

T-03..T-06 are not instrumented in M12 beyond the existing Toast/queue behavior. They are out of scope for the missing-control diagnosis.

## 4. Classification decision tree

```text
Is there an ARMED event for the surface?
  no  -> NEVER_ARMED
  yes -> Is there a VISIBLE event with attached+visible?
           yes -> ATTACHED_VISIBLE
           no  -> Did a CREATED event ever happen?
                    yes -> ARMED_INVISIBLE (use the last HIDDEN reason)
                    no  -> ARMED_REFUSED (use the last REFUSED reason)
```

The `m12 summary` line prints exactly this derivation, so the classification does not have to be reconstructed by hand.

## 5. Branches that cannot be reached on a built APK

The patch throws at build time if the feed binder, the pager constructor or the settings watcher is missing or ambiguous. Therefore:

- there is no "patch applied but hook not inserted" state;
- F-00/R-00 mean the **patch was not enabled** or its insertion point never executed, not that the bytecode insertion failed.

## 6. Count

| Surface | Branches | Refusal | Invisible |
|---|---|---|---|
| Feed | 14 (F-00..F-13) | 6 | 8 |
| Reels | 16 (R-00..R-14 plus R-05b) | 7 | 9 |
| Cross-cutting | 6 (T-01..T-06) | — | — |

Every Feed and Reels branch now has a dedicated trace line, except F-13 (absence of lines is the signal) and the second cause of R-02 (distinguished by `PAGER_ATTEMPT`).
