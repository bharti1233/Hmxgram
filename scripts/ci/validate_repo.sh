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
  HMX_INSTAPLUS_CI_PLAN.md \
  docs/HMX_INSTAPLUS_UNIVERSAL_APK_PACKAGING.md \
  scripts/ci/validate_input_apk.sh \
  scripts/ci/validate_bundle.sh \
  scripts/ci/validate_standalone_apk.sh \
  scripts/ci/install_smoke_test.sh \
  scripts/ci/test_universal_packaging.sh \
  scripts/ci/verify_patch_report.py \
  scripts/ci/test_patch_report.sh \
  scripts/ci/validate_branding.sh \
  scripts/branding/pngtool.py \
  scripts/branding/generate_launcher_icons.py \
  scripts/branding/apply_launcher_branding.py \
  docs/HMX_INSTA_REBRANDING_AUDIT.md \
  docs/HMX_INSTA_BRANDING_GUIDE.md \
  docs/HMX_INSTA_UPDATER.md \
  docs/HMX_INSTA_BASELINE_FEATURE_INVENTORY.md \
  scripts/ci/verify_protected_methods.py \
  scripts/ci/test_protected_methods.sh \
  scripts/ci/test_downloader_logic.sh \
  scripts/ci/validate_features.sh \
  scripts/ci/test_m1_isolation.sh \
  docs/HMX_INSTA_M1_DOWNLOADER_CORE.md
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
for f in gradlew build.sh scripts/ci/validate_input_apk.sh scripts/ci/validate_bundle.sh scripts/ci/validate_standalone_apk.sh scripts/ci/install_smoke_test.sh scripts/ci/test_universal_packaging.sh scripts/ci/verify_patch_report.py scripts/ci/test_patch_report.sh scripts/ci/validate_branding.sh scripts/ci/verify_protected_methods.py scripts/ci/test_protected_methods.sh scripts/ci/test_downloader_logic.sh scripts/ci/validate_features.sh scripts/ci/test_m1_isolation.sh scripts/branding/pngtool.py scripts/branding/generate_launcher_icons.py scripts/branding/apply_launcher_branding.py; do
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

note "== Debug APK distribution safeguards =="
for f in \
  distribution/README.md \
  distribution/cleanup-debug-releases.yml \
  distribution/cleanup_debug_releases.sh \
  scripts/ci/test_cleanup_workflow.sh \
  scripts/ci/test_publish_gates.sh
do
  if [ -f "$f" ]; then note "ok: $f"; else bad "missing distribution file: $f"; fi
done

if grep -qE 'uses:[[:space:]]+[^[:space:]]+@[0-9a-f]{40}' distribution/cleanup-debug-releases.yml; then
  note "ok: cleanup template actions are pinned to commit SHAs"
else
  bad "cleanup template actions are not pinned to commit SHAs"
fi

# Only the hash and certificate text reports may be uploaded as Actions artifacts.
if grep -nE '^[[:space:]]+[^[:space:]]*\.apk[[:space:]]*$' .github/workflows/apk.yml >/dev/null; then
  bad "apk.yml contains a bare .apk path (possible Actions artifact upload)"
else
  note "ok: no bare .apk path in apk.yml"
fi
if grep -q -- '--clobber' .github/workflows/apk.yml distribution/cleanup_debug_releases.sh; then
  bad "release upload must never clobber an existing asset"
else
  note "ok: no asset clobbering"
fi

while IFS= read -r needle; do
  if grep -qF "$needle" .github/workflows/apk.yml; then
    note "ok: apk.yml contains: $needle"
  else
    bad "apk.yml is missing safeguard: $needle"
  fi
done <<'NEEDLES'
publish_test_apk
SIGNING_MODE}" = "throwaway"
SIGNATURE_BYPASS:-false}" != "true"
sha256sum -c feurstagram.apk.sha256
server_digest
DIST_TOKEN must be distinct from BASE_APK_TOKEN
aapt dump badging "$APK_OUT"
APK package is not
APK version is
disable_patches
DISABLE_PATCHES
validate_input_apk.sh
validate_bundle.sh
validate_standalone_apk.sh
install_smoke_test.sh
EXPECTED_INSTAGRAM_VERSION_CODE
run_install_test
verify_protected_methods.py
hmx-baseline-454-verified
protected-methods-report.json
NEEDLES

if grep -qF 'scripts/ci/test_protected_methods.sh' .github/workflows/ci.yml; then
  note "ok: ci.yml runs the protected-method verifier self-test"
else
  bad "ci.yml must run scripts/ci/test_protected_methods.sh"
fi

for needle in \
  'scripts/ci/test_downloader_logic.sh' \
  'scripts/ci/validate_features.sh' \
  'scripts/ci/test_m1_isolation.sh'
