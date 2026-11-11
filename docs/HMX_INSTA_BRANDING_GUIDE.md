# HMX Insta — Branding Guide

How the HMX Insta identity is built, which files carry it, and how to regenerate
the artwork. The classification of every upstream name is in
[HMX_INSTA_REBRANDING_AUDIT.md](HMX_INSTA_REBRANDING_AUDIT.md).

| | |
| --- | --- |
| Application name | **HMX Insta** |
| Logo wordmark | **HMX INSTA** |
| Developer / organisation | **HMXPanel** |
| Source repository | <https://github.com/bharti1233/Hmxgram> |

---

## 1. The official logo

| Property | Value |
| --- | --- |
| File | `hm insta.png` (repository root) |
| Size | 329,532 bytes |
| Format | PNG, 8-bit, **colour type 2 (RGB, no alpha channel)**, non-interlaced |
| Dimensions | **530 × 532 px** (aspect ratio 0.996 — effectively square) |
| SHA-256 | `5548fc968b0110633d82e91da22ebea152f12ef1d02b3c157dd7a557bef75bcd` |
| Content | a rounded-square badge: a blue → purple → orange gradient, a white camera-aperture glyph, the wordmark **HMX** over **INSTA**, and a small camera mark; the field outside the badge is white |

> The brief named the file `hmx insta.png`. The file in the repository is
> **`hm insta.png`** (no "x" after "hm"). The generator accepts either name and
> uses the one that exists. The file is **never modified** — it is read, resized
> and masked, and the original stays byte-identical.

The artwork is used as-is: no redraw, no recolouring, no replacement of the
wordmark with a generic "HMX" glyph, no stretching. The badge's own rounded
corners are preserved; only the white field around it is made transparent, and
only where the badge's silhouette allows it (per row, between the leftmost and
rightmost badge pixel).

---

## 2. Generated launcher assets

`scripts/branding/generate_launcher_icons.py` regenerates everything below from
the logo. It is deterministic: the same logo always produces byte-identical
files, which is what makes the CI staleness check possible.

### Adaptive icon layers (injected into the APK)

Android adaptive icons are 108 dp. The visible area is the 72 dp circle in the
middle of that 108 dp canvas, so the badge is scaled to **62 %** of the canvas
and centred: the whole wordmark stays inside the mask, and only the badge's
outer corners (plain gradient, no lettering) can be clipped by a circular
launcher mask.

| File | Size | Content |
| --- | --- | --- |
| `patches/src/main/resources/branding/foreground-mdpi.png` | 108 × 108 | the badge, transparent around it |
| `patches/src/main/resources/branding/foreground-hdpi.png` | 162 × 162 | idem |
| `patches/src/main/resources/branding/foreground-xhdpi.png` | 216 × 216 | idem |
| `patches/src/main/resources/branding/foreground-xxhdpi.png` | 324 × 324 | idem |
| `patches/src/main/resources/branding/foreground-xxxhdpi.png` | 432 × 432 | idem |
| `patches/src/main/resources/branding/background-mdpi.png` | 108 × 108 | flat deep dark `#0B0E1A` |
| `patches/src/main/resources/branding/background-hdpi.png` | 162 × 162 | idem |
| `patches/src/main/resources/branding/background-xhdpi.png` | 216 × 216 | idem |
| `patches/src/main/resources/branding/background-xxhdpi.png` | 324 × 324 | idem |
| `patches/src/main/resources/branding/background-xxxhdpi.png` | 432 × 432 | idem |
| `patches/src/main/resources/branding/ig_launcher_monochrome.xml` | vector | themed-icon (Android 13+) silhouette: an "H" monogram on the 108 dp viewport |

### Legacy square icons (reference only)

| File | Size |
| --- | --- |
| `branding/legacy/ic_launcher-mdpi.png` | 48 × 48 |
| `branding/legacy/ic_launcher-hdpi.png` | 72 × 72 |
| `branding/legacy/ic_launcher-xhdpi.png` | 96 × 96 |
| `branding/legacy/ic_launcher-xxhdpi.png` | 144 × 144 |
| `branding/legacy/ic_launcher-xxxhdpi.png` | 192 × 192 |

