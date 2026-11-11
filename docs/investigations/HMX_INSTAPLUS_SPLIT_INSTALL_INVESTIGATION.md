# HMX Insta+ — Split Install Failure Investigation

- **Date:** 2026-11-11
- **Artifact under test:** `debug-test-42998995696-1` (release id `441504347`, asset id `734995951`)
- **Asset:** `feurstagram-454.0.0.54.77-test.apk`, 260,967,921 bytes
- **SHA-256:** `68446312f639ca6d873e3dee5fdcc5439f9b0cb3e969153c308cbbb5444c2dd2` (matches the release digest)
- **Device:** OPPO A38 (CPH2579, `OP5759L1`), ColorOS 15 / Android 15 / API 35, `arm64-v8a`
- **Ground-truth result:** `INSTALL_FAILED_MISSING_SPLIT: Missing split for com.instagram.android`
- **Stock input:** `Apk-input-original`, SHA-256 `b334feef23722f0364a80c2c9e99af48e0c460cc652213d3f1a919f2007e5136`

This report supersedes the verdict in `HMX_INSTAPLUS_DEVICE_COMPATIBILITY_REPORT.md`
(that report concluded the split metadata was inert; the device result disproves it).

No rebuild, republish, manifest edit, patch change, ABI change, minSdk change, or
signature-verification change was performed during this investigation.

---

## 1. Root cause

**The published APK is a bundle-derived *base* APK, not a standalone APK. It declares a required split
type (`base__density`) but ships no split APKs, and the build pipeline never normalized that metadata
because it only merges inputs whose filename ends in `.apkm`, `.xapk`, or `.apks`.**

The install therefore fails at Android's split reconciliation step, before any compatibility, ABI, SDK,
or signature gate. The failure is deterministic and reproducible for this artifact; it is not a
device-side policy rejection and not a signing defect.

Root-cause chain, in order:

1. The input artifact already carries `android:requiredSplitTypes="base__density"` on `<manifest>` and
   Play split markers (`com.android.vending.splits.required=true`). It was produced from a Play bundle,
   not built as a universal APK.
2. `../../build.sh` only invokes APKEditor when the input extension is `.apkm/.xapk/.apks` (`../../build.sh:104-132`).
   The input was a plain `.apk`, so the merge/normalization branch was skipped entirely.
3. Morphe CLI patched the APK in place and re-signed it. It preserves the manifest's split attributes
   verbatim — confirmed by comparing the patched APK to the stock input.
4. The CI gate only accepts `base_apk_kind=apk` and rejects split bundles
   (`../../.github/workflows/apk.yml:289-292`), so the one code path that would have merged the splits and
   cleaned the manifest is structurally unreachable.
5. The "Verify APK" and publish steps check package name, version, digest, and signer only
   (`../../.github/workflows/apk.yml:505-527`). Nothing checks for split metadata or standalone installability.
6. On the device, the framework reads `requiredSplitTypes={base__density}`, stages exactly one APK
   (the base), and throws `INSTALL_FAILED_MISSING_SPLIT`.

---

## 2. Manifest evidence (binary-level)

Verified with both `aapt` and `aapt2 dump xmltree` on the exact published bytes. Attribute IDs are shown
because attribute-name tables can differ between `aapt` versions.

| Property | Location | Value in published APK | Attribute ID |
| --- | --- | --- | --- |
| `android:requiredSplitTypes` | `<manifest>` | `"base__density"` | `0x0101064e` |
| `android:splitTypes` | `<manifest>` | `""` (empty) | `0x0101064f` |
| `android:isSplitRequired` | `<application>` | **absent** | `0x0101064d` |
| `android:configForSplit` | — | **absent** | `0x0101063f` |
| `android:uses-split` | — | **absent** | `0x01010649` |
| `android:split` | — | **absent** | `0x01010234` |
| `com.android.vending.splits.required` | meta-data | `true` | — |
| `com.android.vending.splits` | meta-data | `@xml/splits0` (`0x7f17000c`) | — |
| `com.android.stamp.source` | meta-data | `https://play.google.com/store` | — |
| `com.android.vending.derived.apk.id` | meta-data | `2` | — |
| Sibling split APKs | — | **none** | — |

Raw dump of the `<manifest>` element:

```
E: manifest (line=1)
  A: android:versionCode(0x0101021b)=0x17021e26          (386014758)
  A: android:versionName(0x0101021c)="454.0.0.54.77"
  A: android:compileSdkVersion(0x01010572)=37
  A: android:requiredSplitTypes(0x0101064e)="base__density"
  A: android:splitTypes(0x0101064f)=""
  A: package="com.instagram.android"
```

