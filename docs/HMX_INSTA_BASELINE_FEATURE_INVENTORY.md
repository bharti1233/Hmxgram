# HMX Insta — baseline feature inventory (M0)

**Purpose:** state exactly what the frozen baseline does today, so every later
milestone can be judged against a named behavior instead of "it still works".
Anything not listed here is not baseline functionality and must not be assumed.

The machine-readable half of the freeze is
`scripts/ci/protected_methods.json`; the enforcement is
`scripts/ci/verify_protected_methods.py`.

---

## 1. Frozen artifact

| Item | Value |
| --- | --- |
| Version | Instagram `454.0.0.54.77`, version code `386014712` |
| Package | `com.instagram.android` (no clone suffix) |
| APK | `hmx-insta-454.0.0.54.77-test.apk`, 164,081,474 bytes |
| SHA-256 | `6a327db6ec9c0ccc83f82a76eaf62451c6fac526131e6f12b260bec2f5cd2822` |
| Release | `debug-test-43210749344-1`; protected copy `hmx-baseline-454-verified` |
| Source | `6474df0d504b7fdce45bb6c5c1e7d19e24b0ed8b` / tree `9e02ce3824586f01ed165686dcda08100c8495dc` (identical to recovery `a5bace0`) |
| Patches | **14 applied, 0 failed** |
| Signature | throwaway `CN=Morphe`, APK Signature Scheme v2 only |
| Device | OPPO A38 (CPH2579), Android 15 / API 35, arm64-v8a — installed and tested by the project owner |
| Dex content | `sha256:8d9ffacd38097fb58c9e608e0195fba5a1823926a2a62dd7de30bd0141564ba7` |

---

## 2. Protected systems — tested baseline behavior

These twelve surfaces are frozen. They are the user-facing behavior that every
new milestone must leave intact.

| # | Baseline system | Behavior that exists today |
| --- | --- | --- |
| 1 | Login | Instagram's own login screen, credential entry and error handling are untouched |
| 2 | Signup | Instagram's own signup entry point and flow are untouched |
| 3 | Authentication / credentials | Credential storage, key-hash trust and session token handling are Instagram's; nothing in HMX stores or reads them |
| 4 | Session init / persistence | Signed-in state survives restarts; the app opens directly into the signed-in experience |
| 5 | Startup / activity init | App shell, launcher activity and main activity initialise normally |
| 6 | Feed init / loading | Home feed loads and paginates (unless the user enabled a block that hides it) |
| 7 | Media parsing | Instagram's JSON→`Media` path is unchanged; no capture hook was added |
| 8 | Stories playback / navigation | Story viewer opens, plays, advances, and closes normally |
| 9 | Reels playback | Reels tab/viewer plays and scrolls normally |
| 10 | Navigation / bottom tabs | Tabs, back stack and swipe behavior work; tab visibility is user-configurable |
| 11 | Network request lifecycle | Tigon request creation, dispatch and retry are untouched; no global interception |
| 12 | Branding / launcher assets | Label `HMX Insta`, HMX adaptive icon and monochrome icon, original signature replaced by the test key |

The protected-method gate resolves these systems from stable anchors and fails
the build on any normalized dex difference.

---

## 3. The 14 baseline patches

Every patch below is part of the baseline. `MIN_APPLIED_PATCHES` is now `14`,
so losing any one of them fails the build.

