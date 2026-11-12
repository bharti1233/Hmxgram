# HMX Ghost Mode — Test Report

Every row says what was actually run. A test that was not executed is marked
**pending**; nothing is reported as passing on the strength of a code review.

* **offline** — runs on a plain JDK, no device, no emulator, no network
  (`scripts/ci/test_ghost_mode.sh`)
* **static** — repository checks (`scripts/ci/validate_features.sh`)
* **device** — needs an OPPO A38 / Android 15 / arm64-v8a, with a second
  account to observe the other side

---

## 1. What ran

| Suite | Command | Result |
| --- | --- | --- |
| Ghost-mode rules (unit) | `bash scripts/ci/test_ghost_mode.sh` | **passed — 36 checks** |
| Feature safeguards (static) | `bash scripts/ci/validate_features.sh` | **passed** |
| Repository validation | `bash scripts/ci/validate_repo.sh` | **passed** |

---

## 2. Scenario matrix

### The controls

| Scenario | Level | Result | What was checked |
| --- | --- | --- | --- |
| Hide Story Seen, on its own | offline | **passed** | the control is active when the master switch is on and the control is on |
| Hide Message Seen, on its own | offline | **passed** | idem |
| Hide Typing Status, on its own | offline | **passed** | idem |
| Hide Online Status | offline | **passed** | it is marked unsupported and never becomes active, whatever the user picks |
| Disable Activity Status | offline | **passed** | idem |
| Anonymous Story Viewing | offline | **passed** | idem, with the reason shown |
| Hide Live View | offline | **passed** | idem |
| Master switch off | offline | **passed** | nothing is active, whatever the individual controls say |
| Master switch on, control off | offline | **passed** | the control stays off |
| The master switch never activates an unsupported control | offline | **passed** | `GhostPolicy.isActive` refuses it |
| An unsupported control cannot be switched on in the UI | static | **passed** | the switch is disabled and refuses the click |
| The preferences survive a restart | device | **pending** | the preferences file is written and read back; needs the app to be killed and started again |

### The requests that are dropped

| Scenario | Level | Result | What was checked |
| --- | --- | --- | --- |
| The story-view receipt is dropped when the control is on | offline | **passed** | `/api/v1/media/{id}/seen/` matches |
| The story-view receipt is sent when the control is off | offline | **passed** | idem |
| A media URL that is not a receipt is left alone | offline | **passed** | `/api/v1/media/{id}/info/` does not match |
| The message read receipt is dropped | offline | **passed** | `/api/v1/direct_v2/threads/{t}/items/{i}/seen/` matches |
| The typing indicator is dropped | offline | **passed** | `/api/v1/direct_v2/threads/{t}/items/{i}/activity/` matches |
| Unrelated requests are never touched | offline | **passed** | the feed, the reels tray, the profile and the empty path are all left alone |

### Normal behaviour

| Scenario | Level | Result |
| --- | --- | --- |
| Normal messaging | device | **pending** |
| Normal notifications | device | **pending** |
| The story viewer still opens the story | device | **pending** |
| The other account does not see a read receipt | device | **pending** — needs a second account |
| The other account does not see a typing indicator | device | **pending** — needs a second account |
| The story is still marked as seen on the poster's side | device | **pending** — this is the honest limitation: the receipt is stopped, the media fetch is not |
| Instagram login | device | **pending** |
| The 16 patches still apply | CI | **pending** — see the implementation report |

---

## 3. Honest summary

The ghost-mode **rules** are implemented and tested: which control is active,
which request is dropped, which control is never active because Instagram
decides it on the server, and that the master switch cannot turn on something
that cannot work.

What the rules *do* on a real account is **pending**: dropping a request
changes what the other person sees, and that can only be checked with a second
account on a real device. Until then, the controls are described as what they
are — three receipts that are no longer sent — and not as invisibility.
