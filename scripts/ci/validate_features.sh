#!/usr/bin/env bash
#
# Static checks for the HMX downloader and HMX ghost mode.
#
# These are the rules that must not be broken by a later change, checked
# without a device:
#
#   * the downloader stays local: no third-party service, no credentials, no
#     cookies, no token, nothing uploaded;
#   * nothing is downloaded without the user asking for it;
#   * nothing is installed automatically;
#   * no broad storage permission is asked for;
#   * the ghost-mode controls Instagram decides on the server stay unsupported
#     and cannot be switched on;
#   * both new patches are part of the expected patch manifest.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/../.."

fail=0
note() { printf '%s\n' "$*"; }
bad() { printf 'ERROR: %s\n' "$*" >&2; fail=1; }

DL="extensions/extension/src/main/java/com/feurstagram/extension/downloader"
GH="extensions/extension/src/main/java/com/feurstagram/extension/ghost"
PATCHES="patches/src/main/kotlin/com/feurstagram/patches"

note "== The downloader stays on the device =="
# Only the code is checked: a comment that says "no third-party service" must
# not be read as a third-party service.
code_only() {
    grep -rni -- "$1" "$2" | grep -vE ':[[:space:]]*(\*|//|/\*|\*)' || true
}
for needle in "api.github.com" "http://" "https://graph" "third-party" "upload" "multipart"; do
    hits="$(code_only "$needle" "$DL")"
    if [ -n "$hits" ]; then
        bad "the downloader mentions '$needle' in code; it must stay local"
        printf '%s\n' "$hits" >&2
    else
        note "ok: the downloader does not mention '$needle' in code"
    fi
done

note "== No credentials are touched =="
for needle in "CookieManager" "getCookie" "Authorization" "Bearer" "access_token" "sessionid"; do
    hits="$(code_only "$needle" "$DL"; code_only "$needle" "$GH")"
    if [ -n "$hits" ]; then
        bad "the new code mentions '$needle' in code; it must not touch credentials"
        printf '%s\n' "$hits" >&2
    else
        note "ok: the new code does not mention '$needle' in code"
    fi
done

note "== Nothing is installed and no permission is asked for =="
for needle in "PackageInstaller" "ACTION_INSTALL_PACKAGE" "REQUEST_INSTALL_PACKAGES" \
        "WRITE_EXTERNAL_STORAGE" "READ_EXTERNAL_STORAGE" "READ_MEDIA_IMAGES" "MANAGE_EXTERNAL_STORAGE"; do
    hits="$(code_only "$needle" "$DL"; code_only "$needle" "$GH")"
    if [ -n "$hits" ]; then
        bad "the new code mentions '$needle' in code"
        printf '%s\n' "$hits" >&2
    else
        note "ok: the new code does not mention '$needle' in code"
    fi
done

note "== Downloads only start when the user asks =="
starts="$(grep -rl "HmxDownloader.get().enqueue" "$DL" | tr '\n' ' ')"
case "$starts" in
    *DownloadMenu.java*) note "ok: a download only starts from the download menu" ;;
    *) bad "a download can be started from somewhere else: $starts" ;;
esac
if grep -rn "enqueue" "$DL" | grep -v "DownloadMenu.java" | grep -v "HmxDownloader.java" \
        | grep -v "^\s*\*" >/dev/null 2>&1; then
    bad "the download queue is started from outside the download menu"
    grep -rn "enqueue" "$DL" | grep -v "DownloadMenu.java" | grep -v "HmxDownloader.java" >&2 || true
else
    note "ok: the queue is only fed by the download menu"
fi

note "== The capture hook keeps the parsed media =="
CAPTURE_PATCH="patches/src/main/kotlin/com/feurstagram/patches/downloader/DownloaderCapturePatch.kt"
if grep -q 'capture(Ljava/lang/Object;)Ljava/lang/Object;' "$CAPTURE_PATCH" &&
   grep -q 'move-result-object v\$register' "$CAPTURE_PATCH"; then
    note "ok: the capture hook puts the parsed media back into its register"
else
    bad "the capture hook must return the media and restore it (move-result-object)"
fi
if grep -q 'public Object capture(Object model)' \
        extensions/extension/src/main/java/com/feurstagram/extension/downloader/MediaRegistry.java; then
    note "ok: the registry hands the media back to the patch"
else
    bad "MediaRegistry.capture must return the media so the patch can restore the register"
fi
if grep -q 'removeOnGlobalLayoutListener(listener)' \
        extensions/extension/src/main/java/com/feurstagram/extension/downloader/DownloaderInstaller.java; then
    note "ok: the layout listener is taken off again when the screen goes away"
else
    bad "the downloader installer must remove its layout listener"
fi

note "== The ghost-mode controls Instagram decides on the server stay off =="
for feature in HIDE_ONLINE_STATUS DISABLE_ACTIVITY_STATUS ANONYMOUS_STORY_VIEWING HIDE_LIVE_VIEW; do
    if grep -A6 "$feature(" "$GH/GhostFeature.java" | grep -q "Support.UNSUPPORTED"; then
        note "ok: $feature is marked unsupported"
    else
        bad "$feature must be marked unsupported"
    fi
done
if grep -q "isControllable" "$GH/GhostPolicy.java"; then
    note "ok: the policy only lets supported controls be active"
else
    bad "the ghost policy must refuse to activate unsupported controls"
fi
if grep -q "setEnabled(false)" "$GH/GhostModeScreen.java" || \
        grep -q "toggle.setEnabled(enabled)" "$GH/GhostModeScreen.java"; then
    note "ok: an unsupported control cannot be switched on"
else
    bad "the ghost-mode screen must disable unsupported controls"
fi

note "== The new patches are part of the expected patch manifest =="
for patch in "HMX downloader capture" "HMX ghost mode"; do
    if grep -qF "$patch" .github/workflows/apk.yml; then
        note "ok: apk.yml expects '$patch'"
    else
        bad "apk.yml must expect '$patch'"
    fi
    if grep -rqF "name = \"$patch\"" "$PATCHES"; then
        note "ok: the patch '$patch' exists"
    else
        bad "the patch '$patch' does not exist"
    fi
done

if [ "$fail" -ne 0 ]; then
    printf '\nFeature validation failed.\n' >&2
    exit 1
fi
printf '\nFeature validation passed.\n'
