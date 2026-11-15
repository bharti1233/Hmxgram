# M12 final report — Feed and Reels placement diagnosis

Baseline: `ca16a54a7131e599b5267e7260e1e9e1f21ac345`, branch `feature/hmx-downloader-ghost-mode-v2`.

> **Addendum — device evidence received.** Two OPPO A38 / Android 15 captures were analysed in
> [M12_DEVICE_EVIDENCE_ANALYSIS.md](M12_DEVICE_EVIDENCE_ANALYSIS.md). They confirm the exact Reels
> branch as **R-05 `ANCHOR_TYPE`**: `ClipsViewerActionBar` extends `RelativeLayout`, so M11's
> `anchor instanceof FrameLayout` was false on every pass. Feed remains unclassified. Sections 2, 4,
> 5 and 9 below are updated accordingly; the original reasoning is kept so the trail stays honest.

## 1. Executive summary

M12 set out to answer one question: **why is the HMX download control missing or mispositioned in Feed and Reels?**

The answer this phase can defend is narrower than a bug report, and it is stated that way on purpose:

1. **The absence cannot be a failed patch insertion.** Every insertion point is asserted at patch time; a shape change throws and fails the build. The control's absence is therefore a runtime decision.
2. **Every runtime decision that can produce absence is now enumerated and instrumented.** 14 Feed branches, 16 Reels branches (R-05b included) and 6 tap-time branches; each has a stable ID, a trace signal and a classification state.
3. **The source review identifies four concrete, testable hypotheses** (H1/H2 for Feed, R1/R2 for Reels), each with a specific trace line that would confirm or refute it.
4. **The pure placement policy is written and proven by tests**, and is running in shadow mode beside the real decision. It changes no placement.
5. **No branch is confirmed on a device.** No device log was received, so the report does not claim one.

The decisive next action is not a code change. It is the documented capture: build the M12 APK through the gated workflow, run the seven-step scenario, and read the `m12 summary` lines. Until then, any placement edit would be a guess.

## 2. Evidence status

| Evidence class | Status | What it supports |
|---|---|---|
| Source audit of the reviewed M11 code | **Complete** | the branch register, the hypotheses, the policy contract |
| Pure-JVM policy tests (20 groups) | **Pass** | the policy is total, deterministic and fail-closed |
| Downloader/ghost/queue regression tests | **Pass** | M12 changed no identity, confirmation or queue rule |
| Offline repository, feature, isolation gates | **Pass** | no protected file or patch source changed |
| Protected-method self-test + manifest pin | **Pass** | the verifier still rejects tampering; 12 surfaces pinned |
| Full extension type-check (`javac` + `android.jar`) | **Pass** | the M12 sources compile with `-Werror` |
| Instrumented APK | **Not built** | no APK exists from this phase |
| APK signature / package / version / patch report / ZIP | **Not run** | nothing to verify |
| Device logcat on OPPO A38 / Android 15 (Reels) | **Performed** (M11.1 build) | exact branch `ANCHOR_TYPE`, proven by log + class hierarchy |
| Device logcat on OPPO A38 / Android 15 (Feed) | **Not captured** | Feed remains `NOT CLASSIFIED` |
| Device test of the fix | **Not performed** | no APK from this branch has run on a phone |
| Screenshots | **Inherited from M11.1 only** | they prove absence, not cause |

No statement below is presented as device-verified. Where a likely cause is named, it is labelled as a hypothesis with the evidence that would settle it.

## 3. What the source already proves

### 3.1 The patch is not silent

`InContextDownloadPatch` fails the build when its anchors are missing or ambiguous:

| Anchor | Rule | Failure mode |
|---|---|---|
| Feed binder | non-static `void`, 4 parameters, wire string `itas-android`, holder with ≥2 bouncy buttons, model with one `Media` field | `PatchException` |
| Feed call | `bindFeed` at instruction index **0** | none at build time |
| Reels constructor | single `<init>` of the `ClipsViewerAdapter` class | `PatchException` |
| Reels call | `register(this)` before the single `RETURN_VOID` | none at build time |
| Settings watcher | `installHomeTabWatcher(ViewGroup)` | `PatchException` |

