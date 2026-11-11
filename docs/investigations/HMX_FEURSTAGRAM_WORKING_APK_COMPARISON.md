# HMX FeurStagram — Working Official APK vs Failed HMX Build

- **Date:** 2026-11-11
- **Working artifact:** `feurstagram-454-0-0-54-77.apk` (downloaded copy: `feurstagram-454-0-0-54-77 (1).apk`)
- **Working artifact SHA-256:** `354553ff5431766227bfdd7c932e95b651874d79bc43d9cbff11c94e644533a5`
- **Working artifact source:** GitHub release `v454-0-0-54-77` of `jean-voila/FeurStagram` (asset size 162,181,070 bytes; digest matches the release asset exactly)
- **Failed HMX artifact:** `feurstagram-454.0.0.54.77-test.apk`, SHA-256 `68446312f639ca6d873e3dee5fdcc5439f9b0cb3e969153c308cbbb5444c2dd2`
- **Stock input:** `Instagram.apk` (APK `Apk-input-original`), SHA-256 `b334feef23722f0364a80c2c9e99af48e0c460cc652213d3f1a919f2007e5136`
- **Device class:** OPPO A38 (CPH2579), ColorOS 15 / Android 15 / API 35, `arm64-v8a`

Method: read-only inspection with `aapt`, `aapt2`, `apksigner`, `unzip`, `zipfile`, plus source-level
reading of APKEditor `V1.4.9` and the upstream FeurStagram `build.sh` / `Dockerfile` / `CHANGELOG.md`.
No APK was rebuilt, patched, re-signed, or published during this investigation. The production workflow
was not modified.

---

## 1. Verdict

**The official APK is a fully merged universal APK produced by APKEditor from an APKMirror `.apkm`
split bundle, then patched by Morphe and signed with the FeurStagram release key.**

The HMX build is a **bundle-derived base APK** patched in place with no merge step. It still declares
`android:requiredSplitTypes="base__density"` and ships none of the density splits.

The install-relevant difference is exactly the split packaging:

| | Official (installs on Android 15) | HMX build (fails) |
| --- | --- | --- |
| Split attributes in manifest | none | `requiredSplitTypes="base__density"`, `splitTypes=""` |
| Play split meta-data | removed | present |
| `res/xml/splits0.xml` | removed | present |
| Density split payload | merged (14,453 `res/` files) | absent (7,202 `res/` files) |

The official APK proves that the working recipe is: **feed a split bundle, merge it with APKEditor,
then patch.** Removing the attributes without merging would install, but the artifact would still be
missing roughly 7,250 density-qualified resource files (see §6).

---

## 2. Official APK identity

| Property | Value |
| --- | --- |
| Release asset | `feurstagram-454-0-0-54-77.apk` (tag `v454-0-0-54-77`, published 2026-11-06) |
| Downloaded copy | `feurstagram-454-0-0-54-77 (1).apk` |
| Size | 162,181,070 bytes |
| SHA-256 | `354553ff5431766227bfdd7c932e95b651874d79bc43d9cbff11c94e644533a5` (matches release digest) |
| Package | `com.instagram.android` |
| versionName | `454.0.0.54.77` |
| versionCode | `386014712` |
| `minSdk` / `targetSdk` / `compileSdk` | 28 / 37 / 37 |
| ABI | `arm64-v8a` only (15 `lib/arm64-v8a/*.so`) |
| Signer DN | `CN=Feurstagram, OU=Feurstagram, O=Feurstagram, L=Unknown, ST=Unknown, C=XX` |
| Signer SHA-256 | `cfddc301047c7b08b7151cf15d91a585f1d9518b6cdefadfe40f0996d11b3918` |
| Key | RSA 2048-bit |
| Signature schemes | v2 and v3 present (v3 verifies by default at `minSdk 28`; v2 verifies when forced to API 24–27; v1 blocks present, `X-Android-APK-Signed: 2, 3`) |
| ZIP entries | 18,367 |
| Uncompressed size | 296,917,299 bytes |

The signer is a project key, not Meta and not Morphe. This is expected: a patched APK must be
re-signed, and the project's `build.sh` documents a release keystore (`feurstagram.keystore`,
alias `feurstagram`).

---

## 3. Manifest inspection

### 3.1 Split metadata

