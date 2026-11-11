#!/usr/bin/env bash
#
# Repository checks for the HMX Insta rebranding.
#
# These are static checks: they need no Android SDK, no device and no build.
# They guard the three things that are easy to break silently:
#
#   1. the launcher artwork still matches the official logo (the generated
#      assets are reproducible, so a stale asset is caught);
#   2. the upstream FeurStagram updater is really gone (no release API, no
#      download, no install path);
#   3. the user-facing strings say HMX Insta.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/../.."

fail=0
note() { printf '%s\n' "$*"; }
bad() { printf 'ERROR: %s\n' "$*" >&2; fail=1; }

EXTENSION="extensions/extension/src/main/java/com/feurstagram/extension"
BRANDING="patches/src/main/resources/branding"
LOGO_CANDIDATES=("hm insta.png" "hmx insta.png")

note "== Official logo =="
logo=""
for candidate in "${LOGO_CANDIDATES[@]}"; do
    if [ -f "$candidate" ]; then logo="$candidate"; break; fi
done
if [ -n "$logo" ]; then
    note "ok: official logo present: $logo"
else
    bad "the official logo is missing (expected one of: ${LOGO_CANDIDATES[*]})"
fi

note "== Launcher artwork =="
for density in mdpi hdpi xhdpi xxhdpi xxxhdpi; do
    for layer in foreground background; do
        if [ -f "$BRANDING/$layer-$density.png" ]; then
            note "ok: $BRANDING/$layer-$density.png"
        else
            bad "missing launcher asset: $BRANDING/$layer-$density.png"
        fi
    done
done
if [ -f "$BRANDING/ig_launcher_monochrome.xml" ]; then
    note "ok: $BRANDING/ig_launcher_monochrome.xml"
else
    bad "missing launcher asset: $BRANDING/ig_launcher_monochrome.xml"
fi

# The generated artwork must still match the logo: regenerate into a temporary
# directory and compare. A stale asset would ship the wrong icon.
if [ -n "$logo" ] && [ -f "scripts/branding/generate_launcher_icons.py" ]; then
    tmp="$(mktemp -d)"
    trap 'rm -rf "$tmp"' EXIT
    cp -r "$BRANDING" "$tmp/branding"
    cp "branding/legacy" -r "$tmp/legacy" 2>/dev/null || true
    if python3 scripts/branding/generate_launcher_icons.py >/dev/null 2>&1; then
        mismatch=0
        for density in mdpi hdpi xhdpi xxhdpi xxxhdpi; do
            for layer in foreground background; do
                a="$tmp/branding/$layer-$density.png"
                b="$BRANDING/$layer-$density.png"
                if [ -f "$a" ] && [ -f "$b" ]; then
                    cmp -s "$a" "$b" || { bad "$BRANDING/$layer-$density.png is stale: regenerate it"; mismatch=1; }
                fi
            done
        done
        [ "$mismatch" -eq 0 ] && note "ok: the launcher artwork matches the official logo"
    else
        bad "the launcher icon generator failed; the artwork cannot be verified"
    fi
fi

note "== Updater removed =="
if grep -rn "api\.github\.com" "$EXTENSION" >/dev/null 2>&1; then
    bad "the extension still talks to a GitHub release API"
    grep -rn "api\.github\.com" "$EXTENSION" >&2 || true
else
    note "ok: no release API call is left in the extension"
fi
for needle in "jean-voila/Feurstagram" "PackageInstaller" "UPDATE_FILE_NAME" "browser_download_url"; do
    if grep -rn -- "$needle" "$EXTENSION" >/dev/null 2>&1; then
        bad "the extension still contains '$needle'"
    else
        note "ok: the extension no longer contains '$needle'"
    fi
done
if grep -q "FEATURE_ENABLED = false" "$EXTENSION/UpdateChecker.java"; then
    note "ok: the updater is behind a disabled feature flag"
else
    bad "UpdateChecker must keep the updater behind a disabled feature flag"
fi

note "== User-facing branding =="
if grep -rn '"Feurstagram\|"FeurStagram' "$EXTENSION" >/dev/null 2>&1; then
    bad "the extension still shows FeurStagram to the user"
    grep -rn '"Feurstagram\|"FeurStagram' "$EXTENSION" >&2 || true
else
    note "ok: no user-facing FeurStagram string is left in the extension"
fi
if grep -q 'title.setText("HMX Insta")' "$EXTENSION/Settings.java"; then
    note "ok: the settings page is titled HMX Insta"
else
    bad "the settings page must be titled HMX Insta"
fi
if grep -q 'buildAbout' "$EXTENSION/Settings.java"; then
    note "ok: the About page is present"
else
    bad "the About page is missing"
fi
if grep -q 'APP_NAME = "HMX Insta"' patches/src/main/kotlin/com/feurstagram/patches/branding/HmxInstaAppNamePatch.kt; then
    note "ok: the app-name patch renames the app to HMX Insta"
else
    bad "the app-name patch must rename the app to HMX Insta"
fi

if [ "$fail" -ne 0 ]; then
    printf '\nBranding validation failed.\n' >&2
    exit 1
fi
printf '\nBranding validation passed.\n'
