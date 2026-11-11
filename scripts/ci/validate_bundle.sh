#!/usr/bin/env bash
#
# Validate that an APKMirror-style split bundle is complete before merging.
#
# A complete bundle contains the original base.apk plus the split APKs the base
# declares as required (at minimum the density config splits). A single APK, or
# a bundle whose required splits are missing, must be rejected: patching it
# would reproduce INSTALL_FAILED_MISSING_SPLIT.
#
# Expected identity can be supplied by flags or environment:
#   EXPECTED_BUNDLE_SHA256           (default: empty = do not enforce)
#   EXPECTED_INSTAGRAM_PACKAGE       (default: com.instagram.android)
#   EXPECTED_INSTAGRAM_VERSION       (default: empty = do not enforce)
#   EXPECTED_INSTAGRAM_VERSION_CODE  (default: empty = do not enforce)
#   EXPECTED_META_CERT_SHA256        (default: empty = do not enforce)
#
# Tool paths can be overridden for tests:
#   AAPT, AAPT2, APKSIGNER, UNZIP
set -euo pipefail

AAPT="${AAPT:-aapt}"
AAPT2="${AAPT2:-aapt2}"
APKSIGNER="${APKSIGNER:-apksigner}"
UNZIP="${UNZIP:-unzip}"
SHA256SUM="${SHA256SUM:-sha256sum}"

BUNDLE=""
EXPECTED_SHA="${EXPECTED_BUNDLE_SHA256:-}"
EXPECTED_PACKAGE="${EXPECTED_INSTAGRAM_PACKAGE:-com.instagram.android}"
EXPECTED_VERSION="${EXPECTED_INSTAGRAM_VERSION:-}"
EXPECTED_VERSION_CODE="${EXPECTED_INSTAGRAM_VERSION_CODE:-}"
EXPECTED_META_CERT="${EXPECTED_META_CERT_SHA256:-}"
EXTRACT_BASE=""
REPORT=""

usage() {
  echo "usage: $0 [options] <bundle.apkm|.apks|.xapk>" >&2
  echo "  --expected-sha256 SHA256" >&2
  echo "  --expected-package NAME" >&2
  echo "  --expected-version VERSION" >&2
  echo "  --expected-version-code CODE" >&2
  echo "  --expected-meta-cert SHA256" >&2
  echo "  --extract-base PATH" >&2
  echo "  --report PATH" >&2
  exit 2
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --expected-sha256) EXPECTED_SHA="${2:-}"; shift 2 ;;
    --expected-package) EXPECTED_PACKAGE="${2:-}"; shift 2 ;;
    --expected-version) EXPECTED_VERSION="${2:-}"; shift 2 ;;
    --expected-version-code) EXPECTED_VERSION_CODE="${2:-}"; shift 2 ;;
    --expected-meta-cert) EXPECTED_META_CERT="${2:-}"; shift 2 ;;
    --extract-base) EXTRACT_BASE="${2:-}"; shift 2 ;;
    --report) REPORT="${2:-}"; shift 2 ;;
    --help|-h) usage ;;
    -*) echo "unknown option: $1" >&2; usage ;;
    *) BUNDLE="$1"; shift ;;
  esac
done

[ -n "$BUNDLE" ] || usage
[ -f "$BUNDLE" ] || { echo "ERROR: bundle not found: $BUNDLE" >&2; exit 1; }

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
ok() { printf 'ok: %s\n' "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------------------
# Digest pin
# ---------------------------------------------------------------------------
if [ -n "$EXPECTED_SHA" ]; then
  actual_sha="$("$SHA256SUM" "$BUNDLE" | awk '{print $1}')"
  [ "$actual_sha" = "$EXPECTED_SHA" ] || \
    die "bundle SHA-256 is $actual_sha, expected $EXPECTED_SHA"
  ok "bundle SHA-256 matches the pin"
else
  ok "bundle SHA-256 ($("$SHA256SUM" "$BUNDLE" | awk '{print $1}'))"
fi

# ---------------------------------------------------------------------------
# ZIP layout: base + required splits
# ---------------------------------------------------------------------------
"$UNZIP" -t "$BUNDLE" >/dev/null 2>&1 || die "ZIP integrity check failed: $BUNDLE"
entries="$("$UNZIP" -Z1 "$BUNDLE" || true)"

base_entry="$(printf '%s\n' "$entries" | grep -E '^(base\.apk|splits/base-master\.apk|base-master\.apk)$' | head -1 || true)"
[ -n "$base_entry" ] || \
  die "bundle has no base.apk. A single .apk is not a complete bundle; supply the original .apkm/.apks."

split_entries="$(printf '%s\n' "$entries" | grep -E '\.apk$' | grep -vxF "$base_entry" || true)"
split_count="$(printf '%s\n' "$split_entries" | grep -c . || true)"
[ "$split_count" -ge 1 ] || \
  die "bundle contains only $base_entry and no split APKs. A plain base APK is not a complete bundle."

density_entries="$(printf '%s\n' "$split_entries" | grep -Ei '(ldpi|mdpi|hdpi|xhdpi|xxhdpi|xxxhdpi|tvdpi)' || true)"
density_count="$(printf '%s\n' "$density_entries" | grep -c . || true)"
[ "$density_count" -ge 1 ] || \
  die "bundle has no density config split (ldpi/mdpi/hdpi/xhdpi/xxhdpi/xxxhdpi/tvdpi). The complete bundle is required."

abi_entries="$(printf '%s\n' "$split_entries" | grep -Ei '(arm64[_-]v8a|armeabi[_-]v7a|x86_64|x86)' || true)"
abi_count="$(printf '%s\n' "$abi_entries" | grep -c . || true)"

ok "bundle contains $split_count split APK(s), $density_count density split(s), $abi_count ABI split(s)"
printf '%s\n' "$split_entries" | sed 's/^/    split: /' | head -40

# ---------------------------------------------------------------------------
# Extract and verify the original base APK
# ---------------------------------------------------------------------------
base_out="$EXTRACT_BASE"
if [ -z "$base_out" ]; then
  base_out="$tmp/base.apk"
else
  mkdir -p "$(dirname "$base_out")"
fi
"$UNZIP" -p "$BUNDLE" "$base_entry" > "$base_out" || die "could not extract $base_entry"
[ -s "$base_out" ] || die "$base_entry is empty"
"$UNZIP" -t "$base_out" >/dev/null 2>&1 || die "$base_entry is not a valid APK zip"
ok "extracted $base_entry"

base_entries="$("$UNZIP" -Z1 "$base_out")"
if grep -qiE '(^|/)(feurstagram|morphe)(/|\.|$)' <<< "$base_entries"; then
  die "bundle base APK already contains FeurStagram/Morphe artifacts; use the original stock bundle"
fi

if "$AAPT2" dump xmltree --file AndroidManifest.xml "$base_out" > "$tmp/base-manifest.txt" 2>/dev/null; then
  :
else
  "$AAPT" dump xmltree "$base_out" AndroidManifest.xml > "$tmp/base-manifest.txt" 2>/dev/null || \
    die "could not dump base AndroidManifest.xml"
fi

badging="$("$AAPT" dump badging "$base_out" 2>/dev/null)" || die "aapt dump badging failed on base APK"
printf '%s\n' "$badging" | grep -E '^package:|^sdkVersion|^targetSdkVersion|^densities:|^native-code:' || true
grep -qF "package: name='$EXPECTED_PACKAGE'" <<< "$badging" || \
  die "base package is not $EXPECTED_PACKAGE"

base_version="$(printf '%s\n' "$badging" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p" | head -1)"
base_code="$(printf '%s\n' "$badging" | sed -n "s/.*versionCode='\([^']*\)'.*/\1/p" | head -1)"
if [ -n "$EXPECTED_VERSION" ] && [ "$base_version" != "$EXPECTED_VERSION" ]; then
  die "base versionName is '$base_version', expected '$EXPECTED_VERSION'"