| Attribute / meta-data | Official working APK | HMX failed build | Stock input |
| --- | --- | --- | --- |
| `android:requiredSplitTypes` (0x0101064e) | **absent** | `"base__density"` | `"base__density"` |
| `android:splitTypes` (0x0101064f) | **absent** | `""` | `""` |
| `android:isSplitRequired` | absent | absent | absent |
| `android:configForSplit` | absent | absent | absent |
| `android:uses-split` | absent | absent | absent |
| `com.android.vending.splits.required` | **absent** | `true` | `true` |
| `com.android.vending.splits` | **absent** | `@xml/splits0` | `@xml/splits0` |
| `com.android.vending.derived.apk.id` | **absent** | `2` | `2` |
| `com.android.stamp.source` | **absent** | `https://play.google.com/store` | same |
| `com.android.stamp.type` | **absent** | `STAMP_TYPE_DISTRIBUTION_APK` | same |

The only `split` substring left anywhere in the official manifest is Instagram's internal build-flavor
string (`com.facebook.build_rule = instagram_multi_bytecode_dextr_splitarsc_dlstr_noxz_arm64_release_fbsign`),
which is not install metadata.

### 3.2 Exact manifest diff (normalized `aapt2 dump xmltree`)

Comparing the failed HMX build against the working official APK, the whole manifest differs in only
three places:

```diff
     E: manifest
-      A: versionCode=386014758
+      A: versionCode=386014712
       A: versionName="454.0.0.54.77"
-      A: requiredSplitTypes="base__density"
-      A: splitTypes=""
       A: package="com.instagram.android"
...
-              A: value="1793609739000L"      # com.facebook.build_time
+              A: value="1793609715000L"
...
-              A: value=1105698528           # com.facebook.build_id
+              A: value=1105698298
...
-            E: meta-data
-              A: name="com.android.vending.splits.required"
-              A: value=true
-            E: meta-data
-              A: name="com.android.stamp.source"
-              A: value="https://play.google.com/store"
-            E: meta-data
-              A: name="com.android.stamp.type"
-              A: value="STAMP_TYPE_DISTRIBUTION_APK"
-            E: meta-data
-              A: name="com.android.vending.splits"
-              A: resource=@0x7f17000c
-            E: meta-data
-              A: name="com.android.vending.derived.apk.id"
-              A: value=2
```

Permissions, components, `uses-sdk`, libraries, and the FeurStagram patch surface
(`assets/feurstagram/quoicouview.jpg`, `com.feurstagram.extension.*` components,
`REQUEST_INSTALL_PACKAGES`) are otherwise identical.

`versionCode` and the Facebook build metadata differ only because the official release was built from a
different base APK of the same `versionName` (386014712 vs the HMX-pinned 386014758). That is not
install-relevant.

---

## 4. Standalone or split-derived?

The official APK is a **merged, standalone universal APK**. Evidence:

1. No split metadata anywhere in the manifest (§3).
2. It carries the merged density payload: **14,453 `res/` files** including **3,590 images**, versus
   7,202 files / 352 images in the base-only HMX artifact.
3. `resources.arsc` is 6,867,848 bytes (vs 6,098,496 in the base-only build) — the resource table grew
   with the merged density configurations.
4. All four APKEditor merge fingerprints are present (code confirmed in `APKEditor-1.4.9`):
   - `Merger.sanitizeManifest()` removes `requiredSplitTypes` and `splitTypes` (`merge/Merger.java:147-157`).
   - `AndroidManifestHelper.listSplitRequired()` removes every `<meta-data>` whose name starts with
     `com.android.vending.` or `com.android.stamp.` (`common/AndroidManifestHelper.java:60-61`).
   - `Merger.removeSplitsTableEntry()` deletes the `com.android.vending.splits` table file
     (`res/xml/splits0.xml`) and nulls its entry (`merge/Merger.java:175-221`).
   - `CommandExecutor.clearMeta()` deletes `META-INF/*.SF|*.RSA` and `stamp-cert-sha256`
     (`CommandExecutor.java:139-147`).
5. The official APK has **no** `res/xml/splits0.xml` and **no** `stamp-cert-sha256`, exactly as
   APKEditor leaves an output.
6. The upstream changelog for this release states it explicitly:
   > *"Instagram 454.0.0.54.77. No fingerprint changed. **This APKMirror bundle** has no
   > `helium` / `executorch` / `pytorch` feature splits and packs its native libraries in
   > `assets/lib/libs.spo`, so the APK is about 160 MB instead of 420 MB."*

---

## 5. How the official APK was produced

