# HMX Insta — M1 downloader core

**Status:** implemented on `feature/hmx-m1-downloader-core`, stacked on the M0
milestone branch `feature/hmx-downloader-ghost-mode-v2` (`b9326c0`). No APK was
built, signed or published for this milestone. The feature is not connected to
Instagram's UI, manifest or patches.

**Milestone exit criterion** (from `docs/HMX_INSTA_NEXT_PHASE_PLAN.md` §5):
`test_downloader_logic.sh` is green and the core has no Android imports.

---

## 1. Scope

M1 delivers the pure-Java engine core only:

| In scope | Out of scope (later milestones) |
| --- | --- |
| media models and kind mapping | MediaStore writes (M2) |
| bounded queue, concurrency, retry | workers and notifications (M2) |
| duplicate detection and history | audio extraction (M2) |
| safe naming, MIME and storage plans | share target (M2) |
| plain-JDK unit tests | downloader page (M3) |
| static isolation gates | in-context actions (M4), ghost mode (M5) |

Nothing in this milestone reads Instagram's media parser, auth, session, feed
or network code. No patch, protected method, manifest entry or existing
extension source was modified.

### Documentation naming note

The request referenced `docs/HMX_INSTAPLUS_NEXT_PHASE_PLAN.md`. That file does
not exist on the M0 branch. The canonical plan lives on the
`docs/next-phase-plan` branch as `docs/HMX_INSTA_NEXT_PHASE_PLAN.md`; it and
`docs/HMX_DOWNLOADER_ARCHITECTURE.md` are imported unchanged into this branch
so the implementation and its design are reviewable together.

---

## 2. Delivered classes

All classes are under
`extensions/extension/src/main/java/com/feurstagram/extension/downloader/core/`
and import only `java.*`.

| Class | Responsibility |
| --- | --- |
| `DownloadKind` | `PHOTO`, `VIDEO`, `CAROUSEL`, plus derived `AUDIO` and `COVER`; maps Instagram media types 1/2/8 |
| `DownloadState` | `QUEUED → RUNNING → DONE / FAILED / CANCELLED`, terminal and active predicates |
| `DownloadError` | error taxonomy with stable codes, user messages and recoverability |
| `DownloadResult` | immutable success / failure / cancellation outcome |
| `MediaItem` | validated item: id, kind, HTTP(S) URL, MIME, size, owner, dimensions, children, expiry |
| `MediaNaming` | one-segment sanitisation, MIME ↔ extension mapping, deterministic names, duplicate keys |
| `DuplicateDetector` | bounded, insertion-ordered set of saved keys; evicts the oldest |
| `MediaSource` | source contract and carousel expansion (`flatten`) |
| `DownloadTask` | validated per-task state machine and retry predicate |
| `DownloadQueue` | bounded waiting list, concurrency limit, retry policy, duplicate policy |
| `DownloadRepository` | history contract |
| `InMemoryDownloadRepository` | bounded, thread-safe reference implementation (default 100 entries) |
| `StoragePlan` | pure storage decision: directory, file name, MIME, pending flag, temp name |

### Queue rules

* At most **20 waiting** downloads.
* At most **2 running** downloads.
* At most **3 attempts** for a recoverable error (`network`, `timeout`,
  `server`).
* `expired-link`, `expired-media`, `storage`, `invalid-media`, `unsupported`,
  `cancelled` and `unknown` are never retried.
* An item is a duplicate when it is **waiting**, **running** or **already saved
  successfully**.
* A failed or cancelled item may be enqueued again; a permanently failed item
  is not remembered as a duplicate.
* Retrying a failed task is allowed only while attempts remain, the error is
  recoverable and the waiting list has room.
* An expired story is refused at enqueue time with `IllegalArgumentException`;
  it never occupies a slot.

### Storage rules

* Images (including video covers): `Pictures/HMXInsta`.
* Video: `Movies/HMXInsta`.
* Extracted audio: `Music/HMXInsta`.
* Every plan is `pending` (`IS_PENDING`) so a half-written file never appears
  in the gallery.
* The temporary name is the final name plus `.part`.
* File names are a single safe segment (`hmx_<owner>_<id>_<kind>_<time>.<ext>`),
  capped at 120 characters; `..`, separators and reserved device names cannot
  survive sanitisation.
* No broad storage permission is requested or referenced.

### Error taxonomy

