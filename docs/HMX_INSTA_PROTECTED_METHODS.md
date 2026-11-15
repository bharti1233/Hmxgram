# HMX Insta — protected Instagram surfaces (M0 record, enforced)

**Purpose:** freeze the Instagram code that new work must not change. This is
the human-readable half of the record; the machine-readable half is
`scripts/ci/protected_methods.json`. Unlike the first M0 draft, the verifier is
**real and enforced now**: `scripts/ci/verify_protected_methods.py` runs inside
the gated APK workflow before any publish, and its offline self-test runs in
`.github/workflows/ci.yml`.

Baseline for every statement below:
`a5bace00e84094f06622ff8f47ae60990ded8e09`
(`backup/hmx-insta-last-known-good-454`), source
`6474df0d504b7fdce45bb6c5c1e7d19e24b0ed8b`, APK
`debug-test-43210749344-1` (protected copy
`hmx-baseline-454-verified`) /
`sha256:6a327db6ec9c0ccc83f82a76eaf62451c6fac526131e6f12b260bec2f5cd2822`.

---

## 1. The rule

> **New features observe. They do not rewrite Instagram.**

Nothing in the downloader or ghost mode may modify:

1. **authentication** — key-hash trust, signing checks, login;
2. **session initialisation** — launcher/bootstrap, signed-in decision;
3. **feed loading** — request construction, pagination headers, feed parsing;
4. **media parsing** — Media deserialisation and field dispatch.

Those four are absolute: no exceptions, no "unavoidable" carve-out recorded at
M0. If a future requirement appears to need one, it must come back as an
explicit design review with device evidence — it is not pre-approved here.

One additional surface allows a **declared, read-only observation**:
`IgRecyclerViewAdapter` row binding (Tier 3). That is the only Instagram method
new work may add instructions to, and the exact diff is bounded below.

**Ghost mode has no declared network exception.** A network guard was
considered and **rejected for v2**: intercepting global requests is exactly
what the protected-surface policy forbids. Any ghost capability that cannot be
implemented without a network or authentication hook is reported *unsupported*;
it is not implemented by changing Instagram's request path.

---

## 2. How the rule is enforced (M0 onward)

`scripts/ci/verify_protected_methods.py` runs on every gated APK build:

1. extracts the dex files of the **baseline APK** (`hmx-baseline-454-verified`)
   and the **candidate APK**;
2. resolves every surface below in **both** APKs using the same stable anchors
   (descriptors, wire strings, regexes on stable shapes) — obfuscated names are
   never hardcoded;
3. canonicalises each resolved class with `dexdump -d`:
   * keeps class descriptor, access flags, superclass and interfaces;
   * keeps every field (name, type, access, static value);
   * keeps every method (name, type, access, registers/ins/outs);
   * keeps every instruction opcode, register, literal and resolved reference;
   * maps catch ranges to method-relative instruction ordinals and keeps catch
     types;
   * drops instruction offsets, code-unit addresses, debug info, line numbers,
     `positions`/`locals`, and dex table indices;
4. requires **equality after canonicalisation** for every enforced surface;
5. allows only declared exceptions, and only once their exact-diff check is
   activated (the row-binding exception stays record-only until M4).

If an anchor cannot be resolved unambiguously, the gate **fails closed**. The
verifier also refuses a baseline APK whose SHA-256 does not match the manifest
pin, so replacing the reference cannot silently widen the gate.

This is the test that would have caught the previous regression: the old
capture hook inserted a call into the media-parse path and invalidated the
dex. The new guard rejects *any* change to those methods.

The `MIN_APPLIED_PATCHES` gate is now the exact `14`: a dropped baseline patch
fails the build instead of passing at 10/14.

---

## 3. Protected surfaces

### 3.1 Authentication — absolute

| # | Surface | Anchor at baseline | Concrete evidence |
| --- | --- | --- | --- |
| A1 | Key-hash allowlist check | class containing `"Invalid SHA256 key hash"`; static `boolean(X.3uq)` | 454 resolves `X.1eX.A01`; verified by the existing *Signature check bypass* patch source |
| A2 | Family-app scope trust | static `boolean(X.3uq, X.3uq, Z)`, or (454) instance `boolean(CallerIdentity, Z)` reading both key hashes from the caller identity | same class family; shape-only anchor because the class name is obfuscated |
| A3 | APK signature / install identity | `AndroidManifest.xml` signature and the platform PackageManager check | packaging gate already covers the signature; no code patch may target it |

**Allowed new changes:** none.
**Explicitly out of scope:** enabling *Signature check bypass*. It stays off by
default and is not a login workaround. Any login investigation is separate and
evidence-first.

### 3.2 Network / request lifecycle — absolute

| # | Surface | Anchor at baseline | Concrete evidence |
| --- | --- | --- | --- |
| N1 | `TigonServiceLayer.startRequest` | `Lcom/instagram/api/tigon/TigonServiceLayer;`, name starts with `startRequest`, body loads a `java.net.URI` field | `startRequest(LX/03xt;LX/03yf;LX/03zp;)LX/0Bhm;` |
| N2 | `TigonServiceLayer.startRequestInternal` | same class, same shape | `startRequestInternal(LX/03xt;LX/03yf;LX/03zp;)LX/0Bhm;` |
| N3 | Request URI selection | the `iget-object` of type `java.net.URI` inside the resolved start-request method | used by *Network content blocking* as its insertion point |

