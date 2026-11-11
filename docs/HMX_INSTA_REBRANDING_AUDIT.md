# HMX Insta — Rebranding Audit

**Project:** HMX Insta (HMXPanel) — an Instagram patcher built on FeurStagram.
**Scope:** every occurrence of the upstream *FeurStagram* / *Feurstagram* name in
the repository, classified by what it is and what it may become.

Product identity after this change:

| | |
| --- | --- |
| Application name | **HMX Insta** |
| Logo wordmark | **HMX INSTA** |
| Developer / organisation | **HMXPanel** |
| Official logo file | `hm insta.png` (repository root, 530 × 532 PNG) |
| Upstream project | FeurStagram (GPL-3.0) — attribution preserved |

Nothing in the upstream project is destroyed: the patches, the packaging, the
GPL-3.0 licence, the NOTICE attribution and every internal identifier stay as
they are. Only what a user can see is renamed.

---

## 1. Classification summary

| Class | What it is | Decision |
| --- | --- | --- |
| 1. User-facing branding | Text a user reads in the app | **Renamed to HMX Insta** |
| 2. Launcher and application resources | Icon layers, app label | **Replaced with the official logo / HMX Insta** |
| 3. Updater infrastructure | Release API, popup, download, install | **Removed** (no polling, no download, no install) |
| 4. Internal technical identifiers | Package names, preferences, intents, file names | **Kept** — renaming them risks the patches and resets user data |
| 5. Required GPL-3.0 attribution | NOTICE, licence, About page | **Kept**, and stated explicitly on the About page |
| 6. Documentation and build metadata | Docs, CI, issue templates | **Docs rewritten; CI identifiers kept; user-facing templates rebranded** |

---

## 2. Class 1 — user-facing branding (renamed)

Every entry below was visible to a user and now says **HMX Insta**.

| File | Before | After |
| --- | --- | --- |
| `extensions/.../Settings.java` | settings page title `Feurstagram` | `HMX Insta` |
| `extensions/.../Settings.java` | toast `Feurstagram settings unavailable here` | `HMX Insta settings unavailable here` |
| `extensions/.../Settings.java` | toast `Feurstagram will clear the cache and restart the app…` | `HMX Insta will clear the cache…` |
| `extensions/.../Settings.java` | "Updates" page (auto-update toggle + *Check for updates*) | removed — see class 3 |
| `extensions/.../Settings.java` | "Support" page with upstream donation links | replaced by an **About HMX Insta** page (version, publisher, GPL attribution, source link, update policy) |
| `extensions/.../CacheCleaner.java` | toast `Feurstagram: clearing cache…` | `HMX Insta: clearing cache…` |
| `extensions/.../Block.java` | `IOException("Blocked by Feurstagram")` | `Blocked by HMX Insta` |
| `extensions/.../Onboarding.java` | coach mark `…to open Feurstagram settings` | `…to open HMX Insta settings` |
| `extensions/.../QuoicouviewIntro.java` | `…the Feurstagram settings.` | `…the HMX Insta settings.` |
| `extensions/.../FollowPrompt.java` | `@feurstagram_off is Feurstagram's official Instagram account.` | `Follow the project's official Instagram account for news.` (the upstream account is not claimed as ours) |
| `.github/ISSUE_TEMPLATE/bug_report.yml` | "Report a problem with Feurstagram", "Feurstagram version", "Feurstagram settings" | "…with HMX Insta", "HMX Insta version", "HMX Insta settings" |
| `.github/ISSUE_TEMPLATE/feature_request.yml` | "Suggest an improvement for Feurstagram" | "…for HMX Insta" |
| `.github/ISSUE_TEMPLATE/build_installation.yml` | "Feurstagram version or commit" | "HMX Insta version or commit" |
| `.github/ISSUE_TEMPLATE/config.yml` | security policy link → `jean-voila/Feurstagram` | → `bharti1233/Hmxgram` (verified) |
| `.github/RELEASE_TEMPLATE.md` | `# FeurStagram <version>` | `# HMX Insta <version>` |
| `.github/workflows/apk.yml` | published asset `feurstagram-<version>-test.apk` | `hmx-insta-<version>-test.apk` |
| `.github/workflows/apk.yml` | private release title `FeurStagram debug …` | `HMX Insta debug …` |
| `distribution/README.md` | `feurstagram-<version>-test.apk` | `hmx-insta-<version>-test.apk` |