Consequence: a device showing no control is showing a **runtime refusal or a runtime invisibility**, not a partially applied patch. `NEVER_ARMED` means the in-context patch was not enabled in the patcher, or the anchor method never executed.

### 3.2 Feed discovery is a public-field contract evaluated too early

Two independent structural facts, both visible in source:

- `Class.getFields()` returns **public** fields only. A holder that stores the bookmark in a private field, or behind a generated binding accessor, is invisible.
- The hook runs at **instruction index 0** of the binder. Nothing in the binder body has executed. On a recycled holder, every field read is the *previous* item's state; on first bind, some fields may still be `null`.

The icon is inserted at `row.indexOfChild(bookmark)` with `weight = 0` and `width = 40dp`. That satisfies "immediately left of the bookmark" only if the parent is an ordered horizontal container — which is why the code checks `LinearLayout` and `HORIZONTAL` and `LTR`.

### 3.3 Reels placement cannot tell a control from a wrapper

`freeSlot` refuses when the icon's hit rect intersects the hit rect of **any visible sibling**, without considering background, clickability, alpha or type. A transparent full-width child — a scrim, a spacer, a gradient, a measurement wrapper — blocks the upper-right corner even when the corner looks empty.

`freeSlot` also requires the icon to be measured (`width > 0 && height > 0`), so the first frames after creation necessarily fail, which is why the icon starts `INVISIBLE`.

### 3.4 Reels discovery is narrow by construction

- The pager must be a **public field** whose type name is exactly `androidx.viewpager2.widget.ViewPager2`.
- The page model must be reachable through a **no-arg method** on the pager owner whose return type has **exactly one** non-static `Media` field.
- The window is **30 × 100 ms** after the adapter constructor.

Any of these failing produces refusal, not a wrong placement.

### 3.5 Identity is not the problem

| Guarantee | Where it is enforced |
|---|---|
| Per-control model | `HmxDownloadIcon.bindModel` |
| Stale-holder rejection | `BindingGeneration` invalidated on rebind and detach |
| Feed confirmation | same icon, same generation, attached and shown |
| Reels confirmation | same pager, page, index, idle state, generation |
| Request validation | `ExplicitDownloadRequest.evaluateBound` |
| Confirm-time revalidation | `InContextDownloadSheet` |
| Duplicate prevention | `MediaNaming.duplicateKey`, queue duplicate status, saved history |

M12 changed none of these, and the regression suites for all of them still pass. A missing control cannot be explained by identity logic, because identity logic runs *after* a control exists and is tapped.

## 4. The four hypotheses, ranked

Each hypothesis is stated with the trace line that would confirm it and the line that would refute it. "Confirm" here means "the device log shows this branch firing on the surface where the control was absent".

### H1 — Feed: the bookmark is unreachable through public holder fields (highest prior)

**Claim.** On the current host, the bookmark id `row_feed_button_save` is not reachable from any public `View` field of the holder at binder entry, so `BOOKMARK_MISSING` (F-03) fires on every row.

**Why plausible.** The holder is a generated binding class; Instagram has moved fields between public/private and between binding accessors across releases. The hook is at index 0, so a field assigned *by* the binder body is still `null` even if it is public.

**Confirms:** `surface=feed name=REFUSED reason=BOOKMARK_MISSING` repeating across rows and posts.
**Refutes:** `BOOKMARK_FOUND` on the same rows, with the control only failing later (then H2 applies).

### H2 — Feed: the bookmark is found, but the row fails the parent/orientation contract, or the icon is laid out at zero size

**Claim.** The bookmark resolves, but `parent instanceof LinearLayout` fails (F-04), or orientation/direction fails (F-05), or the icon ends up `ZERO_SIZE`/`NOT_SHOWN` after layout (F-07/F-09).

**Why plausible.** Modern rows are frequently `ConstraintLayout`; a `LinearLayout`-only check is a class check, not a capability check. And reading params at index 0 can inherit a `0`-height or `WRAP_CONTENT` shape from the previous binding.

**Confirms:** `REFUSED reason=PARENT` or `ORIENTATION` (with `PARENT` detail showing the real parent type), or `CREATED` followed by `HIDDEN reason=ZERO_SIZE`/`NOT_SHOWN`.
**Refutes:** `ATTACHED_VISIBLE` on the same rows.