| Code | Recoverable | Meaning |
| --- | --- | --- |
| `network` | yes | connection failed or interrupted |
| `timeout` | yes | connection or read timed out |
| `server` | yes | server rejected the request |
| `expired-link` | no | the signed CDN link is no longer valid |
| `expired-media` | no | a story is past its lifetime |
| `storage` | no | the gallery write failed |
| `invalid-media` | no | no usable URL or child list |
| `unsupported` | no | media kind not supported by this downloader |
| `cancelled` | no | the user stopped the download |
| `unknown` | no | unclassified failure |

---

## 3. Tests and gates

### 3.1 Unit tests (plain JDK, no device)

`scripts/ci/test_downloader_logic.sh` compiles the 13 core classes and the
test runner with `javac --release 11 -Xlint:all -Werror`, then runs
`DownloaderCoreTest`. There is no JUnit dependency.

Coverage groups:

1. kind mapping (1/2/8 and rejected values)
2. error taxonomy (recoverability, unique codes, round trip)
3. result validation
4. media item validation (URL scheme, carousel children, dimensions, size)
5. expiry rules (boundary, carousel child)
6. naming sanitisation (traversal, reserved names, control characters, cap)
7. MIME and extension mapping
8. file naming (determinism, fallbacks, length cap)
9. duplicate keys (id, URL without query, carousel)
10. duplicate detector (eviction, capacity)
11. task state machine (success, failure, retry exhaustion, cancellation)
12. queue capacity (waiting limit, defaults)
13. queue concurrency (two slots, refill)
14. queue duplicate handling (queued, running, saved, re-enqueue after cancel)
15. queue retry policy (automatic requeue, exhaustion, full queue, manual retry)
16. queue expiry refusal
17. media source carousel expansion
18. repository history (terminal-only, order, limit, capacity, contains)
19. storage plans (directories, pending, temp name, path safety)
20. MIME round trip

Result on this branch: **PASS 20 downloader-core tests**.

### 3.2 Static gates

`scripts/ci/validate_features.sh`:

* core imports are `java.*` only;
* core contains no Android/AndroidX, socket, cookie, auth or storage-permission
  symbols;
* the downloader package contains no cookie/auth/storage-permission symbols;
* no existing extension source or patch references the core;
* the Android manifest contains no downloader component;
* the patch inventory is exactly the 19 M0 sources (a new patch must be added
  deliberately);
* ghost-mode checks activate only when a ghost package exists (M5).

`scripts/ci/test_m1_isolation.sh`:

* re-computes a pinned SHA-256 over 57 protected files and fails if any byte
  changed;
* diffs protected paths against the M0 milestone commit `b9326c0`;
* re-asserts `protected_methods.json`: baseline APK
  `6a327db6…f5cd2822`, 12 surfaces, `ghost.network.guard` rejected;
* proves every M1 change is in the milestone allowlist;
* proves the core has no Android imports.

This gate is pinned to the M1 milestone. M2 must update its base commit,
protected digest and allowlist in the same commit that introduces the Android
shell, so the gate keeps failing closed rather than silently widening.

The APK-level protected-method verifier (`scripts/ci/verify_protected_methods.py`)
is unchanged and still runs in the gated APK build.

### 3.3 How to reproduce

```bash
bash scripts/ci/test_downloader_logic.sh
bash scripts/ci/validate_features.sh
bash scripts/ci/test_m1_isolation.sh
bash scripts/ci/test_protected_methods.sh
bash scripts/ci/validate_repo.sh
```

All five commands are also wired into the `validate` job of
`.github/workflows/ci.yml`. The job checks out full history so the M1 baseline
diff is available, and sets up JDK 17 for `javac --release 11`.

---

## 4. What did not change

* No file under `patches/src` changed.
* No existing extension Java source changed.
* No protected method, patch fingerprint or protected-method manifest changed.
* No login, signup, auth, session, feed rendering, Instagram networking or
  navigation code changed.
* No Android manifest component or storage permission was added.
* The downloader core is referenced by nothing except its own tests and gate
  scripts.
* Features remain invisible in the app until M2/M3 connect them.

---

## 5. Remaining work

| Milestone | Work |
| --- | --- |
| M2 | Android shell: `MediaStoreWriter` (`IS_PENDING`), `HmxDownloader` workers, `AudioExtractor`, preferences-backed `DownloadHistory`, notifications, share target |
| M3 | Downloader page in HMX settings, fed by a read-only row-binder observation |
| M4 | In-context actions, one surface per default-off patch |
| M5 | Ghost mode, separate patch and preference file |
| M6 | Wire the dex-level protected-method verifier into the gated APK build as a hard failure |

No APK build will be dispatched until M1's CI is green and reviewed. This
milestone is not merged automatically.