**Baseline already modifies this:** *Network content blocking* inserts
`Block.throwIfBlocked(URI)V` after the URI load in the resolved method. That
modification is part of the baseline and is compared against, not removed.

**Allowed new changes:** **none.**

* The downloader resolves media through the share intent or its own read-only
  source, never through the request layer.
* Ghost mode may not add a network guard. `ghost.network.guard` is recorded in
  `rejected_exceptions` and must not reappear without a new design decision.

### 3.3 Media parsing — absolute

| # | Surface | Anchor at baseline | Concrete evidence |
| --- | --- | --- | --- |
| P1 | Feed-item type dispatch | method name contains `parseFromJson` **and** the wire tokens `media_or_ad`, `clips_netego`, `stories_netego`, `ad4ad` | *Feed item filtering* rewrites the type token and filters media at this level |
| P2 | Media entry parser | returns `Lcom/instagram/feed/media/Media;`, contains `Media:null_pointer_exception` + `Media:array_out_of_bounds_exception`, calls the field dispatcher `(parser, String, Map)V` | 454 uses the inline variant: `(parser, Z)` entry point that hashes field names and recurses for carousel children |
| P3 | Media codec wrapper | same return type and error strings, calls a codec `(UserSession, parser, Z)Z` | 452/453 shape; kept for cross-version resolution |
| P4 | `unsafeParseFromJson` | method name exactly `unsafeParseFromJson` | referenced by the existing patch |
| P5 | Per-field dispatch / field-name hash | `invoke-static` returning `void` with `(parser, String, Map)`, or `String.hashCode()` on a loaded field name | *Feed item filtering* inserts `Block.onMediaField(String)V` here |

**Baseline already modifies all of these.** The candidate must reproduce the
baseline instructions exactly; **no new capture hook, no extra field hook, no
return-value rewriting, no dex surgery is permitted here — ever.**

**Hard prohibition for the downloader:** it must never obtain media by hooking
the parser. Tier 1 uses the system share intent; Tier 2/3 use the row model.

### 3.4 Feed loading — absolute

| # | Surface | Anchor at baseline | Concrete evidence |
| --- | --- | --- | --- |
| F1 | Main-feed request object | class containing `"Request{mReason="` **and** `", mInstanceNumber="` | obfuscated class name; matched by these debug strings |
| F2 | Feed request constructor | `<init>` of F1 | *Limit feed to following* wraps the header-map store |
| F3 | Feed header assembly | method containing `pagination_source` and `FEED_REQUEST_SENT` | used to discover the request's header `Map` field |

**Baseline already modifies F2.** New work: **none**.

### 3.5 Session initialisation and app start — absolute

| # | Surface | Anchor at baseline | Concrete evidence |
| --- | --- | --- | --- |
| S1 | Launcher entry | `Lcom/instagram/mainactivity/LauncherActivity;` | manifest launcher; methods include `onCreate(Bundle)V`, `A05`, `A06` |
| S2 | Main activity / bootstrap | `Lcom/instagram/mainactivity/InstagramMainActivity;` | 233 methods at baseline; lifecycle + session bootstrap |
| S3 | App process / application class | whatever `AndroidManifest.xml` declares as `android:name` for the application | not modified by any new patch |

**Allowed new changes:** none. No startup hook, no `ContentProvider`
initialisation, no process-wide `Application` subclass.

### 3.6 Story viewer and story surfaces

| # | Surface | Anchor at baseline | Concrete evidence |
| --- | --- | --- | --- |
| R1 | Story viewer fragment | `Linstagram/features/stories/fragment/ReelViewerFragment;` | 556 methods at baseline; lifecycle, playback, receipts |
| R2 | Story tray tap | `void` method containing `fragment_not_visible` + `story_pog_blocked` | *Quoicouview mode* gates the tray tap |
| R3 | Story launch environment | `boolean` method containing `ENVIRONMENT_RESUMED`, `fragment_resumed`, `feed_visible` | *Quoicouview mode* |
| R4 | Story tray launch | `void` method containing `MainFeedReelTrayController.preloadAndLaunchViewer` | *Quoicouview mode* |
| R5 | Story viewer push | `void` method containing `ReelViewerFragment.BACK_STACK_NAME` | *Quoicouview mode* |

**Allowed new changes:** none. The final story download surface will be decided
at M5 and must be an HMX-owned view or the existing HMX story-tray row, never an
insertion into Instagram's viewer.

### 3.7 List adapter / row binding — one declared read-only exception

| # | Surface | Anchor at baseline | Concrete evidence |
| --- | --- | --- | --- |
| L1 | Adapter constructor | `<init>` with one parameter, containing `"IgRecyclerViewAdapter.mDefinitions"` | *Quoicouview mode* adds its own definition here |
| L2 | Row definition: bind | inside the definition object: `void bind(Holder, Model)` where `Holder` is the model's holder type | signature discovered structurally; this is the **only** new insertion point |

