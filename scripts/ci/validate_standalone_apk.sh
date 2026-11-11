#!/usr/bin/env bash
#
# Validate that an APK is a standalone, universally merged Instagram APK.
#
# Used twice in the pipeline:
#   --stage merge  after APKEditor, before Morphe patching
#   --stage final  after patching and signing, before publishing
#
# The "merge" stage does not require a signature (APKEditor clears signatures).
# The "final" stage requires a valid APK signature and ZIP alignment.
#
# Tool paths can be overridden for tests:
#   AAPT, AAPT2, APKSIGNER, UNZIP, ZIPALIGN
#
# Expected identity can be supplied by flags or environment:
#   EXPECTED_INSTAGRAM_PACKAGE   (default: com.instagram.android)
#   EXPECTED_INSTAGRAM_VERSION   (default: empty = do not enforce)
#   EXPECTED_INSTAGRAM_VERSION_CODE (default: empty = do not enforce)
#   EXPECTED_APK_SIGNER_SHA256   (default: empty = do not enforce)
#   MIN_RES_ENTRIES              (default: 12000)
set -euo pipefail

AAPT="${AAPT:-aapt}"
AAPT2="${AAPT2:-aapt2}"
APKSIGNER="${APKSIGNER:-apksigner}"
UNZIP="${UNZIP:-unzip}"
ZIPALIGN="${ZIPALIGN:-zipalign}"

STAGE="merge"
APK=""
REPORT=""
EXPECTED_PACKAGE="${EXPECTED_INSTAGRAM_PACKAGE:-com.instagram.android}"
EXPECTED_VERSION="${EXPECTED_INSTAGRAM_VERSION:-}"
EXPECTED_VERSION_CODE="${EXPECTED_INSTAGRAM_VERSION_CODE:-}"
EXPECTED_SIGNER="${EXPECTED_APK_SIGNER_SHA256:-}"
MIN_RES_ENTRIES="${MIN_RES_ENTRIES:-12000}"
REQUIRE_ARM64="${REQUIRE_ARM64:-1}"

usage() {
  echo "usage: $0 --stage merge|final [options] <apk>" >&2
  echo "  --expected-package NAME" >&2
  echo "  --expected-version VERSION" >&2
  echo "  --expected-version-code CODE" >&2
  echo "  --expected-signer SHA256" >&2
  echo "  --min-res-entries N" >&2
  echo "  --report PATH" >&2
  exit 2
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --stage) STAGE="${2:-}"; shift 2 ;;
    --expected-package) EXPECTED_PACKAGE="${2:-}"; shift 2 ;;
    --expected-version) EXPECTED_VERSION="${2:-}"; shift 2 ;;
    --expected-version-code) EXPECTED_VERSION_CODE="${2:-}"; shift 2 ;;
    --expected-signer) EXPECTED_SIGNER="${2:-}"; shift 2 ;;
    --min-res-entries) MIN_RES_ENTRIES="${2:-}"; shift 2 ;;
    --report) REPORT="${2:-}"; shift 2 ;;
    --help|-h) usage ;;
    -*) echo "unknown option: $1" >&2; usage ;;
    *) APK="$1"; shift ;;
  esac
done

case "$STAGE" in
  merge|final) ;;
  *) echo "ERROR: --stage must be merge or final (got '$STAGE')" >&2; exit 2 ;;
esac
[ -n "$APK" ] || usage
[ -f "$APK" ] || { echo "ERROR: APK not found: $APK" >&2; exit 1; }
[ -s "$APK" ] || { echo "ERROR: APK is empty: $APK" >&2; exit 1; }

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

