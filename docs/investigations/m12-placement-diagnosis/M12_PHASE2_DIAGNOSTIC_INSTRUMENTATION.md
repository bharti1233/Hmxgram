# M12 Phase 2 — diagnostic instrumentation

## 1. What was added

| File | Status | Role |
|---|---|---|
| `downloader/android/NativeUiTrace.java` | rewritten | structured event + derived-state summary |
| `downloader/android/PlacementProbe.java` | new | reads views into policy nodes; shadow decision |
| `downloader/android/ReelsDownloadBinding.java` | instrumented | pager, page, anchor, slot, blocker, attachment |
| `downloader/DownloaderBinder.java` | instrumented | binder, media model, bookmark, parent, geometry |
| `downloader/core/PlacementPolicy.java` | new | pure placement decision (used in shadow mode) |
| `core/MediaModelReader.java` | one method | `isMediaModel(Object)`, a class-name check only |
| `src/test/.../PlacementPolicyTest.java` | new | every policy branch |
| `scripts/ci/test_downloader_logic.sh` | one line | runs the new test |

No Instagram method is modified by M12. The patch file is unchanged; its reviewed hashes still match.

## 2. Line format

Tag: `HmxNativeUI` (unchanged, so existing filters still work).

```text
I/HmxNativeUI: m12 event surface=<feed|reels> name=<EVENT> reason=<CODE> count=<n> detail=<structure>
I/HmxNativeUI: m12 summary surface=<feed|reels> state=<STATE> reason=<CODE> armed=.. created=.. attached=.. visible=.. refused=.. taps=.. failures=..
```

The example below is **illustrative**, not captured from a device:

```text
I/HmxNativeUI: m12 event surface=feed name=REFUSED reason=PARENT count=1 detail=X.1Abc id=none attached=false shown=false visibility=0 size=0x0 measured=0x0 alpha=1.0 clickable=false background=false enabled=true parent=X.1Def lp=X.1Ghi:-2x-2
I/HmxNativeUI: m12 summary surface=feed state=ARMED_REFUSED reason=PARENT armed=1 created=0 attached=0 visible=0 refused=1 taps=0 failures=0
```

`detail` is structural only: class names, resource entry name, attachment, shown state, visibility, size, measured size, alpha, clickability, whether a background exists, the switch state, parent type and layout-param type. It never includes text, descriptions, tags, URLs or account data.

## 3. Required Feed events

| Phase 2 requirement | Event |
|---|---|
| Hook registered | `ARMED` |
| Target binder discovered | patch-time unique match; runtime `BINDER` with the holder type |
| Binder invocation | `BINDER` per bind |
| Media model availability | `MEDIA_MODEL` with `MEDIA`, `WRAPPER`, `UNREADABLE` or `MODEL_NULL` |
| Bookmark view discovery | `BOOKMARK_FOUND`, `REFUSED/BOOKMARK_MISSING`, `REFUSED/BOOKMARK_AMBIGUOUS` |
| Parent discovery | `PARENT` |
| Placement decision | `DECISION_LEGACY`, `DECISION_POLICY`, `DECISION_AGREE` |
| Attachment result | `ATTACH_RESULT` with `SHOWN` or a reason |
| Visibility result | `VISIBLE` or `HIDDEN` |
| Rejection reason | `REFUSED/<code>` or `HIDDEN/<code>` |

`MEDIA_MODEL` is a **structural** answer: it reports whether the object is the media class or a wrapper with exactly one public `Media` field. It does not read a URL at bind time, so a long feed does not pay the full reader cost during scroll.

## 4. Required Reels events

| Phase 2 requirement | Event |
|---|---|
| Pager discovery | `PAGER_ATTEMPT` (`FOUND`/`NOT_FOUND`), `PAGER_FOUND`, `REFUSED/PAGER_ATTACH_TIMEOUT` |
| Current page identification | `PAGE` with `IDLE`/`SCROLLING` and the page type, `PAGE_MODEL` |
| Native control discovery | `ANCHOR` with `FOUND`/`MISSING`, `ANCHOR_TYPE` with the container kind, `REFUSED/ANCHOR` |
| Free-slot evaluation | `SLOT_LEGACY`, `SLOT_POLICY` |
| Sibling overlap detection | `SLOT_BLOCKER` (child index), `SLOT_POLICY_BLOCKER` (type), `BAR_CHILD_<i>` (bounds, once per geometry) |
| Attachment attempt | `CREATED` |
| Attachment result | `VISIBLE` / `HIDDEN` plus `summary` |
| Rejection reason | `REFUSED/<code>` or `HIDDEN/<code>` |

