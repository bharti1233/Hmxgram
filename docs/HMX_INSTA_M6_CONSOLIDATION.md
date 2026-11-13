# HMX Insta+ — M6 consolidation into one application

**Status:** implemented on `feature/hmx-m6-consolidation`, stacked on the M5
branch `feature/hmx-m5-ghost-mode` (PR #23). Not merged. The new APK is built
by the gated workflow; **no device test has been performed**.

---

## 1. What the device showed, and why

The OPPO A38 run of the M3+M4+M5 build showed three launcher icons and two
broken features. Each symptom has one root cause, and each was confirmed
against the source and the dex of the published build — not guessed.

### 1.1 Three launcher icons

The downloader and the Ghost Mode screens were declared with a
`MAIN`/`LAUNCHER` intent filter, so Android treated each of them as its own
application entry. The share target never had that filter: it is a
`SEND`/`SEND_MULTIPLE` receiver and it is not in the launcher ✓.

**Fix:** both screens are now internal activities (`exported="false"`, no
launcher filter). They are opened from the HMX Insta settings — long-press the
Home tab — which is where the other HMX features live. The app keeps exactly
one launcher icon, Instagram's own, now labelled **HMX Insta+**.

The `SEND` and `SEND_MULTIPLE` filters of the share target are untouched:
Android sharing keeps working ✓.

### 1.2 The Ghost Mode screen crashed

The manifest named `com.feurstagram.extension.ghost.GhostModeActivity`, but the
class lives in `com.feurstagram.extension.ghost.android.GhostModeActivity`
(it was moved into an `android` sub-package when the pure and the Android
parts were separated). Launching the entry therefore failed with a
`ClassNotFoundException` — the screen was never reached.

**Fix:** the manifest now names the class that exists. A new gate makes this
impossible to merge: `validate_features.sh` extracts every
`com.feurstagram.extension.*` name out of the downloader and ghost patches and
fails when the matching source file is missing ✓. The gate was verified by
re-introducing the bug: it fails with the exact class name ✓.

### 1.3 The downloader screen was empty

The screen worked, but nothing ever reached it. The observation hook never
installed itself, for two independent reasons:

1. the fingerprint used a **method name** that does not exist (the media
   model's accessors are obfuscated);
2. the fingerprint's string criterion is matched against the **method**, not
   the class, and the method it targets holds exactly one key
   (`image_versions2`), not the three the fingerprint demanded.

With no anchor resolved, the patch printed that it installed nothing and the
registry stayed empty — the screen showed "No downloads yet" ✓, exactly what
the device showed.

**Fix:** the fingerprint now matches the behaviour — a no-argument method that
returns `Lcom/instagram/model/mediasize/ImageInfo;` and reads
`image_versions2`. That anchor was confirmed against the dex of the published
build, where it is the only method with both properties (`A3N`; the sibling
`A3M` returns the same type but reads `carousel_thumbnail`) ✓.

---

## 2. What changed

| File | Change |
| --- | --- |
| `patches/.../ghost/GhostModePatch.kt` | the correct class name; no launcher filter; not exported |
| `patches/.../downloader/DownloaderShareTargetPatch.kt` | the downloader screen loses its launcher filter and is not exported; the share target's filters are unchanged |
| `patches/.../downloader/DownloaderMediaObserverPatch.kt` | the fingerprint is matched against the method, not the class |
| `extension/Settings.java` | two rows in the HMX Insta settings: **Downloader** and **Ghost Mode** |
| `patches/.../branding/HmxInstaAppNamePatch.kt` | the launcher label is **HMX Insta+** |
| `scripts/ci/validate_features.sh` | two new gates: every declared component must exist; no launcher entry |
| `scripts/ci/validate_branding.sh` | the expected product name |
| `scripts/ci/test_downloader_isolation.sh` | two narrow, documented exceptions (the settings screen, the app-name patch) and the re-pinned digest |

### The integration

The two features are opened from the **HMX Insta settings** (long-press the
Home tab → *App* section):

* **Downloader** → the queue, the progress, the history, the observed media
  and the per-item actions;
* **Ghost Mode** → the privacy controls, off by default, with the reason for
  every control that cannot be implemented safely.

Both are ordinary activities of the app: Back returns to Instagram, they
appear in the task switcher as part of Instagram, and they add nothing to the
launcher ✓.

---

## 3b. A build gate that was comparing layout, not behaviour

The first consolidated build failed in the **protected-method gate**, with no
behavioural difference at all. Every hunk of every protected class differed
only in two places: the byte address printed on the method header and the
source file index. The instructions, the registers, the operands, the access
flags and the catch ranges were identical.

Both are dex **layout**: rebuilding a dex file moves every method header in
it, including the classes nobody patched, and moves the source file index
with the string table. The M6 build touched one more dex file than the
previous builds, so the untouched protected classes moved.

The gate now normalises the header address and the source file index, like it
already normalised the instruction offsets and the table indices ("Table
indices are layout, not behaviour"). Everything behavioural stays compared:
the method name, the descriptor, the source file name, every instruction,
register, operand and access flag, and the catch ranges. The self-test grew
two checks (the addresses may move, the source index may move) and still
fails on a changed opcode, a changed register and a changed access flag. The
same 12 surfaces, the same baseline pin, the same fail-closed behaviour.

This was a **bug fix in the gate**, not a relaxation of it: a gate that
fires on layout noise is a gate whose real findings get lost.

---

## 4. What did not change

* `main` was not modified and no branch was merged.
* No login, signup, authentication, session, password, cookie or
  authentication-header code was touched.
* No Instagram networking, feed parsing, story rendering, reel playback,
  profile or navigation code was touched.
* No Instagram request path was changed; the rejected `ghost.network.guard`
  exception is still rejected.
* The protected-method baseline, the 12 protected surfaces and the protected
  digest remain the gate they were (the digest was re-pinned with the
  attribution proof: reverting the five changed protected files reproduces
  the previous pin exactly).
* The share target's `SEND` / `SEND_MULTIPLE` filters are unchanged ✓.

---

## 5c. Acceptance criteria for the new APK

1. Exactly one launcher icon, labelled **HMX Insta+**.
2. Login and signup work.
3. The feed, stories, reels and profiles work.
4. The HMX Insta settings (long-press Home) offer *Downloader* and *Ghost
   Mode*, and both open inside the app.
5. The downloader lists the media that was browsed and can save it.
6. The Ghost Mode screen opens without crashing.
7. Android sharing still offers "HMX Insta" as a target.
8. No extra launcher entries.
9. All automated gates and the protected-method verification pass.

---

## 7. Device checklist (OPPO A38) — every row NOT TESTED

| # | Check | Result |
| --- | --- | --- |
| M6-1 | The launcher shows exactly one icon, "HMX Insta+" | NOT TESTED |
| M6-2 | Login and signup still work | NOT TESTED |
| M6-3 | Feed, stories, reels, profiles, navigation unchanged | NOT TESTED |
| M6-4 | Long-press Home → HMX Insta settings → the two new rows | NOT TESTED |
| M6-5 | *Downloader* opens inside the app and lists the browsed media | NOT TESTED |
| M6-6 | A photo, a video and a carousel download from the list | NOT TESTED |
| M6-7 | *Ghost Mode* opens without crashing; the unsupported controls show their reason | NOT TESTED |
| M6-8 | Android sharing still lists HMX Insta | NOT TESTED |
| M6-9 | No extra launcher entries | NOT TESTED |