The only other `split` occurrence in the entire manifest is an internal build-flavor string
(`com.facebook.build_rule = instagram_multi_bytecode_dextr_splitarsc_dlstr_noxz_arm64_release_fbsign`),
which is not install metadata.

### Comparison with the stock input

The stock `Apk-input-original` has **identical** split metadata: `requiredSplitTypes="base__density"`,
`splitTypes=""`, no `isSplitRequired`, no `configForSplit`, no `uses-split`, and the same
`com.android.vending.splits.required=true` / `@xml/splits0` / Play stamp markers. The patched-vs-stock
manifest delta is only the added `REQUEST_INSTALL_PACKAGES` permission.

**Conclusion: the defect is inherited from the input artifact. The patches and Morphe CLI did not
introduce it, and they did not remove it either.**

### `splits0.xml` is a neutralized split table

`res/xml/splits0.xml` (resource `0x7f17000c`) contains one `<module name="">` with 90 `<language>`
entries and **no density or ABI entries**; every `split` attribute is an empty string:

```
E: splits
  E: module   A: name=""
    E: language
      E: entry  A: key="af"  A: split=""
      E: entry  A: key="am"  A: split=""
      ... (88 more, all split="")
```

A genuine Play base names its splits here (`split="config.af"`, etc.). Here the names were erased by
whatever tool repackaged the artifact — but `requiredSplitTypes` was left behind. This is the signature
of a semi-normalized bundle base, not of a universal APK.

---

## 3. What is actually missing

- **Required split type:** `base__density` (exactly one type).
- **Split APKs shipped:** none. The base declares `splitTypes=""`, i.e. it provides no split types itself.
- **Staged set size:** 1 (base only). The framework's own `stagedSplits.size() <= 1` condition is already
  sufficient to trigger the failure on any framework that treats this base as split-required.
- **Concrete split files:** density config splits (`config.<density>` under bundletool/AGP naming —
  e.g. `config.mdpi`, `config.hdpi`, `config.xhdpi`, `config.xxhdpi`, `config.xxxhdpi`). Their exact
  filenames and payloads cannot be enumerated without the original `.apkm/.apks`, which is not available
  locally; only the single `.apk` input exists.

---

## 4. Why the device throws — AOSP and vendor behavior

Android 15 (`android-15.0.0_r30`, `services/core/java/com/android/server/pm/PackageInstallerSession.java`),
full-install path:

```java
// line 3652
if (baseApk.isSplitRequired() && (stagedSplits.size() <= 1
        || !stagedSplitTypes.containsAll(requiredSplitTypes))) {
    throw new PackageManagerException(INSTALL_FAILED_MISSING_SPLIT,
            "Missing split for " + mPackageName);
}
```

Android 16 (`android-16.0.0_r1`) keeps the same gate at lines 3809-3813. In both, the full-install gate
reads the raw `isSplitRequired` **attribute** (`ApkLiteParseUtils.java:435`, `android:isSplitRequired`,
default `false`). Our APK does not set it, so **stock AOSP 15/16 would not throw here**.

However, the same release already defines the broader semantics in
`core/java/android/content/pm/parsing/PackageLite.java:165`:

```java
mSplitRequired = (baseApk.isSplitRequired() || hasAnyRequiredSplitTypes());
```

where `hasAnyRequiredSplitTypes()` is true when `requiredSplitTypes` is non-empty. The inherited-install
path uses `packageLite.isSplitRequired()` (`PackageInstallerSession.java:3797`), so a base with a
non-empty `requiredSplitTypes` **is** treated as split-required there even without the attribute.

ColorOS 15 applied that same semantics to the full-install path: `requiredSplitTypes` was enforced, and
the install failed with `INSTALL_FAILED_MISSING_SPLIT`. The device log was not captured, so the exact
vendor line is not quoted; the observable behavior is unambiguous.

Practical consequence: **do not rely on the missing `isSplitRequired` attribute.** `requiredSplitTypes`
is part of the split-reconciliation contract, and at least one shipping Android 15 OEM enforces it.

---

## 5. Pipeline trace

### 5.1 `../../build.sh`

```
104  # A split bundle has to become one universal APK before the patcher can touch it.
105  case "$APK" in
106      *.apkm | *.xapk | *.apks)
...          # APKEditor merge + manifest sanitization
130          APK="$MERGED"
131          ;;
132  esac
```

The merge gate is **filename-based**. A bundle-derived base named `*.apk` bypasses it and goes straight to:

```
143  == [2/3] Applying to ...
153  ARGS=(-jar "$CLI" patch -p "$MPP" -f -r "$REPORT" -o "$OUT")
```

