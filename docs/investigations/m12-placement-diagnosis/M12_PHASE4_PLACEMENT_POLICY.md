# M12 Phase 4 — placement policy

`extensions/extension/src/main/java/com/feurstagram/extension/downloader/core/PlacementPolicy.java`
Tests: `extensions/extension/src/test/java/com/feurstagram/extension/downloader/core/PlacementPolicyTest.java`

## 1. Purpose

M11 decides placement inside Android code. This policy expresses the same decision as a pure function of a small view description, so it can be:

- unit-tested without a device;
- compared against the running code on a real device (**shadow mode**);
- adopted later, one surface at a time, only after the comparison and the capture agree.

**M12 does not use the policy to place anything.** It only evaluates and logs it.

## 2. Contract

```java
PlacementPolicy.Result feed(Node parent, Node bookmark, int iconSizePx)
PlacementPolicy.Result reels(Node bar, int iconWidth, int iconHeight)
```

Properties:

1. **No Android import.** Only `java.util`.
2. **Deterministic.** No time, randomness, locale-sensitive formatting, I/O or global state.
3. **Total.** Every input returns a `Result`; no input throws for a valid tree.
4. **Fail-closed.** Any condition that cannot be verified is a refusal.
5. **Bounded.** Depth 6, 64 blockers; a pathological tree cannot make it loop.

`Node` fields: type (diagnostics only), axis, layout direction, ordered children, visibility, attachment, blocking, position, size, padding, alpha, children.

The Reels entry point also takes a `Container` (`FRAME`, `RELATIVE`, `UNSUPPORTED`). M11 accepted only `FrameLayout`; the device capture proved the bar is a `RelativeLayout` subclass, so the kind is now explicit and anything else is refused as `ANCHOR_TYPE` instead of guessed.

## 3. Feed decision

Order is significant: the first failing rule names the reason.

| # | Condition | Result |
|---|---|---|
| 1 | parent null | `REFUSE:PARENT_MISSING` |
| 2 | parent not visible | `REFUSE:PARENT_INVISIBLE` |
| 3 | bookmark null | `REFUSE:BOOKMARK_MISSING` |
| 4 | parent axis not horizontal | `REFUSE:PARENT_VERTICAL` |
| 5 | parent not left-to-right | `REFUSE:PARENT_RTL` |
| 6 | parent does not order children | `REFUSE:PARENT_NOT_ORDERED` |
| 7 | bookmark not visible | `REFUSE:BOOKMARK_INVISIBLE` |
| 8 | bookmark not attached | `REFUSE:BOOKMARK_DETACHED` |
| 9 | parent not measured | `REFUSE:PARENT_NOT_MEASURED` |
| 10 | icon size not positive | `REFUSE:ICON_UNMEASURED` |
| 11 | `parent.width - padding - iconSize < 0` | `REFUSE:NO_HORIZONTAL_ROOM` |
| — | otherwise | `PLACE` |

Notes:

- Rule 6 is the structural reason M11's `instanceof LinearLayout` check exists. A `ConstraintLayout` is not ordered: child index does not determine horizontal position, so inserting at an index would not satisfy "immediately left of the bookmark". The policy refuses instead of guessing.
- Rule 11 is a minimum: it proves the icon can fit somewhere in the row, not that it fits at the bookmark's index with the bookmark's own width. Margin and sibling-width checks are intentionally left to the adapter, which has the real child list.
- Feed placement has no anchor; child index is the anchor.

## 4. Reels decision

| # | Condition | Result |
|---|---|---|
| 0 | container kind unsupported or null | `REFUSE:ANCHOR_TYPE` |
| 1 | bar null | `REFUSE:BAR_MISSING` |
| 2 | bar not visible | `REFUSE:BAR_INVISIBLE` |
| 3 | bar not attached | `REFUSE:BAR_DETACHED` |
| 4 | icon not measured | `REFUSE:ICON_UNMEASURED` |
| 5 | bar not measured | `REFUSE:BAR_NOT_MEASURED` |
| 6 | `END_TOP` slot leaves the padding box | try `END_CENTER` |
| 7 | `END_TOP` intersects a blocker | try `END_CENTER` |
| 8 | `END_CENTER` leaves the padding box | mark out-of-bounds |
| 9 | `END_CENTER` intersects a blocker | mark overlap |
| — | any anchor free | `PLACE:END_TOP` or `PLACE:END_CENTER` |
| — | both blocked | `REFUSE:OVERLAP` + first blocker type |
| — | neither fits | `REFUSE:OUT_OF_BOUNDS` |