The upstream repository (`jean-voila/FeurStagram`) has **no GitHub Actions build workflow**; releases
are built locally (or in the provided Docker image) with `build.sh`.

### 5.1 `build.sh` flow (upstream, identical logic in the HMX fork)

```
./build.sh <instagram.apk|.apkm|.xapk|.apks> [--clone] [--install] [--debug]
```

1. **Merge** — if the input is `.apkm/.xapk/.apks`, run APKEditor:
   ```sh
   java -Xmx4g -jar tools/APKEditor-1.4.9.jar m -f -i <bundle> -o build/merged/<name>.apk
   APK="$MERGED"
   ```
2. **Build patches** — `./gradlew :patches:build` → `.mpp`.
3. **Patch** — Morphe CLI: `patch -p <.mpp> -f -r report.json -o feurstagram.apk`.
4. **Sign** — if `FEURSTAGRAM_KEYSTORE_PASS` is set, the CLI outputs `--unsigned`, then `apksigner`
   signs with the release keystore forcing v1 + v2 + v3.
5. Output: `feurstagram.apk`, renamed to the release asset name.

`Dockerfile` documents the intended invocation (`./build.sh instagram.apkm --clone`), and
`docs/BUILD_FROM_SOURCE.md` says to use an arm64-v8a Instagram APK from a trusted source such as
APKMirror.

### 5.2 Produced artifact

An APKMirror `.apkm` for 454.0.0.54.77 (arm64-v8a) was merged by APKEditor, patched by Morphe, and
signed with the FeurStagram release key. The result is the verified release asset inspected here.

**This is not reproducible by patching a single base `.apk`**, because the merge is what both removes
the split metadata and supplies the density payload.

---

## 6. Packaging comparison

### 6.1 ZIP-level

| Property | Official working | HMX failed | Stock input |
| --- | --- | --- | --- |
| Entries | 18,367 | 11,115 | 11,112 |
| `res/` entries | 14,453 | 7,202 | 7,202 |
| `res/` images (png/webp/jpg) | 3,590 | 352 | 352 |
| `res/` XML | 10,478 | 6,467 | 6,467 |
| `resources.arsc` | 6,867,848 B (STORED) | 6,098,496 B (STORED) | 6,053,268 B (STORED) |
| DEX files | 31 | 32 | 30 |
| DEX compression | deflated (80.7 MB compressed / 189.4 MB raw) | **stored** (187.4 MB / 189.0 MB) | stored (186.5 MB) |
| `assets/` | 3,152 entries, 63.5 MB raw | 3,152 entries, 63.8 MB raw | 3,151 entries |
| `lib/arm64-v8a/` | 15 libs | 15 libs | 15 libs |
| `res/xml/splits0.xml` | **absent** | present | present |
| `stamp-cert-sha256` | **absent** | present | present |
| `META-INF/*.SF/*.RSA` | present (v1 blocks, release key) | absent | absent |
| Signer | `CN=Feurstagram` | `CN=Morphe` (throwaway) | Meta / Instagram rotation |
| Signature schemes | v2 + v3 | v2 only | v3 + v3.1 (+ SourceStamp) |

The 100 MB size difference (162 MB vs 261 MB) is almost entirely **DEX compression**: the official
build's merged output deflates dex (saves ~107 MB); the HMX build inherits the stock APK's uncompressed
dex. Size is not install-relevant.

### 6.2 Why the density payload matters beyond the attribute

The official APK contains ~7,250 more `res/` files than the base-only artifact — 4,011 more XML
drawables and ~3,238 more images. Those are the density-configuration resources that live in
`config.<density>` splits. The HMX base-only APK does not have them at all; they are not merely
"alternate versions" of existing resources.

So the two possible "fixes" are **not equivalent**:

- **Strip the attributes only:** installs, but ships ~7,250 fewer density resources than the official
  build. Visual/behavioral regressions are likely on some screens and densities.
- **Merge the split bundle:** installs *and* matches the official resource set — the correct fix.

---

## 7. Exact differences responsible for successful installation

In order of causality:

1. **Split metadata is gone.** The official manifest has no `requiredSplitTypes`/`splitTypes`
   (§3). The HMX APK still declares `requiredSplitTypes="base__density"` with no split staged, which
   is precisely what ColorOS 15 rejects with `INSTALL_FAILED_MISSING_SPLIT` (confirmed in the prior
   investigation; `isSplitRequired` is *not* required for enforcement on this device).