### R1 — Reels: a transparent sibling blocks the slot permanently

**Claim.** `clips_viewer_action_bar` exists and is found, but a visible, non-interactive child (scrim, spacer, gradient, wrapper) overlaps the upper-right and centre slots, so `freeSlot` returns false on every idle frame (R-11).

**Why plausible.** The rule is a blanket hit-rect collision with no notion of decoration; the source review found no exemption for wrappers. The icon is created and stays `INVISIBLE`, which matches "present in code, absent on screen".

**Confirms:** `HIDDEN reason=SLOT` + `SLOT_BLOCKER reason=<child index>` while idle, and the policy line `SLOT_POLICY reason=PLACE:...` on the same frame → `SLOT_AGREE reason=DISAGREE`.
**Refutes:** `SLOT_BLOCKER` never appears, and the reason is `ANCHOR`/`PAGER_*`/`GETTER_*` instead.

### R2 — Reels: discovery never completes

**Claim.** The pager is not a public `ViewPager2` field, the getter is missing or ambiguous, or the pager does not attach within 3 s. The control is never created (R-01..R-04).

**Why plausible.** The field type is matched by exact string, and the getter must be unique by return shape. A host refactor breaks either without breaking the patch anchor.

**Confirms:** `REFUSED reason=PAGER_ATTACH_TIMEOUT` with `PAGER_ATTEMPT reason=NOT_FOUND`, or `GETTER_MISSING`/`GETTER_AMBIGUOUS`.
**Refutes:** `PAGER_FOUND` + `LISTENER_ATTACHED`.

### Confirmed branch (device evidence)

**R-05, sub-condition `ANCHOR_TYPE`.** The anchor is `instagram.features.clips.viewer.actionbar.ClipsViewerActionBar`, which extends `android.widget.RelativeLayout` in the installed `454.0.0.54.77` build. The M11 guard `!(anchor instanceof FrameLayout)` was therefore true on every pre-draw pass, before the slot, page, idle or switch checks. `freeSlot` never ran, no icon was created, and the rejection repeated once per rendered frame (~29.4/s). The `2048` in the log is the sampler's power-of-two counter, not a retry count.

This branch was in the register as R-05, but it was not in the four ranked hypotheses. That is the value of the capture: the code's own strictest assumption — a `FrameLayout` cast — was the failure, and no amount of source-only ranking would have made it the top candidate.

### Prior ranking (kept for the record)

Given only source evidence, the ranking is H1 ≈ R1 > H2 > R2. H1 and R1 share the property that the code path is complete and reachable but the *host shape* contradicts an assumption; H2 and R2 require a host change that is also plausible but less directly implied by the code's own strictness. **This ranking is a prior, not a finding.** The capture exists to overturn it.

## 5. Recommended fixes, in order

Nothing in this section is implemented in M12. Each item states the trigger that justifies it.

### P0 — Accept the container the host actually uses (implemented, unverified on device)

**What.** A `Container` kind (`FRAME`, `RELATIVE`, `UNSUPPORTED`) in the pure policy, `ANCHOR_TYPE` as a named refusal, and container-specific params: `Gravity.END | CENTER_VERTICAL` for a `FrameLayout`, `ALIGN_PARENT_RIGHT | CENTER_VERTICAL` for a `RelativeLayout`. An unknown container is refused, never guessed.

**Trigger.** The device capture above. **Status:** implemented and unit-tested; **not** device-verified, and no claim is made that the icon is visible until a capture shows `ATTACHED_VISIBLE` and a screenshot confirms it.

**Why it cannot break the host.** The control is our own view in a bar we already target; `freeSlot` still decides visibility, so a blocked slot means no icon, not a moved native button.

### P1 — Adopt the pure placement policy for Reels, after one capture confirms R1

**What.** Replace the blanket `freeSlot` sibling test with `PlacementPolicy.reels`: two candidate anchors, padding-box check, flattened blockers with wrapper pass-through.

**Trigger.** A capture showing `SLOT_AGREE reason=DISAGREE` where the policy places and the legacy code refuses, paired with a `SLOT_BLOCKER` whose type is a plain container.