---

## 3. Class 2 — launcher and application resources

| Item | Before | After |
| --- | --- | --- |
| Application label | `Instagram` (Instagram's `app_name`) | `HMX Insta`, set by the new **HMX Insta app name** patch |
| Launcher icon (`mipmap/ig_launcher_foreground`) | Instagram's artwork | the official HMX INSTA badge, per density |
| Launcher icon (`mipmap/ig_launcher_background`) | Instagram's artwork | flat deep dark `#0B0E1A` |
| Themed icon (`drawable/ig_launcher_monochrome`) | Instagram's vector | an HMX monogram vector, written by the same patch |
| Legacy square icon | not shipped by Instagram (adaptive only, minSdk 28) | generated under `branding/legacy/` for reference; not injected, because Instagram's manifest has no legacy icon slot |

Details, dimensions and the regeneration procedure are in
[HMX_INSTA_BRANDING_GUIDE.md](HMX_INSTA_BRANDING_GUIDE.md).

---

## 4. Class 3 — updater infrastructure (removed)

The upstream updater lived entirely in
`extensions/extension/src/main/java/com/feurstagram/extension/UpdateChecker.java`
(1,166 lines) and did all of this:

* polled `https://api.github.com/repos/jean-voila/Feurstagram/releases/latest` on
  every launch when the "Automatic update check" preference was on;
* compared the upstream tag with the installed version and showed an
  **"Update available — FeurStagram v…"** page;
* downloaded the upstream `.apk` and staged it through `PackageInstaller`,
  asking Android for the install confirmation;
* fetched the upstream release notes by tag for the "What's new" card.

That is what the installed app was showing. All of it is gone:

| Removed | Detail |
| --- | --- |
| Release API polling | no `api.github.com` call is left in the extension; the automatic launch check is a no-op |
| "Update available" page and its dialog | deleted |
| Download and `PackageInstaller` install path | deleted (`PackageInstaller` no longer appears in the extension) |
| Upstream download URL and asset selection | deleted |
| Background polling of upstream releases | none: the updater only ever ran on an explicit action, and that action is gone too |
| "Automatic update check" preference and its settings page | removed |
| Upstream donation links in "Support" | replaced by the About page |

What replaced it is a disabled seam, not a re-pointed upstream:

* `UpdateChecker.FEATURE_ENABLED = false` and `Config.isUpdaterEnabled()`
  (default `false`) — the updater cannot run;
* `UpdateChecker` contains **no network code at all**, so no request can be made
  by any path;
* a manual check says *"Updates are published by HMXPanel"* — no version, no
  download, no fake release;
* the future design is written down in [HMX_INSTA_UPDATER.md](HMX_INSTA_UPDATER.md).

`scripts/ci/validate_branding.sh` fails the build if `api.github.com`,
`jean-voila/Feurstagram`, `PackageInstaller`, `browser_download_url` or the
download file name come back into the extension, or if the feature flag is
switched on.

---

## 5. Class 4 — internal technical identifiers (kept on purpose)

These are not visible to a user and they are load-bearing. Renaming them would
break the patches or wipe user data.

| Identifier | Where | Why it stays |
| --- | --- | --- |
| `com.feurstagram.extension` (package) | extension sources, `.mpe` artifact | renaming changes the extension's identity that the patches inject |
| `com.feurstagram.patches.*` (package) | patch sources | same; the patch bundle is compiled against it |
| `com.instagram.android.feurstagram` | clone package name | the side-by-side clone's package; changing it would need a separate approved clone strategy |
| `feurstagram_prefs` | `Config.PREFS` | renaming the preferences file resets every user's settings, including the permanent lock |
| `com.feurstagram.extension.RESTART_PID`, `…UPDATE_CANCEL`, `com.feurstagram.debug.*` | intents and broadcast actions | internal wiring; the debug bridge is development-only |
| `feurstagram/quoicouview.jpg` | asset path in the patch bundle | internal asset path |
| `feurstagram_stories_tray` | view tag | internal |
| `FEURSTAGRAM_KEYSTORE*`, `feurstagram.keystore` | build environment variables | the release keystore plumbing in `build.sh` and CI |
| `feurstagram.apk`, `feurstagram-*.apk.sha256` | workspace files in CI | referenced by the validators, the publish gates and the report artifacts |
| `feurstagram|morphe` detection patterns | `scripts/ci/validate_input_apk.sh`, `validate_bundle.sh`, `verify-base.yml` | these *detect* an already-patched build and must keep matching the upstream artefacts |
| `CN=Feurstagram` / `O=Feurstagram` checks | same validators | same reason: they reject a patched APK used as input |

---

## 6. Class 5 — required GPL-3.0 attribution (kept)

| File | Content |
| --- | --- |
| `LICENSE` | the GNU GPL v3 text, unchanged |
| `NOTICE` | "Feurstagram", the copyright line "Copyright (C) Feurstagram contributors.", the note about the Morphe name and the line "All other code is original to FeurStagram." — all unchanged |
| About page (in-app) | states that HMX Insta is built on FeurStagram, free and open source under GPL-3.0, and names FeurStagram's contributors as the original authors of the patches and the companion app |

Removing any of these would break the licence. They are also the reason the
rebranding is a *rebrand* and not a fork presented as something else.

---

## 7. Class 6 — documentation and build metadata

| Area | Decision |
| --- | --- |
| `docs/HMX_INSTA_*.md` (new) | this audit, the branding guide and the updater design |
| `docs/HMXPANEL_*.md` (new) | short pointers to the HMX Insta documents, so both naming schemes in the request resolve to one canonical set |
| Existing `docs/investigations/*`, `HMX_INSTAPLUS_*.md` | kept: they are engineering records of the packaging work, and they name the upstream project accurately |
| `build.sh`, `.github/workflows/*`, `scripts/ci/*` | internal identifiers kept (see class 4); the *user-visible* parts were rebranded |
| `Dockerfile`, `settings.gradle.kts`, `patches/build.gradle.kts` | internal names kept |

---

## 8. What was deliberately **not** changed

* Instagram's own strings, icons, layouts and behaviour — they are upstream's.
* The package name `com.instagram.android` and the version
  `454.0.0.54.77` / versionCode `386014712`.
* The 13 existing Morphe patches and the patch list; a **14th** patch,
  "HMX Insta app name", was added and is now part of the expected patch set.
* The APKEditor V1.4.9 universal packaging, the `-clean-meta` merge flag, the
  bundle/standalone validators, the signing verification and the private debug
  distribution — all unchanged.

---

## 9. Verification status

| Check | Result |
| --- | --- |
| `scripts/ci/validate_branding.sh` | passed locally; wired into CI |
| `scripts/ci/validate_repo.sh` | passed locally |
| `scripts/ci/test_universal_packaging.sh`, `test_publish_gates.sh`, `test_patch_report.sh` | passed locally |
| Launcher artwork reproducibility | passed — the committed assets are byte-identical to a fresh generation from the logo |
| Icon replacement on a real APK | verified against the stock base APK: 11 icon files replaced, the archive stays valid |
| CI on the pull request | see the pull request |
| Device install of the rebranded build | **not yet performed** — see the final report |
