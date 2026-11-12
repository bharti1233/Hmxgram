# HMX Ghost Mode — Architecture

Ghost mode is the set of controls that decide what Instagram is told about what
the user does. This document records what is actually under the app's control,
what Instagram decides on its servers, and how the two are presented.

**The rule this implementation follows:** a control that cannot work is shown
as not supported and cannot be switched on. A toggle that pretends to work is
worse than no toggle.

---

## 1. What is client-side and what is server-side

| Behaviour | Where it is decided | Can the app change it? |
| --- | --- | --- |
| Story view receipt | the app sends it when a story is shown | **yes**, client-side |
| Message read receipt | the app sends it when a message is opened | **yes**, client-side |
| Typing indicator | the app sends it while the user types | **yes**, client-side |
| Online status | an account setting on Instagram's servers | **no** — it is the account's own setting, and the app only reads it |
| Activity status | an account setting on Instagram's servers | **no** — same |
| Anonymous story viewing | Instagram records the view against the account when the media is fetched | **no** — there is no anonymous view |
| Live view | Instagram records it when the stream is fetched | **no** |

The last four are not "hard to implement"; they are decided by the server. A
patch can hide a label on the screen, but it cannot stop Instagram from
recording the view, and it must not pretend to.

---

## 2. What is implemented

Three controls, each of which stops one outbound request:

| Control | What it stops | How |
| --- | --- | --- |
| **Hide Story Seen** | the story-view receipt (`/api/v1/media/{id}/seen/`) | the request is dropped before it is sent |
| **Hide Message Seen** | the message read receipt (`/api/v1/direct_v2/threads/{t}/items/{i}/seen/`) | idem |
| **Hide Typing Status** | the typing indicator (`/api/v1/direct_v2/threads/{t}/items/{i}/activity/`) | idem |

The path fragments were verified against Instagram 454.0.0.54.77: the strings
`media/seen/`, `/direct_v2/`, `/items/`, `/activity/` and `seen` all exist in
the build.

### The hook

The hook sits in Instagram's network layer, right where the request URI is
materialised — `com.instagram.api.tigon.TigonServiceLayer.startRequest`, the
same anchor the content blocker already uses, which has not moved between
releases. The patch asks the extension whether the request may go out; when it
may not, the request fails as a network error and Instagram carries on.

Nothing else is touched: messages are still delivered, notifications are still
shown, no security event is intercepted and nothing about the account changes.

---

## 3. What is not implemented, and why

| Control | Status | Reason shown in the app |
| --- | --- | --- |
| Hide Online Status | not supported | the online status is an account setting on Instagram's servers; the app cannot hide it for the user |
| Disable Activity Status | not supported | the same: it is an account setting, changed in Instagram's own settings |
| Anonymous Story Viewing | not supported | a story view is recorded against the account when the media is fetched; there is no anonymous view |
| Hide Live View | not supported | the live view is recorded when the stream is fetched |

These controls are shown, greyed out, with the reason. They are never faked.

---

## 4. The master switch

The master switch controls **only the three verified controls**. It never
touches the unsupported ones, and it never changes anything Instagram decides
on its servers.

| Master | A control's own preference | Result |
| --- | --- | --- |
| off | on or off | nothing is suppressed |
| on | on | the request is suppressed, for a supported control only |
| on | off | the request is sent as usual |

The rule is implemented once, in `GhostPolicy.isActive`, and unit-tested.

---

## 5. Persistence

The controls live in their own preferences file, `hmx_ghost_mode`:

* they are independent of Instagram's own settings and of the extension's
  content-block preferences;
* they are never frozen by the permanent lock (which guards the content
  blocks) and never freeze it;
* they are written with `apply()` and read back the next time the app starts.

---

## 6. The screen

`HMX Ghost Mode` is a page in the HMX Insta settings, built with the same
design language as the rest of the app:

```text
HMX Ghost Mode
  Master Ghost Mode            [switch]  Controls every supported option below

Stories
  Hide Story Seen              [switch]  Supported
  Anonymous Story Viewing      [off]     Not supported — …the reason…

Messages
  Hide Message Seen            [switch]  Supported
  Hide Typing Status           [switch]  Supported

Activity
  Hide Online Status           [off]     Not supported — …the reason…
  Disable Activity Status      [off]     Not supported — …the reason…
  Hide Live View               [off]     Not supported — …the reason…
```

Every control shows its title, a short explanation and its status. An
unsupported control's switch is disabled and refuses to be turned on.

---

## 7. What is not claimed

* Ghost mode does **not** make the user invisible. It stops three receipts
  from being sent; Instagram still sees the media being fetched, the account
  being used and the connections it has.
* It does not stop a story from being recorded when its media is fetched.
* It does not change what another person can see about the account in
  general.
* It does not suppress anything to do with account security.

---

## 8. Files

| File | Role |
| --- | --- |
| `ghost/GhostFeature.java` | the controls, their explanations and their support level |
| `ghost/GhostPolicy.java` | the master-switch rule and the request matching (pure Java) |
| `ghost/GhostMode.java` | the preferences and the effective state |
| `ghost/GhostNetwork.java` | the network hook |
| `ghost/GhostModeScreen.java` | the settings page |
| `patches/ghost/GhostModePatch.kt` | the hook into Instagram's network layer |
