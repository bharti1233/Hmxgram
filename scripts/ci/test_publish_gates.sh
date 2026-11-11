#!/usr/bin/env bash
# Offline tests for the APK publish gates.
#
# The real `run:` block is extracted from .github/workflows/apk.yml and executed
# with stubbed `curl`/`apksigner` and a fake APK. No network call is made and
# nothing is uploaded.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/../.."

fail=0
note() { printf '%s\n' "$*"; }
bad() { printf 'ERROR: %s\n' "$*" >&2; fail=1; }
expect_rc() { [ "$(cat "$tmp/$1.rc")" = "$2" ] || bad "$1: expected exit $2, got $(cat "$tmp/$1.rc")"; }
expect_log() { grep -qF "$2" "$tmp/$1.log" || bad "$1: log missing: $2"; }

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/bin" "$tmp/work" "$tmp/runner"

SCRIPT="$tmp/publish_step.sh"
awk '
  /^      - name: Publish throwaway test APK to private input repo/ { inblock = 1; next }
  inblock && /^        run: \|/ { run = 1; next }
  inblock && run {
    if (/^      - / || /^      #/) exit
    sub(/^          /, "")
    print
  }
' .github/workflows/apk.yml > "$SCRIPT"
[ -s "$SCRIPT" ] || { echo "ERROR: could not extract the publish step" >&2; exit 1; }
bash -n "$SCRIPT" || { echo "ERROR: extracted publish step has a syntax error" >&2; exit 1; }
grep -qF 'sha256sum -c feurstagram.apk.sha256' "$SCRIPT" || bad "publish step must verify the build report"

cat > "$tmp/bin/apksigner" <<'EOS'
#!/usr/bin/env bash
echo "Signer #1 certificate SHA-256 digest: $APK_CERT_SHA256"
EOS
chmod +x "$tmp/bin/apksigner"

# The publish step re-runs the real standalone validator. Stub it inside the fake
# work tree so this test stays offline: it records the invocation and can be told
# to fail, which must abort publishing before any upload.
VALIDATOR="$tmp/work/scripts/ci/validate_standalone_apk.sh"
mkdir -p "$(dirname "$VALIDATOR")"
cat > "$VALIDATOR" <<'EOS'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "${VALIDATION_LOG:?}"
[ "${VALIDATE_STUB_FAIL:-0}" = "1" ] && { echo "ERROR: forced standalone validation failure" >&2; exit 1; }
exit 0
EOS
chmod +x "$VALIDATOR"

cat > "$tmp/bin/aapt" <<'EOS'
#!/usr/bin/env bash
echo "package: name='${FAKE_PKG:-com.instagram.android}' versionCode='1' versionName='${FAKE_VERSION:-1.0}'"
EOS
chmod +x "$tmp/bin/aapt"

cat > "$tmp/bin/curl" <<'EOS'
#!/usr/bin/env bash
set -euo pipefail
for a in "$@"; do
  case "$a" in
    *%{http_code}*) printf '404'; exit 0 ;;
  esac
done
for a in "$@"; do
  case "$a" in
    */releases) printf '{"id":123}\n'; exit 0 ;;
  esac
done
printf '{"digest":"sha256:%s"}\n' "$APK_SHA256"
EOS
chmod +x "$tmp/bin/curl"

apk="hmx-insta-1.0-test.apk"
printf 'fake-apk-bytes\n' > "$tmp/work/$apk"
printf 'fake-apk-bytes\n' > "$tmp/work/feurstagram.apk"
sha="$(sha256sum "$tmp/work/$apk" | awk '{print $1}')"
printf '%s  feurstagram.apk\n' "$sha" > "$tmp/work/feurstagram.apk.sha256"
cert="$(printf 'ab%.0s' $(seq 1 32))"
printf 'Signer #1 certificate SHA-256 digest: %s\n' "$cert" > "$tmp/runner/apk-certs.txt"