**Why it is safe.** The policy is stricter in the places that matter (padding box, explicit anchor order) and looser only in the one place proven wrong by the capture (a wrapper's own rectangle). Hidden and alpha-0 subtrees remain excluded, and every visible descendant still blocks.

**Risk.** The policy would show the icon in a corner the host might paint over with a scrim that is *visible* but has no background and no clickability. The capture must show the blocker is structurally undecorated; otherwise the flattening rule is wrong and the fix is a new slot, not pass-through.

### P2 — Move the Feed hook to a post-bind point, or re-resolve the row after layout

**What.** Two candidate shapes, both requiring a reviewed patch change:

1. insert `bindFeed` at the binder's `RETURN` instead of index 0, so fields are assigned; or
2. keep index 0 but re-resolve the bookmark and row in the existing `post`, and insert there when the first attempt refused.

**Trigger.** A capture showing `BOOKMARK_MISSING` or `PARENT` at binder entry while the same row's child dump shows the bookmark present.

**Why it is not done now.** It changes a byte-identical reviewed patch anchor and the hook's timing contract. It needs its own review, its own exact-diff test, and a second capture.

**Risk.** Inserting at `RETURN` may run after Instagram's own layout pass, so the icon can appear one frame late; re-resolving in `post` can race recycling. Both must keep the generation check that already protects confirmation.

### P3 — Accept any ordered horizontal parent, not only `LinearLayout`

**What.** When the parent is a `ViewGroup` that resolves left-to-right and lays children out in index order (a horizontal `LinearLayout` or an equivalent), insert at the bookmark index with matching params; otherwise refuse with a named reason.

**Trigger.** `REFUSED reason=PARENT` with a non-null bookmark whose parent is a non-`LinearLayout` that still positions children by index.

**Why it is deferred.** "Equivalent" cannot be proven by class name. A `ConstraintLayout` is *not* equivalent, and inserting into one by index would misposition the icon — the exact failure the current check prevents. A safe version needs a declared capability, not a heuristic.

### P4 — Add a placement ledger, so absence is diagnosable without logcat

**What.** Keep a bounded, in-memory count per surface and reason (`feed.BOOKMARK_MISSING=12`, `reels.SLOT=40`) and show it in the HMX downloader screen's diagnostics section.

**Trigger.** None needed; this is the durable fix for the *diagnosis* problem, not the placement problem.

**Why.** The M12 trace requires a workstation and `adb`. A user on a locked-down ColorOS build may not be able to provide logs at all. A ledger turns "it does not show" into a reason code the user can read.

**Constraint.** Aggregate counts only. No view references, no model references, no URLs, no account data, no timestamps per row.

### P5 — Do not add a native-menu fallback yet

The phase restriction stands. A fallback would mask the placement failure and make the next diagnosis harder. Revisit only after the capture, and only as an explicit product decision.

## 6. What M12 changed, precisely

| File | Change | Placement effect |
|---|---|---|
| `downloader/android/NativeUiTrace.java` | structured events, four-state summary, bounded sampling; legacy `event`/`children` preserved | none |
| `downloader/android/PlacementProbe.java` | **new**; view → policy node, shadow decision, geometry signature | none |
| `downloader/DownloaderBinder.java` | trace calls; attachment post now also reports the shadow decision | none: same checks, same order, same index, same params |
| `downloader/android/ReelsDownloadBinding.java` | trace calls; `freeSlot` result hoisted into a local under the identical short-circuit | none: same visibility expression |
| `downloader/core/PlacementPolicy.java` | **new**; pure decision, shadow use only | none |
| `downloader/core/MediaModelReader.java` | `isMediaModel(Object)`, a class-name check | none |
| `src/test/.../PlacementPolicyTest.java` | **new**; 18 groups, 20 after the device fix | none |
| `scripts/ci/test_downloader_logic.sh` | runs the new test | none |
| `scripts/ci/test_downloader_isolation.sh` | allowlist entry for the M12 docs | none |

The reviewed patch file `InContextDownloadPatch.kt` is **byte-identical**, and `m11_reviewed_sources.sha256` still matches. No Instagram method was modified.

## 7. Limitations

1. **No device evidence.** The central question — which branch fires on the OPPO A38 — is unanswered. Every causal claim is a hypothesis.
2. **The instrumentation is not free.** `bindFeed` now does reflection and trace work at binder entry. It cannot change the decision, but it can shift frame timing. The capture compares *states*, not frame times, so this does not invalidate the classification; it does mean a performance claim would need separate measurement.
3. **Feed visibility is sampled once per bind.** A control hidden later without a rebind is not re-sampled.
4. **Reels probing is idle-only and signature-gated.** A slot blocked only during scrolling is not measured — but the control is not shown then either.
5. **The blocker rule is an inference.** `PlacementProbe` decides "wrapper vs control" from clickability, background presence and type. A transparent `View` subclass that is clickable would be treated as a blocker; a decorated wrapper that is not clickable would be treated as transparent. The capture's `SLOT_BLOCKER` index is what resolves this.
6. **Two coverage gaps remain.** Reels pager/getter discovery and Feed holder-field discovery are Android-bound reflection and have no JVM test. `test_native_feed_hook.py` proves the patch anchor, not the holder shape.
7. **The type-check is not a build.** `javac` + `android.jar` proves the sources compile; it does not run the Morphe plugin, resource linking, dexing, signing or packaging. A green local check is not a green Gradle build.
8. **No APK-level verification was possible.** Signature, package, version, patch report, ZIP integrity and protected-method comparison are documented as an acceptance checklist, not executed.
9. **Policy adoption criteria are unmet.** The policy may not replace M11 placement until the capture shows agreement or explains every disagreement.

## 8. Next steps, in order

1. **Commit and push** the M12 branch (no merge to `main`).
2. **Run the gated workflow** `apk.yml` on the branch. Record the run id and the artifact digest.
3. **Verify the artifact independently**: `validate_standalone_apk.sh --stage final`, `apksigner verify --print-certs`, `verify_patch_report.py`, `verify_protected_methods.py` against the pinned baseline, `unzip -t`, `sha256sum`. All commands are in `M12_PHASE6_VERIFICATION.md` §5.
4. **Install that exact APK** and run the `M12_PHASE3_DEVICE_CAPTURE_PROCEDURE.md` scenario. Confirm the in-context patch is enabled and the downloader switch is on before capturing.
5. **Classify** Feed and Reels per step; every refusal must name a branch ID from the register.
6. **Re-rank the hypotheses** against the evidence. Only then open the fix for the confirmed branch, starting with P1 or P2 as the evidence dictates.
7. **Re-run the full gate set** on the fix, and capture again to prove the control is visible — because a policy test cannot prove a visible icon, and this phase exists because that distinction was blurred.

## 8b. What changed after the capture

| File | Change | Verified |
|---|---|---|
| `core/PlacementPolicy.java` | `Container` enum, `ANCHOR_TYPE`, 4-arg `reels` | JVM tests |
| `core/PlacementPolicyTest.java` | 2 new groups (20 total) | JVM tests pass |
| `android/PlacementProbe.java` | `container(View)` mapping | `javac -Werror` |
| `android/ReelsDownloadBinding.java` | `ViewGroup` anchor, `ANCHOR_TYPE` refusal, container params, cached action-bar id, bounded child dump | `javac -Werror` |
| `android/NativeUiTrace.java` | `bounds=l,t,r,b` in the structural detail | `javac -Werror` |

No Feed file changed. No patch file changed. No identity, queue or Ghost Mode file changed.

## 9. Answer to the original question, stated honestly

**Why is the control missing?**

For **Reels**, the answer is proven: the native action bar is a `RelativeLayout` subclass (`ClipsViewerActionBar`), and M11 accepted only a `FrameLayout`. The guard failed on its first conjunct on every pre-draw pass, so the control was never created. Every discovery step before that — pager, getter, listener, anchor — succeeded. The fix accepts the proven container kinds and refuses unknown ones; whether the resulting icon is visible depends on the slot, which the capture could not show because the code never reached the slot check.

For **Feed**, the answer is still unknown. The capture contains no `feed.*` event even though the same build emits them, so either the Feed was not bound during the captured window or the patched binder did not run. The M12 build makes those two distinguishable (`surface=feed name=ARMED` versus `name=BINDER`). No Feed change was made.

The next step is a capture of the fix, not another guess.
