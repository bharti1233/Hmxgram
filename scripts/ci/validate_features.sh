#!/usr/bin/env bash
#
# Static feature gates for the downloader core, its M2 Android shell and the
# M3 downloader screen.
#
# Enforces the rules from docs/HMX_INSTA_NEXT_PHASE_PLAN.md section 4.1:
#   * the core imports only JDK classes (no Android, no AndroidX);
#   * the Android shell imports platform classes but never Instagram internals,
#     cookies, authorization headers or broad storage permissions;
#   * every downloader component declared in a manifest comes from the single
#     default-off downloader patch, and the extension's own manifest declares
#     none of them;
#   * the expected patch inventory has not grown implicitly.
#
# No build, no Android SDK, no device, no network.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/../.."

CORE_DIR="extensions/extension/src/main/java/com/feurstagram/extension/downloader/core"
DOWNLOADER_DIR="extensions/extension/src/main/java/com/feurstagram/extension/downloader"
ANDROID_DIR="extensions/extension/src/main/java/com/feurstagram/extension/downloader/android"
SHARE_PATCH="patches/src/main/kotlin/com/feurstagram/patches/downloader/DownloaderShareTargetPatch.kt"
GHOST_DIR="extensions/extension/src/main/java/com/feurstagram/extension/ghost"
MANIFEST="extensions/extension/src/main/AndroidManifest.xml"

# The reviewed patch inventory. A new patch must be added here explicitly,
# together with its own default-off gate and review.
EXPECTED_PATCH_SOURCES=(
  patches/src/main/kotlin/com/feurstagram/patches/branding/HmxInstaAppNamePatch.kt
  patches/src/main/kotlin/com/feurstagram/patches/clone/ClonePatch.kt
  patches/src/main/kotlin/com/feurstagram/patches/debug/DebugBridgePatch.kt
  patches/src/main/kotlin/com/feurstagram/patches/display/SetColorModePatch.kt
  patches/src/main/kotlin/com/feurstagram/patches/downloader/DownloaderMediaObserverPatch.kt
  patches/src/main/kotlin/com/feurstagram/patches/downloader/DownloaderShareTargetPatch.kt
  patches/src/main/kotlin/com/feurstagram/patches/network/FeedItemFilterPatch.kt
  patches/src/main/kotlin/com/feurstagram/patches/network/LimitFeedToFollowingPatch.kt
  patches/src/main/kotlin/com/feurstagram/patches/network/NetworkBlockPatch.kt
  patches/src/main/kotlin/com/feurstagram/patches/network/SuggestedTrayEntriesPatch.kt
  patches/src/main/kotlin/com/feurstagram/patches/quoicouview/QuoicouviewAssetsPatch.kt
  patches/src/main/kotlin/com/feurstagram/patches/quoicouview/QuoicouviewPatch.kt
  patches/src/main/kotlin/com/feurstagram/patches/restart/RestartRelayPatch.kt
  patches/src/main/kotlin/com/feurstagram/patches/settings/BackHistoryPatch.kt
  patches/src/main/kotlin/com/feurstagram/patches/settings/SettingsEntryPointPatch.kt
  patches/src/main/kotlin/com/feurstagram/patches/shared/Constants.kt
  patches/src/main/kotlin/com/feurstagram/patches/shared/ReelModel.kt
  patches/src/main/kotlin/com/feurstagram/patches/signature/SignatureCheckBypassPatch.kt
  patches/src/main/kotlin/com/feurstagram/patches/ui/NotesTrayPatch.kt
  patches/src/main/kotlin/com/feurstagram/patches/ui/PopupHidingPatch.kt
  patches/src/main/kotlin/com/feurstagram/patches/update/InstallPermissionPatch.kt
)

fail=0
note() { printf '%s\n' "$*"; }
bad() { printf 'ERROR: %s\n' "$*" >&2; fail=1; }