**Allowed new change (Tier 3):** exactly one
`invoke-static/range { holder, model }` inserted at the **first instruction** of
the resolved `bind(Holder, Model)` method, calling a defensive
`DownloaderBinder.observe(holder, model)`.

The inserted call must:

* only read the model parameter and the holder reference;
* never throw (catch everything; a failed observation must be a no-op);
* never remove, reorder or rewrite an existing instruction;
* never touch a view until the user has enabled in-context actions, and then
  only the holder's own view group, tagged so recycled rows are handled;
* be inert when the downloader master switch is off (one boolean check).

Declared exception id: `tier2.row_binding.observe`. Any second method or any
additional instruction is a gate failure.

**Activation:** the exception is declared in the manifest with
`enforce: false` until M4. At M4 it becomes an exact-diff check: the resolved
`bind(Holder, Model)` body in the candidate may differ from the baseline by
exactly that one inserted call, and by nothing else.

### 3.8 Navigation, toasts, tabs, trays

| # | Surface | Anchor at baseline | Baseline patch that touches it |
| --- | --- | --- | --- |
| U1 | Back navigation | zero-arg `boolean` method containing `NavigationController.onBackPress` | *Hidden Home in Back history* |
| U2 | App toast | zero-arg `void show()` on a class whose superclass is `android/widget/Toast` | *Popup hiding* |
| U3 | Main tab change | `void` method containing `MainTabControllerImpl.onTabChanged: new ` | *Quoicouview mode* |
| U4 | Tab host create | `onCreate(Bundle)` containing `IgSwipeableTabHostFragment.onCreate` + `EAGER_FRAGMENT_LOADING` | *Quoicouview mode* |
| U5 | Settings entry binder | tab-bar binder `<init>(View)` with the tab-bar class shape | *Settings entry point* |
| U6 | Story-tray type getter | `trayEntryTypeGetter` on the tray-entry block class | *Suggested accounts in the story tray* |
| U7 | Notes tray binding | `void` method with one parameter containing `notes_tray_bind_` | *Notes tray* |

**Allowed new changes:** none. The settings pages and the downloader page are
**our** UI, reached through the existing *Settings entry point* route; they do
not modify Instagram's tab bar or navigation.

### 3.9 Branding — identity only

| # | Surface | Anchor | Note |
| --- | --- | --- | --- |
| B1 | App label and launcher icon | `AndroidManifest.xml` + resources | baseline branding is canonical; no new patch may change identity |
| B2 | App-name patch | *HMX Insta app name* | must remain applied |

---

## 4. Concrete class inventory from the baseline APK

These were read with `dexdump` from the verified baseline APK. Obfuscated
classes that are found only through fingerprints are deliberately **not**
hardcoded.

| Descriptor | Dex | Methods | Role |
| --- | --- | --- | --- |
| `Lcom/instagram/api/tigon/TigonServiceLayer;` | `classes31.dex` | 38 | network layer; contains `startRequest` and `startRequestInternal` |
| `Lcom/instagram/feed/media/Media;` | `classes.dex` | 1,482 | media model; the parsers live in resolver classes and return this type |
| `Lcom/instagram/mainactivity/LauncherActivity;` | `classes5.dex` | 15 | launcher; `onCreate`, `A05`, `A06` |
| `Lcom/instagram/mainactivity/InstagramMainActivity;` | `classes2.dex` | 233 | main bootstrap; 233 methods frozen |
| `Linstagram/features/stories/fragment/ReelViewerFragment;` | `classes31.dex` | 556 | story viewer; whole class frozen |

Obfuscated, resolved only by anchor:

| Surface | Why no descriptor is recorded |
| --- | --- |
| key-hash trust (`X.1eX` / `X.1eY` at 454) | class and methods are obfuscated per release |
| media parser / codec / field dispatch | obfuscated per release; only wire strings and method shapes are stable |
| main-feed request class | obfuscated; matched by its debug `toString()` literals |
| `IgRecyclerViewAdapter` row definitions | obfuscated; matched by the `IgRecyclerViewAdapter.mDefinitions` string and method shape |
| `IgToastShow`, `NavigationController`, tab controllers | obfuscated; matched by log strings / superclass shape |

---

## 5. What a reviewer should check

1. Is every new patch's target listed in this document as either *forbidden*
   or a *declared exception*?
2. Does any new code path call `Media.parseFromJson`, the parser, the codec, the
   field dispatcher, `TigonServiceLayer`, the feed request class, the login
   flow, or the launcher?
3. Does the downloader ever read cookies, `Authorization`, session data or
   account identifiers? It must not.
4. Does the ghost guard remain inert with the master switch off?
5. Does the manager still run the protected-method gate (M0 onward) and fail
   closed when an anchor is ambiguous or the baseline digest is wrong?
6. Is every new capability listed in the baseline feature inventory and, when
   it cannot be done without a forbidden hook, reported as unsupported?

A "yes" to 2, 3 (reading secrets) or 4 (not inert) is a design failure, not a
review note.