| # | Patch | What it does | Default |
| --- | --- | --- | --- |
| 1 | Feed item filtering | Drops ads, promo and suggested units at Instagram's JSON parse step | always active |
| 2 | Force SDR display | Pins the window to `COLOR_MODE_DEFAULT` (the setting is labelled "Force dark / disable HDR") | ON |
| 3 | HMX Insta app name | Rewrites the launcher/app label and HMX launcher artwork | always active |
| 4 | Hidden Home in Back history | Back from a hidden Home tab goes to the first shown tab | active when Home is hidden |
| 5 | Install-packages permission | Declares `REQUEST_INSTALL_PACKAGES` for the update dialog | manifest-level |
| 6 | Limit feed to following profiles | Optionally rewrites the feed request to following-only | OFF, opt-in |
| 7 | Network content blocking | Blocks feed, stories, explore, reels, ads, suggestions and tracking surfaces through the extension | per-surface toggles |
| 8 | Notes tray | Moves Notes into its own tray row instead of the Direct inbox list | active |
| 9 | Popup hiding | Drops "Couldn't refresh feed" style popups caused by blocked surfaces | active |
| 10 | Quoicouview assets | Adds the Quoicouview screenshot/card resource | always active |
| 11 | Quoicouview mode | Experimental layout that puts the stories tray at the top of the Direct inbox | OFF, opt-in |
| 12 | Restart relay | One-shot activity in its own process that relaunches the app after settings changes | always active |
| 13 | Settings entry point | Long-press Home opens the HMX Insta settings page | always active |
| 14 | Suggested accounts in the story tray | Removes suggested accounts from the stories tray | controlled by the "Suggested accounts" block |

The signature-check bypass patch is **not** part of the baseline and must stay
disabled.

---

## 4. Baseline HMX Insta surfaces

These exist in the baseline and are entry points later milestones may extend
without touching protected code:

* **Settings page** — long-press the Home tab. Sections: content blocks
  (feed, explore, reels, friends lane, stories, instants, notes, suggested, ads,
  notifications button), navigation tab visibility, following-feed-only,
  display, experimental (Quoicouview), hardcore lock, cache cleaner, About.
* **Block screens** — each blocked surface renders a local HMX explanation
  screen instead of the Instagram surface.
* **Restart relay** — settings changes that need a clean process restart use
  HMX's own relay activity.
* **Quoicouview intro and stories tray row** — the extension already owns a row
  type and an intro card, injected by its own patch.
* **Disabled updater** — no network polling, no download, no
  `PackageInstaller`; a manual check only states that updates are published by
  HMXPanel.
* **Debug bridge** — development build aid, gated off in normal use.

The extension resolves its application context reflectively and uses
Instagram's own list-adapter row contract. It does not wrap authentication,
session, network or feed methods.

---

## 5. Explicitly absent in the baseline

The frozen dex contains no downloader and no ghost-mode code:

| Needle | Occurrences |
| --- | --- |
| `com.feurstagram.extension.downloader` | none |
| `com.feurstagram.ghost` | none |
| Downloader UI, queue, history, media resolver | none |
| Ghost preferences, policy, capability registry | none |
| Share target for media URLs | none |

Therefore every downloader or ghost behavior observed after this point is new
work, and every baseline behavior is preserved only if it still matches the
inventory above on the OPPO A38.

---

## 6. Read-only surfaces available to new work

These already exist and do not require a protected-method change:

* **Android share intents** — a new activity/filter can receive shared text or
  media from other apps.
* **HMX settings page** — new entries can be added in the extension's own
  process, gated by their own preferences.
* **HMX-owned row/view inserted by an existing patch** — the extension's own
  row definition (stories tray / Notes tray) is HMX code, not Instagram code.
* **Their own local storage** — app-private files and `MediaStore` for user
  downloads.

Anything that needs Instagram's media model or viewer state must first prove a
stable local observation point. If none exists, the contextual action is
disabled and documented as unsupported; URLs are never fabricated.

---

## 7. How to re-check the inventory

On the device (manual, after every milestone):

1. Cold start into the signed-in app.
2. Open Home feed; scroll; open a post.
3. Open a story; advance and close.
4. Open Reels; play and scroll.
5. Open Messages, Search, Profile; press Back from each tab.
6. Long-press Home → settings opens; toggle one block and confirm the restart
   relay returns to the app.
7. Confirm the label and icon still read HMX Insta.

On CI: the protected-method gate, the patch report (`14/0`) and the branding
checks run automatically; they do not replace the device pass.