run_case() {
  local name="$1"; shift
  : > "$tmp/work/github_env"
  : > "$tmp/validation.log"
  set +e
  ( cd "$tmp/work" && env \
      PATH="$tmp/bin:$PATH" \
      RUNNER_TEMP="$tmp/runner" \
      GITHUB_ENV="$tmp/work/github_env" \
      VALIDATION_LOG="$tmp/validation.log" \
      APK_OUT="$apk" APK_SHA256="$sha" APK_CERT_SHA256="$cert" \
      SIGNING_MODE=throwaway SIGNATURE_BYPASS=false \
      DIST_TOKEN=dist-token BASE_APK_TOKEN=base-token \
      DIST_REPO=owner/repo PROTECTED_TAG=Apk-input-original \
      PUBLISH_TAG=debug-test-1-1 REQUESTED_VERSION=1.0 \
      EXPECTED_PACKAGE=com.instagram.android CLONE=false \
      GITHUB_SHA=deadbeef GITHUB_SERVER_URL=https://github.com \
      GITHUB_REPOSITORY=o/r GITHUB_RUN_ID=1 GITHUB_RUN_ATTEMPT=1 \
      "$@" bash "$SCRIPT" > "$tmp/$name.log" 2>&1 )
  echo "$?" > "$tmp/$name.rc"
  set -e
}

note "== publish gate tests =="

run_case release_mode SIGNING_MODE=release
expect_rc release_mode 1
expect_log release_mode "Only throwaway test builds may be published"

run_case bypass_enabled SIGNATURE_BYPASS=true
expect_rc bypass_enabled 1
expect_log bypass_enabled "Refusing to publish with signature bypass enabled"

run_case missing_dist DIST_TOKEN=
expect_rc missing_dist 1
expect_log missing_dist "DIST_TOKEN is not configured"

run_case same_token DIST_TOKEN=base-token
expect_rc same_token 1
expect_log same_token "DIST_TOKEN must be distinct from BASE_APK_TOKEN"

run_case protected_tag PUBLISH_TAG=Apk-input-original
expect_rc protected_tag 1
expect_log protected_tag "Refusing to use the protected tag"

run_case malformed_tag PUBLISH_TAG=debug-test-1
expect_rc malformed_tag 1
expect_log malformed_tag "Publish tag does not match"

run_case digest_mismatch APK_SHA256=0000000000000000000000000000000000000000000000000000000000000000
expect_rc digest_mismatch 1
expect_log digest_mismatch "APK digest changed after verification"

run_case cert_mismatch APK_CERT_SHA256=1111111111111111111111111111111111111111111111111111111111111111
expect_rc cert_mismatch 1
expect_log cert_mismatch "Build report signer does not match"

run_case wrong_package FAKE_PKG=com.instagram.android.feurstagram
expect_rc wrong_package 1
expect_log wrong_package "APK package is not com.instagram.android"

run_case wrong_version FAKE_VERSION=9.9.9
expect_rc wrong_version 1
expect_log wrong_version "APK version is '9.9.9', expected '1.0'"

run_case publish_revalidation_fail VALIDATE_STUB_FAIL=1
expect_rc publish_revalidation_fail 1
expect_log publish_revalidation_fail "forced standalone validation failure"

run_case clone_success CLONE=true FAKE_PKG=com.instagram.android.feurstagram
expect_rc clone_success 0
expect_log clone_success "Published $apk as owner/repo@debug-test-1-1"
grep -qF -- "--expected-package com.instagram.android.feurstagram" "$tmp/validation.log" \
  || bad "clone_success: publish path must re-validate the clone package"

run_case success
expect_rc success 0
expect_log success "Published $apk as owner/repo@debug-test-1-1"
grep -qF -- "--stage final" "$tmp/validation.log" \
  || bad "success: publish path must re-run the standalone validator before upload"
grep -qF -- "--expected-package com.instagram.android " "$tmp/validation.log" \
  || bad "success: publish path must re-validate the stock package"
grep -qF "PUBLISHED_TAG=debug-test-1-1" "$tmp/work/github_env" \
  || bad "success: PUBLISHED_TAG was not exported"

if [ "$fail" -ne 0 ]; then
  printf '\nPublish gate tests failed.\n' >&2
  exit 1
fi
printf '\nPublish gate tests passed.\n'