Morphe CLI patches one APK; it does not merge, split, or normalize manifests. Observed output: same
11,115 ZIP entries, same 32 dex files, same manifest split attributes, new signature.

### 5.2 APKEditor would have fixed the attribute

The pinned merger (`REAndroid/APKEditor` `V1.4.9`, `merge/Merger.java`, `sanitizeManifest()`) removes
exactly the offending metadata:

```java
removeAttributeFromManifestById(manifest, ID_requiredSplitTypes, this);
removeAttributeFromManifestById(manifest, ID_splitTypes, this);
removeAttributeFromManifestByName(manifest, NAME_splitTypes, this);
removeAttributeFromManifestByName(manifest, NAME_requiredSplitTypes, this);
removeAttributeFromManifestAndApplication(manifest, ID_isSplitRequired, ...);
// plus: removes com.android.vending.splits(.required) meta-data and nulls the splits table entry
```

Because the published APK still contains `requiredSplitTypes`, `splitTypes`, the `splits.required`
meta-data, and a live `splits0.xml`, **it was provably not produced by this merge path.** That confirms
the merge branch never ran for this artifact.

### 5.3 CI (`../../.github/workflows/apk.yml`)

- Base type gate rejects everything except a plain APK (lines 289-292): *"Split bundles are not supported
  until merge and signer verification are implemented."* The supported input shape therefore guarantees
  the split metadata reaches the output untouched.
- Base identity check (lines 283-316): package name, version name, Meta cert SHA-256. No manifest split
  inspection.
- Output/publish verification (lines 505-527): digest, signer, package name, version name. No
  `aapt2 dump xmltree` split assertion, no `adb install` smoke test.

So the pipeline has no gate that could have caught this before publishing.

### 5.4 Where the input came from

`com.android.stamp.source=https://play.google.com/store`, `com.android.vending.derived.apk.id=2`, and
`com.android.vending.splits.required=true` show the input was derived from a Play bundle and then
repackaged by an unspecified tool (which erased the split names in `splits0.xml` but not
`requiredSplitTypes`). The exact repackaging tool cannot be identified from the artifact alone, and it is
outside this repository. What matters is that the repository accepted it as `base_apk_kind=apk`.

---

## 6. Classification

The output is **neither a valid standalone universal APK nor a complete split install set**. It is:

> **A bundle-derived base APK that declares a required split type (`base__density`) and ships no splits.**

Supporting observations on completeness (why this is *not* simply "remove one attribute"):

- 38,439 resources, **0 entries with zero values**, 7,202 file references with **0 dangling files**.
- 701 density-qualified file values across `mdpi/hdpi/xhdpi/xxhdpi/xxxhdpi/anydpi/nodpi`, mapped to 694
  distinct files.
- 57 resources carry complete 5- or 6-density sets with distinct per-density files; no resource shows a
  partially populated multi-density set.
- Full arm64 native set in `lib/arm64-v8a/` (15 ELF aarch64 libs), plus the merged native pack
  `assets/lib/libs.spo` (27.7 MB) and its integrity manifest `assets/lib/metadata.txt`.

So the *content* is probably self-consistent enough to run. But "probably" is not proof: only the
original bundle can prove that no density-split payload is missing. That is exactly why the metadata must
be cleared by a real merge of the real split set, not by hand-editing the manifest of this APK.

---

## 7. Options A / B / C

### A. Build a complete standalone APK — correct output shape

Produce a single APK with **no** `requiredSplitTypes`/`splitTypes`/`isSplitRequired` and all split
payloads merged. This is the only shape suitable for file-manager install and for the current
single-APK distribution model.

Requires the original `.apkm/.apks` for Instagram 454.0.0.54.77 so the density splits are actually
present. Do **not** obtain this by deleting attributes from the current APK unless completeness has been
independently proven against that bundle.

### B. Ship and install the split set with `adb install-multiple` — stopgap only

`adb install-multiple base.apk config.mdpi.apk config.hdpi.apk ...` works for local verification, but:

- the split APKs are not available locally;
- all APKs must share the patched base's signing identity, so they must be re-signed;
- it is incompatible with the current "download one APK and tap it" distribution model;
- CI's `base_apk_kind=apkm` path is explicitly unimplemented.

Use it only to validate that the base runs once all splits are present.

### C. Correct the packaging pipeline — required systemic fix (recommended)

1. **Stop accepting unverified single APKs that carry split metadata.** Reject any `base_apk_kind=apk`
   input whose manifest declares `requiredSplitTypes`, `splitTypes`, `isSplitRequired`, or
   `com.android.vending.splits.required`.
