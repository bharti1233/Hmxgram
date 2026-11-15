# M12 Phase 5 — regression matrix

Every guarantee named in the phase requirements, where it is enforced, what M12 changed, and how it is verified. "Verified" here means a source-level or JVM-test proof. It is not device evidence.

## 1. Matrix

| Guarantee | Enforced at | M12 change | Verification |
|---|---|---|---|
| **Downloader queue** | `DownloadQueue` (20 waiting, 2 concurrent, attempt limits, finished-task retention) | none | `DownloaderCoreTest`: queue capacity, concurrency, retry, clear/retention, expiry refusal |
| **Explicit user-triggered download** | only `InContextDownloadSheet`'s Download button, `ShareTargetActivity`, and the downloader screen enqueue | none | `ExplicitDownloadTest`: "opening a reel creates zero jobs", "ten reels create zero jobs", "explicit tap creates exactly one job"; `BoundDownloadTest`: "observation queued work" |
| **Selected-media identity** | `HmxDownloadIcon.bindModel` + `BindingGeneration`; Reels `Binding.valid` | none | `BoundDownloadTest`: A→B recycle, A-B-A revival, detached control; `ExplicitDownloadTest`: "background B replaced A" |
| **Confirmation-time validation** | sheet re-evaluates `ExplicitDownloadRequest.evaluateBound` and the binding validator at the button | none | `BoundDownloadTest`; `ExplicitDownloadTest`: "downloader off blocks", "failed resolution creates no jobs" |
| **Carousel selection** | `ExplicitDownloadRequest.evaluateBound` child branch; `InContextAction.targets`; sheet item/all choice | none | `InContextActionTest`: carousel targets, single targets; `MediaObservationTest`: carousel model; `ExplicitDownloadTest`: "reel segments create one job" |
| **Duplicate prevention** | `MediaNaming.duplicateKey`, `DownloadQueue` duplicate status, saved-history seeding | none | `DownloaderCoreTest`: duplicate keys, duplicate detector, queue duplicate handling; `BoundDownloadTest`: duplicate accepted? (asserts `DUPLICATE`) |
| **Stale-holder rejection** | generation invalidation on rebind and detach; `valid()` and Feed click validator | none | `BoundDownloadTest` (recycle, A-B-A, detach); `OverlayLifecyclePolicyTest` (attach/abandon) |
| **Downloader ON/OFF** | `DownloaderSwitch` read at draw/bind, tap, confirm and enqueue | none | `ExplicitDownloadTest`: "downloader off blocks", "downloader on allows"; `BoundDownloadTest`: OFF refused |
| **Ghost Mode** | `ghost/` package, unchanged by M12 | none | `GhostPolicyTest`: catalogue, policy, unsupported controls, supported controls first, master gating |
| **Protected Instagram methods** | `scripts/ci/protected_methods.json`, `verify_protected_methods.py`, isolation gate | none; the patch file is untouched | protected-method self-test PASS (9 checks), manifest PASS (12 surfaces), isolation gate PASS |
| **Watching or scrolling never downloads** | no queue call in `observeRow`, `onPreDraw`, `bindModel`, `valid` or `tap` | none; M12 adds trace calls only | source grep (below) + `ExplicitDownloadTest` zero-job tests |

## 2. The "never on scroll" proof

The only production call sites that enqueue are:

```text
InContextDownloadSheet  (Download button, after confirmation)
ShareTargetActivity     (explicit share import)
HmxDownloader           (retry/pending work initiated by the downloader screen or notification)
```

`grep -rn "\.enqueue(\|showBound(" extensions/extension/src/main/java --include='*.java'` returns exactly those, plus the `showBound` definition in the sheet and the two `showBound` calls from the Feed and Reels **click listeners**. No bind path, no `onPreDraw` path and no attach/detach path calls either.

M12 adds `NativeUiTrace.*` and `PlacementProbe.*` calls to those paths. Neither class holds a downloader reference, a queue, a URL or a media value; neither can enqueue.

## 3. Why the instrumentation cannot change identity

| Risk | Why it does not happen |
|---|---|
| Trace leaks media into a global | `NativeUiTrace` takes an `Object` only to print its class and view geometry; it stores no reference after the call |
| Probe reads a URL | `PlacementProbe` reads class names, geometry, alpha, visibility, clickability and background presence |
| Probe changes the bound model | it receives views, not the model, and returns a result used only for logging |
| Feed post captures a stale row | the post captures the same icon, row and bookmark the bind already used; it changes no state |
| Reels probe changes visibility | the visibility expression is computed first and unchanged; the probe runs after |

## 4. Behavioral diff review

Compared with `ca16a54`, the only production changes are:

1. `NativeUiTrace`: added state, events, summaries; the old `event`/`children` behavior is preserved.
2. `DownloaderBinder`: the same checks in the same order, plus trace calls; the old `icon.post(after-layout)` is replaced by a post that logs the same view plus attachment and the shadow decision.
3. `ReelsDownloadBinding`: the same discovery and visibility logic, plus trace calls; `freeSlot` is called through a local variable but under the identical short-circuit (`idle && page != null && enabled`), and its result is used exactly as before. **After the device capture**, the anchor guard also accepts a `RelativeLayout` (proven host type) and refuses unknown containers as `ANCHOR_TYPE`; the icon gets container-specific layout params and the action-bar id is cached. `freeSlot`, `valid()`, `tap()` and the visibility expression are unchanged.
4. `PlacementProbe`: new, logging only.
5. `PlacementPolicy`: new, pure, called only by the probe.
6. `MediaModelReader`: added a class-name predicate; the reader's behavior is unchanged.

No placement index, size, weight, visibility rule, identity check, confirmation rule or queue call changed.

## 5. Residual risk

| Risk | Status |
|---|---|
| Android compilation | verified with `javac` against `android.jar`; **not** a Gradle build |
| Resource/manifest references | M12 adds no resource and no manifest entry |
| Runtime behavior on the device | not verified; requires the capture |
| Probe cost on low-end hardware | bounded and gated; not measured on device |
| Summary log volume | capped; not measured on device |
| Reels discovery JVM coverage | **gap**: the pager/getter reflection is Android-bound and has no pure test; the new container decision is covered |
| Feed binding JVM coverage | **gap**: holder-field discovery is Android-bound; `MediaObservationTest` covers model reading, `test_native_feed_hook.py` covers the patch anchor |

The two coverage gaps are the reason the diagnosis is instrument-first rather than test-first.
