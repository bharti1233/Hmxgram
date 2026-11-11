#!/usr/bin/env bash
#
# Install smoke test for a standalone APK on an arm64 device/emulator.
#
# This deliberately fails when the attached target is not arm64-v8a on API 35:
# an x86_64 emulator cannot prove arm64 installation compatibility for this
# arm64-only APK (it would fail with INSTALL_FAILED_NO_MATCHING_ABIS anyway).
#
# Environment:
#   ADB             adb binary (default: adb)
#   DEVICE_SERIAL   optional adb serial (default: the only attached device)
#   EXPECTED_PACKAGE expected package (default: com.instagram.android)
#   SKIP_LAUNCH     set to 1 to skip the post-install launch check
set -euo pipefail

ADB="${ADB:-adb}"
APK="${1:-}"
EXPECTED_PACKAGE="${EXPECTED_PACKAGE:-com.instagram.android}"

die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
ok() { printf 'ok: %s\n' "$*"; }

[ -n "$APK" ] || { echo "usage: $0 <apk>" >&2; exit 2; }
[ -f "$APK" ] || die "APK not found: $APK"

command -v "$ADB" >/dev/null 2>&1 || die "adb not found; attach an arm64 API 35 device or disable the install test"

ADB_ARGS=()
if [ -n "${DEVICE_SERIAL:-}" ]; then
  ADB_ARGS=(-s "$DEVICE_SERIAL")
fi

"$ADB" "${ADB_ARGS[@]}" wait-for-device || die "no adb device available"
serial="$("$ADB" devices | awk 'NR>1 && $2=="device" {print $1; exit}')"
[ -n "$serial" ] || die "no adb device in 'device' state"
[ -z "${DEVICE_SERIAL:-}" ] || [ "$serial" = "$DEVICE_SERIAL" ] || die "expected device $DEVICE_SERIAL, got $serial"

abi="$("$ADB" "${ADB_ARGS[@]}" shell getprop ro.product.cpu.abi | tr -d '\r')"
sdk="$("$ADB" "${ADB_ARGS[@]}" shell getprop ro.build.version.sdk | tr -d '\r')"
release="$("$ADB" "${ADB_ARGS[@]}" shell getprop ro.build.version.release | tr -d '\r')"
model="$("$ADB" "${ADB_ARGS[@]}" shell getprop ro.product.model | tr -d '\r')"
printf 'device: %s (%s), Android %s / API %s, ABI %s\n' "$model" "$serial" "$release" "$sdk" "$abi"

[ "$abi" = "arm64-v8a" ] || \
  die "device ABI is '$abi', not arm64-v8a. An x86 emulator cannot validate arm64 installation compatibility."
[ "$sdk" = "35" ] || \
  die "device API level is '$sdk', expected 35 (Android 15). Use an Android 15 arm64 target."
ok "target is arm64-v8a on API 35"

# A standalone APK must install as a single package, with no split staging.
if ! "$ADB" "${ADB_ARGS[@]}" install -r -t "$APK"; then
  die "adb install failed: the APK is not installable as a standalone package"
fi

path="$("$ADB" "${ADB_ARGS[@]}" shell pm path "$EXPECTED_PACKAGE" | tr -d '\r')"
case "$path" in
  package:*) ok "installed $EXPECTED_PACKAGE" ;;
  *) die "$EXPECTED_PACKAGE is not installed after adb install" ;;
esac

if [ "${SKIP_LAUNCH:-0}" != "1" ]; then
  "$ADB" "${ADB_ARGS[@]}" shell monkey -p "$EXPECTED_PACKAGE" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || \
    die "$EXPECTED_PACKAGE did not launch"
  ok "launch intent accepted"
fi

printf 'Install smoke test passed.\n'