note "== Downloader core presence =="
if [ -d "$CORE_DIR" ]; then
  core_count="$(find "$CORE_DIR" -maxdepth 1 -type f -name '*.java' | wc -l | tr -d ' ')"
  if [ "$core_count" -ge 1 ]; then
    note "ok: $core_count core source files"
  else
    bad "no core source files under $CORE_DIR"
  fi
else
  bad "missing downloader core directory: $CORE_DIR"
fi

note "== Core imports are JDK-only =="
if [ -d "$CORE_DIR" ]; then
  while IFS= read -r imported; do
    [ -z "$imported" ] && continue
    case "$imported" in
      java.*) note "ok: $imported" ;;
      *) bad "core imports a non-JDK class: $imported" ;;
    esac
  done < <(grep -rhoE '^[[:space:]]*import[[:space:]]+(static[[:space:]]+)?[A-Za-z0-9_.]+' \
      "$CORE_DIR" --include='*.java' \
      | sed -E 's/^[[:space:]]*import[[:space:]]+(static[[:space:]]+)?//' \
      | LC_ALL=C sort -u)
fi

note "== Core contains no Android or networking symbols =="
forbidden_core='android\.|androidx\.|CookieManager|getCookie|Authorization|PackageInstaller|WRITE_EXTERNAL_STORAGE|HttpURLConnection|URLConnection|java\.net\.|openConnection|openStream|Socket'
if [ -d "$CORE_DIR" ] && grep -RInE "$forbidden_core" "$CORE_DIR" --include='*.java' >/dev/null 2>&1; then
  bad "core contains a forbidden Android/network/credential symbol"
  grep -RInE "$forbidden_core" "$CORE_DIR" --include='*.java' >&2 || true
else
  note "ok: core has no Android, socket, cookie or auth symbols"
fi

note "== Downloader never touches cookies, auth headers or storage permissions =="
forbidden_engine='CookieManager|getCookie|Authorization|PackageInstaller|WRITE_EXTERNAL_STORAGE'
if [ -d "$DOWNLOADER_DIR" ] && grep -RInE "$forbidden_engine" "$DOWNLOADER_DIR" --include='*.java' >/dev/null 2>&1; then
  bad "downloader contains a forbidden credential or storage-permission symbol"
  grep -RInE "$forbidden_engine" "$DOWNLOADER_DIR" --include='*.java' >&2 || true
else
  note "ok: downloader has no cookie, auth or storage-permission symbols"
fi

note "== Android shell is present =="
if [ -d "$ANDROID_DIR" ]; then
  for file in \
    HmxDownloader.java \
    DownloadWorker.java \
    MediaStoreWriter.java \
    AudioExtractor.java \
    PreferencesDownloadRepository.java \
    DownloadNotifier.java \
    ShareTargetActivity.java \
    ShareIntentParser.java \
    ShareImporter.java \
    ShareCacheProvider.java \
    DownloadService.java \
    PendingWorkStore.java \
    ShareCacheJanitor.java \
    DownloaderActivity.java
  do
    if [ -f "$ANDROID_DIR/$file" ]; then
      note "ok: downloader/android/$file"
    else
      bad "missing Android shell file: downloader/android/$file"
    fi
  done
else
  bad "missing downloader Android shell directory: $ANDROID_DIR"
fi

note "== Android shell has no Instagram, credential or broad-storage symbol =="
forbidden_android='com\.instagram|CookieManager|getCookie|Authorization|PackageInstaller|WRITE_EXTERNAL_STORAGE|READ_EXTERNAL_STORAGE|MANAGE_EXTERNAL_STORAGE|android\.accounts|AccountManager'
if [ -d "$ANDROID_DIR" ] && grep -RInE "$forbidden_android" "$ANDROID_DIR" --include='*.java' >/dev/null 2>&1; then
  bad "Android shell contains a forbidden Instagram, credential or storage symbol"
  grep -RInE "$forbidden_android" "$ANDROID_DIR" --include='*.java' >&2 || true
else
  note "ok: Android shell touches no Instagram, cookie, account or broad-storage API"
fi

