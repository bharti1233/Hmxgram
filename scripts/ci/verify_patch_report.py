#!/usr/bin/env python3
"""Verify the Morphe patch report before an APK is signed off or published.

The Morphe CLI writes a JSON report of every patch step. Its exact shape has
changed between CLI releases (entries may be plain names or objects, and the
key names differ), so this verifier accepts every shape seen in practice while
still enforcing the release gates:

  * a failed patch is a hard error
  * an empty applied list is a hard error
  * fewer than MIN_APPLIED_PATCHES applied patches is a hard error
  * a patch listed in EXPECTED_PATCHES that did not apply (and was not
    explicitly disabled through DISABLE_PATCHES) is a hard error

Usage:
  verify_patch_report.py <report.json>

Environment:
  MIN_APPLIED_PATCHES  minimum number of applied patches (default 1)
  EXPECTED_PATCHES     comma separated patch names that must have applied
  DISABLE_PATCHES      comma separated patch names that are allowed to be off
"""
import json
import os
import sys

APPLIED_KEYS = ("appliedPatches", "applied", "applied_patches", "patches_applied", "success")
FAILED_KEYS = ("failedPatches", "failed", "failed_patches", "patches_failed", "errors")
NAME_KEYS = ("name", "patch", "patchName", "patch_name", "id", "title")


def pick(report, keys):
    """Return the first present, non-empty value among keys."""
    if isinstance(report, list):
        return []
    for key in keys:
        if key in report:
            value = report[key]
            if value:
                return value
    return []


def name_of(entry):
    """Normalize one report entry to its patch name."""
    if isinstance(entry, str):
        return entry.strip()
    if isinstance(entry, dict):
        for key in NAME_KEYS:
            value = entry.get(key)
            if isinstance(value, str) and value.strip():
                return value.strip()
        return ""
    return str(entry).strip()


def detail_of(entry):
    if isinstance(entry, dict):
        for key in ("exception", "error", "message", "reason"):
            value = entry.get(key)
            if isinstance(value, str) and value.strip():
                return value.strip()
    return ""


def main():
    if len(sys.argv) != 2:
        print("usage: verify_patch_report.py <report.json>", file=sys.stderr)
        return 2

    path = sys.argv[1]
    try:
        with open(path, encoding="utf-8") as handle:
            report = json.load(handle)
    except (OSError, ValueError) as exc:
        print(f"Error: could not read the patch report {path}: {exc}", file=sys.stderr)
        return 1

    if isinstance(report, list):
        applied_entries, failed_entries = report, []
    else:
        applied_entries = pick(report, APPLIED_KEYS)
        failed_entries = pick(report, FAILED_KEYS)

    applied = [name_of(entry) for entry in applied_entries]
    applied = [name for name in applied if name]
    failed = [(name_of(entry), detail_of(entry)) for entry in failed_entries]

    for name in applied:
        print(f"    ok   {name}")
    for name, detail in failed:
        print(f"    FAIL {name}{': ' + detail if detail else ''}")

    if failed:
        names = ", ".join(name for name, _ in failed)
        print(f"Error: {len(failed)} patch(es) failed ({names}); refusing to produce an APK.", file=sys.stderr)
        return 1
    if not applied:
        print("Error: patch report contains no applied patches.", file=sys.stderr)
        return 1

    try:
        minimum = int(os.environ.get("MIN_APPLIED_PATCHES", "1") or "1")
    except ValueError:
        print("Error: MIN_APPLIED_PATCHES must be an integer.", file=sys.stderr)
        return 1
    if len(applied) < minimum:
        print(f"Error: only {len(applied)} patches applied, minimum is {minimum}.", file=sys.stderr)
        return 1

    disabled = {n.strip() for n in os.environ.get("DISABLE_PATCHES", "").split(",") if n.strip()}
    expected = [n.strip() for n in os.environ.get("EXPECTED_PATCHES", "").split(",") if n.strip()]
    missing = [n for n in expected if n not in applied and n not in disabled]
    if missing:
        print(f"Error: expected patches were not applied: {', '.join(missing)}", file=sys.stderr)
        return 1

    print(f"    {len(applied)} patch(es) applied, 0 failed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