ok() { printf 'ok: %s\n' "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------------------
# ZIP integrity and layout
# ---------------------------------------------------------------------------
"$UNZIP" -t "$APK" >/dev/null 2>&1 || die "ZIP integrity check failed (unzip -t): $APK"
ok "ZIP integrity"

entries="$("$UNZIP" -Z1 "$APK")"
grep -qxF 'AndroidManifest.xml' <<< "$entries" || die "AndroidManifest.xml is missing"
grep -qxF 'resources.arsc' <<< "$entries" || die "resources.arsc is missing"
ok "manifest and resource table entries present"

# ---------------------------------------------------------------------------
# Manifest: no split metadata may survive a real merge
# ---------------------------------------------------------------------------
if "$AAPT2" dump xmltree --file AndroidManifest.xml "$APK" > "$tmp/manifest.txt" 2>/dev/null; then
  :
else
  "$AAPT" dump xmltree "$APK" AndroidManifest.xml > "$tmp/manifest.txt" 2>/dev/null || \
    die "could not dump AndroidManifest.xml"
fi

if grep -qE 'requiredSplitTypes|splitTypes|isSplitRequired|configForSplit|uses-split' "$tmp/manifest.txt"; then
  grep -nE 'requiredSplitTypes|splitTypes|isSplitRequired|configForSplit|uses-split' "$tmp/manifest.txt" >&2 || true
  die "split metadata remains in AndroidManifest.xml: this is not a standalone APK"
fi
if grep -qE 'com\.android\.vending\.splits|com\.android\.stamp\.' "$tmp/manifest.txt"; then
  grep -nE 'com\.android\.vending\.splits|com\.android\.stamp\.' "$tmp/manifest.txt" >&2 || true
  die "Play split/stamp meta-data remains in AndroidManifest.xml"
fi
ok "no split metadata in AndroidManifest.xml"

if grep -qxF 'res/xml/splits0.xml' <<< "$entries"; then
  die "res/xml/splits0.xml is present: the APK carries the Play split table and was not merged"
fi
if grep -qxF 'stamp-cert-sha256' <<< "$entries"; then
  die "stamp-cert-sha256 is present: the APK carries the Play source stamp and was not merged"
fi
ok "no Play split table or source stamp"

res_count="$(printf '%s\n' "$entries" | grep -c '^res/' || true)"
image_count="$(printf '%s\n' "$entries" | grep -cE '^res/.*\.(png|webp|jpe?g|gif)$' || true)"
[ "$res_count" -ge "$MIN_RES_ENTRIES" ] || \
  die "only $res_count res/ entries (minimum $MIN_RES_ENTRIES): density/config splits were not merged"
ok "merged resource payload: $res_count res/ entries, $image_count images"

# ---------------------------------------------------------------------------
# Identity, densities, resource table
# ---------------------------------------------------------------------------
badging="$("$AAPT" dump badging "$APK" 2>/dev/null)" || die "aapt dump badging failed"
printf '%s\n' "$badging" | grep -E '^package:|^sdkVersion|^targetSdkVersion|^densities:|^native-code:' || true

grep -qF "package: name='$EXPECTED_PACKAGE'" <<< "$badging" || \
  die "package is not $EXPECTED_PACKAGE"

actual_version="$(printf '%s\n' "$badging" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p" | head -1)"
actual_code="$(printf '%s\n' "$badging" | sed -n "s/.*versionCode='\([^']*\)'.*/\1/p" | head -1)"
if [ -n "$EXPECTED_VERSION" ] && [ "$actual_version" != "$EXPECTED_VERSION" ]; then
  die "versionName is '$actual_version', expected '$EXPECTED_VERSION'"
fi
if [ -n "$EXPECTED_VERSION_CODE" ] && [ "$actual_code" != "$EXPECTED_VERSION_CODE" ]; then
  die "versionCode is '$actual_code', expected '$EXPECTED_VERSION_CODE'"
fi
ok "package $EXPECTED_PACKAGE, version ${actual_version:-unknown} (${actual_code:-unknown})"

densities="$(printf '%s\n' "$badging" | grep -m1 '^densities:' || true)"
[ -n "$densities" ] || die "no densities reported: the APK has no density resources"
for bucket in 160 320 480 640; do
  printf '%s' "$densities" | grep -q "'$bucket'" || \
    die "density bucket '$bucket' is missing from: $densities"
done
ok "density buckets present: $densities"

if [ "$REQUIRE_ARM64" = "1" ]; then
  grep -qF "native-code: 'arm64-v8a'" <<< "$badging" || \
    die "native-code does not contain arm64-v8a"
  ok "arm64-v8a native code present"
fi

# aapt2 parses resources.arsc; a failure here means an unreadable/broken table.
if command -v timeout >/dev/null 2>&1; then
  timeout 300 "$AAPT2" dump configurations "$APK" >/dev/null 2>&1 || \
    die "resources.arsc is not readable (aapt2 dump configurations failed)"
else
  "$AAPT2" dump configurations "$APK" >/dev/null 2>&1 || \
    die "resources.arsc is not readable (aapt2 dump configurations failed)"
fi
ok "resource table is readable"

# ---------------------------------------------------------------------------
# Final-stage gates: signature and alignment
# ---------------------------------------------------------------------------
signer=""
if [ "$STAGE" = "final" ]; then
  "$APKSIGNER" verify --verbose --print-certs "$APK" > "$tmp/certs.txt" 2>&1 || {
    cat "$tmp/certs.txt" >&2 || true
    die "apksigner verify failed: the APK signature is invalid"
  }
  grep -q '^Verifies$' "$tmp/certs.txt" || die "apksigner did not report a verified signature"
  grep -qE 'Verified using v[23] scheme .*: true' "$tmp/certs.txt" || \
    die "APK is not signed with APK Signature Scheme v2 or v3"
  signer="$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' "$tmp/certs.txt" | head -1)"
  [ -n "$signer" ] || die "apksigner did not report a signer SHA-256 digest"
  if [ -n "$EXPECTED_SIGNER" ]; then
    [ "$signer" = "$EXPECTED_SIGNER" ] || \
      die "signer certificate is $signer, expected $EXPECTED_SIGNER"
    ok "release signer certificate matches the pin"
  else
    ok "APK signature is valid (signer $signer)"
  fi

  "$ZIPALIGN" -c -p 4 "$APK" >/dev/null 2>&1 || \
    die "zipalign -c -p 4 failed: the APK is not 4-byte aligned and page-aligned"
  ok "zipalign -c -p 4"
fi

if [ -n "$REPORT" ]; then
  {
    printf 'stage=%s\n' "$STAGE"
    printf 'apk=%s\n' "$APK"
    printf 'package=%s\n' "$EXPECTED_PACKAGE"
    printf 'version=%s\n' "$actual_version"
    printf 'versionCode=%s\n' "$actual_code"
    printf 'res_entries=%s\n' "$res_count"
    printf 'image_entries=%s\n' "$image_count"
    printf 'densities=%s\n' "${densities#densities: }"
    printf 'signer_sha256=%s\n' "$signer"
  } > "$REPORT"
fi

printf 'Standalone APK validation passed (%s stage).\n' "$STAGE"
