#!/usr/bin/env bash
# Offline tests for the debug-release cleanup logic.
#
# No network, no GitHub API, no real release is touched. A fake `gh` supplies
# fixture data and records DELETE calls, so the protected-tag, tag-format,
# retention and dry-run gates are exercised for real.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/../.."

SCRIPT="distribution/cleanup_debug_releases.sh"
TEMPLATE="distribution/cleanup-debug-releases.yml"

fail=0
note() { printf '%s\n' "$*"; }
bad() { printf 'ERROR: %s\n' "$*" >&2; fail=1; }
expect_rc() {
  local name="$1" want="$2" got
  got="$(cat "$tmp/logs/$name.rc")"
  [ "$got" = "$want" ] || bad "$name: expected exit $want, got $got"
}
expect_log() { grep -qF "$2" "$tmp/logs/$1.log" || bad "$1: log missing: $2"; }
expect_no_log() { grep -qF "$2" "$tmp/logs/$1.log" && bad "$1: log unexpectedly contains: $2" || true; }
expect_deleted() { [ -f "$tmp/fixtures/deleted.log" ] && grep -qxF "$2" "$tmp/fixtures/deleted.log" || bad "$1: expected delete: $2"; }
expect_not_deleted() {
  if [ -f "$tmp/fixtures/deleted.log" ] && grep -qxF "$2" "$tmp/fixtures/deleted.log"; then
    bad "$1: must not delete: $2"
  fi
}

note "== static safeguards =="
[ -f "$SCRIPT" ] || { bad "missing $SCRIPT"; exit 1; }
[ -f "$TEMPLATE" ] || { bad "missing $TEMPLATE"; exit 1; }
bash -n "$SCRIPT" || bad "$SCRIPT has a syntax error"

grep -qF "readonly DEBUG_TAG_RE='^debug-test-[0-9]+-[0-9]+$'" "$SCRIPT" \
  || bad "cleanup tag regex must be exactly ^debug-test-[0-9]+-[0-9]+$"
grep -qF 'protected_id="$(gh api "repos/$GH_REPO/releases/tags/$PROTECTED_TAG"' "$SCRIPT" \
  || bad "cleanup must resolve the protected release id"
grep -qF '[ "$id" = "$protected_id" ]' "$SCRIPT" \
  || bad "cleanup must skip the protected release id"
grep -qF 'DELETE_ENABLED' "$SCRIPT" || bad "cleanup must honour the deletion gate"
grep -qF 'Apk-input-original' "$TEMPLATE" || bad "template lost the protected tag"
grep -qF 'CLEANUP_DELETE_ENABLED' "$TEMPLATE" || bad "template lost the deletion gate"
grep -qF 'default: true' "$TEMPLATE" || bad "manual runs must default to dry-run"
grep -qF "cron: '0 */6 * * *'" "$TEMPLATE" || bad "cleanup schedule changed unexpectedly"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/bin" "$tmp/fixtures" "$tmp/logs"

cat > "$tmp/bin/gh" <<'EOS'
#!/usr/bin/env bash
set -euo pipefail
[ "${1:-}" = "api" ] || { echo "fake-gh: unsupported: $*" >&2; exit 2; }
shift
method=GET
if [ "${1:-}" = "-X" ]; then method="$2"; shift 2; fi
if [ "${1:-}" = "--paginate" ]; then shift; fi
path="${1:-}"; shift || true
if [ "${1:-}" = "--jq" ]; then shift 2; fi

case "$method" in
  GET)
    case "$path" in
      */releases/tags/Apk-input-original)
        if [ -f "$FIXTURES/protected_missing" ]; then echo "gh: Not Found" >&2; exit 1; fi
        echo "${PROTECTED_ID:-441281791}"; exit 0 ;;
      */releases\?per_page=100)
        cat "$FIXTURES/releases.tsv"; exit 0 ;;
    esac ;;
  DELETE)
    case "$path" in
      */releases/*) echo "release ${path##*/}" >> "$FIXTURES/deleted.log"; exit 0 ;;
      */git/refs/tags/*) echo "tag ${path##*/}" >> "$FIXTURES/deleted.log"; exit 0 ;;
    esac ;;
esac
echo "fake-gh: unexpected $method $path" >&2
exit 2
EOS
chmod +x "$tmp/bin/gh"

now="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
printf '%s\t%s\t%s\t%s\n' \
  441281791 Apk-input-original false 2026-11-11T04:25:44Z \
  441237887 apk-input true 2020-01-01T00:00:00Z \
  900000001 debug-test-100-1 true 2020-01-01T00:00:00Z \
  900000002 debug-test-100-2 true "$now" \
  900000003 debug-test-1-1-extra true 2020-01-01T00:00:00Z \
  900000004 other-release false 2020-01-01T00:00:00Z \
  900000005 debug-test-200-1 false 2020-01-01T00:00:00Z \
  441281791 debug-test-300-1 true 2020-01-01T00:00:00Z \
  > "$tmp/fixtures/releases.tsv"

run_cleanup() {
  local name="$1"; shift
  rm -f "$tmp/fixtures/deleted.log"
  set +e
  PATH="$tmp/bin:$PATH" FIXTURES="$tmp/fixtures" GH_TOKEN=dummy GH_REPO=owner/repo \
    env "$@" bash "$SCRIPT" > "$tmp/logs/$name.log" 2>&1
  echo "$?" > "$tmp/logs/$name.rc"
  set -e
}

note "== 1. deletion gate closed forces dry-run =="
run_cleanup gate_closed DELETE_ENABLED=false DRY_RUN=false
expect_rc gate_closed 0
expect_log gate_closed "deletion gate closed"
expect_log gate_closed "would delete: debug-test-100-1"
[ -f "$tmp/fixtures/deleted.log" ] && bad "gate_closed: a deletion was attempted" || true

note "== 2. manual dry-run performs no deletion =="
run_cleanup dry_run DELETE_ENABLED=true DRY_RUN=true
expect_rc dry_run 0
expect_log dry_run "would delete: debug-test-100-1"
[ -f "$tmp/fixtures/deleted.log" ] && bad "dry_run: a deletion was attempted" || true

note "== 3. gated run deletes only expired debug-test releases =="
run_cleanup delete DELETE_ENABLED=true DRY_RUN=false
expect_rc delete 0
expect_deleted delete "release 900000001"
expect_deleted delete "tag debug-test-100-1"
expect_not_deleted delete "release 441281791"
expect_not_deleted delete "tag Apk-input-original"
expect_not_deleted delete "release 441237887"
expect_not_deleted delete "release 900000002"
expect_not_deleted delete "release 900000003"
expect_not_deleted delete "release 900000004"
expect_not_deleted delete "release 900000005"
expect_not_deleted delete "tag debug-test-300-1"
expect_no_log delete "deleting: Apk-input-original"

note "== 4. missing protected release fails closed =="
touch "$tmp/fixtures/protected_missing"
run_cleanup protected_missing DELETE_ENABLED=true DRY_RUN=false
rm -f "$tmp/fixtures/protected_missing"
expect_rc protected_missing 1
expect_log protected_missing "refusing to delete anything"
[ -f "$tmp/fixtures/deleted.log" ] && bad "protected_missing: a deletion was attempted" || true

if [ "$fail" -ne 0 ]; then
  printf '\nCleanup workflow tests failed.\n' >&2
  exit 1
fi
printf '\nCleanup workflow tests passed.\n'
