# M12 Phase 1 — implementation audit

Baseline: `ca16a54a7131e599b5267e7260e1e9e1f21ac345`.

This is a read of the code that M12 instruments. It records what can reject or hide the control, and — importantly — what the code assumes about Instagram's view tree.

## 1. Patch-time installation

`patches/src/main/kotlin/com/feurstagram/patches/downloader/InContextDownloadPatch.kt`

| Step | Rule | Failure |
|---|---|---|
| Feed binder | A non-static `void` method with 4 parameters containing the wire string `itas-android`; parameter 2's class has exactly one non-static `Media` field; parameter 3's class has at least two `IgBouncyUfiButtonImageView` fields | `PatchException`, build fails |
| Feed insertion | `bindFeed(model, holder)` at instruction index **0** of that method | none at build time |
| Reels pager | Class containing `android_purge_26_q2_ClipsViewerAdapter_getItemOrNull`; its **single** `<init>`; insert `register(this)` before the single `RETURN_VOID` | `PatchException`, build fails |
| Switch install | `DownloaderBinder.install(ViewGroup)` at index 0 of `Settings.installHomeTabWatcher(ViewGroup)` | `PatchException`, build fails |

**Confirmed consequence:** a built APK has all three insertions. A missing control is therefore a runtime decision, not a silent patch failure.

**Confirmed risk:** `singleOrNull` means a shape change fails the build loudly. That is good. It does **not** prove the selected method is the one that renders the row on the current host; it only proves it is unique and shaped as expected.

## 2. Feed path

### 2.1 Entry

`DownloaderBinder.bindFeed(Object model, Object holder)` (`DownloaderBinder.java:47`) → `observeRow` (`:68`).

The hook runs at the **first instruction** of Instagram's binder. Nothing in the binder body has executed yet. That matters: any view the method itself assigns to the holder is still `null` when `observeRow` runs.

### 2.2 Bookmark discovery

```text
for each public field of holder.getClass():
    skip static fields and non-View fields
    resolve row_feed_button_save through that view
    first candidate wins; a second, different candidate refuses
```

Properties, all confirmed in source:

1. `Class.getFields()` returns **public** fields only, including inherited public ones. A private or package-private holder field is invisible.
2. The bookmark must be reachable from a public `View` field on the holder. A bookmark that exists only as a local in the binder, or behind a generated binding accessor, is invisible.
3. `getResources().getIdentifier("row_feed_button_save", ...)` is resolved **per field, per bind**. A renamed id yields `0` for every field.
4. Two different candidates on one holder is an explicit refusal, not a guess.
5. If no candidate is found, `bookmark` stays `null`.

### 2.3 Parent checks

```text
bookmark == null                      -> feed.reject.parent
!(bookmark.getParent() instanceof LinearLayout)
                                      -> feed.reject.parent (+ child dump)
row.orientation != HORIZONTAL         -> feed.reject.orientation
row.layoutDirection != LTR            -> feed.reject.orientation
```

The `LinearLayout` requirement is a class check, not a capability check. A `ConstraintLayout` row with a visually horizontal arrangement is refused.

Layout direction matters even in a left-to-right language if the view inherits `INHERIT` and its resolved direction differs.

### 2.4 Insertion geometry

```text
params = new LinearLayout.LayoutParams((LinearLayout.LayoutParams) bookmark.getLayoutParams())
params.weight = 0
params.width = 40dp
row.addView(icon, row.indexOfChild(bookmark), params)
```

- The icon is inserted at the bookmark's **index**, so it is immediately to the left in child order. This satisfies the product requirement **if** the row is an ordered horizontal container.
- Height is inherited from the bookmark's params. If the bookmark is `MATCH_PARENT`, the icon matches the row height; if the row is shorter than 40 dp, the icon can be clipped.
- `weight = 0` and a fixed width mean the icon does not steal space from weighted siblings.
- The cast assumes the bookmark's params are already `LinearLayout.LayoutParams`. That is guaranteed by the parent check.

### 2.5 Icon reuse and binding

- Existing icons are found by `instanceof HmxDownloadIcon` in the row. A recycled row keeps its icon.
- `bindModel` invalidates the previous `BindingGeneration` and stores the new model. `media()` reads the model lazily at tap time.
- Visibility is set on every bind: `VISIBLE` when the switch is on, `GONE` otherwise.
- The click listener is installed **only when the icon is created**, and it closes over that icon. It refuses when the icon is detached or not shown, and passes a validator requiring the same generation.

### 2.6 ViewHolder lifecycle

Confirmed sequence for a recycled row:

```text
bind method entry
  -> bindFeed(holder, model)
       observeRow: read holder fields, insert/bind icon, schedule post
  -> binder body runs: native views are bound, visibility/params updated
  -> insets/measure/layout
  -> icon.onAttachedToWindow (only on first attachment)
```