2. **Split payload is merged.** All density resources are inside the single APK, so it is genuinely
   standalone rather than merely labelled standalone.
3. **The Play split table and stamp are removed.** No `res/xml/splits0.xml`, no
   `com.android.vending.splits(.required)`, no `com.android.stamp.*`, no `stamp-cert-sha256`. This is
   part of APKEditor's merge sanitation and prevents any Play-style split reconciliation.
4. **Signing scheme** (official v2+v3 release key vs HMX v2-only throwaway) is **not** the cause of the
   split failure. Both verify on API 35.
5. **DEX compression and base revision** (`versionCode` 386014712 vs 386014758) are **not**
   install-relevant.

The HMX build failed solely because it skipped step 1–3: it patched a bundle-derived base `.apk`
directly, without ever running the merge.

---

## 8. Recommended way to reproduce this in HMX GitHub Actions

Do **not** change the production workflow until the input and gates below are in place; this section is
a recommendation only. Do **not** use the already-patched official APK as an input, and do not
hand-edit manifest attributes.

### 8.1 Input

Pin the same kind of artifact the official build consumed:

- an **APKMirror `.apkm` bundle** for Instagram `454.0.0.54.77`, `arm64-v8a`, with the density config
  splits included;
- pinned by immutable URL **and** SHA-256 in the private input repository;
- not the current `Apk-input-original` single base APK (it is a bundle-derived base, not a bundle).

Exact byte-equality with the official release is not required (the official used versionCode
386014712). What is required is that the input is a complete bundle.

### 8.2 Pipeline steps

1. Download the pinned `.apkm`; verify SHA-256.
2. Extract `base.apk` from the bundle and verify identity on it:
   - `aapt dump badging base.apk` → `com.instagram.android`, `454.0.0.54.77`;
   - `apksigner verify --print-certs base.apk` → Meta certificate
     `3a10c50c18ba937506c2875e9047be74f8fd8b86fae2ff9feaa50a81fcb4c014`.
   Note: the current step runs `aapt`/`apksigner` directly on `$BASE_APK`; that must change to the
   extracted `base.apk` when the input is a bundle.
3. Verify the bundle actually contains the required split types (at minimum the density split APKs).
   A bundle that declares `base__density` but omits those splits must be rejected.
4. Merge with the already-pinned APKEditor:
   ```sh
   java -Xmx4g -jar tools/APKEditor-pinned.jar m -f -i instagram.apkm -o merged.apk
   ```
   (`build.sh` already does this for `.apkm/.xapk/.apks`; the CI currently blocks those inputs at
   `apk.yml:289-292`.)
5. Build the `.mpp` and patch `merged.apk` with Morphe CLI (unchanged).
6. Sign with the release keystore through `apksigner`, forcing v1 + v2 + v3 (unchanged `build.sh`
   behavior).
7. Publish only after the gates in §8.3 pass.

### 8.3 New verification gates (add before publish)

```bash
# 1. The output must be standalone: no split metadata at all.
aapt2 dump xmltree --file AndroidManifest.xml "$APK_OUT" > "$RUNNER_TEMP/manifest.txt"
if grep -qE 'requiredSplitTypes|splitTypes|isSplitRequired|configForSplit|uses-split' \
        "$RUNNER_TEMP/manifest.txt"; then
    echo "::error::APK still declares split metadata; it is not standalone-installable"
    exit 1
fi
if grep -qE 'com\.android\.vending\.splits|com\.android\.stamp\.' "$RUNNER_TEMP/manifest.txt"; then
    echo "::error::APK still carries Play split/stamp metadata"
    exit 1
fi

# 2. The merged split table and stamp must be gone.
unzip -l "$APK_OUT" | grep -q 'res/xml/splits0.xml' && {
    echo "::error::splits0.xml survived; the bundle was not merged"
    exit 1; }
unzip -l "$APK_OUT" | grep -q 'stamp-cert-sha256' && {
    echo "::error::Play source stamp survived; the bundle was not merged"
    exit 1; }

# 3. The merged payload must be present (sanity floor, not a byte pin).
res_count="$(unzip -l "$APK_OUT" | awk '{print $4}' | grep -c '^res/')"
[ "$res_count" -ge 12000 ] || {
    echo "::error::Only $res_count res/ entries; density splits were not merged"
    exit 1; }

# 4. Package/version/signer.
aapt dump badging "$APK_OUT" | grep -qF "package: name='com.instagram.android'"
apksigner verify --print-certs "$APK_OUT" >/dev/null

# 5. Real install test on an arm64 Android 15 target (x86_64 emulators cannot install
#    this arm64-only APK: INSTALL_FAILED_NO_MATCHING_ABIS).
adb install -r -t "$APK_OUT"
```

