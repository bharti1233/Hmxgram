#!/usr/bin/env bash
#
# Offline tests for the protected-method verifier. No APK, no Android SDK and
# no network: the test exercises the dexdump canonicaliser and the fail-closed
# rules that the gated build relies on.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."

python3 scripts/ci/verify_protected_methods.py --self-test

python3 - <<'PY'
import json
import re
import sys

manifest = json.load(open("scripts/ci/protected_methods.json"))
gate = manifest.get("gate") or {}
surfaces = gate.get("surfaces") or []
ids = [surface.get("id") for surface in surfaces]
if len(ids) != len(set(ids)):
    print("ERROR: duplicate gate surface ids", file=sys.stderr)
    sys.exit(1)
if not any(surface.get("enforce", True) for surface in surfaces):
    print("ERROR: no enforced surfaces", file=sys.stderr)
    sys.exit(1)
for surface in surfaces:
    if surface.get("enforce", True):
        if not (surface.get("descriptors") or surface.get("string_sets")
                or surface.get("regex_sets") or surface.get("manifest_application")):
            print("ERROR: enforced surface %s has no anchors" % surface.get("id"), file=sys.stderr)
            sys.exit(1)

baseline_sha = (manifest.get("baseline") or {}).get("apk_sha256", "")
if not re.fullmatch(r"[0-9a-f]{64}", baseline_sha):
    print("ERROR: baseline.apk_sha256 is not a 64-character hex digest", file=sys.stderr)
    sys.exit(1)
if baseline_sha != gate.get("baseline_pin"):
    print("ERROR: gate.baseline_pin does not match baseline.apk_sha256", file=sys.stderr)
    sys.exit(1)

exception_ids = [item.get("id") for item in manifest.get("exceptions") or []]
if exception_ids != ["tier2.row_binding.observe"]:
    print("ERROR: unexpected declared exceptions: %s" % exception_ids, file=sys.stderr)
    sys.exit(1)
rejected_ids = [item.get("id") for item in manifest.get("rejected_exceptions") or []]
if "ghost.network.guard" not in rejected_ids:
    print("ERROR: ghost.network.guard must stay a rejected exception", file=sys.stderr)
    sys.exit(1)
print("protected-method manifest: PASS (%d surfaces)" % len(surfaces))
PY

echo "protected-method verifier tests: PASS"