`PAGE` is emitted only when the page object, page index or idle state changes, so a static Reel does not flood the log.

## 5. Derived state

```text
!armed                         -> NEVER_ARMED
visible && attached            -> ATTACHED_VISIBLE
created                        -> ARMED_INVISIBLE
otherwise                      -> ARMED_REFUSED
```

State transitions:

| Call | Effect |
|---|---|
| `armed` | `armed = true` |
| `refused` | `armed = true`, clears created/attached/visible, stores reason, increments refusals |
| `created` | `created = true`, reason `CREATED` |
| `attached` | `attached = true` |
| `detached` | clears attached/visible, reason `DETACHED` |
| `visible` | sets attached and visible, reason `NONE` |
| `hidden` | clears visible, stores reason |
| `tap` | increments taps |
| `failure` | increments failures, stores reason |

`ARMED_REFUSED` always reports a reason: `reason()` returns `UNSPECIFIED` if a branch forgot one, so the capture cannot show a reasonless refusal.

## 6. Shadow placement comparison

M12 does **not** move anything. `PlacementProbe` evaluates `PlacementPolicy` with the same views and logs the result beside the running M11 decision.

| Line | Meaning |
|---|---|
| `DECISION_LEGACY reason=ACCEPT` / `REFUSE:<branch>` | what the running code decided |
| `DECISION_POLICY reason=PLACE:<anchor>` / `REFUSE:<reason>` | what the pure policy would decide |
| `DECISION_AGREE reason=AGREE` / `DISAGREE` | whether the two agree on accept/refuse |
| `SLOT_LEGACY reason=AVAILABLE` / `BLOCKED` | the running `freeSlot` result |
| `SLOT_POLICY reason=...` | the policy slot result |
| `SLOT_AGREE` | whether they agree |

Expected disagreements, and why they are **useful**:

1. **Unmeasured geometry.** The probe runs after layout for Feed and only while idle for Reels, so this should be rare. If it appears, it means the policy was evaluated too early.
2. **`NO_HORIZONTAL_ROOM`.** The policy adds a room check M11 does not have. A disagreement here means the row is narrower than one icon.
3. **`PARENT_NOT_ORDERED`.** M11 refuses any non-`LinearLayout` parent; the policy names the structural reason. Both refuse, so the decision agrees but the reason is more precise.
4. **Transparent wrapper in Reels.** M11 blocks on any visible sibling; the policy looks through an undecorated wrapper and tests its children. A `DISAGREE` here is the evidence R1 hypothesis needs.

A disagreement is not a bug in M12. It is the measurement that decides whether the policy is safe to adopt.

## 7. Bounds and cost

| Guard | Value | Why |
|---|---|---|
| Distinct sampled stages | 128 | a log cannot grow without bound |
| Sampling | first 3, then powers of two | enough to see a pattern, not every frame |
| Summaries per surface | 40, then every 50th | summaries cannot flood |
| Probe depth | 6 | view trees are shallow below the bar |
| Probe width | 32 children per container | a bar has a handful |
| Policy blockers | 64 | bounds the overlap walk |
| Feed probe | one `post` per bind | not per frame |
| Reels probe | only when the geometry signature changes, and only while idle | not per frame |

The Reels `freeSlot` call keeps its original short-circuit: it still runs only when the pager is idle, a page exists and the switch is on. M12 does not add per-frame slot work during scrolling.

## 8. Behavioral invariants

M12 changes diagnostics only. Confirmed by inspection and by compiled output:

- the Feed insertion index, layout params, width and weight are unchanged;
- the Feed checks run in the same order and return at the same points;
- the Reels visibility expression is unchanged (`idle && page != null && enabled && slotFree`);
- the pager discovery rules, retry count and getter uniqueness are unchanged;
- the anchor requirement is unchanged;
- `valid()`, `tap()`, the sheet call and the queue call are unchanged;
- every failure still returns without throwing into Instagram.

The only new work is trace emission and the shadow probe.

## 9. Instrumentation limits

1. `note` samples after the third occurrence; a very late `ARMED` could be hidden if a stage name is reused heavily.
2. The summary is emitted when a state **or reason** changes, not on a timer. If a surface never changes state, only the `ARMED` line appears.
3. Feed visibility is sampled once per bind via `post`. A control that becomes invisible later without a rebind is not re-sampled.
4. Reels probes only while idle. A slot blocked only during a scroll is not measured, but the control is not shown then anyway.
5. The probe reads geometry only. It cannot tell whether a sibling is visually transparent; it infers that from decoration, clickability and type.
