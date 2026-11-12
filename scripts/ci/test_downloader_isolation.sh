#!/usr/bin/env bash
#
# Downloader regression gate: prove the protected M0 baseline is unchanged and
# that the downloader milestones stay inside their exact allowlist.
#
# Two independent checks:
#   1. a pinned SHA-256 over every protected source/configuration file;
#   2. a diff against the M1 integration commit (ba72c21) limited to the M2
#      allowlist, plus the M1 files that were already reviewed.
#
# The APK-level protected-method verifier still runs in the gated build; this
# script is the offline, no-build regression guard that runs on every CI run.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/../.."

BASE="${HMX_DOWNLOADER_BASE:-e7074eb789c0cc66c0ce0cd946d52ca5519522e9}"
# The baseline is the reviewed CI commit that lets the gated build apply the
# downloader's own patches and the privacy screen (the M1 integration commit
# ba72c21 is its ancestor). Everything the downloader milestones change is measured from
# there, so the milestone diff still contains no protected file. The digest
# below pins the exact content of every protected file, build.sh and apk.yml
# included, so any later change to them still fails this check.
#
# Computed from the reviewed CI baseline plus the reviewed M2/M3/M4/M5 files
# over the protected paths listed below. The share-target patch is deliberately
# included. The digest was last re-pinned for the M5 ghost-mode work: the
# delta was attributed to the new ghost sources and their patch, with every
# other protected file byte-identical to the previous pin.
#
# The ghost sources are inside the digest on purpose: a privacy control that
# is changed after its review must fail this check like any other protected
# file.
PINNED_PROTECTED_DIGEST="f11958d55461408266fc1d5c69bb0bcccd61ac20df1df7605631c7bde499f863"
CORE_DIR="extensions/extension/src/main/java/com/feurstagram/extension/downloader/core"
ANDROID_DIR="extensions/extension/src/main/java/com/feurstagram/extension/downloader/android"

fail=0
note() { printf '%s\n' "$*"; }
bad() { printf 'ERROR: %s\n' "$*" >&2; fail=1; }

WORK="$(mktemp -d "${TMPDIR:-/tmp}/hmx-downloader-isolation.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

note "== Downloader base commit =="
if git cat-file -e "${BASE}^{commit}" 2>/dev/null; then
  note "ok: base $BASE is available"
else
  bad "base commit $BASE is not available; fetch full history (fetch-depth: 0)"
fi

note "== Protected baseline digest =="
actual_digest="$(
  {
    git ls-files -z -- \
      patches/src \
      extensions/extension/src/main/java/com/feurstagram/extension \
      extensions/extension/src/main/AndroidManifest.xml \
      extensions/extension/build.gradle.kts \
      scripts/ci/protected_methods.json \
      scripts/ci/verify_protected_methods.py \
      scripts/ci/test_protected_methods.sh \
      build.sh \
      .github/workflows/apk.yml \
    | grep -zv '^extensions/extension/src/main/java/com/feurstagram/extension/downloader/' \
    | LC_ALL=C sort -z \
    | while IFS= read -r -d '' file; do
        printf '%s\n' "$file"
        sha256sum "$file" | cut -d' ' -f1
      done
  } | sha256sum | cut -d' ' -f1
)"
if [ "$actual_digest" = "$PINNED_PROTECTED_DIGEST" ]; then
  note "ok: protected files match the pinned downloader baseline digest"
else
  bad "protected files changed (expected $PINNED_PROTECTED_DIGEST, got $actual_digest)"
fi

note "== Protected paths unchanged since the reviewed CI baseline =="
protected_changed="$(git diff --name-only "$BASE" -- \
  patches/src \
  extensions/extension/src/main/java/com/feurstagram/extension \
  extensions/extension/src/main/AndroidManifest.xml \
  extensions/extension/build.gradle.kts \
  scripts/ci/protected_methods.json \
  scripts/ci/verify_protected_methods.py \
  scripts/ci/test_protected_methods.sh \
  build.sh \
  .github/workflows/apk.yml \
  | grep -v '^extensions/extension/src/main/java/com/feurstagram/extension/downloader/' \
  | grep -v '^extensions/extension/src/main/java/com/feurstagram/extension/ghost/' \
  | grep -v '^patches/src/main/kotlin/com/feurstagram/patches/downloader/' \
  | grep -v '^patches/src/main/kotlin/com/feurstagram/patches/ghost/' || true)"
