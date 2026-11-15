# HMX Insta — M5 Ghost Mode

**Status:** implemented on `feature/hmx-m5-ghost-mode`, stacked on the M4
branch `feature/hmx-m4-instagram-media` (PR #22). Not merged, no APK was
built for it yet, and every device row is still NOT TESTED.

**Prerequisite:** M4 (the Instagram media integration) is implemented and its
CI is green.

---

## 1. Scope, and an honest statement about it

M5 delivers the Ghost Mode settings screen, the privacy preferences and the
rules that decide whether a control is in effect.

**Most of the Instagram-facing controls are not implemented, and the screen
says so.** This is a deliberate decision, not an omission:

The repository's protected-surface policy rejects the global request hook
(`ghost.network.guard` in `scripts/ci/protected_methods.json`): *"Ghost
capabilities that need a network hook must be reported unsupported rather
than implemented by changing Instagram request paths."* Every Instagram-facing
privacy control works by suppressing something Instagram sends or shows:

| Control | What it would need | Status |
| --- | --- | --- |
| Hide story seen | a hook on Instagram's story items, or on the seen receipt | **UNSUPPORTED** |
| Hide message seen | a change to Instagram's messaging request path | **UNSUPPORTED** |
| Hide typing status | a change to Instagram's messaging connection | **UNSUPPORTED** |
| Hide online status | a change to Instagram's messaging connection | **UNSUPPORTED** |
| Disable activity status | a change to Instagram's messaging connection | **UNSUPPORTED** |
| Anonymous story viewing | Instagram records the view on its own servers | **UNSUPPORTED** |
| Hide live view | a hook on Instagram's story tray items | **UNSUPPORTED** |

Each unsupported control is shown in the screen **with the exact reason**, and
it cannot be switched on: the state layer refuses to store an enabled value
for it, so a stale preference can never make a control that does nothing look
as if it works.

Two controls are implemented, because they are entirely local and verifiable:

| Control | What it does | Status |
| --- | --- | --- |
| Ask before saving | the share target asks before a shared item is saved | **SUPPORTED** |
| Hide the download history | the downloader screen keeps the list hidden until it is tapped | **SUPPORTED** |

---

## 2. What was implemented

| Class | Package | Purpose |
| --- | --- | --- |
| `GhostFeature` | `ghost` | the catalogue: id, title, explanation, support level, reason, preference key |
| `GhostPolicy` | `ghost` | the pure rules: the master switch gates a control, an unsupported control is never active |
| `GhostState` | `ghost/android` | the preferences in their own file `hmx_ghost_mode`, cached in memory |
| `GhostModeActivity` | `ghost/android` | the settings screen |
| `GhostModePatch` | `patches/ghost` | declares the screen in the manifest, **default-off** |

The state is independent of the downloader's preferences and of every other
setting of the app, so switching a privacy control off can never disturb the
downloader and the other way round. Every control starts off, including the
master switch. The master switch turns the supported controls off at once
without erasing their individual choices.

The screen uses platform widgets only and touches no Instagram class. The
patch is default-off, like the downloader patch: a feature that changes what
the app shows is reviewed before it ships.

### Tests

`GhostPolicyTest` (new, plain JDK) covers the catalogue (every control has
an id, a title, an explanation and its own preference key), the policy (the
master switch and the control's own switch both gate the effect) and the
honesty rule: an unsupported control is never switchable and never active,
even with both switches on. The core suite grows from 44 to **47 groups**.

---

## 3. Change control

| File | Why it changed |
| --- | --- |
| `extension/ghost/GhostFeature.java` (new) | the catalogue |
| `extension/ghost/GhostPolicy.java` (new) | the rules |
| `extension/ghost/android/GhostState.java` (new) | the preferences |
| `extension/ghost/android/GhostModeActivity.java` (new) | the screen |
| `extension/downloader/android/ShareTargetActivity.java` | asks before saving when the control is on |
| `extension/downloader/android/DownloaderActivity.java` | hides the history when the control is on |
| `patches/.../ghost/GhostModePatch.kt` (new) | the default-off manifest declaration |
| `scripts/ci/validate_features.sh` | the new patch source in the inventory |
| `scripts/ci/test_downloader_logic.sh` | compiles and runs the ghost tests |
| `scripts/ci/test_downloader_isolation.sh` | the M5 document and the new paths |

**Not touched:** no login, signup, authentication, session, feed, story, reel,
profile or navigation code; no Instagram patch outside the ghost and
downloader folders; no existing patch was removed or replaced; no permission
was added; no protected method, hook or manifest surface of Instagram was
modified; no request path was changed.

---

## 4. Acceptance criteria

1. The screen lists every control with a plain explanation.
2. A control that works has a switch; a control that cannot be implemented
   safely shows its reason and cannot be switched on.
3. Every control starts off, and the master switch turns the supported ones
   off without erasing their choices.
4. The preferences live in their own file and survive a restart.
5. The core suite passes (47 groups) and all ten repository gates pass.

---

## 5. Device checklist (OPPO A38) — all rows NOT TESTED

| # | Check | Result |
| --- | --- | --- |
| M5-1 | The launcher shows "HMX Ghost Mode" and the screen opens | NOT TESTED |
| M5-2 | Every control starts off | NOT TESTED |
| M5-3 | The unsupported controls show their reason and cannot be switched | NOT TESTED |
| M5-4 | "Ask before saving" makes the share target ask, and cancelling saves nothing | NOT TESTED |
| M5-5 | "Hide the download history" keeps the list hidden until it is tapped | NOT TESTED |
| M5-6 | The master switch turns the supported controls off at once | NOT TESTED |
| M5-7 | The choices survive a restart | NOT TESTED |
| M5-8 | Instagram is unchanged by the new screen | NOT TESTED |

---

## 6. What did not change

* `main` was not modified; no branch was merged.
* No login, signup, authentication, session, feed, story, reel, profile or
  navigation code was touched.
* No Instagram request path was changed; the rejected `ghost.network.guard`
  exception is still rejected.
* The protected-method baseline pin and the 12 protected surfaces are
  unchanged.
* No APK was built or published for M5, and no device test was performed.