These are the full badge on an opaque field. They are **not injected**: the
target app (Instagram, minSdk 28) ships a single adaptive icon, so its manifest
has no legacy icon resource to replace. They are kept so a legacy launcher, a
future fork with a lower minSdk, or a website can use the same artwork.

### Regenerating

```sh
python3 scripts/branding/generate_launcher_icons.py
```

The script prints the logo it used and every file it wrote. `scripts/ci/validate_branding.sh`
regenerates into a temporary copy and compares, so a stale asset fails CI
instead of shipping.

---

## 3. How the artwork reaches the APK

Instagram ships one adaptive icon:

```
mipmap/ig_launcher            -> res/<obfuscated>.xml   (adaptive-icon)
  background  -> mipmap/ig_launcher_background   (PNG per density)
  foreground  -> mipmap/ig_launcher_foreground   (PNG per density)
  monochrome  -> drawable/ig_launcher_monochrome (vector)
```

The APK's file names are obfuscated, so they are resolved from the resource
table rather than guessed.

### PNG layers — packaging step

`scripts/branding/apply_launcher_branding.py` runs in `build.sh` right after the
APKEditor merge and before the Morphe patch:

1. `aapt2 dump resources` resolves `mipmap/ig_launcher_foreground`,
   `mipmap/ig_launcher_background` and `drawable/ig_launcher_monochrome` to their
   per-density file paths;
2. each PNG is replaced by the generated artwork, resized to the layer's own
   pixel size so Instagram's geometry is preserved exactly (108/162/216/324/432);
3. the archive is rewritten and the replaced files are listed.

The script fails loudly: a missing resource, a non-PNG layer or a missing
asset aborts the build. `build.sh` also aborts when the branding assets are
missing — an Instagram-branded APK is never patched silently.

### Monochrome layer — Morphe patch

The themed-icon layer is a vector. A vector cannot be dropped into an APK
without the resource compiler (`aapt2 compile` produces a flat container, not
the APK's binary XML), so it is replaced by the **HMX Insta app name** patch
instead: it writes the XML as text, and the patcher compiles it. The patch
probes the candidate paths and prints an explicit line if the layer is not
found, so the outcome is never silent.

### Application label — Morphe patch

The same patch renames the app. The launcher reads the label from the
manifest's `android:label`, which points at a string resource (`app_name`);
the patch sets that one string to `HMX Insta`, which re-labels the launcher
entry, the task-switcher entry and the app information screen at once. If the
string cannot be found, it falls back to setting the manifest attribute
directly.

---

## 4. In-app branding

| Surface | Text |
| --- | --- |
| Settings page title | `HMX Insta` |
| Settings → About | *HMX Insta*, the installed version, *Published by HMXPanel.*, the GPL-3.0 attribution to FeurStagram and its contributors, and the update policy |
| Settings → About → Source code | <https://github.com/bharti1233/Hmxgram> |
| Cache-clearing toast | `HMX Insta: clearing cache…` |
| Restart confirmation | `HMX Insta will clear the cache and restart the app to apply your changes.` |
| Coach mark | `Press and hold the Home button below to open HMX Insta settings.` |
| Quoicouview card | `…the HMX Insta settings.` |
| Blocked feed error | `Blocked by HMX Insta` |
| Follow card | `Follow the project's official Instagram account for news.` |

The About page deliberately keeps the upstream attribution: HMX Insta is
GPL-3.0 software built on FeurStagram, and saying so is required, not optional.

---

## 5. What is *not* rebranded

* Instagram's own strings, icons, splash and layout.
* The package name `com.instagram.android` and the version
  `454.0.0.54.77` / versionCode `386014712`.
* The extension's package (`com.feurstagram.extension`), the patch packages,
  the preferences file, the intent actions and the CI artifact names — internal
  identifiers whose renaming would break the patches or reset user settings.
* The detection patterns that spot an already-patched upstream build.

---

## 6. Checklist before shipping a new logo

1. Replace `hm insta.png` (or `hmx insta.png`) in the repository root. Never
   edit it in place.
2. Run `python3 scripts/branding/generate_launcher_icons.py`.
3. Run `bash scripts/ci/validate_branding.sh` — it fails if the committed
   assets do not match a fresh generation.
4. Check the rendered result on a device: the launcher icon, the app name and
   the About page.