### 8.4 Workflow changes required (recommendation, not applied)

- Lift the unconditional `base_apk_kind != "apk"` rejection only together with merge support
  (`.github/workflows/apk.yml:289-292`). The choice list already offers `apkm`/`xapk`/`apks`
  (`.github/workflows/apk.yml:30-39`), and the download step already names the file
  `instagram.$BASE_APK_KIND` (`.github/workflows/apk.yml:254-281`).
- Change the base-identity step to extract and verify `base.apk` from the bundle before `aapt`/
  `apksigner` (`.github/workflows/apk.yml:283-316`).
- Keep the existing APKEditor download/pin step (`.github/workflows/apk.yml:332-352`) — it is already
  wired but currently unreachable for `apkm`.
- Add the §8.3 gates to the “Verify APK” step and before the publish step
  (`.github/workflows/apk.yml:427-458` and `:505-527`).
- Keep all existing security properties: pinned URL + SHA-256, Meta certificate check on the base,
  release signer pin, throwaway-only test publishing, signature bypass forbidden on release.

### 8.5 What would happen if APKEditor were run on the current single base APK

`build.sh` would still skip the merge (input is `.apk`), so nothing changes. Forcing APKEditor on the
base-only APK would remove the split metadata (APKEditor sanitizes any merge input) but would merge no
density splits, producing an installable artifact that is still missing the ~7,250 density resources.
That is an inferior outcome to merging a real bundle and must not be presented as the fix.

---

## 9. Verification checklist for the reproduced HMX artifact

1. `sha256sum` matches the recorded digest.
2. `apksigner verify --print-certs` — valid, signer equals the pinned release certificate.
3. `unzip -t` — archive integrity.
4. `zipalign -c -p 4` — alignment.
5. `aapt dump badging` — `com.instagram.android`, `454.0.0.54.77`, `minSdk 28`, `native-code arm64-v8a`.
6. `aapt2 dump xmltree --file AndroidManifest.xml` — no split attributes and no Play split/stamp meta-data.
7. ZIP check — no `res/xml/splits0.xml`, no `stamp-cert-sha256`.
8. Resource sanity — `res/` entry count in the merged range (≈14k), density buckets present.
9. `adb install -r -t` on an arm64 Android 15 device — must succeed.
10. Launch once and confirm the FeurStagram features work.

---

## 10. Constraints honored

- Production workflow not modified.
- The original Instagram input was not replaced with an already-patched APK.
- No manifest split attributes were removed manually.
- No new APK was produced or published.
- No ABI, `minSdk`, or signature-verification change was made.

---

## 11. Evidence index

Temporary local files (not committed): `/tmp/opencode/apkcmp/` (`working.txt`, `hmx.txt` normalized
manifest dumps), `/tmp/opencode/apkeditor-full/APKEditor-1.4.9/` (merger source),
`/tmp/opencode/fs-upstream/` (upstream `build.sh`, `Dockerfile`, `CHANGELOG.md`,
`docs/BUILD_FROM_SOURCE.md`, release metadata).

Artifacts inspected (read-only, in `/mnt/sdcard/Download/`):

- `feurstagram-454-0-0-54-77 (1).apk` — official working release.
- `feurstagram-454.0.0.54.77-test.apk` — failed HMX build.
- `Instagram.apk` — pinned stock input (`Apk-input-original`).

Repository files referenced:

- `build.sh:104-132` — split-bundle merge via APKEditor (skipped for plain `.apk`).
- `build.sh:143-186` — Morphe patch and signing flow.
- `.github/workflows/apk.yml:283-316` — base identity verification on `$BASE_APK`.
- `.github/workflows/apk.yml:289-292` — split bundles rejected; only `base_apk_kind=apk`.
- `.github/workflows/apk.yml:332-352` — APKEditor download/pin step.
- `.github/workflows/apk.yml:427-458` — output verification.
- `.github/workflows/apk.yml:505-527` — publish verification.
- `docs/investigations/HMX_INSTAPLUS_SPLIT_INSTALL_INVESTIGATION.md` — root cause of the HMX failure.
