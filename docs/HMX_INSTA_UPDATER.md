# HMX Insta — Updater Architecture

The upstream FeurStagram updater is **removed**. This document records what was
removed, what replaced it, and the rules a future HMXPanel updater has to obey
before it may be enabled.

---

## 1. What the installed app was doing

`extensions/extension/src/main/java/com/feurstagram/extension/UpdateChecker.java`
(1,166 lines before this change) implemented a complete in-app updater:

| Behaviour | Detail |
| --- | --- |
| Background polling | on every launch, if the "Automatic update check" preference was on, it fetched `https://api.github.com/repos/jean-voila/Feurstagram/releases/latest` |
| Version comparison | the upstream tag was normalised (`v454-0-0-54-77` → `454.0.0.54.77`) and compared with the installed `versionName` |
| Popup | an "Update available — FeurStagram v…" page with the release notes, an *Update* button and a *Release page* fallback |
| Download | the `.apk` asset was downloaded to the app's external files directory, with a progress page and a cancellable ongoing notification |
| Install | staged through `PackageInstaller` and committed, so Android showed its own install confirmation |
| Release notes | fetched per tag for the "What's new" card after a version change |

That is the source of the "Update available — FeurStagram v455…" screen seen in
the installed app. It pointed at the *upstream* project's releases, so it
offered an upstream build of an app that is not HMX Insta.

---

## 2. What is removed

| Removed | Where |
| --- | --- |
| The GitHub release API client, its URLs and its user agent | `UpdateChecker` |
| The automatic launch check and the "Automatic update check" preference | `UpdateChecker`, `Config`, `Settings` |
| The "Update available" page and its dialog | `UpdateChecker` |
| The APK download, the progress notification and the cancel action | `UpdateChecker` |
| The `PackageInstaller` staging and install path | `UpdateChecker` |
| The release-notes ("What's new") fetch | `UpdateChecker` |
| The "Updates" settings page | `Settings` |
| The upstream donation links | `Settings` (replaced by the About page) |

The replacement `UpdateChecker` is 76 lines and contains **no network code, no
URL and no install path**:

```java
public static final boolean FEATURE_ENABLED = false;

public static void check(Context context) { if (!enabled()) return; }
public static void checkNow(Context context) { /* "Updates are published by HMXPanel" */ }
public static void checkWhatsNew(Context context) { if (!enabled()) return; }
public static void showLatestNotes(Context context) { if (!enabled()) return; }

private static boolean enabled() { return FEATURE_ENABLED && Config.isUpdaterEnabled(); }
```

Consequences, all of them required by the brief:

* no background request continues polling any release server;
* the FeurStagram update popup cannot appear;
* no upstream download action exists;
* no fake HMX Insta update is displayed — a manual check only says where
  updates come from, without a version, a download or an install;
* nothing is installed automatically;
* Instagram's own behaviour is untouched.

`scripts/ci/validate_branding.sh` enforces all of this in CI: the extension may
not contain `api.github.com`, `jean-voila/Feurstagram`, `PackageInstaller`,
`browser_download_url` or the download file name, and the feature flag must stay
off.

---

## 3. The seam the future updater plugs into

`Config.isUpdaterEnabled()` reads the `hmx_updater_enabled` preference and
defaults to **false**. Even with the preference set, `FEATURE_ENABLED` keeps the
updater off, so no configuration mistake can bring the old behaviour back.

The future updater will be implemented behind this seam and may only be enabled
when **every** one of these rules holds:

| Rule | Requirement |
| --- | --- |
| Trusted source | only releases published by HMXPanel in a verified repository are considered — never a third-party mirror, never the upstream project |
| Version verification | the release's version is compared with the installed version using a strict, dotted numeric comparison (no lexical comparison) |
| Digest verification | the APK's SHA-256 is compared with the digest published alongside the release, before anything is staged |
| Signature verification | the APK's signing certificate is compared with the pinned HMXPanel certificate; a mismatch aborts the update |
| Explicit confirmation | the user confirms the update; the flow never installs silently |
| No automatic install | the update is staged and handed to Android's own installer; the app never installs anything on its own |
| No background polling | a check happens only when the user asks for one; there is no scheduled or launch-time fetch |
| Failure is inert | a failed check is silent for an automatic check and a single message for a manual one; it never blocks the app |

The intended shape of the flow:

```text
user asks for a check
      -> fetch the HMXPanel release index (the only endpoint)
      -> compare the version with the installed one
      -> download the APK to the app's own directory
      -> verify the SHA-256 against the published digest
      -> verify the signing certificate against the pinned HMXPanel certificate
      -> show the release notes and an explicit "Update" action
      -> stage through PackageInstaller and let Android confirm
```

---

## 4. Why the upstream updater was not re-pointed

Re-pointing it at an HMXPanel repository would have been a smaller change, but
it would have kept the parts that are unsafe by construction:

* it fetched and installed an APK over the network and handed it to the system
  installer, with no digest check and no certificate check;
* it compared versions with a bespoke parser;
* it ran a launch-time request on every start.

Those are the reasons the flow was removed rather than repointed. The verified
flow is a different program, and it stays switched off until it exists.

---

## 5. Current status

| Item | State |
| --- | --- |
| Upstream FeurStagram updater | removed |
| HMXPanel updater | not implemented; behind a disabled flag |
| Updates page in settings | removed; the About page states the update policy |
| Fake updates shown | never |
| Automatic installs | never |
| Background release polling | none |
| CI guard | `scripts/ci/validate_branding.sh` |