do
  if grep -qF "$needle" .github/workflows/ci.yml; then
    note "ok: ci.yml runs $needle"
  else
    bad "ci.yml must run $needle"
  fi
done

if grep -qF 'fetch-depth: 0' .github/workflows/ci.yml; then
  note "ok: ci.yml fetches history for the M1 baseline check"
else
  bad "ci.yml must use actions/checkout fetch-depth: 0 for test_m1_isolation.sh"
fi

# The build ref allowlist must stay exact: main plus the v2 milestone branch.
if grep -qF "github.ref == 'refs/heads/main'" .github/workflows/apk.yml && \
   grep -qF "github.ref == 'refs/heads/feature/hmx-downloader-ghost-mode-v2'" .github/workflows/apk.yml; then
  note "ok: apk.yml build refs are the exact main + v2 allowlist"
else
  bad "apk.yml must allow exactly refs/heads/main and refs/heads/feature/hmx-downloader-ghost-mode-v2"
fi
if grep -qE "refs/heads/(feature|release)/\*|refs/heads/feature/\*\*" .github/workflows/apk.yml; then
  bad "apk.yml build ref guard must not use a wildcard"
else
  note "ok: apk.yml build ref guard has no wildcard"
fi

# The downloader share target is a default-off patch. The build must only be
# able to enable it through one explicit, reviewed switch, and the patch report
# must then have to list it.
if grep -qF 'enable_downloader:' .github/workflows/apk.yml && \
   grep -qF 'args+=(--downloader)' .github/workflows/apk.yml && \
   grep -qF 'EXPECTED_PATCHES_WITH_DOWNLOADER' .github/workflows/apk.yml && \
   grep -qF -- '--downloader) ENABLE_DOWNLOADER=1 ;;' build.sh && \
   grep -qF 'ARGS+=(-e "Downloader share target")' build.sh; then
  note "ok: the downloader share target is only reachable through the explicit downloader build switch"
else
  bad "the downloader share target must only be enabled through the explicit downloader build switch"
fi

if grep -qF 'bash "$VALIDATE_INPUT"' build.sh && \
   grep -qF 'bash "$VALIDATE_BUNDLE"' build.sh && \
   grep -qF 'bash "$VALIDATE_STANDALONE" --stage merge' build.sh && \
   grep -qF 'bash "$VALIDATE_STANDALONE" --stage final' build.sh; then
  note "ok: build.sh enforces input, bundle, merge, and final validation"
else
  bad "build.sh does not enforce the standalone packaging validators"
fi

if grep -qF 'failedPatches' scripts/ci/verify_patch_report.py && \
   grep -qF 'verify_patch_report.py' build.sh; then
  note "ok: build.sh verifies the Morphe patch report with the shared verifier"
else
  bad "build.sh does not verify the Morphe patch report with scripts/ci/verify_patch_report.py"
fi

if grep -qF -- '-clean-meta' build.sh; then
  note "ok: build.sh merges with APKEditor -clean-meta (strips the Play source stamp)"
else
  bad "build.sh must pass -clean-meta to APKEditor; without it stamp-cert-sha256 survives the merge"
fi

if grep -qF 'apply_launcher_branding.py' build.sh; then
  note "ok: build.sh applies the HMX Insta launcher branding"
else
  bad "build.sh does not apply the launcher branding"
fi

if grep -qF 'HMX Insta app name' .github/workflows/apk.yml; then
  note "ok: apk.yml expects the HMX Insta app name patch"
else
  bad "apk.yml must expect the HMX Insta app name patch"
fi

if grep -qF 'DISABLE_PATCHES' build.sh && grep -qF 'ARGS+=(-d "$name")' build.sh; then
  note "ok: build.sh applies DISABLE_PATCHES as -d"
else
  bad "build.sh does not apply DISABLE_PATCHES"
fi

if grep -qE 'GITHUB_ENV.*DIST_TOKEN|DIST_TOKEN.*GITHUB_ENV' .github/workflows/apk.yml; then
  bad "DIST_TOKEN must never be written to GITHUB_ENV"
else
  note "ok: DIST_TOKEN is not exported to GITHUB_ENV"
fi

if grep -qF "readonly DEBUG_TAG_RE='^debug-test-[0-9]+-[0-9]+$'" distribution/cleanup_debug_releases.sh; then
  note "ok: cleanup tag regex is exact"
else
  bad "cleanup tag regex is not exactly ^debug-test-[0-9]+-[0-9]+$"
fi
for needle in 'Apk-input-original' 'protected_id' 'DELETE_ENABLED'; do
  if grep -qF "$needle" distribution/cleanup_debug_releases.sh; then
    note "ok: cleanup script guard present: $needle"
  else
    bad "cleanup script is missing guard: $needle"
  fi
done

if [ "$fail" -ne 0 ]; then
  printf '\nValidation failed.\n' >&2
  exit 1
fi

printf '\nRepository validation passed.\n'