fi
if [ -n "$EXPECTED_VERSION_CODE" ] && [ "$base_code" != "$EXPECTED_VERSION_CODE" ]; then
  die "base versionCode is '$base_code', expected '$EXPECTED_VERSION_CODE'"
fi
ok "base identity: $EXPECTED_PACKAGE $base_version ($base_code)"

"$APKSIGNER" verify --verbose --print-certs "$base_out" > "$tmp/base-certs.txt" 2>&1 || {
  cat "$tmp/base-certs.txt" >&2 || true
  die "base APK signature verification failed"
}
if grep -qiE 'CN=Feurstagram|O=Feurstagram' "$tmp/base-certs.txt"; then
  die "bundle base APK is already signed by Feurstagram; use the original stock bundle"
fi
if [ -n "$EXPECTED_META_CERT" ]; then
  grep -qi "certificate SHA-256 digest: $EXPECTED_META_CERT" "$tmp/base-certs.txt" || \
    die "base APK does not carry the expected Meta certificate $EXPECTED_META_CERT"
  ok "base Meta signing certificate matches the pin"
fi

# ---------------------------------------------------------------------------
# Every split type the base declares as required must exist in the bundle
# ---------------------------------------------------------------------------
required_types="$(sed -n 's/.*requiredSplitTypes[^=]*="\([^"]*\)".*/\1/p' "$tmp/base-manifest.txt" | head -1 || true)"
required_types="${required_types//,/ }"
if [ -n "$required_types" ]; then
  ok "base declares required split types: $required_types"
  for type in $required_types; do
    token="${type#base__}"
    case "$token" in
      density)
        [ "$density_count" -ge 1 ] || die "base requires '$type' but the bundle has no density split"
        ;;
      abi)
        [ "$abi_count" -ge 1 ] || die "base requires '$type' but the bundle has no ABI split"
        ;;
      *)
        grep -qi -- "$token" <<< "$split_entries" || \
          die "base requires '$type' but no matching split exists in the bundle"
        ;;
    esac
  done
else
  # Even without the attribute, the target Instagram base has density resources
  # that live in config splits. Require them so an incomplete bundle is caught.
  [ "$density_count" -ge 1 ] || die "bundle has no density split; the complete bundle is required"
fi

if grep -qxF 'res/xml/splits0.xml' <<< "$base_entries"; then
  ok "base retains the Play split table (expected for an unmerged bundle)"
fi

if [ -n "$REPORT" ]; then
  {
    printf 'bundle=%s\n' "$BUNDLE"
    printf 'bundle_sha256=%s\n' "$("$SHA256SUM" "$BUNDLE" | awk '{print $1}')"
    printf 'base_entry=%s\n' "$base_entry"
    printf 'base_apk=%s\n' "$base_out"
    printf 'split_count=%s\n' "$split_count"
    printf 'density_splits=%s\n' "$density_count"
    printf 'abi_splits=%s\n' "$abi_count"
    printf 'package=%s\n' "$EXPECTED_PACKAGE"
    printf 'version=%s\n' "$base_version"
    printf 'versionCode=%s\n' "$base_code"
    printf 'required_split_types=%s\n' "${required_types:-none}"
  } > "$REPORT"
fi

printf 'Bundle validation passed: %s\n' "$BUNDLE"
printf 'BASE_APK=%s\n' "$base_out"