if [ -z "$protected_changed" ]; then
  note "ok: no protected path changed"
else
  bad "protected paths changed since $BASE:"
  printf '%s\n' "$protected_changed" >&2
fi

note "== Protected-method manifest pin =="
if python3 - <<'PY'
import json
import sys

manifest = json.load(open("scripts/ci/protected_methods.json"))
baseline = manifest.get("baseline") or {}
gate = manifest.get("gate") or {}
surfaces = gate.get("surfaces") or []
problems = []
if baseline.get("apk_sha256") != "6a327db6ec9c0ccc83f82a76eaf62451c6fac526131e6f12b260bec2f5cd2822":
    problems.append("baseline APK digest changed")
if baseline.get("commit") != "a5bace00e84094f06622ff8f47ae60990ded8e09":
    problems.append("baseline commit changed")
if len(surfaces) != 12:
    problems.append("expected 12 gate surfaces, found %d" % len(surfaces))
ids = [s.get("id") for s in surfaces]
if len(ids) != len(set(ids)):
    problems.append("duplicate gate surface ids")
if "ghost.network.guard" not in [
        item.get("id") if isinstance(item, dict) else item
        for item in (manifest.get("rejected_exceptions") or [])
]:
    problems.append("ghost.network.guard is not rejected")
if problems:
    for problem in problems:
        print("ERROR: " + problem, file=sys.stderr)
    sys.exit(1)
PY
then
  note "ok: 12 surfaces, baseline APK pin and rejected ghost guard intact"
else
  bad "protected_methods.json no longer matches the M0 baseline"
fi

note "== Downloader changes are confined to the allowlist =="
{
  git diff --name-only "$BASE" -- .
  git ls-files --others --exclude-standard
} | LC_ALL=C sort -u > "$WORK/changed"
while IFS= read -r path; do
  [ -z "$path" ] && continue
  case "$path" in
    extensions/extension/src/main/java/com/feurstagram/extension/downloader/core/*) ;;
    extensions/extension/src/main/java/com/feurstagram/extension/downloader/android/*) ;;
    extensions/extension/src/test/java/com/feurstagram/extension/downloader/core/*) ;;
    extensions/extension/src/main/java/com/feurstagram/extension/ghost/*) ;;
    extensions/extension/src/main/java/com/feurstagram/extension/ghost/android/*) ;;
    extensions/extension/src/test/java/com/feurstagram/extension/ghost/*) ;;
    patches/src/main/kotlin/com/feurstagram/patches/downloader/*) ;;
    patches/src/main/kotlin/com/feurstagram/patches/ghost/*) ;;
    scripts/ci/test_downloader_logic.sh) ;;
    scripts/ci/test_downloader_isolation.sh) ;;
    scripts/ci/test_m1_isolation.sh) ;;
    scripts/ci/validate_features.sh) ;;
    scripts/ci/validate_repo.sh) ;;
    .github/workflows/ci.yml) ;;
    docs/HMX_INSTA_M1_DOWNLOADER_CORE.md) ;;
    docs/HMX_INSTA_M2_ANDROID_SHELL.md) ;;
    docs/HMX_INSTA_M2_DEVICE_CHECKLIST.md) ;;
    docs/HMX_INSTA_M3_DOWNLOADER_UI.md) ;;
    docs/HMX_INSTA_M4_INSTAGRAM_MEDIA.md) ;;
    docs/HMX_INSTA_M5_GHOST_MODE.md) ;;
    docs/HMX_INSTA_NEXT_PHASE_PLAN.md) ;;
    docs/HMX_DOWNLOADER_ARCHITECTURE.md) ;;
    CHANGELOG.md) ;;
    *) bad "downloader changed a path outside the allowlist: $path" ;;
  esac
done < "$WORK/changed"
if [ "$fail" -eq 0 ]; then
  note "ok: every changed path belongs to the downloader milestones"
fi

note "== Core has no Android imports =="
if [ -d "$CORE_DIR" ] && grep -RInE '^[[:space:]]*import[[:space:]]+(android|androidx)\.' \
    "$CORE_DIR" --include='*.java' >/dev/null 2>&1; then
  bad "core imports an Android class"
else
  note "ok: core imports no Android class"
fi

if [ "$fail" -ne 0 ]; then
  printf '\nDownloader isolation check failed.\n' >&2
  exit 1
fi
printf '\nDownloader isolation check passed.\n'