Slot geometry:

```text
right  = bar.width - paddingRight
left   = right - iconWidth
top    = paddingTop                        (END_TOP)
       = (bar.height - iconHeight) / 2     (END_CENTER)
bottom = top + iconHeight
```

Intersection is strict: touching edges do not overlap, matching `Rect.intersects`.

## 5. Blocker flattening

The bar's children are flattened before the slot test:

```text
for each child, in drawing order:
  hidden or alpha == 0        -> skip the whole subtree
  blocking                    -> add its absolute rect
  non-blocking with children  -> recurse, do not add its own rect
  non-blocking leaf           -> add (a leaf is content)
```

"Blocking" is supplied by the adapter: clickable, has a background, or is a text/image view.

Why this is conservative:

- A real control, a label, an icon or a scrim always blocks.
- A transparent structural wrapper does **not** block with its own bounds, but every visible descendant is still tested. The policy never ignores a subtree, only a wrapper's own rectangle.
- A hidden subtree is skipped because the user cannot see it either.

This is the only rule that can make M11 and the policy disagree on Reels. That disagreement is exactly the evidence needed to accept or reject the R1 hypothesis.

## 6. Invariants proven by the tests

1. A refusal always has a reason other than `NONE`.
2. A placement never has a refusal reason and always has a non-`NONE` anchor in Reels or `NONE` in Feed.
3. The same input produces the same code twice.
4. A placement in Reels is inside the padding box and intersects no blocker.
5. Feed places on a row exactly one icon wide, including one plus padding.
6. Every reason value is reachable by at least one test.

## 7. Test groups (20)

Feed:

1. missing parent
2. invisible parent
3. missing bookmark
4. vertical parent
5. right-to-left parent
6. unordered parent
7. hidden bookmark / detached bookmark
8. unmeasured parent / zero icon / no room
9. complete row, including the exact-fit and padded-fit cases

Reels:

10. missing bar
11. hidden bar / detached bar
12. unmeasured icon / unmeasured bar / out of bounds
13. full-width blocker blocks both anchors and names the blocker
14. upper-right preferred when free
15. centre fallback when the top is blocked; top preferred when the centre is blocked
16. transparent wrapper ignored, its real child still blocks
17. hidden subtree and fully transparent control ignored
18. totality and determinism over a 64-case mask plus geometry sweep
19. unsupported container refused (`ANCHOR_TYPE`)
20. `FRAME` and `RELATIVE` containers accepted, with overlap still refused

Every rejection branch in the policy has a dedicated test. The branch register has 30 Feed/Reels branches; of those, the policy models 12 structural refusals (including `ANCHOR_TYPE`) plus `OVERLAP` and `OUT_OF_BOUNDS`. The remaining branches belong to discovery (`BOOKMARK_MISSING`, `PAGER_*`, `GETTER_*`, `ANCHOR`) and to attachment/visibility, which are not placement decisions and are reported by the trace instead.

## 8. What the policy deliberately does not model

| Not modelled | Why |
|---|---|
| margins of siblings | the adapter provides bounds; margin effects are already reflected in position/size |
| translation, scale, rotation | hit rects do not include them; a transformed control is an M13 concern |
| z-order and elevation | overlap is refused regardless of which view is on top |
| ancestor clipping | a clipped-but-visible view is treated as visible; the resulting false accept is caught by the Feed `ZERO_SIZE`/`NOT_SHOWN` trace |
| insets and cutouts | the bar's own geometry already reflects them |
| RTL resolution beyond `leftToRight` | only the boolean the host reports is used |
| font scale | icon size is density-based, not font-based |

## 9. Adoption criteria

The policy may replace the M11 checks only when **all** of these hold:

1. A device capture shows `AGREE` for every reachable row and Reel on the test scenarios.
2. Every `DISAGREE` is explained by a policy check that is stricter than M11, and the stricter result is the one that keeps the product requirement.
3. The pure tests still pass.
4. The protected-method verifier passes on the candidate APK.
5. A new device capture shows the control visible where the policy places it.

**M12 addendum:** one branch is now proven on a device (the bar is a `RelativeLayout`; M11's `FrameLayout` check could never pass). The adapter therefore accepts both container kinds, with container-specific params. The policy remains in shadow mode for the slot choice.

Until then, the policy is a measurement tool, not a placement implementation.