note "== Share target patch is present and default-off =="
if [ -f "$SHARE_PATCH" ]; then
  if grep -qE 'default[[:space:]]*=[[:space:]]*false' "$SHARE_PATCH"; then
    note "ok: share target patch is default-off"
  else
    bad "share target patch must be default-off"
  fi
  for needle in \
    'ShareTargetActivity' \
    'DownloaderActivity' \
    'android.intent.action.SEND' \
    'android.intent.action.SEND_MULTIPLE' \
    'ShareCacheProvider' \
    'DownloadService' \
    'android.permission.POST_NOTIFICATIONS' \
    'android.permission.FOREGROUND_SERVICE' \
    'android.permission.FOREGROUND_SERVICE_DATA_SYNC'
  do
    if grep -qF "$needle" "$SHARE_PATCH"; then
      note "ok: share target patch contains $needle"
    else
      bad "share target patch is missing $needle"
    fi
  done
  forbidden_manifest='WRITE_EXTERNAL_STORAGE|READ_EXTERNAL_STORAGE|MANAGE_EXTERNAL_STORAGE'
  if grep -qE "$forbidden_manifest" "$SHARE_PATCH"; then
    bad "share target patch asks for a broad storage permission"
  else
    note "ok: share target patch asks for no storage permission"
  fi
else
  bad "missing share target patch: $SHARE_PATCH"
fi

note "== Existing features do not reference the downloader core =="
core_refs="$(grep -RInE 'com\.feurstagram\.extension\.downloader\.core|downloader/core' \
  extensions/extension/src/main/java/com/feurstagram/extension \
  patches/src \
  --include='*.java' --include='*.kt' \
  --exclude-dir=downloader 2>/dev/null || true)"
if [ -n "$core_refs" ]; then
  bad "existing sources reference the downloader core:"
  printf '%s\n' "$core_refs" >&2
else
  note "ok: no patch or existing extension source references the core"
fi

note "== Downloader is not wired into the Android manifest =="
if [ -f "$MANIFEST" ] && grep -qi 'downloader' "$MANIFEST"; then
  bad "AndroidManifest.xml references the downloader before its UI milestone"
else
  note "ok: manifest contains no downloader component"
fi

note "== Patch inventory is exactly the reviewed set =="
mapfile -t actual_patches < <(git ls-files -- 'patches/src/main/kotlin/**/*.kt' | LC_ALL=C sort)
actual_list="$(printf '%s\n' "${actual_patches[@]}")"
expected_list="$(printf '%s\n' "${EXPECTED_PATCH_SOURCES[@]}")"
if [ "$actual_list" = "$expected_list" ]; then
  note "ok: ${#actual_patches[@]} patch sources, reviewed inventory"
else
  bad "patch inventory changed; add the new patch to EXPECTED_PATCH_SOURCES intentionally"
  diff <(printf '%s\n' "$expected_list") <(printf '%s\n' "$actual_list") >&2 || true
fi

note "== Ghost mode is absent or obeys its M5 rules =="
if [ -d "$GHOST_DIR" ]; then
  if grep -RInE 'volatile[[:space:]]+boolean' "$GHOST_DIR" --include='*.java' >/dev/null 2>&1; then
    note "ok: ghost hook short-circuits on a volatile boolean"
  else
    bad "ghost package exists but has no volatile-boolean short-circuit"
  fi
  if grep -RInE 'try[[:space:]]*\{' "$GHOST_DIR" --include='*.java' >/dev/null 2>&1 \
      && grep -RInE 'catch[[:space:]]*\(' "$GHOST_DIR" --include='*.java' >/dev/null 2>&1; then
    note "ok: ghost hook is wrapped in a fail-safe"
  else
    bad "ghost package exists but is not wrapped in a fail-safe"
  fi
else
  note "ok: no ghost package in M1 (M5 scope)"
fi

if [ "$fail" -ne 0 ]; then
  printf '\nFeature validation failed.\n' >&2
  exit 1
fi
printf '\nFeature validation passed.\n'
