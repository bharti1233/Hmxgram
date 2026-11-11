#!/usr/bin/env bash
#
# Offline tests for the Morphe patch report verifier.
#
# The verifier is the gate between "Morphe said it applied everything" and
# "the APK may be signed and published". The CLI's report shape differs
# between releases, so every accepted shape is covered here together with the
# failure modes that must abort the build.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/../.."

VERIFIER="scripts/ci/verify_patch_report.py"

fail=0
note() { printf '%s\n' "$*"; }
bad() { printf 'ERROR: %s\n' "$*" >&2; fail=1; }

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

check() { # name expected_rc [ENV=VAL ...] -- fixture
  local name="$1" want="$2"; shift 2
  local envs=() fixture=""
  while [ "$#" -gt 0 ]; do
    if [ "$1" = "--" ]; then shift; fixture="$1"; break; fi
    envs+=("$1"); shift
  done
  local got
  set +e
  env "${envs[@]}" python3 "$VERIFIER" "$fixture" > "$tmp/$name.log" 2>&1
  got=$?
  set -e
  [ "$got" = "$want" ] || bad "$name: expected exit $want, got $got"
  [ "$got" = "$want" ] || sed 's/^/    /' "$tmp/$name.log" >&2
}

expect_log() { grep -qF "$2" "$tmp/$1.log" || { bad "$1: log missing: $2"; sed 's/^/    /' "$tmp/$1.log" >&2; }; }

note "== patch report verifier =="

# 1. Plain string entries (the shape the pinned CLI writes).
cat > "$tmp/strings.json" <<'EOF'
{"appliedPatches": ["Force SDR display", "Network content blocking"], "failedPatches": []}
EOF
check strings 0 "EXPECTED_PATCHES=Force SDR display,Network content blocking" -- "$tmp/strings.json"
expect_log strings "2 patch(es) applied, 0 failed"

# 2. Object entries.
cat > "$tmp/objects.json" <<'EOF'
{"appliedPatches": [{"name": "Force SDR display"}, {"name": "Notes tray"}], "failedPatches": []}
EOF
check objects 0 "EXPECTED_PATCHES=Force SDR display,Notes tray" -- "$tmp/objects.json"

# 3. Alternative key names.
cat > "$tmp/altkeys.json" <<'EOF'
{"applied": ["Notes tray"], "failed": []}
EOF
check altkeys 0 "EXPECTED_PATCHES=Notes tray" -- "$tmp/altkeys.json"

# 4. A top-level list is accepted as the applied set.
cat > "$tmp/list.json" <<'EOF'
["Notes tray", "Popup hiding"]
EOF
check list 0 "EXPECTED_PATCHES=Notes tray,Popup hiding" -- "$tmp/list.json"

# 5. Failed patches abort the build.
cat > "$tmp/failed.json" <<'EOF'
{"appliedPatches": ["Notes tray"], "failedPatches": ["Quoicouview mode"]}
EOF
check failed 1 "EXPECTED_PATCHES=Notes tray" -- "$tmp/failed.json"
expect_log failed "patch(es) failed"

# 6. A failed patch as an object is detected too.
cat > "$tmp/failedobj.json" <<'EOF'
{"appliedPatches": [{"name": "Notes tray"}], "failedPatches": [{"name": "Popup hiding", "error": "no match"}]}
EOF
check failedobj 1 -- "$tmp/failedobj.json"
expect_log failedobj "Popup hiding"

# 7. An expected patch that did not apply aborts the build.
cat > "$tmp/notes.json" <<'EOF'
{"appliedPatches": ["Notes tray"], "failedPatches": []}
EOF
check missing 1 "EXPECTED_PATCHES=Notes tray,Popup hiding" -- "$tmp/notes.json"
expect_log missing "expected patches were not applied: Popup hiding"

# 8. An explicitly disabled patch does not count as missing.
check disabled 0 "EXPECTED_PATCHES=Notes tray,Popup hiding" "DISABLE_PATCHES=Popup hiding" -- "$tmp/notes.json"

# 9. An empty applied set aborts the build.
cat > "$tmp/empty.json" <<'EOF'
{"appliedPatches": [], "failedPatches": []}
EOF
check empty 1 -- "$tmp/empty.json"
expect_log empty "no applied patches"

# 10. Too few applied patches aborts the build.
check toofew 1 "MIN_APPLIED_PATCHES=5" -- "$tmp/strings.json"
expect_log toofew "minimum is 5"

# 11. A missing or malformed report aborts the build.
check missingfile 1 -- "$tmp/does-not-exist.json"
expect_log missingfile "could not read the patch report"

printf 'not json\n' > "$tmp/broken.json"
check broken 1 -- "$tmp/broken.json"

if [ "$fail" -ne 0 ]; then
  printf '\nPatch report verifier tests failed.\n' >&2
  exit 1
fi
printf '\nPatch report verifier tests passed.\n'
