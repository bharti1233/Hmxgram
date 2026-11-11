#!/usr/bin/env bash
#
# Validate that a plain .apk input is a complete standalone APK and NOT a
# bundle-derived base APK.
#
# A bundle-derived base APK declares split requirements (for example
# requiredSplitTypes="base__density") but ships none of the split payload.
# Installing it produces INSTALL_FAILED_MISSING_SPLIT on Android 15. Such an
# input must be replaced with the complete .apkm/.apks bundle.
#
# Expected identity can be supplied by flags or environment:
#   EXPECTED_INSTAGRAM_PACKAGE      (default: com.instagram.android)
#   EXPECTED_INSTAGRAM_VERSION      (default: empty = do not enforce)
#   EXPECTED_INSTAGRAM_VERSION_CODE (default: empty = do not enforce)
#   EXPECTED_META_CERT_SHA256       (default: empty = do not enforce)
set -euo pipefail

AAPT="${AAPT:-aapt}"
AAPT2="${AAPT2:-aapt2}"
APKSIGNER="${APKSIGNER:-apksigner}"
UNZIP="${UNZIP:-unzip}"

APK=""
EXPECTED_PACKAGE="${EXPECTED_INSTAGRAM_PACKAGE:-com.instagram.android}"
EXPECTED_VERSION="${EXPECTED_INSTAGRAM_VERSION:-}"
EXPECTED_VERSION_CODE="${EXPECTED_INSTAGRAM_VERSION_CODE:-}"
EXPECTED_META_CERT="${EXPECTED_META_CERT_SHA256:-}"

usage() {
  echo "usage: $0 [options] <apk>" >&2
  echo "  --expected-package NAME" >&2
  echo "  --expected-version VERSION" >&2
  echo "  --expected-version-code CODE" >&2
  echo "  --expected-meta-cert SHA256" >&2
  exit 2
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --expected-package) EXPECTED_PACKAGE="${2:-}"; shift 2 ;;
    --expected-version) EXPECTED_VERSION="${2:-}"; shift 2 ;;
    --expected-version-code) EXPECTED_VERSION_CODE="${2:-}"; shift 2 ;;
    --expected-meta-cert) EXPECTED_META_CERT="${2:-}"; shift 2 ;;
    --help|-h) usage ;;
    -*) echo "unknown option: $1" >&2; usage ;;
    *) APK="$1"; shift ;;
  esac
done

[ -n "$APK" ] || usage
[ -f "$APK" ] || { echo "ERROR: APK not found: $APK" >&2; exit 1; }

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
ok() { printf 'ok: %s\n' "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

"$UNZIP" -t "$APK" >/dev/null 2>&1 || die "ZIP integrity check failed: $APK"
entries="$("$UNZIP" -Z1 "$APK")"
grep -qxF 'AndroidManifest.xml' <<< "$entries" || die "AndroidManifest.xml is missing"

# An already-patched FeurStagram APK is not a valid original input.
if grep -qiE '(^|/)(feurstagram|morphe)(/|\.|$)' <<< "$entries"; then
  grep -iE '(^|/)(feurstagram|morphe)(/|\.|$)' <<< "$entries" | head -10 >&2 || true
  die "input already contains FeurStagram/Morphe artifacts; use the original stock APK"
fi

if "$AAPT2" dump xmltree --file AndroidManifest.xml "$APK" > "$tmp/manifest.txt" 2>/dev/null; then
  :
else
  "$AAPT" dump xmltree "$APK" AndroidManifest.xml > "$tmp/manifest.txt" 2>/dev/null || \
    die "could not dump AndroidManifest.xml"
fi

# This is the core rejection: a bundle-derived base APK is not installable on
# its own and must not be patched in place.
if grep -qE 'requiredSplitTypes|splitTypes|isSplitRequired|com\.android\.vending\.splits\.required' "$tmp/manifest.txt"; then
  if grep -qxF 'res/xml/splits0.xml' <<< "$entries" || \
     grep -qE 'requiredSplitTypes="[^"]+"|com\.android\.vending\.splits\.required' "$tmp/manifest.txt"; then
    grep -nE 'requiredSplitTypes|splitTypes|isSplitRequired|com\.android\.vending\.splits\.required' "$tmp/manifest.txt" >&2 || true
    die "input is a bundle-derived base APK with split requirements, but contains no split payload. Supply the complete original .apkm/.apks bundle (base_apk_kind=apkm), not a single .apk."
  fi
fi
if grep -qxF 'res/xml/splits0.xml' <<< "$entries"; then
  die "input contains res/xml/splits0.xml (Play split table) without complete splits. Supply the complete original .apkm/.apks bundle, not a single .apk."
fi
ok "input is not a bundle-derived base APK"

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

"$APKSIGNER" verify --verbose --print-certs "$APK" > "$tmp/certs.txt" 2>&1 || {
  cat "$tmp/certs.txt" >&2 || true
  die "apksigner verify failed: the input APK signature is invalid"
}
if grep -qiE 'CN=Feurstagram|O=Feurstagram' "$tmp/certs.txt"; then
  die "input is already signed by Feurstagram; use the original stock APK, not a patched build"
fi
if [ -n "$EXPECTED_META_CERT" ]; then
  grep -qi "certificate SHA-256 digest: $EXPECTED_META_CERT" "$tmp/certs.txt" || \
    die "input does not carry the expected Meta certificate $EXPECTED_META_CERT"
  ok "Meta signing certificate matches the pin"
else
  ok "input signature is valid and not a FeurStagram build"
fi

printf 'Input APK validation passed: %s\n' "$APK"