2. **Implement the split-bundle path properly.** Pin the original `.apkm/.apks` (URL + SHA-256), merge
   with the pinned APKEditor (`m -f -i <bundle> -o <merged>`), then verify the merged manifest is clean
   (APKEditor's `sanitizeManifest` removes all split attributes and the vending split meta-data).
3. **Add an installability gate before publish**, on the exact bytes to be published:

   ```bash
   aapt2 dump xmltree --file AndroidManifest.xml "$APK_OUT" > "$TMP/manifest.txt"
   if grep -qE 'requiredSplitTypes|splitTypes|isSplitRequired' "$TMP/manifest.txt"; then
       echo "::error::APK declares split metadata and is not standalone-installable"
       exit 1
   fi
   aapt dump badging "$APK_OUT" | grep -q "package: name='$EXPECTED_PACKAGE'" || exit 1
   apksigner verify --print-certs "$APK_OUT" > /dev/null || exit 1
   unzip -t "$APK_OUT" > /dev/null || exit 1
   zipalign -c -p 4 "$APK_OUT" || exit 1
   ```

4. **Add a real install smoke test on API 35.** Because this APK is `arm64-v8a`-only, an x86_64 emulator
   cannot install it (`INSTALL_FAILED_NO_MATCHING_ABIS`); use an arm64 emulator image or a physical
   arm64 device:

   ```bash
   adb install -r -t "$APK_OUT"   # must succeed before any publish step
   ```

5. **Record the split decision in the build report**, so a future artifact cannot silently regress.

**Recommendation: C, delivering A.** B is a local diagnostic aid only.

---

## 8. Verification checklist for the fixed artifact

Run against the exact published bytes, in this order:

1. `sha256sum` matches the recorded digest.
2. `apksigner verify --print-certs` — signature valid; signer matches the intended certificate.
3. `unzip -t` — archive integrity.
4. `zipalign -c -p 4` — alignment.
5. `aapt dump badging` — `package`, `versionName`, `sdkVersion`, `targetSdkVersion`, `native-code`.
6. `aapt2 dump xmltree --file AndroidManifest.xml` — **no** `requiredSplitTypes`, `splitTypes`,
   `isSplitRequired`, `configForSplit`, `uses-split`; no `com.android.vending.splits(.required)` meta-data.
7. Resource sanity (if the merge path was used): 0 empty resource entries, 0 dangling `res/` file refs.
8. `adb install -r -t` on ColorOS 15 / Android 15 arm64 device — must succeed.
9. Launch the app once, confirm the patched behavior.

Until steps 6 and 8 pass on the exact artifact, **do not publish**.

---

## 9. Immediate actions

- Do **not** publish another APK built from this input.
- Retire/deprecate `debug-test-42998995696-1`; it is a test-only asset (72 h retention) and cannot be
  installed on Android 15.
- Do **not** hand-remove `requiredSplitTypes` from the current APK. Without the original bundle there is
  no proof the density payload is complete.
- Do **not** change ABI or `minSdk`. Nothing in the evidence indicates they are involved.
- Do **not** disable or weaken signature verification.
- Obtain and pin the original Instagram 454.0.0.54.77 `.apkm/.apks`, then rebuild through the
  merge + gate path described in §7C.

---

## 10. Evidence index

Local, temporary (not committed): `/tmp/opencode/compat/v2/` (published APK, `res.txt` resource dump,
`manifest.xmltree`), `/tmp/opencode/compat/base/` (stock input manifest dump),
`/tmp/opencode/compat/a15/` and `/tmp/opencode/compat/a16/` (AOSP `PackageInstallerSession`,
`ApkLiteParseUtils`, `PackageLite`), `/tmp/opencode/apkeditor-src/` (APKEditor `Merger.java`,
`AndroidManifestHelper.java`).

Repository files referenced:

- `../../build.sh:104-132` — extension-gated merge, plain-APK bypass.
- `../../build.sh:143-186` — Morphe CLI patch/sign flow.
- `../../.github/workflows/apk.yml:283-316` — base identity verification (no split check).
- `../../.github/workflows/apk.yml:289-292` — split bundles rejected; only `base_apk_kind=apk` accepted.
- `../../.github/workflows/apk.yml:505-527` — publish verification (digest/signer/package/version only).

See also `HMX_FEURSTAGRAM_WORKING_APK_COMPARISON.md` in this folder: the official FeurStagram release for
the same Instagram version is a merged universal APK (APKEditor from an `.apkm`) with zero split
metadata, which independently confirms the fix direction.
