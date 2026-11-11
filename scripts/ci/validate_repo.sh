#!/usr/bin/env bash
# Repository-level validation for HMX Insta+.
#
# This script performs no build. It is safe to run locally and in CI.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/../.."

fail=0
note() { printf '%s\n' "$*"; }
bad() { printf 'ERROR: %s\n' "$*" >&2; fail=1; }

note "== Required files =="
for f in \
  LICENSE \
  NOTICE \
  Readme.md \
  build.sh \
  gradlew \
  settings.gradle.kts \
  patches/build.gradle.kts \
  extensions/extension/build.gradle.kts \
  HMX_INSTAPLUS_SOURCE_AUDIT.md \
  HMX_INSTAPLUS_BUILD_REQUIREMENTS.md \
  HMX_INSTAPLUS_SECURITY_AUDIT.md \
  HMX_INSTAPLUS_CI_PLAN.md
 do
  if [ -f "$f" ]; then note "ok: $f"; else bad "missing required file: $f"; fi
done

note "== License and upstream attribution =="
if grep -q "GNU GENERAL PUBLIC LICENSE" LICENSE; then note "ok: GPL text present"; else bad "LICENSE does not contain GPL text"; fi
if grep -qi "Morphe" NOTICE && grep -qi "Piko" NOTICE; then note "ok: NOTICE attribution present"; else bad "NOTICE is missing Morphe/Piko attribution"; fi

note "== Forbidden tracked artifacts =="
forbidden="$(git ls-files | grep -Ei '(^|/)(askpass|\.env|id_rsa|id_ed25519)|\.(apk|apkm|xapk|apks|keystore|jks|p12|pfx|pem|key|mpp|mpe)$|^tools/' || true)"
if [ -n "$forbidden" ]; then
  bad "forbidden files are tracked:"
  printf '%s\n' "$forbidden" >&2
else
  note "ok: no APK/keystore/tool artifacts tracked"
fi

note "== Secret patterns in tracked files =="
secret_files="$(git grep -lE 'gh[pousr]_[A-Za-z0-9_]{20,}|github_pat_[A-Za-z0-9_]{20,}|AKIA[0-9A-Z]{16}|-----BEGIN (RSA |OPENSSH |EC |DSA |PGP )?PRIVATE KEY-----|xox[baprs]-[A-Za-z0-9-]{10,}' -- . || true)"
if [ -n "$secret_files" ]; then
  bad "possible secret patterns found in tracked files (paths only):"
  printf '%s\n' "$secret_files" >&2
else
  note "ok: no credential-like patterns in tracked files"
fi

note "== Private asset identifiers =="
# Private GitHub release asset IDs are the value of BASE_APK_URL; do not publish them.
asset_ids="$(git grep -lE 'releases/assets/[0-9]+' -- . ':!scripts/ci/validate_repo.sh' || true)"
if [ -n "$asset_ids" ]; then
  bad "private release asset IDs found in tracked files (paths only):"
  printf '%s\n' "$asset_ids" >&2
else
  note "ok: no private release asset IDs in tracked files"
fi

note "== Third-party actions pinned to commit SHAs =="
unpinned=""
while IFS= read -r use; do
  ref="${use##*@}"
  if ! printf '%s' "$ref" | grep -qE '^[0-9a-f]{40}$'; then
    unpinned+="$use"$'\n'
  fi
done < <(grep -rhoE 'uses:[[:space:]]+[^[:space:]]+@[^[:space:]]+' .github/workflows || true)
if [ -n "$unpinned" ]; then
  bad "workflow actions are not pinned to full commit SHAs:"
  printf '%s' "$unpinned" >&2
else
  note "ok: all workflow actions use full commit SHAs"
fi

note "== Executable modes =="
for f in gradlew build.sh; do
  mode="$(git ls-files -s "$f" | awk '{print $1}')"
  if [ "$mode" = "100755" ]; then note "ok: $f is executable in git"; else bad "$f must be mode 100755 (found $mode)"; fi
done

note "== Workflow safety =="
if [ -d .github/workflows ]; then
  while IFS= read -r wf; do
    note "checking: $wf"
    if grep -qE 'pull_request_target' "$wf"; then bad "$wf uses pull_request_target"; fi
    if grep -qE -- '--debug' "$wf"; then bad "$wf enables the debug bridge"; fi
    if grep -qE 'gh[pousr]_[A-Za-z0-9_]{20,}|github_pat_[A-Za-z0-9_]{20,}' "$wf"; then bad "$wf contains a token-like literal"; fi
  done < <(find .github/workflows -maxdepth 1 -type f \( -name '*.yml' -o -name '*.yaml' \) | sort)
else
  bad ".github/workflows is missing"
fi

note "== Morphe target configuration =="
if grep -q 'packageName = "com.instagram.android"' patches/src/main/kotlin/com/feurstagram/patches/shared/Constants.kt; then
  note "ok: Instagram package target present"
else
  bad "Instagram package target missing from Constants.kt"
fi
if grep -q 'ApkFileType.APK' patches/src/main/kotlin/com/feurstagram/patches/shared/Constants.kt; then
  note "ok: APK file type target present"
else
  bad "ApkFileType.APK missing from Constants.kt"
fi

if [ "$fail" -ne 0 ]; then
  printf '\nValidation failed.\n' >&2
  exit 1
fi

printf '\nRepository validation passed.\n'