Two lifecycle facts follow:

1. **Fields read before the body are the previous row's state on a recycled holder.** On the very first bind they may be `null`; on later binds they may be the previous item's views. The icon is then bound to the new model, but the geometry it reads belongs to the old binding until the body runs.
2. **The `bookmark` reference is stable** once resolved; it is a child of the row, not recreated per bind. So the icon is not duplicated, but the parent check runs against whatever the holder exposed at entry.

This is the strongest source-level argument for hypothesis H2 (timing) and H1 (visibility): both are consequences of reading the holder at index 0.

## 3. Reels path

### 3.1 Pager discovery

`ReelsDownloadBinding.register(owner)` (`:22`):

```text
attempts 0..29, one every 100 ms:
    pager = public field of owner whose type name is exactly
            androidx.viewpager2.widget.ViewPager2
    if pager == null or not attached -> retry
on success:
    getter = every no-arg, non-static method whose return type
             has exactly one non-static Media field
    exactly one -> attach
    zero        -> reels.reject.no-getter
    two or more -> reels.reject.ambiguous-getter
after 30 attempts -> reels.reject.attach-timeout
```

Confirmed assumptions:

- The pager is a **public field** of the owner and its type name is exactly `ViewPager2`. A private field, a wrapper, a `RecyclerView`-based viewer or a subclass with a different field type fails.
- The page model lives behind a **no-arg accessor on the pager owner**, and the page type exposes the `Media` through a **public field**. A private field in the page type fails `hasSingleMediaField`.
- `register` runs at constructor exit, normally before the view is attached, hence the retry loop.

### 3.2 Action bar and slot

`Binding.onPreDraw` (`:126`):

```text
id = clips_viewer_action_bar
anchor must exist, be a FrameLayout, be shown, and share the pager's root
otherwise -> hide + reels.reject.anchor
```

`freeSlot` (`:260`):

1. The icon must be measured (`width > 0 && height > 0`). On the first frames after creation it is not, so the icon starts `INVISIBLE`.
2. The icon's **hit rect** must be inside the bar's padding box.
3. The icon's hit rect must not intersect the hit rect of **any visible sibling**, except the icon itself.

Confirmed consequence: a transparent, full-width child — a scrim, spacer, gradient or measurement wrapper — blocks the slot even when the corner looks empty. This is the strongest source-level argument for hypothesis R1.

**Correction after the device capture:** the native bar is
`instagram.features.clips.viewer.actionbar.ClipsViewerActionBar`, which extends
`android.widget.RelativeLayout`, so this `FrameLayout` requirement was false on
the device and the refusal happened before `freeSlot` ever ran. The current
code accepts `FrameLayout` and `RelativeLayout` and refuses anything else as
`ANCHOR_TYPE`. See `M12_DEVICE_EVIDENCE_ANALYSIS.md`.

### 3.3 State and identity

- `generation` increments whenever page object, page index or idle state changes.
- The icon is shown only when `idle && page != null && enabled && slotFree`.
- `valid()` requires the same generation, attached and shown pager and icon, switch on, idle scroll state, same current item and the same page object.
- `tap()` resolves the page through the getter at tap time and reads it with `MediaModelReader.readWrapped`.

Identity is stronger than either audited project. It is also **not reachable** when the icon is absent.

## 4. Media identity and confirmation (unchanged by M12)

| Check | Source | Effect |
|---|---|---|
| Bound model per control | `HmxDownloadIcon.bindModel` | no shared "current media" |
| Generation token | `BindingGeneration` | a stale row cannot confirm |
| Feed validator | `DownloaderBinder` click listener | same icon, same generation |
| Reels validator | `ReelsDownloadBinding.valid` | same pager, page, index, idle, generation |
| Request evaluation | `ExplicitDownloadRequest.evaluateBound` | switch, identity, story expiry, Reels video-only, carousel child |
| Confirm re-evaluation | `InContextDownloadSheet` | switch and generation re-checked at the button |
| Queue | `DownloadQueue` | 20 waiting, 2 concurrent, duplicate key, saved history |

M12 does not change any of these.

## 5. What the audit establishes

1. The missing control is a **runtime** outcome, because a build proves the insertions happened.
2. Feed discovery is a **public-field reflection contract** evaluated **before** the binder body.
3. Reels discovery is a **public-field + exact-type contract** with a **30 × 100 ms** window.
4. Reels placement has a **blanket sibling collision rule** that cannot distinguish a control from a transparent wrapper.
5. Identity, confirmation and queueing are already correct and are not implicated by the absence.

The next document turns this into an exhaustive branch register.
